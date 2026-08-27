import { describe, expect, it } from 'vitest';
import type { MetadataImportItemView, MetadataImportPreviewView } from '@/service/api';
import {
  filterMetadataImportItems,
  metadataImportAvailableItems,
  metadataImportContentError,
  metadataImportDiagnosticMessage,
  metadataImportItemDetail,
  metadataImportItemTitle,
  metadataImportPreviewExpired,
  metadataImportRequestError,
  metadataImportSelectedSummary
} from './metadata-import-state';

const items: MetadataImportItemView[] = [
  {
    id: '61',
    itemType: 'table',
    resourceKey: 'public.orders',
    action: 'create',
    status: 'available',
    currentHash: null,
    contentHash: 'table-hash',
    proposal: {
      schema: 'public',
      name: 'orders',
      displayName: '订单',
      columns: [{ physicalName: 'id' }, { physicalName: 'amount' }]
    },
    appliedResourceId: null,
    errorMessage: null
  },
  {
    id: '62',
    itemType: 'metric',
    resourceKey: 'gross_sales',
    action: 'update',
    status: 'available',
    currentHash: 'old-hash',
    contentHash: 'metric-hash',
    proposal: { name: '销售额', calculationLogic: 'SUM(orders.amount)' },
    appliedResourceId: null,
    errorMessage: null
  },
  {
    id: '63',
    itemType: 'relationship',
    resourceKey: 'orders_customer',
    action: 'create',
    status: 'skipped',
    currentHash: null,
    contentHash: 'relation-hash',
    proposal: { sourceTable: 'public.orders', targetTable: 'public.customers' },
    appliedResourceId: null,
    errorMessage: null
  }
];

function preview(expiresAt = '2099-01-01T00:00:00Z'): MetadataImportPreviewView {
  return {
    id: '51',
    datasetId: '10',
    sourceType: 'ddl',
    status: 'draft',
    datasetRevision: 8,
    revisionNo: 2,
    tableCount: 1,
    columnCount: 2,
    diagnostics: [],
    expiresAt,
    createdBy: '7',
    createdAt: '2026-08-17T12:00:00Z',
    appliedBy: null,
    appliedAt: null,
    items
  };
}

describe('metadata import state', () => {
  it('validates bounded text without imposing DDL dialect assumptions', () => {
    expect(metadataImportContentError('', 'ddl')).toContain('DDL');
    expect(metadataImportContentError('CREATE TYPE mood AS ENUM (\'sad\', \'ok\');', 'ddl')).toBeNull();
    expect(metadataImportContentError('version: 1\ntables: []\n', 'yaml')).toBeNull();
    expect(metadataImportContentError('bad\0text', 'yaml')).toContain('空字符');
  });

  it('filters typed proposals while keeping only available items selectable', () => {
    expect(filterMetadataImportItems(items, '销售额', 'all', 'all').map(item => item.id)).toEqual(['62']);
    expect(filterMetadataImportItems(items, '', 'table', 'create').map(item => item.id)).toEqual(['61']);
    expect(metadataImportAvailableItems(preview()).map(item => item.id)).toEqual(['61', '62']);
  });

  it('summarizes selected atomic changes and renders useful proposal labels', () => {
    const summary = metadataImportSelectedSummary(items, new Set(['61', '62', '63']));
    expect(summary).toEqual({
      total: 2,
      tables: 1,
      metrics: 1,
      relationships: 0,
      creates: 1,
      updates: 1
    });
    expect(metadataImportItemTitle(items[0])).toBe('订单');
    expect(metadataImportItemDetail(items[0])).toBe('public.orders / 2 个字段');
    expect(metadataImportItemDetail(items[2])).toBe('public.orders -> public.customers');
  });

  it('recognizes server and local expiry and normalizes conflict errors', () => {
    expect(metadataImportPreviewExpired(preview('2026-08-17T11:59:59Z'), Date.parse('2026-08-17T12:00:00Z'))).toBe(true);
    expect(metadataImportPreviewExpired(preview('2099-01-01T00:00:00Z'), Date.parse('2026-08-17T12:00:00Z'))).toBe(false);
    expect(metadataImportRequestError({ response: { status: 409 } }, 'fallback')).toContain('重新生成预览');
    expect(metadataImportDiagnosticMessage({
      message: '关系端点缺失', level: 'warning', code: 'missing_endpoint', resourceKey: null
    })).toBe('关系端点缺失');
  });
});
