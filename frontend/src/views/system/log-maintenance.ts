export function formatStorageBytes(bytes: number | null | undefined) {
  if (bytes === null || bytes === undefined || !Number.isFinite(bytes) || bytes < 0) return '-';
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = bytes / 1024;
  let unit = units[0];
  for (let index = 1; index < units.length && value >= 1024; index += 1) {
    value /= 1024;
    unit = units[index];
  }
  return `${value >= 10 ? value.toFixed(1) : value.toFixed(2)} ${unit}`;
}

export function logStorageModeLabel(mode: string) {
  return mode === 'partitioned' ? '按月分区' : mode === 'regular' ? '普通表' : mode || '未知';
}

export function maintenanceStatusLabel(status: string) {
  const labels: Record<string, string> = {
    previewed: '等待确认',
    running: '执行中',
    succeeded: '已完成',
    partial: '部分完成',
    failed: '失败',
    expired: '确认已过期',
    skipped: '已跳过'
  };
  return labels[status] || status;
}

export function maintenanceStatusType(status: string): 'success' | 'warning' | 'error' | 'info' | 'default' {
  if (status === 'succeeded') return 'success';
  if (['partial', 'previewed', 'skipped'].includes(status)) return 'warning';
  if (status === 'failed') return 'error';
  if (status === 'running') return 'info';
  return 'default';
}

export function numericSummary(summary: Record<string, unknown> | null | undefined, key: string) {
  const value = summary?.[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function objectValue(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' ? (value as Record<string, unknown>) : null;
}

export function requestErrorStatus(error: unknown) {
  const response = objectValue(objectValue(error)?.response);
  const status = response?.status;
  return typeof status === 'number' && Number.isInteger(status) ? status : null;
}

export function requestErrorText(error: unknown, fallback: string) {
  const errorValue = objectValue(error);
  const response = objectValue(errorValue?.response);
  const data = objectValue(response?.data);
  const backendMessage = data?.message || data?.msg;
  if (typeof backendMessage === 'string' && backendMessage.trim()) return backendMessage;

  const message = errorValue?.message;
  return typeof message === 'string' && message.trim() ? message : fallback;
}
