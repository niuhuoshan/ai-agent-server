export function redisTypeLabel(type: string) {
  const labels: Record<string, string> = {
    string: '字符串', hash: '哈希', list: '列表', set: '集合', zset: '有序集合', stream: '流', json: 'JSON'
  };
  return labels[type] || type || '未知';
}

export function formatRedisTtl(seconds: number | null | undefined) {
  if (seconds === -1) return '永久';
  if (seconds === -2) return '已过期';
  if (seconds === null || seconds === undefined || !Number.isFinite(seconds) || seconds < 0) return '-';
  if (seconds < 60) return `${seconds} 秒`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时 ${Math.floor((seconds % 3600) / 60)} 分`;
  return `${Math.floor(seconds / 86400)} 天 ${Math.floor((seconds % 86400) / 3600)} 小时`;
}

function displayValue(value: unknown) {
  if (value === null) return 'null';
  if (value === undefined) return '-';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

export interface RedisValueRow {
  key: string;
  label: string;
  value: string;
}

export function redisValueRows(value: unknown): RedisValueRow[] {
  if (Array.isArray(value)) {
    return value.map((item, index) => ({ key: String(index), label: `第 ${index + 1} 项`, value: displayValue(item) }));
  }
  if (value && typeof value === 'object') {
    return Object.entries(value).map(([key, item]) => ({ key, label: key, value: displayValue(item) }));
  }
  return [{ key: 'value', label: '值', value: displayValue(value) }];
}
