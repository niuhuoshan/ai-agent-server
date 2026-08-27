import type {
  MetadataProfileJobStatus,
  MetadataProfileJobView,
  MetadataSmartImportItemView,
  MetadataTemporaryClassification
} from '@/service/api';

export type ProfileTagType = 'default' | 'success' | 'warning' | 'error' | 'info';

export function metadataProfileRequestError(reason: unknown, fallback: string) {
  const result = reason as {
    error?: unknown;
    response?: { status?: number; data?: { msg?: string; message?: string } };
    message?: string;
  };
  const error = (result?.error || result) as {
    response?: { status?: number; data?: { msg?: string; message?: string } };
    message?: string;
  };
  const status = error?.response?.status;
  const serverMessage = error?.response?.data?.msg || error?.response?.data?.message;
  if (status === 403) return '没有查看或维护此数据集画像的权限';
  if (status === 409) return serverMessage || '画像任务或元数据版本已变化，请刷新后重试';
  if (status === 404) return serverMessage || '画像任务或表画像不存在，请刷新后重试';
  return serverMessage || error?.message || fallback;
}

export function metadataProfileJobStatusText(status: MetadataProfileJobStatus) {
  return ({
    queued: '排队中',
    running: '执行中',
    done: '已完成',
    error: '失败',
    cancelled: '已取消'
  } as const)[status];
}

export function metadataProfileJobStatusType(status: MetadataProfileJobStatus): ProfileTagType {
  if (status === 'done') return 'success';
  if (status === 'error') return 'error';
  if (status === 'queued' || status === 'running') return 'warning';
  return 'default';
}

export function metadataProfileClassificationText(value?: MetadataTemporaryClassification | null) {
  if (!value) return '未分类';
  return ({
    business: '业务表',
    temporary: '临时表',
    backup: '备份表',
    staging: '中间表',
    system: '系统表'
  } as const)[value];
}

export function metadataProfileClassificationType(value?: MetadataTemporaryClassification | null): ProfileTagType {
  if (!value) return 'default';
  if (value === 'business') return 'success';
  if (value === 'system') return 'info';
  return 'warning';
}

export function isMetadataProfileJobActive(job?: MetadataProfileJobView | null) {
  return job?.status === 'queued' || job?.status === 'running';
}

export function canResumeMetadataProfileJob(job?: MetadataProfileJobView | null) {
  return job?.status === 'error' || job?.status === 'cancelled';
}

export function metadataProfileProgress(value: number | string | null | undefined) {
  const normalized = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(normalized)) return 0;
  return Math.max(0, Math.min(100, Math.round(normalized)));
}

export function smartImportItemTitle(item: MetadataSmartImportItemView) {
  if (item.tableProposal) {
    return item.tableProposal.displayName || `${item.tableProposal.schemaName}.${item.tableProposal.physicalName}`;
  }
  if (item.relationshipProposal) return `关系 ${item.relationshipProposal.recommendationId}`;
  return item.itemType === 'relationship' ? `关系 ${item.resourceId}` : `表 ${item.resourceId}`;
}

export function smartImportItemDescription(item: MetadataSmartImportItemView) {
  if (item.relationshipProposal) {
    const proposal = item.relationshipProposal;
    return `${proposal.sourceTableId}.${proposal.sourceColumnId} -> ${proposal.targetTableId}.${proposal.targetColumnId} / ${proposal.joinCondition}`;
  }
  if (item.tableProposal) {
    const proposal = item.tableProposal;
    const table = `${proposal.schemaName}.${proposal.physicalName}`;
    return `${table}${proposal.description ? ` / ${proposal.description}` : ''} / ${proposal.columnUpdates.length} 个字段更新`;
  }
  return String(item.resourceId);
}

export function formatMetadataProfileValue(value: unknown) {
  if (value === null || value === undefined) return '-';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}
