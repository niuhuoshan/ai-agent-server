import DOMPurify from "dompurify";
import katex from "katex";
import { Marked, type Tokens } from "marked";
import { linkifyGeneratedFileUrls, resolveGeneratedFileHref } from "@/utils/generated-file-url";
import "katex/dist/katex.min.css";

export type RichMessageSegment =
  | {
      id: string;
      type: "markdown";
      content: string;
      html: string;
    }
  | {
      id: string;
      type: "code";
      content: string;
      language: string;
      runnable: boolean;
      complete: boolean;
    }
  | {
      id: string;
      type: "mermaid";
      content: string;
      complete: boolean;
    }
  | {
      id: string;
      type: "chart";
      content: string;
      option: Record<string, unknown> | null;
      error: string | null;
      complete: boolean;
    }
  | {
      id: string;
      type: "sql-plan";
      content: string;
      plan: Record<string, unknown> | null;
      error: string | null;
    };

const RUNNABLE_LANGUAGES = new Set(["python", "python3", "shell", "sh", "bash"]);
const CHART_LANGUAGES = new Set(["chart", "echarts"]);
const SQL_PLAN_PATTERN = /<sql_plan>([\s\S]*?)<\/sql_plan>/gi;
const CITATION_PATTERN = /(?:[\[【]ID:\s*([\w.-]+)[\]】])|(?:Fig\.\s*(\d+))/gi;
const MATH_PATTERN = /\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\\]|\$((?:\\.|[^$\\\n])+?)\$|\\\(([\s\S]+?)\\\)/g;
const MAX_STRUCTURED_BLOCK_BYTES = 256 * 1024;
const MAX_STRUCTURED_DEPTH = 32;
const MAX_MATH_BLOCKS = 32;
const MAX_MATH_BYTES = 16 * 1024;
const FORBIDDEN_OBJECT_KEYS = new Set(["__proto__", "prototype", "constructor"]);

const marked = new Marked({
  async: false,
  breaks: true,
  gfm: true,
});

function citationHtml(source: string) {
  return source.replace(CITATION_PATTERN, (match, id, figureId) => {
    const citationId = String(id || figureId || "").trim();
    return `<button type="button" class="citation-badge" data-cite-id="${escapeAttribute(citationId)}">${escapeHtml(match)}</button>`;
  });
}

