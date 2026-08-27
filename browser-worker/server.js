import http from "node:http";
import dns from "node:dns/promises";
import net from "node:net";
import fs from "node:fs/promises";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { chromium } from "playwright";

const port = Number(process.env.PORT || 8787);
const maxBodyBytes = 256 * 1024;
const maxHtmlChars = 512 * 1024;
const maxSessions = Math.max(1, Number(process.env.MAX_SESSIONS || 8));
const maxTabsPerSession = Math.max(1, Math.min(32, Number(process.env.MAX_TABS_PER_SESSION || 8)));
const idleMinutes = Math.max(1, Number(process.env.SESSION_IDLE_MINUTES || 30));
const allowPrivateTargets = process.env.ALLOW_PRIVATE_TARGETS === "true";
const uploadRoot = path.resolve(process.env.UPLOAD_ROOT || "/tmp/agent-browser-uploads");
const browserUserAgent = process.env.BROWSER_USER_AGENT
  || "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
const browserLocale = process.env.BROWSER_LOCALE || "zh-CN";
const browserTimezoneId = process.env.BROWSER_TIMEZONE_ID || "Asia/Shanghai";
const browserStealth = process.env.BROWSER_STEALTH !== "false";
const browserLaunchArgs = browserStealth ? [
  "--disable-blink-features=AutomationControlled",
  "--disable-infobars",
  "--disable-dev-shm-usage"
] : [];
const stealthInitScript = `
(() => {
  try {
    Object.defineProperty(navigator, "webdriver", { get: () => undefined, configurable: true });
  } catch (_) {}
  try {
    if (!window.chrome) window.chrome = { runtime: {}, loadTimes: () => {}, csi: () => {}, app: {} };
  } catch (_) {}
  try {
    Object.defineProperty(navigator, "languages", {
      get: () => ["zh-CN", "zh", "en-US", "en"], configurable: true
    });
  } catch (_) {}
  try {
    Object.defineProperty(navigator, "plugins", {
      get: () => [1, 2, 3, 4, 5], configurable: true
    });
  } catch (_) {}
  try {
    const originalQuery = navigator.permissions && navigator.permissions.query;
    if (typeof originalQuery === "function") {
      navigator.permissions.query = (parameters) => (
        parameters && parameters.name === "notifications"
          ? Promise.resolve({ state: Notification.permission })
          : originalQuery(parameters)
      );
    }
  } catch (_) {}
})();`;
const workerGeneration = randomUUID();
const workerStartedAt = new Date().toISOString();
const sessions = new Map();
const snapshotCache = new Map();
const snapshotCacheTtlMs = Math.max(100, Number(process.env.SNAPSHOT_CACHE_TTL_MS || 500));
const snapshotCacheLimit = Math.max(4, Number(process.env.SNAPSHOT_CACHE_LIMIT || 32));
const maxDownloadBytes = Math.max(1024, Number(process.env.MAX_DOWNLOAD_BYTES || 2 * 1024 * 1024));

function json(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(payload)
  });
  res.end(payload);
}

function error(res, status, message) {
  json(res, status, { error: message });
}

function idFromPath(pathname) {
  const match = pathname.match(/^\/sessions\/([A-Za-z0-9._:-]{1,255})(?:\/([^/]+)(?:\/([^/]+)(?:\/([^/]+))?)?)?$/);
  return match ? { id: match[1], operation: match[2] || null, argument: match[3] || null, action: match[4] || null } : null;
}

async function body(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > maxBodyBytes) throw new Error("request body too large");
    chunks.push(chunk);
  }
  if (chunks.length === 0) return {};
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

function requiredText(value, name, max) {
  if (typeof value !== "string" || value.trim() === "" || value.length > max) {
    throw new Error(`${name} is required and bounded`);
  }
  return value.trim();
}

function boundedNumber(value, name, min, max, required = true) {
  if (value === undefined || value === null || value === "") {
    if (required) throw new Error(`${name} is required`);
    return 0;
  }
  const number = Number(value);
  if (!Number.isFinite(number) || number < min || number > max) {
    throw new Error(`${name} is out of bounds`);
  }
  return number;
}

