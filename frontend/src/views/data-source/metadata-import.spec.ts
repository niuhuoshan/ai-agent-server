import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { h, type VNode } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MetadataImport from './metadata-import.vue';

const api = vi.hoisted(() => ({
  applyPreview: vi.fn(),
  createPreview: vi.fn(),
  downloadYaml: vi.fn(),
  fetchPreview: vi.fn()
}));
const dialog = vi.hoisted(() => ({ warning: vi.fn() }));

vi.mock('@/service/api', () => ({
  applyMetadataImportPreview: api.applyPreview,
  createMetadataImportPreview: api.createPreview,
  downloadDatasetMetadataYaml: api.downloadYaml,
  fetchMetadataImportPreview: api.fetchPreview
}));

vi.mock('naive-ui', async importOriginal => {
  const actual = await importOriginal<typeof import('naive-ui')>();
  return { ...actual, useDialog: () => dialog };
});

const preview = {
  id: '51',
  datasetId: '10',
  sourceType: 'ddl',
  status: 'draft',
  datasetRevision: 8,
  revisionNo: 2,
  tableCount: 1,
  columnCount: 2,
  diagnostics: [{
    level: 'warning',
    code: 'missing_endpoint',
    message: '关系 public.orders -> public.customers 缺少端点，本次未导入',
    resourceKey: 'orders_customer'
  }],
  expiresAt: '2099-01-01T00:00:00Z',
  createdBy: '7',
  createdAt: '2026-08-17T12:00:00Z',
  appliedBy: null,
  appliedAt: null,
  items: [
    {
      id: '61', itemType: 'table', resourceKey: 'public.orders', action: 'create', status: 'available',
      currentHash: null, contentHash: 'table-hash', appliedResourceId: null, errorMessage: null,
      proposal: { schema: 'public', name: 'orders', displayName: '订单', columns: [{ name: 'id' }] }
    },
    {
      id: '62', itemType: 'metric', resourceKey: 'gross_sales', action: 'update', status: 'available',
      currentHash: 'old-hash', contentHash: 'metric-hash', appliedResourceId: null, errorMessage: null,
      proposal: { name: '销售额', calculationLogic: 'SUM(orders.amount)' }
    }
  ]
} as const;

const passthrough = { template: '<div><slot /></div>' };
const stubs = {
  SvgIcon: true,
  NAlert: { template: '<div class="alert-stub"><slot /></div>' },
  NButton: {
    inheritAttrs: false,
    props: ['disabled', 'loading'],
    emits: ['click'],
    template: '<button v-bind="$attrs" type="button" :disabled="disabled || loading" @click="$emit(\'click\')"><slot name="icon" /><slot /></button>'
  },
  NCode: { props: ['code'], template: '<pre>{{ code }}</pre>' },
  NDataTable: {
    props: ['data', 'columns'],
    emits: ['update:checkedRowKeys'],
    setup(
      props: { data?: Array<Record<string, unknown>>; columns?: Array<{ key?: string; render?: (row: Record<string, unknown>) => VNode }> },
      { emit }: { emit: (event: 'update:checkedRowKeys', keys: string[]) => void }
    ) {
      return () => h('div', { class: 'data-table-stub' }, [
        h('button', {
          type: 'button',
          class: 'select-first-row',
          onClick: () => emit('update:checkedRowKeys', props.data?.length ? [String(props.data[0].id)] : [])
        }, '仅选择第一项'),
        ...(props.data || []).map(row => {
          const resource = (props.columns || []).find(column => column.key === 'resourceKey')?.render?.(row);
          return h('div', { class: 'data-row', key: String(row.id) }, [resource || String(row.resourceKey)]);
        })
      ]);
    }
  },
  NDescriptions: passthrough,
  NDescriptionsItem: { props: ['label'], template: '<div><span>{{ label }}</span><slot /></div>' },
  NEmpty: { props: ['description'], template: '<div class="empty-stub">{{ description }}</div>' },
  NInput: {
    inheritAttrs: false,
    props: ['value'],
    emits: ['update:value'],
    template: '<textarea v-bind="$attrs" :value="value || \'\'" @input="$emit(\'update:value\', $event.target.value)" />'
  },
  NModal: { props: ['show'], template: '<div v-if="show"><slot /></div>' },
  NRadioButton: passthrough,
  NRadioGroup: passthrough,
  NSelect: passthrough,
  NSpin: passthrough,
  NTag: { template: '<span class="tag-stub"><slot /></span>' },
  NUpload: passthrough
};