export function renderSafeMarkdown(source: string) {
  const math = protectMath(citationHtml(source || ""));
  const rendered = marked.parse(math.source);
  const html = typeof rendered === "string" ? restoreMath(rendered, math.blocks) : "";
  const sanitized = DOMPurify.sanitize(html, {
    ADD_ATTR: ["data-cite-id", "target", "rel"],
    ADD_TAGS: [
      "annotation", "mfrac", "mi", "mn", "mo", "mrow", "ms", "mspace", "msqrt",
      "mstyle", "msub", "msup", "mtable", "mtd", "mtext", "mtr", "munder", "mover",
      "math", "semantics",
    ],
    FORBID_ATTR: ["style"],
    FORBID_TAGS: ["base", "form", "iframe", "object", "script", "style"],
    ALLOWED_URI_REGEXP: /^(?:(?:https?|mailto):|\/|#)/i,
  });
  return hardenHtml(sanitized);
}

type MathBlock = { token: string; source: string; displayMode: boolean; html: string };

/**
 * Protect code before extracting formulas. KaTeX receives no trusted HTML and
 * all formulas are bounded, non-throwing renders before DOMPurify runs.
 */
function protectMath(source: string) {
  const protectedCode: string[] = [];
  let masked = source.replace(/(```[\s\S]*?```|~~~[\s\S]*?~~~)/g, match => {
    const token = `MATHCODEBLOCK${protectedCode.length}END`;
    protectedCode.push(match);
    return token;
  });
  masked = masked.replace(/(`[^`\n]+`)/g, match => {
    const token = `MATHINLINECODE${protectedCode.length}END`;
    protectedCode.push(match);
    return token;
  });
  const blocks: MathBlock[] = [];
  MATH_PATTERN.lastIndex = 0;
  masked = masked.replace(MATH_PATTERN, (match, dollar, bracket, inlineDollar, paren) => {
    if (blocks.length >= MAX_MATH_BLOCKS) return match;
    const expression = String(dollar || bracket || inlineDollar || paren || "").trim();
    if (!expression || new TextEncoder().encode(expression).length > MAX_MATH_BYTES) return match;
    const displayMode = Boolean(dollar || bracket);
    let html: string;
    try {
      html = katex.renderToString(expression, {
        displayMode,
        output: "htmlAndMathml",
        throwOnError: false,
        strict: "warn",
        trust: false,
      });
    } catch {
      return match;
    }
    const token = `MATHFORMULA${blocks.length}END`;
    blocks.push({ token, source: expression, displayMode, html });
    return token;
  });
  protectedCode.forEach((value, index) => {
    masked = masked.replace(`MATHCODEBLOCK${index}END`, value)
      .replace(`MATHINLINECODE${index}END`, value);
  });
  return { source: masked, blocks };
}

function restoreMath(html: string, blocks: MathBlock[]) {
  return blocks.reduce((current, block) => {
    const escaped = block.token.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    return current.replace(new RegExp(escaped, "g"), block.html);
  }, html);
}

export function parseRichMessage(content: string): RichMessageSegment[] {
  if (!content) return [];
  const segments: RichMessageSegment[] = [];
  let offset = 0;
  let sqlMatch: RegExpExecArray | null;
  SQL_PLAN_PATTERN.lastIndex = 0;
  while ((sqlMatch = SQL_PLAN_PATTERN.exec(content)) !== null) {
    appendMarkedSegments(content.slice(offset, sqlMatch.index), segments);
    const raw = (sqlMatch[1] || "").trim();
    const parsed = parseStructuredObject(raw, "SQL Plan");
    segments.push({
      id: segmentId(segments.length, "sql-plan", raw),
      type: "sql-plan",
      content: raw,
      plan: parsed.value,
      error: parsed.error,
    });
    offset = SQL_PLAN_PATTERN.lastIndex;
  }
  appendMarkedSegments(content.slice(offset), segments);
  return segments;
}

function appendMarkedSegments(source: string, target: RichMessageSegment[]) {
  if (!source) return;
  const tokens = marked.lexer(source);
  let markdown = "";
  const flushMarkdown = () => {
    if (!markdown.trim()) {
      markdown = "";
      return;
    }
    target.push({
      id: segmentId(target.length, "markdown", markdown),
      type: "markdown",
      content: markdown,
      html: renderSafeMarkdown(markdown),
    });
    markdown = "";
  };

  for (const token of tokens) {
    if (token.type !== "code") {
      markdown += token.raw;
      continue;
    }
    flushMarkdown();
    appendCodeToken(token as Tokens.Code, target);
  }
  flushMarkdown();
}

function hardenHtml(html: string) {
  const template = document.createElement("template");
  template.innerHTML = html;
  template.content.querySelectorAll<HTMLAnchorElement>("a").forEach(element => {
    const href = element.getAttribute("href");
    if (href) element.setAttribute("href", resolveGeneratedFileHref(href));
  });
  const links: string[] = [];
  let textWithPlaceholders = template.innerHTML.replace(/<a\b[^>]*>[\s\S]*?<\/a>/gi, match => {
    links.push(match);
    return `###GENERATED_LINK_PLACEHOLDER_${links.length - 1}###`;
  });
  textWithPlaceholders = linkifyGeneratedFileUrls(textWithPlaceholders);
  template.innerHTML = textWithPlaceholders.replace(
    /###GENERATED_LINK_PLACEHOLDER_(\d+)###/g,
    (_match, index) => links[Number(index)] || "",
  );
  template.content.querySelectorAll("base, form, iframe, object, script, style").forEach(element => element.remove());
  template.content.querySelectorAll("*").forEach(element => {
    for (const attribute of [...element.attributes]) {
      const name = attribute.name.toLowerCase();
      if (name.startsWith("on") || name === "srcdoc" || name === "style") {
        element.removeAttribute(attribute.name);
        continue;
      }
      if ((name === "href" || name === "src") && !safeRenderedUri(attribute.value, name === "src")) {
        element.removeAttribute(attribute.name);
      }
    }
    if (element instanceof HTMLAnchorElement && element.hasAttribute("href")) {
      element.target = "_blank";
      element.rel = "noopener noreferrer";
    }
  });
  return template.innerHTML;
}