function safeSegment(value, name) {
  const text = requiredText(value, name, 128);
  if (!/^[A-Za-z0-9._-]+$/.test(text) || text === "." || text === "..") {
    throw new Error(`${name} is invalid`);
  }
  return text;
}

function privateAddress(address) {
  if (net.isIPv4(address)) {
    const parts = address.split(".").map(Number);
    return parts[0] === 0 || parts[0] === 10 || parts[0] === 127
      || (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31)
      || (parts[0] === 192 && parts[1] === 168)
      || (parts[0] === 169 && parts[1] === 254)
      || parts[0] >= 224;
  }
  if (net.isIPv6(address)) {
    const value = address.toLowerCase();
    return value === "::1" || value === "::" || value.startsWith("fc")
      || value.startsWith("fd") || value.startsWith("fe80:");
  }
  return true;
}

async function assertAllowedUrl(raw) {
  const target = new URL(requiredText(raw, "url", 2048));
  if (!['http:', 'https:'].includes(target.protocol) || target.username || target.password) {
    throw new Error("only public HTTP/HTTPS URLs are allowed");
  }
  if (allowPrivateTargets) return target.toString();
  const host = target.hostname.toLowerCase();
  if (host === "localhost" || host.endsWith(".localhost") || host.endsWith(".local")
      || host === "metadata.google.internal") {
    throw new Error("private browser targets are disabled");
  }
  const records = await dns.lookup(host, { all: true });
  if (!records.length || records.some(record => privateAddress(record.address))) {
    throw new Error("private browser targets are disabled");
  }
  return target.toString();
}

function isPageLocalResource(raw) {
  try {
    const protocol = new URL(raw).protocol;
    return protocol === "about:" || protocol === "blob:" || protocol === "data:";
  } catch {
    return false;
  }
}

async function pageState(session, includeScreenshot = true) {
  if (includeScreenshot) {
    const cached = snapshotCache.get(session.id);
    if (cached && Date.now() - cached.createdAt <= snapshotCacheTtlMs) {
      return cached.value;
    }
  }
  let lastError;
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      const value = await capturePageState(session, includeScreenshot);
      if (includeScreenshot) {
        snapshotCache.delete(session.id);
        snapshotCache.set(session.id, { createdAt: Date.now(), value });
        while (snapshotCache.size > snapshotCacheLimit) {
          snapshotCache.delete(snapshotCache.keys().next().value);
        }
      }
      return value;
    } catch (cause) {
      lastError = cause;
      const message = cause instanceof Error ? cause.message : String(cause);
      if (attempt === 0 && /Execution context was destroyed|Target page, context or browser has been closed/i.test(message)) {
        await new Promise(resolve => setTimeout(resolve, 50));
        continue;
      }
      throw cause;
    }
  }
  throw lastError;
}

async function capturePageState(session, includeScreenshot = true) {
  const page = session.page;
  const html = (await page.content()).slice(0, maxHtmlChars);
  const result = {
    session_id: session.id,
    url: page.url(),
    title: await page.title(),
    html,
    text: (await page.locator("body").innerText().catch(() => "")).slice(0, maxHtmlChars),
    captured_at: new Date().toISOString()
  };
  result.active_tab_id = session.activeTabId;
  result.tabs = await Promise.all([...session.pages.entries()].map(async ([tabId, tab]) => ({
    tab_id: tabId,
    url: tab.url(),
    title: await tab.title(),
    active: tabId === session.activeTabId
  })));
  if (includeScreenshot) {
    const screenshot = await page.screenshot({ type: "png", fullPage: false });
    result.screenshot_data_url = `data:image/png;base64,${screenshot.toString("base64")}`;
  }
  return result;
}

function invalidateSnapshot(sessionId) {
  snapshotCache.delete(sessionId);
}

async function clearSessionStorage(session) {
  await session.context.clearCookies();
  for (const page of session.pages.values()) {
    await page.evaluate(() => {
      try { window.localStorage.clear(); } catch (_) {}
      try { window.sessionStorage.clear(); } catch (_) {}
    }).catch(() => {});
  }
}

