const GENERATED_FILE_PATH_PREFIX = "/api/v1/chat/generated-files/";
const GENERATED_FILE_URL_PATTERN = /(?:https?:\/\/[^\s<>"']+)?\/api\/v1\/chat\/generated-files\/[0-9a-f]{32}\?token=[A-Za-z0-9_-]+(?:#[A-Za-z0-9._~-]+)?/gi;

function defaultPageUrl() {
  if (typeof window !== "undefined" && window.location?.href) return window.location.href;
  return "http://placeholder.local/";
}

function escapeHtml(value: string) {
  return value.replace(/[&<>"']/g, character => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  })[character] || character);
}

/** Private capability links must use the host that served the current UI. */
export function resolveGeneratedFileHref(href: string, pageUrl = defaultPageUrl()) {
  try {
    const page = new URL(pageUrl);
    const url = new URL(href, page);
    if (!url.pathname.startsWith(GENERATED_FILE_PATH_PREFIX)) return href;
    url.protocol = page.protocol;
    url.host = page.host;
    return url.href;
  } catch {
    return href;
  }
}

/** Converts naked generated-file capability links into safe clickable anchors. */
export function linkifyGeneratedFileUrls(text: string, pageUrl = defaultPageUrl()) {
  return text.replace(GENERATED_FILE_URL_PATTERN, rawUrl => {
    const href = resolveGeneratedFileHref(rawUrl, pageUrl);
    const escapedHref = escapeHtml(href);
    return `<a href="${escapedHref}" class="generated-file-link">${escapedHref}</a>`;
  });
}
