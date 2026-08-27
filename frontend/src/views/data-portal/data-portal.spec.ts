import { describe, expect, it } from 'vitest';
import {
  buildDataPortalCatalog,
  datasetIdFromGroup,
  filterDataPortalCatalog,
  portalQuestionRoute,
  resolveDataPortalSection
} from './data-portal';

describe('data portal presentation state', () => {
  it('normalizes legacy section names and preserves long dataset identifiers', () => {
    expect(resolveDataPortalSection('home')).toBe('overview');
    expect(resolveDataPortalSection('scenes')).toBe('scenarios');
    expect(
      datasetIdFromGroup({
        id: 'dataset_9223372036854775806',
        title: '经营',
        summary: '',
        questions: []
      })
    ).toBe('9223372036854775806');
  });

  it('builds and searches the dataset, table and column catalog', () => {
    const catalog = buildDataPortalCatalog({
      dataset_count: 1,
      dataset_menu_hash: 'hash',
      generated_at: '',
      groups: [
        {
          id: 'dataset_10',
          title: '销售',
          summary: '',
          questions: [],
          related_data: [
            {
              dataset: 'sales',
              display_name: '销售数据',
              tables: ['订单'],
              table_physical_names: { 订单: 'orders' },
              table_columns: { 订单: [{ name: 'amount', term: '订单金额', type: 'decimal' }] }
            }
          ]
        }
      ],
      markdown: '',
      is_fallback: false,
      has_datasets: true,
      from_cache: false,
      llm_generation_failed: false
    });

    expect(catalog[0]).toMatchObject({ id: '10', name: '销售数据', columnCount: 1 });
    expect(filterDataPortalCatalog(catalog, '订单金额')[0].tables[0].physicalName).toBe('orders');
    expect(filterDataPortalCatalog(catalog, '不存在')).toEqual([]);
  });

  it('creates a ChatBI route with question and optional dataset context', () => {
    expect(portalQuestionRoute({ label: '趋势', query: '分析订单趋势' }, '10')).toEqual({
      path: '/chatbi',
      query: { question: '分析订单趋势', source: 'data_portal', dataset_id: '10' }
    });
  });
});
