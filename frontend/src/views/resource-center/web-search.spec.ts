import { describe, expect, it } from 'vitest';
import type { SearchProviderView } from '@/service/api/search';
import { searchCircuitText, searchFailureRate, searchProviderLabel } from './web-search';

const provider: SearchProviderView = {
  connectorId: '12',
  connectorKey: 'enterprise-search',
  name: '企业搜索',
  scope: 'global',
  manageable: true,
  engine: 'brave',
  endpointUrl: 'https://api.search.brave.com/res/v1/web/search',
  status: 'active',
  circuitState: 'closed',
  consecutiveFailures: 0,
  totalRequests: 20,
  totalFailures: 3,
  maxResults: 10,
  rateLimitPerMinute: 60,
  failureThreshold: 3,
  cooldownSeconds: 60
};

describe('web search presentation', () => {
  it('renders persisted provider scope and engine', () => {
    expect(searchProviderLabel(provider)).toBe('企业搜索 · BRAVE · 企业');
  });

  it('calculates a stable failure percentage', () => {
    expect(searchFailureRate(provider)).toBe(15);
  });

  it('distinguishes circuit states', () => {
    expect(searchCircuitText('closed')).toBe('正常');
    expect(searchCircuitText('half_open')).toBe('半开探测');
    expect(searchCircuitText('open')).toBe('已熔断');
  });
});
