import DOMPurify from "dompurify";
import { csvParseRows } from "d3-dsv";
import type {
  CanvasContentType,
  CanvasVersionView,
  CanvasView,
  ConversationMessageView,
} from "@/service/api";

export interface CanvasDraft {
  id: string | null;
  title: string;
  contentType: CanvasContentType;
  content: string;
  metadata: Record<string, unknown>;
  sourceMessageId: string | null;
  currentVersion: number | null;
}

export interface CanvasCsvData {
  columns: string[];
  rows: Record<string, string>[];
  totalRows: number;
  truncated: boolean;
  error: string | null;
}

export interface CanvasCsvPivotRow {
  group: string;
  count: number;
  value: number;
}

export interface CanvasCompareSide {
  label: string;
  content: string;
}

export interface CanvasCompareData {
  left: CanvasCompareSide;
  right: CanvasCompareSide;
  error: string | null;
}

export interface CanvasCompareLine {
  key: string;
  left: string | null;
  right: string | null;
  state: "same" | "changed" | "removed" | "added";
}

const MAX_CSV_BYTES = 5 * 1024 * 1024;
const MAX_CSV_ROWS = 10_000;
const MAX_CSV_COLUMNS = 100;
const MAX_MEDIA_BYTES = 20 * 1024 * 1024;
const SAFE_IMAGE_MIME_TYPES = new Set([
  "image/avif",
  "image/gif",
  "image/jpeg",
  "image/png",
  "image/webp",
]);
const CONTENT_TYPE_EXTENSIONS: Record<CanvasContentType, string> = {
  markdown: "md",
  html: "html",
  code: "txt",
  mermaid: "mmd",
  pdf: "pdf",
  csv: "csv",
  image: "png",
  compare: "json",
};

export function createCanvasDraft(
  seed: Partial<Omit<CanvasDraft, "metadata">> & { metadata?: Record<string, unknown> } = {},
): CanvasDraft {
  return {
    id: seed.id ?? null,
    title: seed.title ?? "未命名 Canvas",
    contentType: seed.contentType ?? "markdown",
    content: seed.content ?? "",
    metadata: cloneMetadata(seed.metadata),
    sourceMessageId: seed.sourceMessageId ?? null,
    currentVersion: seed.currentVersion ?? null,
  };
}

export function canvasDraftFromView(canvas: CanvasView): CanvasDraft {
  return createCanvasDraft({
    id: canvas.id,
    title: canvas.title,
    contentType: canvas.contentType,
    content: canvas.content,
    metadata: canvas.metadata,
    sourceMessageId: canvas.sourceMessageId,
    currentVersion: canvas.currentVersion,
  });
}

export function canvasDraftFromVersion(canvas: CanvasView, version: CanvasVersionView): CanvasDraft {
  return createCanvasDraft({
    id: canvas.id,
    title: version.title,
    contentType: version.contentType,
    content: version.content,
    metadata: version.metadata,
    sourceMessageId: canvas.sourceMessageId,
    currentVersion: canvas.currentVersion,
  });
}

export function canvasDraftSignature(draft: CanvasDraft | null) {
  if (!draft) return "";
  return JSON.stringify({
    title: draft.title,
    contentType: draft.contentType,
    content: draft.content,
    metadata: sortJsonValue(draft.metadata),
    sourceMessageId: draft.sourceMessageId,
  });
}

export function isCanvasDraftDirty(draft: CanvasDraft | null, baselineSignature: string) {
  return Boolean(draft && canvasDraftSignature(draft) !== baselineSignature);
}

export function canvasDraftFromMessage(message: ConversationMessageView): CanvasDraft {
  const inferred = inferCanvasContent(message.content || "");
  const speaker = message.role === "assistant" ? "Agent 回复" : "用户消息";
  return createCanvasDraft({
    title: `${speaker} Canvas`,
    contentType: inferred.contentType,
    content: inferred.content,
    sourceMessageId: message.id,
    metadata: {
      ...inferred.metadata,
      sourceMessageId: message.id,
      sourceRole: message.role,
      sourceCreatedAt: message.createdAt,
    },
  });
}

