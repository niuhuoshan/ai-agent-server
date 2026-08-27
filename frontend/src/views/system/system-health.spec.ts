import { describe, expect, it } from 'vitest';
import {
  componentDetailEntries,
  diagnosticMetricEntries,
  formatSystemHealthBytes,
  formatSystemHealthUptime,
  runtimeMetricEntries,
  systemHealthStatusText,
  systemHealthStatusType
} from './system-health';

describe('system health presentation helpers', () => {
  it('maps statuses to stable labels and tag types', () => {
    expect(systemHealthStatusText('healthy')).toBe('健康');
    expect(systemHealthStatusText('disabled')).toBe('未启用');
    expect(systemHealthStatusType('healthy')).toBe('success');
    expect(systemHealthStatusType('unavailable')).toBe('error');
    expect(systemHealthStatusType('disabled')).toBe('default');
  });

  it('formats memory and uptime without hiding zero values', () => {
    expect(formatSystemHealthBytes(0)).toBe('0 B');
    expect(formatSystemHealthBytes(1024 * 1024)).toBe('1.0 MB');
    expect(formatSystemHealthBytes(null)).toBe('-');
    expect(formatSystemHealthUptime(0)).toBe('0小时 0分钟');
    expect(formatSystemHealthUptime(90061)).toBe('1天 1小时');
  });

  it('converts component and runtime details to renderable rows', () => {
    expect(componentDetailEntries({
      key: 'redis', name: 'Redis', status: 'healthy', critical: true,
      message: '正常', responseTimeMs: 3, details: { keyCount: 2, readOnly: false }
    })).toEqual([
      { key: 'keyCount', label: '键数量', value: '2' },
      { key: 'readOnly', label: '只读连接', value: '否' }
    ]);
    expect(runtimeMetricEntries({
      javaVersion: '21', vmName: 'OpenJDK', availableProcessors: 4, uptimeSeconds: 60,
      heapUsedBytes: 1024, heapCommittedBytes: 2048, heapMaxBytes: 4096,
      liveThreads: 3, systemLoadAverage: null
    }).map(item => item.key)).toContain('heap');
    expect(diagnosticMetricEntries({ metrics: { currentVersion: '64', due: 2 } })).toEqual([
      { key: 'currentVersion', label: '当前版本', value: '64' },
      { key: 'due', label: '已到期', value: '2' }
    ]);
  });
});
