import type {
  MetadataImportDiagnosticView,
  MetadataImportItemAction,
  MetadataImportItemStatus,
  MetadataImportItemType,
  MetadataImportItemView,
  MetadataImportPreviewView,
  MetadataImportSourceType
} from '@/service/api';

export type MetadataImportItemFilter = 'all' | MetadataImportItemType;
export type MetadataImportActionFilter = 'all' | MetadataImportItemAction;
export type MetadataImportTagType = 'default' | 'info' | 'success' | 'warning' | 'error';

export const METADATA_IMPORT_MAX_CHARACTERS = 2_000_000;
export const METADATA_IMPORT_MAX_FILE_BYTES = 8 * 1024 * 1024;

export function metadataImportSourceLabel(sourceType: MetadataImportSourceType) {
  return sourceType === 'yaml' ? 'YAML 元数据' : 'DDL 建表语句';
}

export function metadataImportSourcePlaceholder(sourceType: MetadataImportSourceType) {
  if (sourceType === 'yaml') {
    return 'version: 1\ndataset:\n  key: sales\ntables:\n  - key: public.orders';
  }
  return 'CREATE TABLE public.orders (\n  id BIGINT PRIMARY KEY,\n  total_amount DECIMAL(18, 2)\n);';
}

export function metadataImportFileAccept(sourceType: MetadataImportSourceType) {
  return sourceType === 'yaml' ? '.yaml,.yml,text/yaml,application/yaml' : '.sql,.ddl,text/plain,application/sql';
}

export function metadataImportContentError(content: string, sourceType: MetadataImportSourceType) {
  if (!content.trim()) return `请填写或载入${metadataImportSourceLabel(sourceType)}`;
  if (content.includes('\0')) return '导入内容包含非法空字符';
  if (content.length > METADATA_IMPORT_MAX_CHARACTERS) return '导入内容不能超过 2,000,000 个字符';
  return null;
}

export function metadataImportItemTypeLabel(itemType: MetadataImportItemType) {
  return ({ table: '数据表', metric: '业务指标', relationship: '表关系' } as const)[itemType];
}

export function metadataImportActionLabel(action: MetadataImportItemAction) {
  return action === 'create' ? '新增' : '更新';
}

export function metadataImportActionType(action: MetadataImportItemAction): MetadataImportTagType {
  return action === 'create' ? 'success' : 'info';
}

export function metadataImportItemStatusLabel(status: MetadataImportItemStatus) {
  return ({ available: '待应用', applied: '已应用', skipped: '已跳过' } as const)[status];
}

export function metadataImportItemStatusType(status: MetadataImportItemStatus): MetadataImportTagType {
  if (status === 'applied') return 'success';
  if (status === 'skipped') return 'warning';
  return 'default';
}

export function metadataImportPreviewStatusLabel(status: MetadataImportPreviewView['status']) {
  return ({ draft: '待应用', applied: '已应用', expired: '已过期' } as const)[status];
}

export function metadataImportPreviewStatusType(status: MetadataImportPreviewView['status']): MetadataImportTagType {
  if (status === 'applied') return 'success';
  if (status === 'expired') return 'warning';
  return 'info';
}

export function metadataImportDiagnosticMessage(diagnostic: MetadataImportDiagnosticView) {
  return diagnostic.message;
}

export function metadataImportDiagnosticType(diagnostic: MetadataImportDiagnosticView): MetadataImportTagType {
  if (diagnostic.level === 'error') return 'error';
  if (diagnostic.level === 'info') return 'info';
  return 'warning';
}

export function metadataImportAvailableItems(preview: MetadataImportPreviewView | null | undefined) {
  return preview?.items.filter(item => item.status === 'available') || [];
}

export function filterMetadataImportItems(
  items: MetadataImportItemView[],
  keyword: string,
  itemType: MetadataImportItemFilter,
  action: MetadataImportActionFilter
) {
  const needle = keyword.trim().toLocaleLowerCase();
  return items.filter(item => {
    if (itemType !== 'all' && item.itemType !== itemType) return false;
    if (action !== 'all' && item.action !== action) return false;
    if (!needle) return true;
    const proposal = JSON.stringify(item.proposal).toLocaleLowerCase();
    return item.resourceKey.toLocaleLowerCase().includes(needle) || proposal.includes(needle);
  });
}

export function metadataImportSelectedSummary(items: MetadataImportItemView[], selectedIds: ReadonlySet<string>) {
  const selected = items.filter(item => item.status === 'available' && selectedIds.has(String(item.id)));
  return {
    total: selected.length,
    tables: selected.filter(item => item.itemType === 'table').length,
    metrics: selected.filter(item => item.itemType === 'metric').length,
    relationships: selected.filter(item => item.itemType === 'relationship').length,
    creates: selected.filter(item => item.action === 'create').length,
    updates: selected.filter(item => item.action === 'update').length
  };
}

function proposalText(proposal: Record<string, unknown>, keys: string[]) {
  for (const key of keys) {
    const value = proposal[key];
    if (typeof value === 'string' && value.trim()) return value.trim();
  }
  return '';
}

export function metadataImportItemTitle(item: MetadataImportItemView) {
  const name = proposalText(item.proposal, ['displayName', 'name', 'physicalName', 'metricKey', 'tableKey']);
  return name || item.resourceKey;
}

export function metadataImportItemDetail(item: MetadataImportItemView) {
  let detail = '';
  if (item.itemType === 'table') {
    const schema = proposalText(item.proposal, ['physicalSchema', 'schema']);
    const table = proposalText(item.proposal, ['physicalName', 'tableName', 'name']);
    const columns = Array.isArray(item.proposal.columns) ? `${item.proposal.columns.length} 个字段` : '';
    detail = [[schema, table].filter(Boolean).join('.'), columns].filter(Boolean).join(' / ');
  } else if (item.itemType === 'metric') {
    detail = proposalText(item.proposal, ['description', 'calculationLogic', 'expression']);
  } else {
    const source = proposalText(item.proposal, ['sourceTableKey', 'sourceTable', 'source']);
    const target = proposalText(item.proposal, ['targetTableKey', 'targetTable', 'target']);
    detail = source && target ? `${source} -> ${target}` : '';
  }
  return [detail || item.resourceKey, item.errorMessage].filter(Boolean).join(' / ');
}

export function metadataImportPreviewExpired(preview: MetadataImportPreviewView, now = Date.now()) {
  const expiresAt = Date.parse(preview.expiresAt);
  return preview.status === 'expired' || (Number.isFinite(expiresAt) && expiresAt <= now);
}

export function metadataImportRequestError(reason: unknown, fallback: string) {
  if (!reason || typeof reason !== 'object') return fallback;
  const value = reason as {
    response?: { status?: number; data?: { msg?: string; message?: string } };
    message?: string;
  };
  if (value.response?.status === 403) return '没有导入此数据集元数据的权限';
  if (value.response?.status === 404) return '数据集或导入预览不存在';
  if (value.response?.status === 409) return '数据集或预览已变化，请重新生成预览';
  return value.response?.data?.msg || value.response?.data?.message || value.message || fallback;
}
