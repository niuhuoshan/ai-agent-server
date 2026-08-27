import { describe, expect, it } from 'vitest';
import { formatRedisTtl, redisTypeLabel, redisValueRows } from './redis-operations';

describe('Redis operations presentation helpers', () => {
  it('formats Redis types and TTL semantics', () => {
    expect(redisTypeLabel('hash')).toBe('哈希');
    expect(redisTypeLabel('stream')).toBe('流');
    expect(formatRedisTtl(-1)).toBe('永久');
    expect(formatRedisTtl(-2)).toBe('已过期');
    expect(formatRedisTtl(3660)).toBe('1 小时 1 分');
  });

  it('turns maps and lists into structured detail rows', () => {
    expect(redisValueRows({ state: 'ready', count: 2 })).toEqual([
      { key: 'state', label: 'state', value: 'ready' },
      { key: 'count', label: 'count', value: '2' }
    ]);
    expect(redisValueRows(['a', 'b']).map(row => row.label)).toEqual(['第 1 项', '第 2 项']);
  });
});
