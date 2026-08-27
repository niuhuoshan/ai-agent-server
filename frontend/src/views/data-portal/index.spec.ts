import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import DataPortal from './index.vue';

const api = vi.hoisted(() => ({
  clearClick: vi.fn(),
  fetchHome: vi.fn(),
  fetchMenu: vi.fn(),
  recordClick: vi.fn(),
  recommendTable: vi.fn(),
  refreshGroup: vi.fn()
}));

const navigation = vi.hoisted(() => ({
  route: { query: {} as Record<string, unknown> },
  router: { push: vi.fn(), replace: vi.fn() }
}));

vi.mock('@/service/api', () => ({
  clearNhsDatasetQuestionClick: api.clearClick,
  fetchNhsDatasetMenu: api.fetchMenu,
  fetchPortalDataPortalHome: api.fetchHome,
  recordNhsDatasetQuestionClick: api.recordClick,
  recommendNhsDatasetTableQuestions: api.recommendTable,
  refreshNhsDatasetGroupQuestions: api.refreshGroup
}));

vi.mock('vue-router', () => ({
  useRoute: () => navigation.route,
  useRouter: () => navigation.router
}));

const home = {
  attention: {
    failed_runs_today: 1,
    latest_failed_run: null,
    digests_today: 0,
    latest_digest_at: null,
    active_subscriptions: 2,
    completed_subscriptions_today: 1
  },
  recent_analysis: [
    {
      type: 'conversation',
      id: '501',
      conversation_id: '501',
      title: '订单趋势分析',
      subtitle: 'ChatBI · 数据分析',
      status: 'active',
      occurred_at: '2026-08-17T09:30:00',
      action: 'open_conversation'
    }
  ],
  report_summary: {
    subscribed: 1,
    pinned: 0,
    favorite: 0,
    shared: 1,
    recent: 1,
    items: [
      {
        id: '700',
        title: '经营周报',
        owner_name: '管理员',
        is_owner: false,
        is_favorite: false,
        pinned: false,
        last_run_at: '2026-08-17T08:00:00',
        last_error: null,
        subscription_status: 'active',
        subscription_next_run_at: '2026-08-18T08:00:00'
      }
    ]
  },
  generated_at: '2026-08-17T10:00:00'
};

const menu = {
  dataset_count: 1,
  dataset_menu_hash: 'menu-hash',
  generated_at: '2026-08-17T10:00:00',
  groups: [
    {
      id: 'dataset_101',
      title: '经营分析',
      summary: '围绕订单和客户进行经营分析。',
      tags: ['订单', '客户'],
      questions: [{ label: '订单趋势', query: '分析最近30天订单趋势', type: 'dynamic', click_count: 2 }],
      followups: [{ label: '异常订单', query: '定位最近异常订单' }],
      related_data: [
        {
          dataset: 'sales',
          display_name: '销售经营数据',
          tables: ['订单明细'],
          table_descriptions: [{ name: '订单明细', description: '每笔订单的交易明细' }],
          table_physical_names: { 订单明细: 'biz_orders' },
          table_columns: {
            订单明细: [
              { name: 'customer_phone', term: '客户手机号', type: 'varchar', description: '客户联系方式' },
              { name: 'total_amount', term: '订单金额', type: 'decimal', description: '订单含税金额' }
            ]
          }
        }
      ],
      total_click_count: 2,
      updated_at: '2026-08-17T09:00:00'
    }
  ],
  markdown: '',
  is_fallback: false,
  has_datasets: true,
  from_cache: true,
  llm_generation_failed: false,
  llm_error_message: null
};

function success<T>(data: T) {
  return { data, error: null, response: { status: 200 } };
}

const stubs = {
  SvgIcon: { props: ['icon'], template: '<i :data-icon="icon" />' },
  NAlert: { template: '<div class="alert-stub"><slot /></div>' },
  NButton: {
    inheritAttrs: false,
    props: ['disabled', 'loading'],
    emits: ['click'],
    template:
      '<button v-bind="$attrs" type="button" :disabled="disabled || loading" @click="$emit(\'click\')"><slot name="icon" /><slot /></button>'
  },
  NEmpty: { props: ['description'], template: '<div class="empty-stub">{{ description }}</div>' },
  NInput: {
    inheritAttrs: false,
    props: ['value'],
    emits: ['update:value'],
    template: '<input v-bind="$attrs" :value="value || \'\'" @input="$emit(\'update:value\', $event.target.value)" />'
  },
  NSpin: { template: '<div class="spin-stub"><slot /></div>' },
  NTag: { template: '<span class="tag-stub"><slot /></span>' }
};

