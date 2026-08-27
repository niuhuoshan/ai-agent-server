import { describe, expect, it } from 'vitest';
import type { MetadataProfileJobView, MetadataSmartImportItemView } from '@/service/api';
import {
  canResumeMetadataProfileJob,
  isMetadataProfileJobActive,
  metadataProfileClassificationText,
  metadataProfileJobStatusText,
  metadataProfileProgress,
  metadataProfileRequestError,
  smartImportItemDescription,
  smartImportItemTitle
} from './metadata-profile-state';

function job(status: MetadataProfileJobView['status']): MetadataProfileJobView {
  return {
    id: '1', datasetId: '2', dataSourceId: '3', mode: 'incremental', status,
    totalTables: 10, completedTables: 4, failedTables: 0, progressPercent: '40',
    currentTableId: '11', cancelRequested: false, resumeOfJobId: null,
    attemptNo: 1, maxAttempts: 3, revisionNo: 1, errorMessage: null, requestedBy: '9',
    createdAt: '2026-08-17T10:00:00', startedAt: null, finishedAt: null,
    updatedAt: '2026-08-17T10:00:00'
  };
}

describe('metadata profile presentation state', () => {
  it('uses the persisted backend job vocabulary and action boundaries', () => {
    expect(metadataProfileJobStatusText('done')).toBe('已完成');
    expect(metadataProfileJobStatusText('error')).toBe('失败');
    expect(isMetadataProfileJobActive(job('queued'))).toBe(true);
    expect(isMetadataProfileJobActive(job('running'))).toBe(true);
    expect(canResumeMetadataProfileJob(job('error'))).toBe(true);
    expect(canResumeMetadataProfileJob(job('cancelled'))).toBe(true);
    expect(canResumeMetadataProfileJob(job('done'))).toBe(false);
  });

  it('clamps only server-provided progress and never synthesizes progress', () => {
    expect(metadataProfileProgress('-4')).toBe(0);
    expect(metadataProfileProgress('48.6')).toBe(49);
    expect(metadataProfileProgress('120')).toBe(100);
    expect(metadataProfileProgress('not-a-decimal')).toBe(0);
  });

  it('keeps labels separate from temporary-table classification', () => {
    expect(metadataProfileClassificationText('business')).toBe('业务表');
    expect(metadataProfileClassificationText('temporary')).toBe('临时表');
    expect(metadataProfileClassificationText('backup')).toBe('备份表');
  });

  it('surfaces explicit forbidden and conflict states', () => {
    expect(metadataProfileRequestError({ response: { status: 403 } }, 'fallback')).toContain('没有');
    expect(metadataProfileRequestError({ response: { status: 409, data: { msg: '版本已更新' } } }, 'fallback')).toBe('版本已更新');
  });

  it('renders typed table and relationship proposals without a generic proposed object', () => {
    const tableItem: MetadataSmartImportItemView = {
      id: '1', itemType: 'table', resourceId: '11', status: 'available', contentHash: 'hash',
      appliedResourceId: null, errorMessage: null, relationshipProposal: null,
      tableProposal: {
        profileId: '21', profileRevision: 4, tableId: '11', sourceHash: 'source-hash', schemaName: 'public', physicalName: 'orders',
        expected: { displayName: '旧订单', description: '旧说明', status: 'active', metadataPresent: true, stateHash: 'table-state-hash' },
        displayName: '订单', description: '订单主表', status: 'active',
        columnUpdates: [{
          columnId: '31',
          expected: { columnId: '31', displayName: null, description: null, sensitive: false, status: 'active', metadataPresent: true, stateHash: 'column-state-hash' },
          displayName: '订单号', description: '业务订单号', sensitive: false, status: 'active'
        }]
      }
    };
    const relationshipItem: MetadataSmartImportItemView = {
      id: '2', itemType: 'relationship', resourceId: '12', status: 'available', contentHash: 'hash-2',
      appliedResourceId: null, errorMessage: null, tableProposal: null,
      relationshipProposal: {
        recommendationId: '12', sourceTableId: '11', sourceColumnId: '31', targetTableId: '13', targetColumnId: '41',
        sourceProfileId: '51', sourceProfileRevision: 1, sourceStructureHash: 'source-structure',
        targetProfileId: '52', targetProfileRevision: 1, targetStructureHash: 'target-structure',
        sourceTableStateHash: 'source-table', sourceColumnStateHash: 'source-column',
        targetTableStateHash: 'target-table', targetColumnStateHash: 'target-column', joinType: 'left',
        joinCondition: 'orders.customer_id = customers.id', description: '订单客户关系'
      }
    };

    expect(smartImportItemTitle(tableItem)).toBe('订单');
    expect(smartImportItemDescription(tableItem)).toContain('1 个字段更新');
    expect(smartImportItemTitle(relationshipItem)).toBe('关系 12');
    expect(smartImportItemDescription(relationshipItem)).toContain('orders.customer_id');
    expect(relationshipItem.relationshipProposal).toMatchObject({ sourceColumnId: '31', targetColumnId: '41' });
  });
});
