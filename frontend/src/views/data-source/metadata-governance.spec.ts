import { flushPromises, mount } from '@vue/test-utils';
import { h, type VNode } from 'vue';
import type { VueWrapper } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MetadataGovernance from './metadata-governance.vue';

const api = vi.hoisted(() => ({
  downloadYaml: vi.fn(),
  updateColumn: vi.fn(),
  updateTable: vi.fn()
}));

vi.mock('@/service/api', () => ({
  downloadDatasetMetadataYaml: api.downloadYaml,
  updateDataColumn: api.updateColumn,
  updateDataTable: api.updateTable
}));

const metadata = [
  {
    id: 'table-1',
    tableKey: 'public.orders',
    physicalSchema: 'public',
    physicalName: 'orders',
    displayName: '订单',
    description: '订单主表',
    tableType: 'BASE TABLE',
    status: 'active',
    metadataPresent: true,
    columns: [
      {
        id: 'column-1',
        columnKey: 'public.orders.customer_phone',
        physicalName: 'customer_phone',
        displayName: '客户手机号',
        dataType: 'varchar',
        description: '客户联系方式',
        primary: false,
        sensitive: true,
        status: 'active',
        metadataPresent: true
      },
      {
        id: 'column-2',
        columnKey: 'public.orders.total_amount',
        physicalName: 'total_amount',
        displayName: '订单金额',
        dataType: 'numeric',
        description: null,
        primary: false,
        sensitive: false,
        status: 'active',
        metadataPresent: true
      }
    ]
  }
];

const message = {
  error: vi.fn(),
  success: vi.fn()
};

const passthrough = { template: '<div><slot /></div>' };
const stubs = {
  SvgIcon: true,
  NAlert: {
    props: ['type'],
    emits: ['close'],
    template: '<div class="alert-stub"><slot /></div>'
  },
  NButton: {
    props: ['disabled', 'loading', 'type'],
    emits: ['click'],
    template: '<button type="button" :disabled="disabled" @click="$emit(\'click\')"><slot name="icon" /><slot /></button>'
  },
  NCollapse: passthrough,
  NCollapseItem: {
    props: ['name'],
    template: '<section class="collapse-item"><div class="collapse-header"><slot name="header" /></div><slot /></section>'
  },
  NDataTable: {
    props: ['data', 'columns'],
    setup(props: { data?: Array<{ id: string; physicalName: string; displayName?: string | null }>; columns?: Array<{ key?: string; render?: (row: unknown) => VNode }> }) {
      return () => h('div', { class: 'data-table-stub' }, (props.data || []).map(row => {
        const action = (props.columns || []).find(column => column.key === 'actions');
        const vnode = action?.render?.(row);
        const onClick = (vnode?.props as { onClick?: () => void } | undefined)?.onClick;
        return h('div', { class: 'data-row', key: row.id }, [
          h('span', `${row.displayName || row.physicalName} ${row.physicalName}`),
          h('button', { type: 'button', class: 'column-governance', onClick }, '治理')
        ]);
      }));
    }
  },
  NDescriptions: passthrough,
  NDescriptionsItem: {
    props: ['label'],
    template: '<div class="description-item"><span>{{ label }}</span><slot /></div>'
  },
  NEmpty: {
    props: ['description'],
    template: '<div class="empty-stub">{{ description }}</div>'
  },
  NForm: passthrough,
  NFormItem: {
    props: ['label'],
    template: '<label><span>{{ label }}</span><slot /></label>'
  },
  NInput: {
    inheritAttrs: false,
    props: ['value'],
    emits: ['update:value'],
    template: '<textarea v-if="$attrs.type === \'textarea\'" v-bind="$attrs" :value="value || \'\'" @input="$emit(\'update:value\', $event.target.value)" /><input v-else v-bind="$attrs" :value="value || \'\'" @input="$emit(\'update:value\', $event.target.value)" />'
  },
  NModal: {
    props: ['show', 'title'],
    template: '<div v-if="show" class="modal-stub" :data-title="title"><h2>{{ title }}</h2><slot /><slot name="footer" /></div>'
  },
  NSelect: {
    inheritAttrs: false,
    props: ['value', 'options'],
    emits: ['update:value'],
    template: '<select v-bind="$attrs" :value="value" @change="$emit(\'update:value\', $event.target.value)"><option v-for="option in options" :key="option.value" :value="option.value">{{ option.label }}</option></select>'
  },
  NSpace: passthrough,
  NSpin: { template: '<div class="spin-stub"><slot /></div>' },
  NSwitch: {
    props: ['value'],
    emits: ['update:value'],
    template: '<label><input type="checkbox" :checked="value" @change="$emit(\'update:value\', $event.target.checked)" /><slot name="checked" /><slot name="unchecked" /></label>'
  },
  NTag: { props: ['type'], template: '<span class="tag-stub"><slot /></span>' }
};

