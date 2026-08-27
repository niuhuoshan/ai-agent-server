import type { SearchCircuitState, SearchProviderView } from '@/service/api/search';

export function searchCircuitText(state: SearchCircuitState) {
  if (state === 'open') return '已熔断';
  if (state === 'half_open') return '半开探测';
  return '正常';
}

export function searchCircuitType(state: SearchCircuitState) {
  if (state === 'open') return 'error' as const;
  if (state === 'half_open') return 'warning' as const;
  return 'success' as const;
}

export function searchProviderLabel(provider: SearchProviderView) {
  const scope = provider.scope === 'personal' ? '个人' : '企业';
  return `${provider.name} · ${provider.engine.toUpperCase()} · ${scope}`;
}

export function searchFailureRate(provider: SearchProviderView) {
  if (provider.totalRequests <= 0) return 0;
  return Math.round((provider.totalFailures / provider.totalRequests) * 1000) / 10;
}