const message = { error: vi.fn(), success: vi.fn(), warning: vi.fn() };
let wrapper: VueWrapper | null = null;

function success<T>(data: T) {
  return { data, error: null, response: { status: 200 } };
}

async function mountPage() {
  wrapper = mount(MetadataImport, {
    props: { datasetId: '10', datasetName: '销售分析' },
    global: { stubs }
  });
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  vi.resetAllMocks();
  window.localStorage.clear();
  Object.defineProperty(window, '$message', { configurable: true, value: message });
  api.createPreview.mockResolvedValue(success(preview));
  api.fetchPreview.mockResolvedValue(success(preview));
  api.applyPreview.mockResolvedValue(success({
    previewId: '51',
    status: 'applied',
    datasetRevision: 9,
    revisionNo: 3,
    appliedItemIds: ['61', '62'],
    skippedItemIds: [],
    appliedAt: '2026-08-17T12:05:00Z'
  }));
  dialog.warning.mockImplementation((options: { onPositiveClick?: () => unknown }) => options.onPositiveClick?.());
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = null;
});

describe('metadata import workbench', () => {
  it('creates a typed preview and applies every selected item in one revision-guarded request', async () => {
    await mountPage();
    await wrapper?.get('[data-testid="metadata-import-content"]').setValue('CREATE TABLE public.orders (id BIGINT);');
    await wrapper?.get('[data-testid="metadata-create-preview"]').trigger('click');
    await flushPromises();

    expect(api.createPreview).toHaveBeenCalledWith('10', {
      format: 'ddl',
      content: 'CREATE TABLE public.orders (id BIGINT);'
    });
    expect(wrapper?.text()).toContain('订单');
    expect(wrapper?.text()).toContain('销售额');
    expect(wrapper?.text()).toContain('已选择 2 项');

    await wrapper?.get('[data-testid="metadata-import-content"]').setValue('CREATE TABLE public.orders (id BIGINT); -- changed');
    expect(wrapper?.get('[data-testid="metadata-apply-preview"]').attributes('disabled')).toBeDefined();
    await wrapper?.get('[data-testid="metadata-import-content"]').setValue('CREATE TABLE public.orders (id BIGINT);');

    await wrapper?.get('.select-first-row').trigger('click');
    expect(wrapper?.text()).toContain('已选择 1 项');

    await wrapper?.get('[data-testid="metadata-apply-preview"]').trigger('click');
    await flushPromises();

    expect(dialog.warning).toHaveBeenCalledWith(expect.objectContaining({
      content: expect.stringContaining('其余 1 项将标记为跳过')
    }));
    expect(api.applyPreview).toHaveBeenCalledTimes(1);
    expect(api.applyPreview).toHaveBeenCalledWith('10', '51', {
      revisionNo: 2,
      itemIds: ['61']
    });
    expect(wrapper?.emitted('applied')?.[0]?.[0]).toMatchObject({ datasetRevision: 9, status: 'applied' });
  });

  it('restores a durable dataset-scoped preview without needing the original source text', async () => {
    window.localStorage.setItem('agent:metadata-import-preview:10', '51');
    await mountPage();

    expect(api.fetchPreview).toHaveBeenCalledWith('10', '51');
    expect(wrapper?.text()).toContain('预览 51');
    expect(wrapper?.get('[data-testid="metadata-apply-preview"]').attributes('disabled')).toBeUndefined();
  });
});
