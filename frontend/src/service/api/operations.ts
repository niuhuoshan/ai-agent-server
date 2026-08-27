import { request } from '../request';

export type SystemHealthStatus = 'healthy' | 'degraded' | 'unavailable' | 'disabled' | string;

export interface SystemHealthComponent {
  key: string;
  name: string;
  status: SystemHealthStatus;
  critical: boolean;
  message: string;
  responseTimeMs: number;
  details: Record<string, unknown>;
}

export interface SystemRuntimeMetrics {
  javaVersion: string;
  vmName: string;
  availableProcessors: number;
  uptimeSeconds: number;
  heapUsedBytes: number;
  heapCommittedBytes: number;
  heapMaxBytes: number;
  liveThreads: number;
  systemLoadAverage: number | null;
}

export interface SystemHealthOverview {
  status: SystemHealthStatus;
  checkedAt: string;
  applicationName: string;
  applicationVersion: string | null;
  runtime: SystemRuntimeMetrics;
  components: SystemHealthComponent[];
}

export function fetchSystemHealthOverview() {
  return request<SystemHealthOverview>({ url: '/platform/operations/health', method: 'get' });
}

export interface SystemDiagnosticCheck {
  key: string;
  name: string;
  status: SystemHealthStatus;
  required: boolean;
  message: string;
  metrics: Record<string, unknown>;
  remediation: string | null;
}

export interface SystemDiagnostics {
  status: SystemHealthStatus;
  checkedAt: string;
  checks: SystemDiagnosticCheck[];
}

export function fetchSystemDiagnostics() {
  return request<SystemDiagnostics>({ url: '/platform/operations/diagnostics', method: 'get' });
}

export interface RedisKeyView {
  name: string;
  type: string;
  ttlSeconds: number;
}

export interface RedisKeyListView {
  totalCount: number;
  returnedCount: number;
  truncated: boolean;
  pattern: string;
  keys: RedisKeyView[];
}

export interface RedisKeyDetailView extends RedisKeyView {
  value: unknown;
  valueTruncated: boolean;
}

export interface RedisMutationView {
  status: string;
  affectedCount: number;
  preservedCount: number;
  message: string;
}

export function fetchRedisKeys(pattern = '*') {
  return request<RedisKeyListView>({
    url: '/platform/operations/redis/keys',
    method: 'get',
    params: { pattern }
  });
}

export function fetchRedisKeyDetail(key: string) {
  return request<RedisKeyDetailView>({
    url: '/platform/operations/redis/key-detail',
    method: 'get',
    params: { key }
  });
}

export function deleteRedisKey(key: string) {
  return request<RedisMutationView>({
    url: '/platform/operations/redis/key',
    method: 'delete',
    params: { key, confirm: true }
  });
}

export function deleteRedisKeys(keys: string[]) {
  return request<RedisMutationView>({
    url: '/platform/operations/redis/delete-keys',
    method: 'post',
    data: { keys, confirm: true }
  });
}

export function flushRedisCaches() {
  return request<RedisMutationView>({
    url: '/platform/operations/redis/flush',
    method: 'post',
    data: { confirm: true, preserveConversations: true }
  });
}

export interface LogRetentionConfig {
  retentionDays: number;
  minRetentionDays: number;
  maxRetentionDays: number;
  revisionNo: number;
  updatedBy: string | null;
  updatedAt: string;
  changeReason: string;
  automaticSchedule: string;
}

export interface LogPartition {
  partitionName: string;
  boundExpression: string;
  defaultPartition: boolean;
  estimatedRows: number;
  sizeBytes: number;
  oldestAt: string | null;
  newestAt: string | null;
  expiredRows: number;
  removableCandidate: boolean;
}

export interface LogTableStorage {
  tableName: string;
  displayName: string;
  storageMode: 'partitioned' | 'regular';
  partitionKey: string | null;
  estimatedRows: number;
  sizeBytes: number;
  oldestAt: string | null;
  newestAt: string | null;
  expiredRows: number;
  partitions: LogPartition[];
}

export interface LogPartitionStatus {
  databaseType: string;
  checkedAt: string;
  retentionDays: number;
  cutoffAt: string;
  futureMonthsPrepared: number;
  batchSize: number;
  maxRowsPerTablePerRun: number;
  tables: LogTableStorage[];
}

