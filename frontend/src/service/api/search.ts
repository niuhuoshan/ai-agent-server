import { request } from '../request';

export type SearchCircuitState = 'closed' | 'open' | 'half_open';

export interface SearchProviderView {
  connectorId: string;
  connectorKey: string;
  name: string;
  scope: 'global' | 'personal';
  manageable: boolean;
  engine: 'brave' | 'bing' | 'serper' | 'searxng' | 'custom';
  endpointUrl: string;
  status: string;
  circuitState: SearchCircuitState;
  consecutiveFailures: number;
  totalRequests: number;
  totalFailures: number;
  lastLatencyMs?: number | null;
  lastCheckAt?: string | null;
  lastSuccessAt?: string | null;
  lastFailureAt?: string | null;
  nextProbeAt?: string | null;
  lastError?: string | null;
  maxResults: number;
  rateLimitPerMinute: number;
  failureThreshold: number;
  cooldownSeconds: number;
}

export interface WebSearchHit {
  rank: number;
  title: string;
  url: string;
  snippet?: string | null;
  publishedAt?: string | null;
  source: string;
}

export interface WebSearchResult {
  connectorId: string;
  connectorName: string;
  engine: string;
  query: string;
  resultCount: number;
  latencyMs: number;
  results: WebSearchHit[];
}

export interface WebSearchPayload {
  connectorId: string;
  query: string;
  maxResults: number;
}

export function fetchSearchProviders() {
  return request<SearchProviderView[]>({ url: '/platform/search/providers', method: 'get' });
}

export function executeWebSearch(payload: WebSearchPayload) {
  return request<WebSearchResult>({ url: '/platform/search/query', method: 'post', data: payload });
}