let wrapper: ReturnType<typeof mount> | null = null;

async function mountPortal() {
  wrapper = mount(DataPortal, { global: { stubs } });
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  vi.resetAllMocks();
  navigation.route.query = {};
  navigation.router.push.mockResolvedValue(undefined);
  navigation.router.replace.mockResolvedValue(undefined);
  api.fetchHome.mockResolvedValue(success(structuredClone(home)));
  api.fetchMenu.mockResolvedValue(success(structuredClone(menu)));
  api.recordClick.mockResolvedValue(success({ success: true }));
  api.clearClick.mockResolvedValue(success({ success: true }));
  api.refreshGroup.mockResolvedValue(
    success({
      questions: [{ label: '区域排名', query: '按区域统计订单金额排名', type: 'dynamic' }],
      refresh_disabled_reason: null
    })
  );
  api.recommendTable.mockResolvedValue(
    success({
      questions: [{ label: '客单价分布', query: '分析不同区域客单价分布', type: 'dynamic' }],
      refresh_disabled_reason: null
    })
  );
  Object.defineProperty(window, '$message', {
    configurable: true,
    value: { error: vi.fn(), warning: vi.fn(), success: vi.fn() }
  });
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = null;
});

describe('data portal page', () => {
  it('loads both portal projections and records a scenario question before opening ChatBI', async () => {
    const page = await mountPortal();

    expect(api.fetchHome).toHaveBeenCalledTimes(1);
    expect(api.fetchMenu).toHaveBeenCalledWith(false);
    expect(page.findAll('[role="tab"]')).toHaveLength(4);
    expect(page.text()).toContain('经营周报');

    await page.get('[data-testid="portal-tab-scenarios"]').trigger('click');
    await page.get('[data-testid="portal-question"]').trigger('click');
    await flushPromises();

    expect(api.recordClick).toHaveBeenCalledWith({
      query: '分析最近30天订单趋势',
      label: '订单趋势',
      group_id: 'dataset_101',
      dataset_menu_hash: 'menu-hash'
    });
    expect(navigation.router.push).toHaveBeenCalledWith({
      path: '/chatbi',
      query: {
        question: '分析最近30天订单趋势',
        source: 'data_portal',
        dataset_id: '101'
      }
    });
  });

  it('refreshes one scenario group with its authorized table context', async () => {
    const page = await mountPortal();
    await page.get('[data-testid="portal-tab-scenarios"]').trigger('click');
    await page.get('[data-testid="group-refresh"]').trigger('click');
    await flushPromises();

    expect(api.refreshGroup).toHaveBeenCalledWith({
      group_title: '经营分析',
      tables: ['订单明细'],
      dataset_menu_hash: 'menu-hash',
      group_id: 'dataset_101',
      exclude_questions: [{ label: '订单趋势', query: '分析最近30天订单趋势' }],
      purpose: 'questions'
    });
    expect(page.text()).toContain('区域排名');
    expect(page.text()).toContain('按区域统计订单金额排名');
  });

  it('searches fields, requests table recommendations and opens a recommended question', async () => {
    const page = await mountPortal();
    await page.get('[data-testid="portal-tab-catalog"]').trigger('click');
    await page.get('[data-testid="catalog-search"]').get('input').setValue('客户手机号');

    expect(page.text()).toContain('销售经营数据');
    expect(page.text()).toContain('订单明细');
    expect(page.text()).toContain('customer_phone');

    await page.get('[data-testid="table-recommend"]').trigger('click');
    await flushPromises();
    expect(api.recommendTable).toHaveBeenCalledWith({
      table: '订单明细',
      physical_table_name: 'biz_orders',
      dataset_name: '销售经营数据',
      columns: [
        { name: 'customer_phone', term: '客户手机号', type: 'varchar', description: '客户联系方式' },
        { name: 'total_amount', term: '订单金额', type: 'decimal', description: '订单含税金额' }
      ]
    });
    expect(page.text()).toContain('客单价分布');

    await page.get('[data-testid="table-question"]').trigger('click');
    await flushPromises();
    expect(api.recordClick).toHaveBeenCalledWith(
      expect.objectContaining({
        query: '分析不同区域客单价分布',
        group_id: 'dataset_101'
      })
    );
    expect(navigation.router.push).toHaveBeenLastCalledWith({
      path: '/chatbi',
      query: {
        question: '分析不同区域客单价分布',
        source: 'data_portal',
        dataset_id: '101'
      }
    });
  });
});