export interface LogCleanupPreview {
  runId: string;
  confirmationToken: string;
  confirmationExpiresAt: string;
  retentionDays: number;
  policyRevision: number;
  cutoffAt: string;
  expiredRows: number;
  removablePartitions: number;
  maxRowsPerTablePerRun: number;
  mayRequireMultipleRuns: boolean;
  tables: LogTableStorage[];
  warnings: string[];
}

export interface LogCleanupTableResult {
  tableName: string;
  droppedPartitions: string[];
  droppedRows: number;
  deletedRows: number;
  remainingExpiredRows: boolean;
}

export interface LogCleanupResult {
  runId: string;
  status: string;
  triggerType: string;
  retentionDays: number;
  cutoffAt: string;
  createdPartitions: string[];
  droppedPartitions: string[];
  droppedRows: number;
  deletedRows: number;
  remainingExpiredRows: boolean;
  startedAt: string | null;
  finishedAt: string | null;
  message: string;
  tables: LogCleanupTableResult[];
}

export interface LogMaintenanceRun {
  runId: string;
  triggerType: string;
  status: string;
  retentionDays: number;
  policyRevision: number;
  cutoffAt: string;
  requestedBy: string | null;
  confirmationExpiresAt: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  summary: Record<string, unknown>;
  errorCode: string | null;
  errorMessage: string | null;
  createdAt: string;
}

export interface UpdateLogRetentionPayload {
  retentionDays: number;
  expectedRevision: number;
  changeReason: string;
}

export function fetchLogRetentionConfig() {
  return request<LogRetentionConfig>({ url: '/platform/operations/logs/config', method: 'get' });
}

export function updateLogRetentionConfig(payload: UpdateLogRetentionPayload) {
  return request<LogRetentionConfig>({
    url: '/platform/operations/logs/config',
    method: 'put',
    data: payload
  });
}

export function fetchLogPartitionStatus() {
  return request<LogPartitionStatus>({ url: '/platform/operations/logs/partitions', method: 'get' });
}

export function previewLogCleanup() {
  return request<LogCleanupPreview>({ url: '/platform/operations/logs/cleanup/preview', method: 'post' });
}

export function executeLogCleanup(confirmationToken: string) {
  return request<LogCleanupResult>({
    url: '/platform/operations/logs/cleanup',
    method: 'post',
    data: { confirmationToken, confirm: true }
  });
}

export function fetchLogMaintenanceRuns(limit = 20) {
  return request<LogMaintenanceRun[]>({
    url: '/platform/operations/logs/maintenance-runs',
    method: 'get',
    params: { limit }
  });
}

export interface PublicPlatformConfiguration {
  productName: string;
  productShortName: string;
  logoUrl: string | null;
  faviconUrl: string | null;
  primaryColor: string;
  platformTimezone: string;
  defaultLocale: 'zh-CN' | 'en-US';
  watermarkEnabled: boolean;
}

export interface PlatformConfiguration extends PublicPlatformConfiguration {
  revisionNo: number;
  updatedBy: string | null;
  updatedAt: string;
}

export interface PlatformConfigurationHistory extends PublicPlatformConfiguration {
  id: string;
  revisionNo: number;
  changeReason: string;
  changedBy: string;
  createdAt: string;
}

export interface UpdatePlatformConfigurationPayload extends PublicPlatformConfiguration {
  expectedRevision: number;
  changeReason: string;
}

export function fetchPublicPlatformConfiguration() {
  return request<PublicPlatformConfiguration>({ url: '/open/platform/configuration', method: 'get' });
}

export function fetchPlatformConfiguration() {
  return request<PlatformConfiguration>({ url: '/platform/operations/configuration', method: 'get' });
}

export function updatePlatformConfiguration(payload: UpdatePlatformConfigurationPayload) {
  return request<PlatformConfiguration>({
    url: '/platform/operations/configuration',
    method: 'put',
    data: payload
  });
}

export function fetchPlatformConfigurationHistory(limit = 20) {
  return request<PlatformConfigurationHistory[]>({
    url: '/platform/operations/configuration/history',
    method: 'get',
    params: { limit }
  });
}
