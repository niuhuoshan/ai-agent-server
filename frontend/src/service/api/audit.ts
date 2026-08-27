import { request } from '../request';
import { getAuthorization } from '../request/shared';
import { getServiceBaseURL } from '@/utils/service';
import type { AuditEventView } from './platform';

export interface AuditFeatureView {
  actorTypes: string[];
  actions: string[];
  resourceTypes: string[];
  decisions: string[];
}

export interface AuditStatisticsView {
  total: number;
  allowCount: number;
  denyCount: number;
  approvalRequiredCount: number;
  successCount: number;
  failureCount: number;
  distinctActors: number;
  distinctTraces: number;
}

export interface AuditEventDetailView extends AuditEventView {
  permissionProfileVersion: string | null;
  dataScope: Record<string, unknown>;
  requestSummary: string | null;
  resultSummary: string | null;
  ipAddress: string | null;
  userAgent: string | null;
  metadata: Record<string, unknown>;
}

export interface AuditTraceStepView {
  stepNumber: number;
  eventId: string;
  conversationId: string | null;
  runId: string | null;
  stepId: string | null;
  cursor: number;
  eventType: string;
  eventStatus: string;
  sensitiveLevel: string | null;
  agentName: string | null;
  model: string | null;
  toolName: string | null;
  summary: string | null;
  executionTimeMs: number | null;
  promptTokens: number | null;
  completionTokens: number | null;
  totalTokens: number | null;
  spanId: string | null;
  parentSpanId: string | null;
  metadata: Record<string, unknown>;
  occurredAt: string | null;
}

export interface AuditTraceView {
  traceId: string;
  totalSteps: number;
  steps: AuditTraceStepView[];
}

export interface AuditPageView {
  total: number;
  page: number;
  size: number;
  items: AuditEventView[];
  statistics: AuditStatisticsView | null;
}

export interface AuditConsoleSearchParams {
  actorType?: string;
  actorId?: string;
  action?: string;
  resourceType?: string;
  resourceId?: string;
  taskId?: string;
  runId?: string;
  decision?: string;
  createdFrom?: string;
  createdTo?: string;
  beforeId?: string;
  limit?: number;
}

export function fetchAuditConsoleEvents(params: AuditConsoleSearchParams) {
  return request<AuditEventView[]>({ url: '/platform/audit-events', method: 'get', params });
}

export function fetchAuditFeatures() {
  return request<AuditFeatureView>({ url: '/platform/audit-events/features', method: 'get' });
}

export function fetchAuditStatistics(params: Omit<AuditConsoleSearchParams, 'beforeId' | 'limit'> = {}) {
  return request<AuditStatisticsView>({ url: '/platform/audit-events/statistics', method: 'get', params });
}

export function fetchAuditEventDetail(id: string) {
  return request<AuditEventDetailView>({ url: `/platform/audit-events/${id}`, method: 'get' });
}

export function fetchAuditTrace(traceId: string) {
  return request<AuditTraceView>({ url: `/platform/audit-events/traces/${encodeURIComponent(traceId)}`, method: 'get' });
}

export function fetchAuditTraceSpans(traceId: string) {
  return request<{ traceId: string; spans: AuditTraceStepView[] }>({
    url: `/platform/audit-events/traces/${encodeURIComponent(traceId)}/spans`,
    method: 'get'
  });
}

export async function downloadAuditExport(params: AuditConsoleSearchParams, format: 'csv' | 'json') {
  const query = new URLSearchParams({ format });
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') query.set(key, String(value));
  });
  const response = await fetch(rawUrl(`/platform/audit-events/export?${query.toString()}`), {
    headers: rawHeaders(format === 'json' ? 'application/json' : 'text/csv')
  });
  return { blob: await downloadBlob(response), fileName: fileName(response, `audit-events.${format}`) };
}

export interface MetadataChangelogChangeView {
  id: string;
  datasetId: string;
  resourceType: string;
  resourceId: string | null;
  action: string;
  beforeJson: string | null;
  afterJson: string | null;
  beforeHash: string | null;
  afterHash: string | null;
  actorId: string;
  createdAt: string;
}

export interface MetadataChangelogPageView {
  total: number;
  page: number;
  size: number;
  items: MetadataChangelogChangeView[];
}

export interface MetadataChangelogStatsView {
  total: number;
  createdFrom: string;
  createdTo: string;
  breakdown: { resourceType: string; action: string; count: number }[];
}

export interface MetadataChangeDiffView {
  id: string;
  datasetId: string;
  resourceType: string;
  resourceId: string | null;
  operation: string;
  summary: string;
  changes: { field: string; oldValue: unknown; newValue: unknown }[];
}

export function fetchMetadataChangelog(params: {
  datasetId?: string;
  resourceType?: string;
  resourceId?: string;
  action?: string;
  actorId?: string;
  createdFrom?: string;
  createdTo?: string;
  page?: number;
  size?: number;
} = {}) {
  return request<MetadataChangelogPageView>({ url: '/platform/metadata-changelog', method: 'get', params });
}

export function fetchMetadataChangelogStats(days = 30) {
  return request<MetadataChangelogStatsView>({ url: '/platform/metadata-changelog/stats', method: 'get', params: { days } });
}

export function fetchMetadataChangeDiff(id: string) {
  return request<MetadataChangeDiffView>({ url: `/platform/metadata-changelog/${id}/diff`, method: 'get' });
}

function rawUrl(path: string) {
  const isHttpProxy = import.meta.env.DEV && import.meta.env.VITE_HTTP_PROXY === 'Y';
  const { baseURL } = getServiceBaseURL(import.meta.env, isHttpProxy);
  return `${baseURL.replace(/\/$/, '')}${path}`;
}

function rawHeaders(accept: string) {
  const headers = new Headers({ Accept: accept, clientid: import.meta.env.VITE_APP_CLIENT_ID });
  const authorization = getAuthorization();
  if (authorization) headers.set('Authorization', authorization);
  return headers;
}

async function downloadBlob(response: Response) {
  if (!response.ok) {
    let message = `请求失败 (${response.status})`;
    try {
      const payload = await response.json() as { msg?: string; message?: string };
      message = payload.msg || payload.message || message;
    } catch {
      // Keep the HTTP status when the server did not return JSON.
    }
    throw new Error(message);
  }
  return response.blob();
}

function fileName(response: Response, fallback: string) {
  const value = response.headers.get('content-disposition') || '';
  const encoded = value.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  if (encoded) return decodeURIComponent(encoded);
  return value.match(/filename="?([^";]+)"?/i)?.[1] || fallback;
}
