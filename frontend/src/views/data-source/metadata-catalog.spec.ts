import { describe, expect, it } from 'vitest';
import type { DataTableView } from '@/service/api';
import {
  filterMetadataCatalog,
  metadataCatalogStats,
  metadataColumnRows,
  metadataGovernanceIssues
} from './metadata-catalog';

const metadata: DataTableView[] = [
  {
    id: 'table-1', tableKey: 'public.orders', physicalSchema: 'public', physicalName: 'orders',
    displayName: '订单', description: '订单主表', tableType: 'BASE TABLE', status: 'active', metadataPresent: true,
    columns: [
      {
        id: 'column-1', columnKey: 'public.orders.phone', physicalName: 'phone', displayName: '客户手机号',
        dataType: 'varchar', description: '', primary: false, sensitive: true, status: 'active', metadataPresent: true
      },
      {
        id: 'column-2', columnKey: 'public.orders.amount', physicalName: 'amount', displayName: '订单金额',
        dataType: 'decimal', description: '含税金额', primary: false, sensitive: false, status: 'inactive', metadataPresent: true
      }
    ]
  },
  {
    id: 'table-2', tableKey: 'archive.users', physicalSchema: 'archive', physicalName: 'users',
    displayName: null, description: null, tableType: 'BASE TABLE', status: 'inactive', metadataPresent: false,
    columns: []
  }
];

describe('metadata catalog state', () => {
  it('searches through fields while preserving table context and supports governance filters', () => {
    const searched = filterMetadataCatalog(metadata, {
      keyword: '客户手机号', tableStatus: 'all', columnFilter: 'all'
    });
    expect(searched).toHaveLength(1);
    expect(searched[0].physicalName).toBe('orders');
    expect(searched[0].columns.map(column => column.physicalName)).toEqual(['phone']);

    const sensitive = filterMetadataCatalog(metadata, {
      keyword: '', tableStatus: 'active', columnFilter: 'sensitive'
    });
    expect(sensitive[0].columns).toHaveLength(1);
    expect(sensitive[0].columns[0].sensitive).toBe(true);
  });

  it('builds cross-table column rows and quality statistics', () => {
    expect(metadataColumnRows(metadata)[0]).toMatchObject({
      rowKey: 'table-1:column-1', tableName: '订单', qualifiedTableName: 'public.orders'
    });
    expect(metadataCatalogStats(metadata)).toMatchObject({
      tableCount: 2,
      columnCount: 2,
      schemaCount: 2,
      sensitiveCount: 1,
      inactiveTableCount: 1,
      inactiveColumnCount: 1,
      missingMetadataCount: 1,
      tableDescriptionCoverage: 50,
      columnDescriptionCoverage: 50
    });
  });

  it('prioritizes missing metadata governance issues', () => {
    const issues = metadataGovernanceIssues(metadata);
    expect(issues[0]).toMatchObject({ scope: 'table', level: 'error' });
    expect(issues.map(issue => issue.title)).toContain('订单.客户手机号 缺少字段说明');
  });
});