async function clearProfiles(profileKey, uploadScope) {
  const scope = safeSegment(uploadScope, "upload_scope");
  const requestedProfile = typeof profileKey === "string" && profileKey.trim() !== ""
    ? requiredText(profileKey, "profile_key", 128) : null;
  const matched = [];
  for (const [id, session] of sessions.entries()) {
    if (session.uploadScope !== scope) continue;
    if (requestedProfile !== null && session.profileKey !== requestedProfile) continue;
    matched.push([id, session]);
  }
  for (const [id, session] of matched) {
    await clearSessionStorage(session);
    sessions.delete(id);
    invalidateSnapshot(id);
    await session.browser.close().catch(() => {});
  }
  return { profile_key: requestedProfile, upload_scope: scope, cleared_sessions: matched.length };
}

async function handle(request, response) {
  const url = new URL(request.url, `http://${request.headers.host || "localhost"}`);
  if (request.method === "GET" && url.pathname === "/health") {
    return json(response, 200, {
      status: "ok",
      worker_generation: workerGeneration,
      started_at: workerStartedAt,
      sessions: sessions.size,
      session_ids: [...sessions.keys()],
      max_sessions: maxSessions,
      max_tabs_per_session: maxTabsPerSession,
      browser_locale: browserLocale,
      browser_timezone_id: browserTimezoneId,
      browser_stealth: browserStealth
    });
  }
  if (request.method === "POST" && url.pathname === "/sessions") {
    const input = await body(request);
    const id = requiredText(input.session_id, "session_id", 255);
    if (sessions.has(id)) return error(response, 409, "session already exists");
    if (sessions.size >= maxSessions) return error(response, 429, "browser worker session limit reached");
    const browser = await chromium.launch({ headless: true, args: browserLaunchArgs });
    const context = await browser.newContext({
      viewport: { width: 1280, height: 800 },
      userAgent: browserUserAgent,
      locale: browserLocale,
      timezoneId: browserTimezoneId,
      serviceWorkers: "block"
    });
    if (browserStealth) await context.addInitScript({ content: stealthInitScript });
    const page = await context.newPage();
    const tabId = `tab_${randomUUID().replaceAll("-", "")}`;
    const session = {
      id, browser, context, page, pages: new Map([[tabId, page]]), activeTabId: tabId,
      profileKey: input.profile_key || null, uploadScope: safeSegment(input.upload_scope, "upload_scope"),
      touchedAt: Date.now()
    };
    await context.route("**/*", async route => {
      try {
        // Page-local resources do not create a network connection and are
        // required by normal HTML/CSS/SPA pages. Network requests still go
        // through the target and redirect checks below.
        if (isPageLocalResource(route.request().url())) {
          await route.continue();
          return;
        }
        await assertAllowedUrl(route.request().url());
        await route.continue();
      } catch {
        await route.abort("blockedbyclient");
      }
    });
    sessions.set(id, session);
    invalidateSnapshot(id);
    try {
      if (input.start_url) await page.goto(await assertAllowedUrl(input.start_url), { waitUntil: "domcontentloaded" });
      return json(response, 201, {
        session_id: id,
        profile_key: session.profileKey,
        url: page.url(),
        title: await page.title()
      });
    } catch (cause) {
      sessions.delete(id);
      await browser.close();
      throw cause;
    }
  }
  if (request.method === "POST" && url.pathname === "/profiles/clear") {
    const input = await body(request);
    try {
      return json(response, 200, await clearProfiles(input.profile_key, input.upload_scope));
    } catch (cause) {
      return error(response, 400, cause instanceof Error ? cause.message : String(cause));
    }
  }
  const parsed = idFromPath(url.pathname);
  if (!parsed) return error(response, 404, "not found");
  const session = sessions.get(parsed.id);
  if (!session) return error(response, 404, "session not found");
  session.touchedAt = Date.now();

  if (request.method === "GET" && parsed.operation === "tabs") {
    return json(response, 200, { session_id: session.id, active_tab_id: session.activeTabId,
      tabs: await Promise.all([...session.pages.entries()].map(async ([tabId, tab]) => ({
        tab_id: tabId, url: tab.url(), title: await tab.title(), active: tabId === session.activeTabId
      }))) });
  }
  if (parsed.operation === "tabs" && request.method === "POST" && parsed.action === "activate") {
    const tab = session.pages.get(parsed.argument);
    if (!tab) return error(response, 404, "tab not found");
    session.page = tab;
    session.activeTabId = parsed.argument;
    invalidateSnapshot(session.id);
    return json(response, 200, await pageState(session, false));
  }
  if (parsed.operation === "tabs" && request.method === "DELETE" && parsed.argument) {
    if (session.pages.size <= 1) return error(response, 409, "cannot close the last tab");
    const tab = session.pages.get(parsed.argument);
    if (!tab) return error(response, 404, "tab not found");
    session.pages.delete(parsed.argument);
    await tab.close();
    if (session.activeTabId === parsed.argument) {
      const [nextId, nextPage] = session.pages.entries().next().value;
      session.activeTabId = nextId;
      session.page = nextPage;
    }
    invalidateSnapshot(session.id);
    return json(response, 200, await pageState(session, false));
  }
  if (parsed.operation === "tabs" && request.method === "POST" && !parsed.argument) {
    if (session.pages.size >= maxTabsPerSession) return error(response, 429, "tab limit reached");
    const input = await body(request);
    const target = input.url ? await assertAllowedUrl(input.url) : "about:blank";
    const tab = await session.context.newPage();
    const tabId = `tab_${randomUUID().replaceAll("-", "")}`;
    session.pages.set(tabId, tab);
    await tab.goto(target, { waitUntil: "domcontentloaded" });
    session.page = tab;
    session.activeTabId = tabId;
    invalidateSnapshot(session.id);
    return json(response, 201, await pageState(session, false));
  }

  if (request.method === "DELETE" && parsed.operation === null) {
    sessions.delete(parsed.id);
    invalidateSnapshot(parsed.id);
    await session.browser.close();
    return json(response, 200, { session_id: parsed.id, closed: true });
  }
  if (request.method === "GET" && parsed.operation === "snapshot") {
    return json(response, 200, await pageState(session));
  }
  const input = await body(request);
  if (request.method === "POST" && parsed.operation === "navigate") {
    const target = await assertAllowedUrl(input.url);
    invalidateSnapshot(session.id);
    const previousUrl = session.page.url();
    try {
      await session.page.goto(target, { waitUntil: "domcontentloaded" });
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : String(cause);
      const finalUrl = session.page.url();
      if (!/Timeout|timeout/i.test(message) || !finalUrl || finalUrl === previousUrl) throw cause;
      await assertAllowedUrl(finalUrl);
    }
    return json(response, 200, await pageState(session, false));
  }
  if (request.method === "POST" && parsed.operation === "click") {
    const selector = requiredText(input.selector, "selector", 1000);
    invalidateSnapshot(session.id);
    await session.page.locator(selector).click();
    return json(response, 200, await pageState(session, false));
  }
  if (request.method === "POST" && parsed.operation === "fill") {
    const selector = requiredText(input.selector, "selector", 1000);
    if (typeof input.value !== "string" || input.value.length > 20000) {
      throw new Error("value must be a string no longer than 20000 characters");
    }
    const value = input.value;
    invalidateSnapshot(session.id);
    await session.page.locator(selector).fill(value);
    return json(response, 200, await pageState(session, false));
  }
  if (request.method === "POST" && parsed.operation === "press") {
    const key = requiredText(input.key, "key", 64);
    invalidateSnapshot(session.id);
    await session.page.keyboard.press(key);
    return json(response, 200, await pageState(session, false));
  }
  if (request.method === "POST" && ["back", "forward", "reload"].includes(parsed.operation)) {
    invalidateSnapshot(session.id);
    if (parsed.operation === "back") await session.page.goBack({ waitUntil: "domcontentloaded" }).catch(() => null);
    else if (parsed.operation === "forward") await session.page.goForward({ waitUntil: "domcontentloaded" }).catch(() => null);
    else await session.page.reload({ waitUntil: "domcontentloaded" });
    return json(response, 200, await pageState(session, false));
  }
  if (request.method === "POST" && parsed.operation === "wait-for") {
    const condition = requiredText(input.condition, "condition", 32);
    const value = typeof input.value === "string" ? input.value.trim() : "";
    const timeoutMs = Math.max(100, Math.min(30000, Number(input.timeout_ms || 5000)));
    if (!["text", "url", "target", "page_state"].includes(condition)) throw new Error("unsupported wait condition");
    if (condition !== "page_state" && !value) throw new Error("value is required for this wait condition");
    if (condition === "text") await session.page.getByText(value, { exact: false }).first().waitFor({ state: "visible", timeout: timeoutMs });
    else if (condition === "target") await session.page.locator(value).first().waitFor({ state: "visible", timeout: timeoutMs });
    else if (condition === "url") await session.page.waitForURL(urlValue => urlValue.toString().includes(value), { timeout: timeoutMs });
    else {
      const state = ["domcontentloaded", "load", "networkidle"].includes(value) ? value : "domcontentloaded";
      await session.page.waitForLoadState(state, { timeout: timeoutMs });
    }
    return json(response, 200, await pageState(session, false));
  }
  if (request.method === "POST" && parsed.operation === "select-option") {
    const selector = requiredText(input.selector, "selector", 1000);
    const value = typeof input.value === "string" && input.value.trim() ? input.value.trim() : null;
    const label = typeof input.label === "string" && input.label.trim() ? input.label.trim() : null;
    if (!value && !label) throw new Error("value or label is required");
    invalidateSnapshot(session.id);
    await session.page.locator(selector).selectOption(value ? { value } : { label });
    return json(response, 200, await pageState(session, false));
  }
  if (request.method === "GET" && parsed.operation === "read-visible") {
    const visible = await session.page.locator("body").evaluate((body) => {
      const viewportHeight = window.innerHeight || 800;
      const viewportWidth = window.innerWidth || 1280;
      const visibleText = [];
      const walker = document.createTreeWalker(body, NodeFilter.SHOW_ELEMENT);
      let node;
      while ((node = walker.nextNode())) {
        const element = /** @type {HTMLElement} */ (node);
        const rect = element.getBoundingClientRect();
        const style = window.getComputedStyle(element);
        if (rect.width <= 0 || rect.height <= 0 || rect.bottom <= 0 || rect.top >= viewportHeight
            || rect.right <= 0 || rect.left >= viewportWidth || style.visibility === "hidden"
            || style.display === "none") continue;
        if (element.children.length === 0) {
          const text = (element.textContent || "").replace(/\s+/g, " ").trim();
          if (text) visibleText.push(text);
        }
      }
      return visibleText.join("\n").slice(0, 512 * 1024);
    });
    return json(response, 200, { session_id: session.id, url: session.page.url(), title: await session.page.title(), text: visible, captured_at: new Date().toISOString() });
  }
  if (request.method === "POST" && parsed.operation === "drag") {
    const source = requiredText(input.source_selector, "source_selector", 1000);
    const target = requiredText(input.target_selector, "target_selector", 1000);
    invalidateSnapshot(session.id);
    await session.page.locator(source).dragTo(session.page.locator(target));
    return json(response, 200, await pageState(session, false));
  }
  if (request.method === "POST" && parsed.operation === "download") {
    const selector = requiredText(input.selector, "selector", 1000);
    invalidateSnapshot(session.id);
    const downloadPromise = session.page.waitForEvent("download");
    await session.page.locator(selector).click();
    const download = await downloadPromise;
    const filePath = await download.path();
    if (!filePath) throw new Error("download file is unavailable");
    const bytes = await fs.readFile(filePath);
    if (bytes.length > maxDownloadBytes) throw new Error("download exceeds worker size limit");
    return json(response, 200, {
      session_id: session.id,
      filename: download.suggestedFilename() || "download",
      mime_type: download.createReadStream ? (download.suggestedFilename() || "").toLowerCase().endsWith(".csv") ? "text/csv" : "application/octet-stream" : "application/octet-stream",
      size: bytes.length,
      content_base64: bytes.toString("base64"),
      current_url: session.page.url(),
      page_title: await session.page.title()
    });
  }
  if (request.method === "POST" && parsed.operation === "manual-input") {
    const input = await body(request);
    const event = requiredText(input.event, "event", 32);
    const x = boundedNumber(input.x, "x", 0, 4096, false);
    const y = boundedNumber(input.y, "y", 0, 4096, false);
    invalidateSnapshot(session.id);
    if (["mouse_click", "mouse_down", "mouse_move", "mouse_up"].includes(event)) {
      if (input.x === undefined || input.y === undefined) throw new Error("mouse coordinates are required");
      await session.page.mouse.move(x, y);
      if (event === "mouse_click") await session.page.mouse.click(x, y);
      else if (event === "mouse_down") await session.page.mouse.down();
      else if (event === "mouse_up") await session.page.mouse.up();
    } else if (event === "key") {
      await session.page.keyboard.press(requiredText(input.key, "key", 64));
    } else if (event === "text") {
      await session.page.keyboard.type(requiredText(input.text, "text", 2000));
    } else if (event === "scroll") {
      const deltaY = boundedNumber(input.delta_y, "delta_y", -2000, 2000);
      await session.page.mouse.wheel(0, deltaY);
    } else {
      throw new Error("unsupported manual input event");
    }
    return json(response, 200, await pageState(session, false));
  }
  if (request.method === "POST" && parsed.operation === "scroll") {
    const input = await body(request);
    invalidateSnapshot(session.id);
    const x = Number.isFinite(Number(input.x)) ? Math.max(-100000, Math.min(100000, Number(input.x))) : 0;
    const y = Number.isFinite(Number(input.y)) ? Math.max(-100000, Math.min(100000, Number(input.y))) : 600;
    if (input.selector) await session.page.locator(requiredText(input.selector, "selector", 1000)).scrollIntoViewIfNeeded();
    else await session.page.mouse.wheel(x, y);
    return json(response, 200, await pageState(session, false));
  }
  if (request.method === "POST" && parsed.operation === "hover") {
    const input = await body(request);
    const selector = requiredText(input.selector, "selector", 1000);
    invalidateSnapshot(session.id);
    await session.page.locator(selector).hover();
    return json(response, 200, await pageState(session, false));
  }
  if (request.method === "POST" && parsed.operation === "upload") {
    const input = await body(request);
    const selector = requiredText(input.selector, "selector", 1000);
    invalidateSnapshot(session.id);
    if (!Array.isArray(input.files) || input.files.length < 1 || input.files.length > 10) {
      throw new Error("files must contain between 1 and 10 paths");
    }
    const files = input.files.map(value => requiredText(value, "file", 512));
    const scopedUploadRoot = path.resolve(uploadRoot, session.uploadScope);
    const resolved = files.map(value => path.resolve(scopedUploadRoot, value));
    if (resolved.some(value => value !== scopedUploadRoot && !value.startsWith(`${scopedUploadRoot}${path.sep}`))) {
      throw new Error("file is outside upload root");
    }
    for (const file of resolved) await fs.access(file);
    await session.page.locator(selector).setInputFiles(resolved);
    return json(response, 200, await pageState(session, false));
  }
  return error(response, 404, "operation not found");
}

const server = http.createServer((request, response) => {
  handle(request, response).catch((cause) => {
    const message = cause instanceof Error ? cause.message : "worker failure";
    error(response, 400, message.slice(0, 1000));
  });
});

server.listen(port, "0.0.0.0", () => {
  console.log(`agent browser worker listening on ${port}`);
});

setInterval(() => {
  const cutoff = Date.now() - idleMinutes * 60 * 1000;
  for (const [id, session] of sessions) {
    if ((session.touchedAt || 0) < cutoff) {
      sessions.delete(id);
      invalidateSnapshot(id);
      session.browser.close().catch(() => {});
    }
  }
}, 60_000).unref();

async function shutdown() {
  for (const session of sessions.values()) await session.browser.close().catch(() => {});
  server.close(() => process.exit(0));
}
process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);
