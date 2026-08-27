import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import {
  createDatasetMetric,
  createDatasetRelation,
  deleteDatasetMetric,
  deleteDatasetRelation,
  fetchDatasetMetadataChanges,
  fetchDatasetMetrics,
  fetchDatasetRelations,
  fetchDatasetRowPolicy,
  updateDatasetMetric,
  updateDatasetRelation,
  updateDatasetRowPolicy
} from './platform';

vi.mock('../request', () => ({ request: vi.fn() }));

describe('metadata semantic model API contract', () => {
  beforeEach(() => vi.mocked(request).mockClear());

  it('uses dataset-scoped metric CRUD endpoints and preserves optimistic version', () => {
    const createPayload = {
      metricKey: 'gross_sales',
      name: '销售额',
      description: '含税销售额',
      calculationLogic: 'SUM(orders.total_amount)',
      unit: '元',
      status: 'active' as const
    };
    const { metricKey: _metricKey, ...metricFields } = createPayload;
    const updatePayload = { ...metricFields, versionNo: 3 };
    fetchDatasetMetrics('10');
    createDatasetMetric('10', createPayload);
    updateDatasetMetric('10', '20', updatePayload);
    deleteDatasetMetric('10', '20');

    expect(vi.mocked(request).mock.calls).toEqual([
      [{ url: '/platform/datasets/10/metrics', method: 'get' }],
      [{ url: '/platform/datasets/10/metrics', method: 'post', data: createPayload }],
      [{ url: '/platform/datasets/10/metrics/20', method: 'put', data: updatePayload }],
      [{ url: '/platform/datasets/10/metrics/20', method: 'delete' }]
    ]);
  });

  it('uses dataset-scoped relation CRUD endpoints', () => {
    const createPayload = {
      sourceTableId: '100',
      targetTableId: '200',
      joinType: 'left' as const,
      joinCondition: 'orders.customer_id = customers.id',
      description: '订单客户关系',
      status: 'active' as const
    };
    const updatePayload = { ...createPayload, revisionNo: 2 };
    fetchDatasetRelations('10');
    createDatasetRelation('10', createPayload);
    updateDatasetRelation('10', '30', updatePayload);
    deleteDatasetRelation('10', '30');

    expect(vi.mocked(request).mock.calls).toEqual([
      [{ url: '/platform/datasets/10/relationships', method: 'get' }],
      [{ url: '/platform/datasets/10/relationships', method: 'post', data: createPayload }],
      [{ url: '/platform/datasets/10/relationships/30', method: 'put', data: updatePayload }],
      [{ url: '/platform/datasets/10/relationships/30', method: 'delete' }]
    ]);
  });

  it('loads and updates a revision-guarded row policy without reshaping rules', () => {
    const payload = {
      revisionNo: 4,
      enabled: true,
      rules: [
        {
          tableId: '100',
          columnId: '101',
          operator: 'eq' as const,
          valueSource: 'principal_id' as const
        }
      ]
    };
    fetchDatasetRowPolicy('10');
    updateDatasetRowPolicy('10', payload);
    expect(vi.mocked(request).mock.calls).toEqual([
      [{ url: '/platform/datasets/10/row-policy', method: 'get' }],
      [{ url: '/platform/datasets/10/row-policy', method: 'put', data: payload }]
    ]);
  });

  it('loads durable metadata change facts with an explicit bound', () => {
    fetchDatasetMetadataChanges('10', 200);
    expect(request).toHaveBeenCalledWith({
      url: '/platform/datasets/10/metadata-changes',
      method: 'get',
      params: { limit: 200 }
    });
  });
});
