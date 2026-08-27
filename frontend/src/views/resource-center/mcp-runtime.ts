type TagType = 'default' | 'error' | 'success' | 'warning';

const healthLabels: Record<string, string> = {
  unknown: '暂无运行数据',
  healthy: '健康',
  degraded: '降级',
  unavailable: '不可用'
};

const circuitLabels: Record<string, string> = {
  closed: '关闭',
  open: '已熔断',
  half_open: '半开探测'
};

const mountLabels: Record<string, string> = {
  mounting: '挂载中',
  mounted: '已挂载',
  idle: '空闲保活',
  degraded: '连接降级',
  closed: '已释放',
  expired: '空闲过期',
  abandoned: '异常回收'
};

const usageLabels: Record<string, string> = {
  success: '成功',
  provider_error: '服务执行错误',
  transport_error: '传输错误',
  circuit_open: '熔断拒绝'
};

export function runtimeHealthText(status: string) {
  return healthLabels[status] || status;
}

export function runtimeHealthType(status: string): TagType {
  if (status === 'healthy') return 'success';
  if (status === 'degraded') return 'warning';
  if (status === 'unavailable') return 'error';
  return 'default';
}

export function circuitStateText(status: string) {
  return circuitLabels[status] || status;
}

export function mountStatusText(status: string) {
  return mountLabels[status] || status;
}

export function mountStatusType(status: string): TagType {
  if (status === 'mounted') return 'success';
  if (['mounting', 'idle'].includes(status)) return 'warning';
  if (['degraded', 'abandoned'].includes(status)) return 'error';
  return 'default';
}

export function usageStatusText(status: string) {
  return usageLabels[status] || status;
}

export function usageStatusType(status: string): TagType {
  if (status === 'success') return 'success';
  if (status === 'provider_error') return 'warning';
  return 'error';
}

export function formatMcpBytes(value: number | null) {
  if (value === null) return '-';
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}