export function inferCanvasContent(source: string): {
  contentType: CanvasContentType;
  content: string;
  metadata: Record<string, unknown>;
} {
  const content = source.trim();
  const fence = /^```([^\n`]*)\n([\s\S]*?)\n```\s*$/.exec(content);
  if (fence) {
    const language = (fence[1] || "").trim().toLowerCase();
    const fencedContent = fence[2] || "";
    if (language === "mermaid") {
      return { contentType: "mermaid", content: fencedContent, metadata: {} };
    }
    if (language === "csv") {
      return { contentType: "csv", content: fencedContent, metadata: {} };
    }
    if (["html", "htm"].includes(language)) {
      return { contentType: "html", content: fencedContent, metadata: {} };
    }
    return {
      contentType: "code",
      content: fencedContent,
      metadata: { language: language || "text" },
    };
  }
  if (/^data:image\/(?:avif|gif|jpeg|png|webp);base64,/i.test(content)) {
    const mimeType = content.slice(5, content.indexOf(";"));
    return { contentType: "image", content, metadata: { encoding: "data-url", mimeType } };
  }
  if (/^data:application\/pdf;base64,/i.test(content)) {
    return {
      contentType: "pdf",
      content,
      metadata: { encoding: "data-url", mimeType: "application/pdf" },
    };
  }
  if (/^(?:<!doctype\s+html|<html[\s>]|<(?:article|div|main|section|table)[\s>])/i.test(content)) {
    return { contentType: "html", content, metadata: {} };
  }
  const csv = parseCanvasCsv(content, 3);
  if (!csv.error && csv.columns.length > 1 && csv.totalRows > 0) {
    return { contentType: "csv", content, metadata: {} };
  }
  return { contentType: "markdown", content: source, metadata: {} };
}

export function parseCanvasCsv(content: string, rowLimit = 500): CanvasCsvData {
  if (!content.trim()) {
    return { columns: [], rows: [], totalRows: 0, truncated: false, error: null };
  }
  if (new TextEncoder().encode(content).length > MAX_CSV_BYTES) {
    return {
      columns: [],
      rows: [],
      totalRows: 0,
      truncated: false,
      error: "CSV 超过 5MB 预览限制",
    };
  }
  try {
    const parsed = csvParseRows(content);
    if (!parsed.length) {
      return { columns: [], rows: [], totalRows: 0, truncated: false, error: null };
    }
    if (parsed.length > MAX_CSV_ROWS + 1) {
      return {
        columns: [],
        rows: [],
        totalRows: parsed.length - 1,
        truncated: false,
        error: `CSV 超过 ${MAX_CSV_ROWS.toLocaleString()} 行预览限制`,
      };
    }
    const width = Math.max(...parsed.map(row => row.length));
    if (width > MAX_CSV_COLUMNS) {
      return {
        columns: [],
        rows: [],
        totalRows: Math.max(0, parsed.length - 1),
        truncated: false,
        error: `CSV 超过 ${MAX_CSV_COLUMNS} 列预览限制`,
      };
    }
    const columns = uniqueColumnNames(parsed[0] || [], width);
    const body = parsed.slice(1);
    const limited = body.slice(0, Math.max(1, rowLimit));
    const rows = limited.map(row =>
      Object.fromEntries(columns.map((column, index) => [column, row[index] ?? ""])),
    );
    return {
      columns,
      rows,
      totalRows: body.length,
      truncated: limited.length < body.length,
      error: null,
    };
  } catch (error) {
    return {
      columns: [],
      rows: [],
      totalRows: 0,
      truncated: false,
      error: error instanceof Error ? `CSV 解析失败：${error.message}` : "CSV 解析失败",
    };
  }
}

export function createCanvasCsvPivot(
  data: CanvasCsvData,
  groupColumn: string,
  valueColumn: string | null,
  aggregate: "count" | "sum" | "average",
): CanvasCsvPivotRow[] {
  if (data.error || !data.columns.includes(groupColumn)) return [];
  const groups = new Map<string, { count: number; sum: number; numericCount: number }>();
  for (const row of data.rows) {
    const group = row[groupColumn] || "（空值）";
    const current = groups.get(group) || { count: 0, sum: 0, numericCount: 0 };
    current.count += 1;
    const numeric = valueColumn ? Number(row[valueColumn]) : Number.NaN;
    if (Number.isFinite(numeric)) {
      current.sum += numeric;
      current.numericCount += 1;
    }
    groups.set(group, current);
  }
  return [...groups.entries()].map(([group, state]) => ({
    group,
    count: state.count,
    value:
      aggregate === "count"
        ? state.count
        : aggregate === "sum"
          ? state.sum
          : state.numericCount
            ? state.sum / state.numericCount
            : 0,
  }));
}

export function parseCanvasCompare(
  content: string,
  metadata: Record<string, unknown>,
): CanvasCompareData {
  const metadataLeft = typeof metadata.leftContent === "string" ? metadata.leftContent : null;
  const metadataRight = typeof metadata.rightContent === "string" ? metadata.rightContent : null;
  const leftLabel = metadataLabel(metadata.leftLabel, metadata.leftPath, "左侧");
  const rightLabel = metadataLabel(metadata.rightLabel, metadata.rightPath, "右侧");
  if (metadataLeft !== null && metadataRight !== null) {
    return {
      left: { label: leftLabel, content: metadataLeft },
      right: { label: rightLabel, content: metadataRight },
      error: null,
    };
  }
  try {
    const parsed: unknown = JSON.parse(content);
    if (isPlainRecord(parsed) && typeof parsed.left === "string" && typeof parsed.right === "string") {
      return {
        left: {
          label: typeof parsed.leftLabel === "string" ? parsed.leftLabel : leftLabel,
          content: parsed.left,
        },
        right: {
          label: typeof parsed.rightLabel === "string" ? parsed.rightLabel : rightLabel,
          content: parsed.right,
        },
        error: null,
      };
    }
  } catch {
    // The explicit text format below remains available for non-JSON content.
  }
  const marker = "\n======= CANVAS COMPARE =======\n";
  const markerIndex = content.indexOf(marker);
  if (markerIndex >= 0) {
    return {
      left: { label: leftLabel, content: content.slice(0, markerIndex) },
      right: { label: rightLabel, content: content.slice(markerIndex + marker.length) },
      error: null,
    };
  }
  return {
    left: { label: leftLabel, content: "" },
    right: { label: rightLabel, content: "" },
    error: "比较内容需要 JSON { left, right }，或使用 CANVAS COMPARE 分隔线",
  };
}

export function buildCanvasLineComparison(leftSource: string, rightSource: string): CanvasCompareLine[] {
  const left = leftSource.split("\n");
  const right = rightSource.split("\n");
  if (left.length > 500 || right.length > 500) {
    const length = Math.max(left.length, right.length);
    return Array.from({ length }, (_, index) => compareLine(left[index], right[index], index));
  }
  const matrix = Array.from({ length: left.length + 1 }, () => new Uint16Array(right.length + 1));
  for (let leftIndex = left.length - 1; leftIndex >= 0; leftIndex -= 1) {
    for (let rightIndex = right.length - 1; rightIndex >= 0; rightIndex -= 1) {
      matrix[leftIndex]![rightIndex] = left[leftIndex] === right[rightIndex]
        ? (matrix[leftIndex + 1]?.[rightIndex + 1] || 0) + 1
        : Math.max(matrix[leftIndex + 1]?.[rightIndex] || 0, matrix[leftIndex]?.[rightIndex + 1] || 0);
    }
  }
  const lines: CanvasCompareLine[] = [];
  let leftIndex = 0;
  let rightIndex = 0;
  while (leftIndex < left.length || rightIndex < right.length) {
    if (left[leftIndex] === right[rightIndex] && leftIndex < left.length && rightIndex < right.length) {
      lines.push(compareLine(left[leftIndex], right[rightIndex], lines.length));
      leftIndex += 1;
      rightIndex += 1;
      continue;
    }
    const removeScore = matrix[leftIndex + 1]?.[rightIndex] || 0;
    const addScore = matrix[leftIndex]?.[rightIndex + 1] || 0;
    if (leftIndex < left.length && (rightIndex >= right.length || removeScore >= addScore)) {
      lines.push(compareLine(left[leftIndex], undefined, lines.length));
      leftIndex += 1;
    } else {
      lines.push(compareLine(undefined, right[rightIndex], lines.length));
      rightIndex += 1;
    }
  }
  return lines;
}

export function sanitizedCanvasHtmlDocument(source: string) {
  const sanitized = DOMPurify.sanitize(source, {
    WHOLE_DOCUMENT: false,
    ADD_TAGS: ["style"],
    FORBID_TAGS: ["base", "embed", "form", "iframe", "link", "meta", "object", "script"],
    FORBID_ATTR: ["srcdoc"],
    ALLOWED_URI_REGEXP: /^(?:(?:https?):|\/|#|data:image\/(?:avif|gif|jpeg|png|webp);base64,)/i,
  });
  const template = document.createElement("template");
  template.innerHTML = sanitized;
  template.content.querySelectorAll("*").forEach(element => {
    for (const attribute of [...element.attributes]) {
      const name = attribute.name.toLowerCase();
      if (name.startsWith("on") || name === "srcdoc") element.removeAttribute(attribute.name);
    }
    if (element instanceof HTMLAnchorElement) {
      element.target = "_blank";
      element.rel = "noopener noreferrer";
    }
  });
  return [
    "<!doctype html><html><head><meta charset=\"utf-8\">",
    "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data: blob:; style-src 'unsafe-inline'; font-src 'none'; form-action 'none'; base-uri 'none'\">",
    "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">",
    "<style>body{margin:16px;font:14px/1.6 system-ui,sans-serif;color:#202124;overflow-wrap:anywhere}table{border-collapse:collapse}th,td{border:1px solid #d5d8dc;padding:6px 8px}img{max-width:100%;height:auto}pre{white-space:pre-wrap}</style>",
    "</head><body>",
    template.innerHTML,
    "</body></html>",
  ].join("");
}

export function resolveCanvasMediaSource(
  content: string,
  contentType: "image" | "pdf",
  metadata: Record<string, unknown>,
  origin = window.location.origin,
): { url: string | null; requiresAuthorization: boolean; mimeType: string; error: string | null } {
  const requestedMime = typeof metadata.mimeType === "string" ? metadata.mimeType.toLowerCase() : "";
  const defaultMime = contentType === "pdf" ? "application/pdf" : "image/png";
  const mimeType = requestedMime || defaultMime;
  if (!isSafeCanvasMimeType(contentType, mimeType)) {
    return { url: null, requiresAuthorization: false, mimeType, error: "不支持或不安全的媒体类型" };
  }
  const encoding = typeof metadata.encoding === "string" ? metadata.encoding.toLowerCase() : "data-url";
  if (encoding === "base64") {
    const base64 = content.replace(/\s+/g, "");
    if (!/^[a-z\d+/]*={0,2}$/i.test(base64) || base64.length % 4 === 1) {
      return { url: null, requiresAuthorization: false, mimeType, error: "Base64 内容无效" };
    }
    if (Math.ceil((base64.length * 3) / 4) > MAX_MEDIA_BYTES) {
      return { url: null, requiresAuthorization: false, mimeType, error: "媒体内容超过 20MB 预览限制" };
    }
    return {
      url: `data:${mimeType};base64,${base64}`,
      requiresAuthorization: false,
      mimeType,
      error: null,
    };
  }
  if (encoding === "data-url") {
    const match = /^data:([^;,]+);base64,([a-z\d+/\s]*={0,2})$/i.exec(content.trim());
    if (!match || match[1]?.toLowerCase() !== mimeType) {
      return { url: null, requiresAuthorization: false, mimeType, error: "Data URL 与媒体类型不匹配" };
    }
    const base64 = (match[2] || "").replace(/\s+/g, "");
    if (Math.ceil((base64.length * 3) / 4) > MAX_MEDIA_BYTES) {
      return { url: null, requiresAuthorization: false, mimeType, error: "媒体内容超过 20MB 预览限制" };
    }
    return { url: content.trim(), requiresAuthorization: false, mimeType, error: null };
  }
  if (encoding === "url") {
    try {
      const url = new URL(content.trim(), origin);
      const allowedPath = url.pathname.startsWith("/api/")
        || url.pathname.startsWith("/platform/")
        || url.pathname.includes("/platform/");
      if (url.origin !== origin || !allowedPath || url.username || url.password) {
        return { url: null, requiresAuthorization: false, mimeType, error: "仅允许同源平台授权地址" };
      }
      return { url: url.href, requiresAuthorization: true, mimeType, error: null };
    } catch {
      return { url: null, requiresAuthorization: false, mimeType, error: "媒体地址无效" };
    }
  }
  return { url: null, requiresAuthorization: false, mimeType, error: "不支持的媒体编码" };
}

export function validateCanvasWorkspacePath(path: string) {
  const normalized = path.trim();
  if (!normalized) return null;
  if (
    normalized.length > 512
    || normalized.startsWith("/")
    || normalized.includes("\\")
    || normalized.includes(":")
    || normalized.includes("\0")
    || normalized.split("/").some(part => part === "..")
  ) {
    return "工作区路径必须是 512 字符内、且不越界的相对路径";
  }
  return null;
}

export function suggestedCanvasWorkspacePath(draft: CanvasDraft) {
  const metadataName = typeof draft.metadata.fileName === "string" ? draft.metadata.fileName.trim() : "";
  if (metadataName && !validateCanvasWorkspacePath(metadataName)) return metadataName;
  const safeName = draft.title
    .trim()
    .toLowerCase()
    .replace(/[^\p{L}\p{N}._-]+/gu, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 80) || "canvas";
  return `canvas/${safeName}.${CONTENT_TYPE_EXTENSIONS[draft.contentType]}`;
}

export function canvasRequestError(
  error: { message?: string; response?: { status?: number; data?: unknown } } | null,
  fallback: string,
) {
  const status = error?.response?.status;
  const data = error?.response?.data;
  const backendMessage = isPlainRecord(data)
    ? typeof data.msg === "string"
      ? data.msg
      : typeof data.message === "string"
        ? data.message
        : null
    : null;
  const message = backendMessage || error?.message || fallback;
  if (status === 403) return { status, title: "没有 Canvas 权限", message };
  if (status === 404) return { status, title: "Canvas 不存在", message };
  if (status === 409) return { status, title: "Canvas 版本冲突", message };
  if (status && status >= 500) return { status, title: "Canvas 服务暂不可用", message };
  return { status: status || 0, title: fallback, message };
}

function compareLine(left: string | undefined, right: string | undefined, index: number): CanvasCompareLine {
  return {
    key: `${index}:${left ?? ""}:${right ?? ""}`,
    left: left ?? null,
    right: right ?? null,
    state: left === undefined ? "added" : right === undefined ? "removed" : left === right ? "same" : "changed",
  };
}

function uniqueColumnNames(headers: string[], width: number) {
  const used = new Map<string, number>();
  return Array.from({ length: width }, (_, index) => {
    const base = headers[index]?.trim() || `列 ${index + 1}`;
    const count = (used.get(base) || 0) + 1;
    used.set(base, count);
    return count === 1 ? base : `${base} (${count})`;
  });
}

function metadataLabel(primary: unknown, path: unknown, fallback: string) {
  if (typeof primary === "string" && primary.trim()) return primary.trim();
  if (typeof path === "string" && path.trim()) return path.trim();
  return fallback;
}

function isSafeCanvasMimeType(contentType: "image" | "pdf", mimeType: string) {
  return contentType === "pdf" ? mimeType === "application/pdf" : SAFE_IMAGE_MIME_TYPES.has(mimeType);
}

function cloneMetadata(value: Record<string, unknown> | undefined) {
  if (!isPlainRecord(value)) return {};
  try {
    const clone: unknown = JSON.parse(JSON.stringify(value));
    return isPlainRecord(clone) ? clone : {};
  } catch {
    return {};
  }
}

function sortJsonValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortJsonValue);
  if (!isPlainRecord(value)) return value;
  return Object.fromEntries(
    Object.keys(value)
      .sort()
      .map(key => [key, sortJsonValue(value[key])]),
  );
}

function isPlainRecord(value: unknown): value is Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) return false;
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}
