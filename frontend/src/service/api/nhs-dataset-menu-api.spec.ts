import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import {
  clearNhsDatasetQuestionClick,
  fetchNhsDatasetMenu,
  recordNhsDatasetQuestionClick,
  refreshNhsDatasetGroupQuestions,
  recommendNhsDatasetTableQuestions
} from './portal';

vi.mock('../request', () => ({ request: vi.fn() }));

describe('Nhs dataset-menu API contract', () => {
  beforeEach(() => {
    vi.mocked(request).mockClear();
  });

  it('loads the authorized menu and keeps refresh opt-in', () => {
    fetchNhsDatasetMenu();
    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/chat/dataset-menu',
      method: 'get',
      params: undefined
    });

    vi.mocked(request).mockClear();
    fetchNhsDatasetMenu(true);
    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/chat/dataset-menu',
      method: 'get',
      params: { refresh: true }
    });
  });

  it('records and clears a question without changing Nhs snake_case fields', () => {
    recordNhsDatasetQuestionClick({
      query: '统计订单趋势',
      label: '趋势',
      group_id: 'dataset_1',
      dataset_menu_hash: 'a'.repeat(64)
    });
    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/chat/dataset-menu/click',
      method: 'post',
      data: {
        query: '统计订单趋势',
        label: '趋势',
        group_id: 'dataset_1',
        dataset_menu_hash: 'a'.repeat(64)
      }
    });

    vi.mocked(request).mockClear();
    clearNhsDatasetQuestionClick('统计订单趋势');
    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/chat/dataset-menu/click/clear',
      method: 'post',
      data: { query: '统计订单趋势' }
    });
  });

  it('posts refresh and table recommendation payloads to their V1 routes', () => {
    refreshNhsDatasetGroupQuestions({
      group_title: '经营分析',
      tables: ['订单明细'],
      dataset_menu_hash: 'menu-hash',
      group_id: 'dataset_1',
      exclude_questions: [{ query: '旧问题' }],
      purpose: 'questions'
    });
    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/chat/dataset-menu/refresh-group-questions',
      method: 'post',
      data: {
        group_title: '经营分析',
        tables: ['订单明细'],
        dataset_menu_hash: 'menu-hash',
        group_id: 'dataset_1',
        exclude_questions: [{ query: '旧问题' }],
        purpose: 'questions'
      }
    });

    vi.mocked(request).mockClear();
    recommendNhsDatasetTableQuestions({
      table: '订单明细',
      physical_table_name: 'biz_orders',
      dataset_name: '经营分析',
      columns: [{ name: 'amount', term: '金额' }]
    });
    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/chat/dataset-menu/recommend-table-questions',
      method: 'post',
      data: {
        table: '订单明细',
        physical_table_name: 'biz_orders',
        dataset_name: '经营分析',
        columns: [{ name: 'amount', term: '金额' }]
      }
    });
  });
});
