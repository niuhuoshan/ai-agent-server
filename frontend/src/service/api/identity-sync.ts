import { request } from '../request';

export type IdentityProviderType = 'database' | 'http_json';
export type IdentitySyncSchedule = 'off' | 'hourly' | 'daily' | 'weekly';
export type IdentitySyncRunStatus = 'running' | 'succeeded' | 'partial' | 'failed' | 'unavailable';

export interface IdentitySyncFieldMapping {
  userName: string;
  displayName: string | null;
  email: string | null;
  phoneNumber: string | null;
  remark: string | null;
  status: string | null;
}

export interface IdentitySyncExtraMapping {
  key: string;
  sourceColumn: string;
}

export interface IdentitySyncConfig {
  enabled: boolean;
  providerType: IdentityProviderType;
  dataSourceId: string | null;
  endpointUrl: string | null;
  credentialRef: string | null;
  authType: 'none' | 'basic' | 'bearer' | 'header';
  credentialHeader: string | null;
  requestMethod: 'GET' | 'POST';
  requestHeaders: Record<string, string>;
  requestBody: Record<string, unknown>;
  responseItemsPath: string | null;
  tableName: string | null;
  fieldMapping: IdentitySyncFieldMapping;
  extraMappings: IdentitySyncExtraMapping[];
  defaultRoleKey: string | null;
  schedule: IdentitySyncSchedule;
  revisionNo: number;
  lastPreviewAt: string | null;
  lastRunAt: string | null;
  lastRunStatus: IdentitySyncRunStatus | null;
  lastError: string | null;
  updateTime: string;
}

export interface SaveIdentitySyncConfigPayload {
  enabled: boolean;
  providerType: IdentityProviderType;
  dataSourceId?: string;
  endpointUrl?: string;
  credentialRef?: string;
  authType: IdentitySyncConfig['authType'];
  credentialHeader?: string;
  requestMethod: IdentitySyncConfig['requestMethod'];
  requestHeaders: Record<string, string>;
  requestBody: Record<string, unknown>;
  responseItemsPath?: string;
  tableName?: string;
  fieldMapping: IdentitySyncFieldMapping;
  extraMappings: IdentitySyncExtraMapping[];
  defaultRoleKey?: string;
  schedule: IdentitySyncSchedule;
  expectedRevision: number;
}

export interface IdentitySyncDataSource {
  id: string;
  name: string;
  dbType: string;
  databaseName: string;
  status: string;
}

export interface IdentitySyncTable {
  schema: string | null;
  name: string;
  qualifiedName: string;
  type: string;
}

export interface IdentitySyncColumn {
  name: string;
  type: string;
  nullable: boolean;
  sample: string | null;
}

export interface IdentitySyncPreviewItem {
  userName: string;
  displayName: string | null;
  email: string | null;
  phoneNumber: string | null;
  remark: string | null;
  status: string | null;
  extraData: Record<string, unknown>;
  existing: boolean;
  action: 'create' | 'update' | 'invalid';
}

export interface IdentitySyncPreview {
  providerType: IdentityProviderType;
  configRevision: number;
  total: number;
  creates: number;
  updates: number;
  items: IdentitySyncPreviewItem[];
  previewedAt: string;
}

export interface IdentitySyncRunItem {
  userName: string;
  displayName: string | null;
  email: string | null;
  phoneNumber: string | null;
  remark: string | null;
  sourceStatus: string | null;
  extraData: Record<string, unknown>;
  result: 'created' | 'updated' | 'skipped' | 'failed';
  localUserId: string | null;
  error: string | null;
}

export interface IdentitySyncRun {
  id: string;
  retryOfRunId: string | null;
  providerType: IdentityProviderType;
  configRevision: number;
  status: IdentitySyncRunStatus;
  requestedNames: string[];
  items: IdentitySyncRunItem[];
  discoveredCount: number;
  selectedCount: number;
  createdCount: number;
  updatedCount: number;
  skippedCount: number;
  failedCount: number;
  errorSummary: string | null;
  requestedBy: string;
  startedAt: string;
  finishedAt: string | null;
  retryable: boolean;
}

export function fetchIdentitySyncConfig() {
  return request<IdentitySyncConfig>({ url: '/platform/identity-sync/config', method: 'get' });
}

export function saveIdentitySyncConfig(data: SaveIdentitySyncConfigPayload) {
  return request<IdentitySyncConfig>({ url: '/platform/identity-sync/config', method: 'put', data });
}

export function fetchIdentitySyncDataSources() {
  return request<IdentitySyncDataSource[]>({ url: '/platform/identity-sync/datasources', method: 'get' });
}

export function fetchIdentitySyncTables(dataSourceId: string) {
  return request<IdentitySyncTable[]>({
    url: '/platform/identity-sync/tables',
    method: 'get',
    params: { dataSourceId }
  });
}

export function fetchIdentitySyncColumns(dataSourceId: string, tableName: string) {
  return request<IdentitySyncColumn[]>({
    url: '/platform/identity-sync/columns',
    method: 'get',
    params: { dataSourceId, tableName }
  });
}

export function previewIdentitySync(config: SaveIdentitySyncConfigPayload) {
  return request<IdentitySyncPreview>({
    url: '/platform/identity-sync/preview',
    method: 'post',
    data: { config }
  });
}

export function executeIdentitySync(config: SaveIdentitySyncConfigPayload, userNames: string[]) {
  return request<IdentitySyncRun>({
    url: '/platform/identity-sync/run',
    method: 'post',
    data: { config, userNames }
  });
}

export function fetchIdentitySyncRuns(limit = 50) {
  return request<IdentitySyncRun[]>({
    url: '/platform/identity-sync/runs',
    method: 'get',
    params: { limit }
  });
}

export function retryIdentitySyncRun(runId: string) {
  return request<IdentitySyncRun>({
    url: `/platform/identity-sync/runs/${encodeURIComponent(runId)}/retry`,
    method: 'post'
  });
}