function safeRenderedUri(value: string, image: boolean) {
  const normalized = value.trim();
  if (!normalized || normalized.startsWith("#") || normalized.startsWith("/")) return true;
  try {
    const url = new URL(normalized, window.location.origin);
    if (["http:", "https:"].includes(url.protocol)) return true;
    return !image && url.protocol === "mailto:";
  } catch {
    return false;
  }
}

function appendCodeToken(token: Tokens.Code, target: RichMessageSegment[]) {
  const language = normalizeLanguage(token.lang);
  const content = token.text || "";
  const complete = /```\s*$/.test(token.raw) || /~~~\s*$/.test(token.raw);
  if (language === "mermaid") {
    target.push({
      id: segmentId(target.length, "mermaid", content),
      type: "mermaid",
      content,
      complete,
    });
    return;
  }
  if (CHART_LANGUAGES.has(language)) {
    const parsed = complete
      ? parseStructuredObject(content, "图表配置")
      : { value: null, error: null };
    target.push({
      id: segmentId(target.length, "chart", content),
      type: "chart",
      content,
      option: parsed.value,
      error: parsed.error,
      complete,
    });
    return;
  }
  target.push({
    id: segmentId(target.length, "code", content),
    type: "code",
    content,
    language: language || "text",
    runnable: RUNNABLE_LANGUAGES.has(language),
    complete,
  });
}

function parseStructuredObject(
  source: string,
  label: string,
): { value: Record<string, unknown> | null; error: string | null } {
  if (new TextEncoder().encode(source).length > MAX_STRUCTURED_BLOCK_BYTES) {
    return { value: null, error: `${label}超过 256KB 限制` };
  }
  try {
    const parsed: unknown = JSON.parse(source);
    if (!isSafeObject(parsed, 0)) {
      return { value: null, error: `${label}必须是安全的 JSON 对象` };
    }
    return { value: parsed, error: null };
  } catch {
    return { value: null, error: `${label}不是合法 JSON` };
  }
}

function isSafeObject(value: unknown, depth: number): value is Record<string, unknown> {
  if (depth > MAX_STRUCTURED_DEPTH || value === null || Array.isArray(value) || typeof value !== "object") {
    return false;
  }
  return Object.entries(value).every(([key, nested]) => {
    if (FORBIDDEN_OBJECT_KEYS.has(key)) return false;
    return isSafeValue(nested, depth + 1);
  });
}

function isSafeValue(value: unknown, depth: number): boolean {
  if (depth > MAX_STRUCTURED_DEPTH) return false;
  if (value === null || ["string", "boolean"].includes(typeof value)) return true;
  if (typeof value === "number") return Number.isFinite(value);
  if (Array.isArray(value)) return value.length <= 10000 && value.every(item => isSafeValue(item, depth + 1));
  if (typeof value !== "object") return false;
  return Object.entries(value).every(
    ([key, nested]) => !FORBIDDEN_OBJECT_KEYS.has(key) && isSafeValue(nested, depth + 1),
  );
}

function normalizeLanguage(language: string | undefined) {
  return String(language || "")
    .trim()
    .split(/\s+/)[0]
    ?.toLowerCase() || "";
}

function segmentId(index: number, type: RichMessageSegment["type"], content: string) {
  let hash = 2166136261;
  for (let cursor = 0; cursor < content.length; cursor += 1) {
    hash ^= content.charCodeAt(cursor);
    hash = Math.imul(hash, 16777619);
  }
  return `${index}-${type}-${(hash >>> 0).toString(36)}`;
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

function escapeAttribute(value: string) {
  return escapeHtml(value).replace(/`/g, "&#96;");
}
