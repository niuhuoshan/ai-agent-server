import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import {
  createDataSource,
  deleteDataset,
  fetchDatasetDeleteImpact,
  updateDataColumn,
  updateDataSource,
  updateDataTable,
  type SaveDataSourcePayload
} from './platform';

vi.mock('../request', () => ({ request: vi.fn() }));

const payload: SaveDataSourcePayload = {
  sourceKey: 'analytics',
  revisionNo: 3,
  name: 'Analytics',
  dbType: 'clickhouse',
  endpointUrl: 'clickhouse://db.internal:8123',
  databaseName: 'analytics',
  credentialRef: 'env:ANALYTICS_DB',
  config: { sslMode: 'disable' },
  status: 'active',
  connectionTimeoutMs: 5000,
  statementTimeoutMs: 30000,
  maxRows: 1000,
  maxResultBytes: 1048576
};

describe('data source API contract', () => {
  beforeEach(() => {
    vi.mocked(request).mockClear();
  });

  it('keeps dbType when creating a data source', () => {
    createDataSource({ ...payload, sourceKey: 'analytics' });

    expect(request).toHaveBeenCalledWith(expect.objectContaining({
      url: '/platform/data-sources',
      method: 'post',
      data: expect.objectContaining({ dbType: 'clickhouse' })
    }));
    expect(vi.mocked(request).mock.calls[0][0].data).not.toHaveProperty('revisionNo');
  });

  it('keeps dbType when updating a data source', () => {
    updateDataSource('41', { ...payload, revisionNo: 3 });

    expect(request).toHaveBeenCalledWith(expect.objectContaining({
      url: '/platform/data-sources/41',
      method: 'put',
      data: expect.objectContaining({ dbType: 'clickhouse', revisionNo: 3 })
    }));
    expect(vi.mocked(request).mock.calls[0][0].data).not.toHaveProperty('sourceKey');
  });

  it('sends the table governance payload to the dataset-scoped endpoint', () => {
    updateDataTable('dataset-7', 'table-8', {
      displayName: '订单明细',
      description: '用于经营分析的订单明细表',
      status: 'active'
    });

    expect(request).toHaveBeenCalledWith({
      url: '/platform/datasets/dataset-7/tables/table-8',
      method: 'put',
      data: {
        displayName: '订单明细',
        description: '用于经营分析的订单明细表',
        status: 'active'
      }
    });
  });

  it('sends column sensitivity and status without leaking unrelated fields', () => {
    updateDataColumn('dataset-7', 'column-9', {
      displayName: '客户手机号',
      description: '用于联系客户，禁止默认查询',
      sensitive: true,
      status: 'inactive'
    });

    expect(request).toHaveBeenCalledWith({
      url: '/platform/datasets/dataset-7/columns/column-9',
      method: 'put',
      data: {
        displayName: '客户手机号',
        description: '用于联系客户，禁止默认查询',
        sensitive: true,
        status: 'inactive'
      }
    });
  });

  it('reads dataset deletion impact before a destructive action', () => {
    fetchDatasetDeleteImpact('dataset-7');

    expect(request).toHaveBeenCalledWith({
      url: '/platform/datasets/dataset-7/delete-impact',
      method: 'get'
    });
  });

  it('keeps dataset deletion on the dataset-scoped endpoint', () => {
    deleteDataset('dataset-7');

    expect(request).toHaveBeenCalledWith({
      url: '/platform/datasets/dataset-7',
      method: 'delete'
    });
  });
});
