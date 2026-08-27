import { describe, expect, it } from 'vitest';
import {
  circuitStateText,
  formatMcpBytes,
  mountStatusText,
  runtimeHealthType,
  usageStatusText
} from './mcp-runtime';

describe('MCP runtime presentation', () => {
  it('keeps circuit and transport failures visibly distinct', () => {
    expect(circuitStateText('open')).toBe('已熔断');
    expect(usageStatusText('transport_error')).toBe('传输错误');
    expect(usageStatusText('circuit_open')).toBe('熔断拒绝');
    expect(runtimeHealthType('unavailable')).toBe('error');
  });

  it('shows retained and terminal mount lifecycle states', () => {
    expect(mountStatusText('idle')).toBe('空闲保活');
    expect(mountStatusText('expired')).toBe('空闲过期');
    expect(mountStatusText('abandoned')).toBe('异常回收');
  });

  it('formats persisted request and response byte counts', () => {
    expect(formatMcpBytes(null)).toBe('-');
    expect(formatMcpBytes(512)).toBe('512 B');
    expect(formatMcpBytes(1536)).toBe('1.5 KB');
  });
});