let wrapper: VueWrapper | null = null;

function success<T>(data: T) {
  return { data, error: null, response: { status: 200 } };
}

function failure(messageText: string, status: number) {
  const response = { status, data: { code: status, msg: messageText } };
  const error = Object.assign(new Error(messageText), { response });
  return { data: null, error, response };
}

async function mountPage() {
  wrapper = mount(MetadataGovernance, {
    props: { datasetId: 'dataset-1', metadata },
    global: { stubs }
  });
  await flushPromises();
  return wrapper;
}

function button(label: string) {
  const match = wrapper?.findAll('button').find(item => item.text().includes(label));
  if (!match) throw new Error(`Button not found: ${label}`);
  return match;
}

beforeEach(() => {
  vi.resetAllMocks();
  Object.defineProperty(window, '$message', { configurable: true, value: message });
  api.updateTable.mockResolvedValue(success(undefined));
  api.updateColumn.mockResolvedValue(success(undefined));
  api.downloadYaml.mockResolvedValue({ blob: new Blob(['version: 1']), fileName: 'metadata.yaml' });
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = null;
});

describe('metadata governance', () => {
  it('renders searchable table and field governance facts', async () => {
    await mountPage();

    expect(wrapper?.text()).toContain('订单主表');
    expect(wrapper?.text()).toContain('客户手机号');
    expect(wrapper?.text()).toContain('1 张表');
    expect(wrapper?.text()).toContain('敏感字段1');

    await wrapper?.get('[data-testid="metadata-keyword"]').setValue('金额');
    expect(wrapper?.text()).toContain('订单');
    expect(wrapper?.text()).not.toContain('暂无匹配');
  });

  it('updates table description and status through the dataset-scoped endpoint', async () => {
    await mountPage();

    await button('编辑表治理').trigger('click');
    await wrapper?.get('[data-testid="table-display-name"]').setValue('订单主表');
    await wrapper?.get('[data-testid="table-description"]').setValue('用于订单经营分析');
    await wrapper?.get('[data-testid="table-status"]').setValue('inactive');
    await button('保存').trigger('click');
    await flushPromises();

    expect(api.updateTable).toHaveBeenCalledWith('dataset-1', 'table-1', {
      displayName: '订单主表',
      description: '用于订单经营分析',
      status: 'inactive'
    });
    expect(wrapper?.emitted('refresh')).toHaveLength(1);
    expect(message.success).toHaveBeenCalledWith('数据表治理已保存');
  });

  it('updates field sensitivity and status and surfaces forbidden responses', async () => {
    await mountPage();

    await wrapper?.find('.column-governance').trigger('click');
    await wrapper?.get('[data-testid="column-description"]').setValue('禁止默认查询');
    await wrapper?.get('[data-testid="column-status"]').setValue('inactive');
    await wrapper?.find('.modal-stub input[type="checkbox"]').setValue(false);
    await button('保存').trigger('click');
    await flushPromises();

    expect(api.updateColumn).toHaveBeenCalledWith('dataset-1', 'column-1', {
      displayName: '客户手机号',
      description: '禁止默认查询',
      sensitive: false,
      status: 'inactive'
    });

    api.updateColumn.mockResolvedValueOnce(failure('拒绝治理', 403));
    await wrapper?.find('.column-governance').trigger('click');
    await button('保存').trigger('click');
    await flushPromises();
    expect(wrapper?.text()).toContain('没有治理此数据集元数据的权限');
  });
});
