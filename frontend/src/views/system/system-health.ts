import type { SystemDiagnosticCheck, SystemHealthComponent, SystemHealthStatus, SystemRuntimeMetrics } from '@/service/api';

type TagType = 'default' | 'error' | 'success' | 'warning';

const statusLabels: Record<string, string> = {
  healthy: '健康',
  degraded: '降级',
  unavailable: '不可用',
  disabled: '未启用'
};

const componentLabels: Record<string, string> = {
  application: '应用服务',
  database: '主数据库',
  redis: 'Redis',
  agentRuntime: 'Agent 运行时'
};

const detailLabels: Record<string, string> = {
  name: '应用名称',
  product: '产品',
  version: '版本',
  readOnly: '只读连接',
  keyCount: '键数量',
  implementation: '运行实现',
  currentVersion: '当前版本',
  expectedVersion: '要求版本',
  pending: '待发布',
  failed: '失败',
  due: '已到期',
  taskExpired: '任务过期租约',
  sandboxExpired: '沙箱过期租约',
  reportExpired: '报表过期租约',
  active: '健康实例',
  stale: '失联实例',
  tracked: '已跟踪 Provider',
  open: '熔断打开',
  halfOpen: '半开探测'
};

export function systemHealthStatusText(status: SystemHealthStatus) {
  return statusLabels[status] || status || '未知';
}

export function systemHealthStatusType(status: SystemHealthStatus): TagType {
  if (status === 'healthy') return 'success';
  if (status === 'degraded') return 'warning';
  if (status === 'unavailable') return 'error';
  return 'default';
}

export function systemHealthComponentName(component: Pick<SystemHealthComponent, 'key' | 'name'>) {
  return componentLabels[component.key] || component.name || component.key;
}

export function formatSystemHealthBytes(value: number | null | undefined) {
  if (value === null || value === undefined || !Number.isFinite(value)) return '-';
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  if (value < 1024 * 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`;
  return `${(value / 1024 / 1024 / 1024).toFixed(1)} GB`;
}

export function formatSystemHealthUptime(seconds: number | null | undefined) {
  if (seconds === null || seconds === undefined || !Number.isFinite(seconds) || seconds < 0) return '-';
  const total = Math.floor(seconds);
  const days = Math.floor(total / 86400);
  const hours = Math.floor((total % 86400) / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  return days > 0 ? `${days}天 ${hours}小时` : `${hours}小时 ${minutes}分钟`;
}

export function componentDetailEntries(component: SystemHealthComponent) {
  const details = component.details || {};
  return Object.entries(details).map(([key, value]) => ({
    key,
    label: detailLabels[key] || key,
    value: typeof value === 'boolean' ? (value ? '是' : '否') : String(value ?? '-')
  }));
}

export function diagnosticMetricEntries(check: Pick<SystemDiagnosticCheck, 'metrics'>) {
  return Object.entries(check.metrics || {}).map(([key, value]) => ({
    key,
    label: detailLabels[key] || key,
    value: typeof value === 'boolean' ? (value ? '是' : '否') : String(value ?? '-')
  }));
}

export function runtimeMetricEntries(runtime: SystemRuntimeMetrics) {
  return [
    { key: 'java', label: 'Java', value: runtime.javaVersion || '-' },
    { key: 'vm', label: '虚拟机', value: runtime.vmName || '-' },
    { key: 'processors', label: '处理器', value: `${runtime.availableProcessors || 0} 核` },
    { key: 'uptime', label: '运行时长', value: formatSystemHealthUptime(runtime.uptimeSeconds) },
    { key: 'heap', label: '堆内存', value: `${formatSystemHealthBytes(runtime.heapUsedBytes)} / ${formatSystemHealthBytes(runtime.heapMaxBytes)}` },
    { key: 'threads', label: '活动线程', value: String(runtime.liveThreads || 0) },
    { key: 'load', label: '系统负载', value: runtime.systemLoadAverage === null ? '-' : runtime.systemLoadAverage.toFixed(2) }
  ];
}
