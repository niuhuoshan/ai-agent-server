import type { NhsV1TraceDataExportFormat } from '@/service/api';

export function traceDataExportKey(traceId: string, format: NhsV1TraceDataExportFormat) {
  return JSON.stringify([traceId.trim(), format]);
}

export function triggerTraceDataDownload(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
}

export function traceDataExportErrorMessage(error: unknown, format: NhsV1TraceDataExportFormat) {
  const detail = error instanceof Error && error.message.trim() ? error.message.trim() : '请求未完成，请稍后重试';
  return `${format.toUpperCase()} 数据导出失败：${detail}`;
}
