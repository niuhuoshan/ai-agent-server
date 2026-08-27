import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MetadataMetrics from './metadata-metrics.vue';
import MetadataRelations from './metadata-relations.vue';
import MetadataRowPolicy from './metadata-row-policy.vue';

const api = vi.hoisted(() => ({
  createMetric: vi.fn(),
  createRelation: vi.fn(),
  deleteMetric: vi.fn(),
  deleteRelation: vi.fn(),
  fetchMetrics: vi.fn(),
  fetchRelations: vi.fn(),
  fetchRowPolicy: vi.fn(),
  updateMetric: vi.fn(),
  updateRelation: vi.fn(),
  updateRowPolicy: vi.fn()
}));

vi.mock('@/service/api', () => ({
  createDatasetMetric: api.createMetric,
  createDatasetRelation: api.createRelation,
  deleteDatasetMetric: api.deleteMetric,
  deleteDatasetRelation: api.deleteRelation,
  fetchDatasetMetrics: api.fetchMetrics,
  fetchDatasetRelations: api.fetchRelations,
  fetchDatasetRowPolicy: api.fetchRowPolicy,
  updateDatasetMetric: api.updateMetric,
  updateDatasetRelation: api.updateRelation,
  updateDatasetRowPolicy: api.updateRowPolicy
}));

const metadata = [
  {
    id: '100', tableKey: 'public.orders', physicalSchema: 'public', physicalName: 'orders',
    displayName: '订单', description: '订单主表', tableType: 'BASE TABLE', status: 'active', metadataPresent: true,
    columns: [
      {
        id: '101', columnKey: 'public.orders.customer_id', physicalName: 'customer_id', displayName: '客户 ID',
        dataType: 'bigint', description: '下单客户', primary: false, sensitive: false, status: 'active', metadataPresent: true
      }
    ]
  },
  {
    id: '200', tableKey: 'public.customers', physicalSchema: 'public', physicalName: 'customers',
    displayName: '客户', description: '客户主表', tableType: 'BASE TABLE', status: 'active', metadataPresent: true,
    columns: [
      {
        id: '201', columnKey: 'public.customers.id', physicalName: 'id', displayName: '客户 ID',
        dataType: 'bigint', description: '客户主键', primary: true, sensitive: false, status: 'active', metadataPresent: true
      }
    ]
  }
];

function success<T>(data: T) {
  return { data, error: null, response: { status: 200 } };
}

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
  NDynamicTags: {
    props: ['value'],
    emits: ['update:value'],
    template: '<input class="dynamic-tags" :value="(value || []).join(\',\')" @input="$emit(\'update:value\', $event.target.value.split(\',\').filter(Boolean))" />'
  },
  NEmpty: { props: ['description'], template: '<div class="empty-stub">{{ description }}</div>' },
  NForm: passthrough,
  NFormItem: { props: ['label'], template: '<label><span>{{ label }}</span><slot /></label>' },
  NInput: {
    inheritAttrs: false,
    props: ['value'],
    emits: ['update:value'],
    template: '<textarea v-if="$attrs.type === \'textarea\'" v-bind="$attrs" :value="value || \'\'" @input="$emit(\'update:value\', $event.target.value)" /><input v-else v-bind="$attrs" :value="value || \'\'" @input="$emit(\'update:value\', $event.target.value)" />'
  },
  NModal: {
    props: ['show', 'title'],
    template: '<div v-if="show" class="modal-stub"><h2>{{ title }}</h2><slot /><slot name="footer" /><slot name="action" /></div>'
  },
  NSelect: {
    inheritAttrs: false,
    props: ['value', 'options'],
    emits: ['update:value'],
    template: '<select v-bind="$attrs" :value="value" @change="$emit(\'update:value\', $event.target.value)"><option v-for="option in options" :key="option.value" :value="option.value">{{ option.label }}</option></select>'
  },
  NSpace: passthrough,
  NSwitch: {
    props: ['value', 'disabled'],
    emits: ['update:value'],
    template: '<input type="checkbox" :disabled="disabled" :checked="value" @change="$emit(\'update:value\', $event.target.checked)" />'
  },
  NTag: { template: '<span class="tag-stub"><slot /></span>' }
};

let wrapper: ReturnType<typeof mount> | null = null;

function button(label: string) {
  const match = wrapper?.findAll('button').find(item => item.text().includes(label));
  if (!match) throw new Error(`Button not found: ${label}`);
  return match;
}

beforeEach(() => {
  vi.resetAllMocks();
  api.fetchMetrics.mockResolvedValue(success([]));
  api.fetchRelations.mockResolvedValue(success([]));
  api.fetchRowPolicy.mockResolvedValue(success({
    datasetId: '10',
    revisionNo: 4,
    enabled: false,
    rules: [{ tableId: '100', columnId: '101', operator: 'eq', valueSource: 'principal_id' }],
    updatedAt: null
  }));
  api.createMetric.mockResolvedValue(success({ id: '1' }));
  api.createRelation.mockResolvedValue(success({ id: '2' }));
  api.updateRowPolicy.mockResolvedValue(success({
    datasetId: '10',
    revisionNo: 5,
    enabled: true,
    rules: [{ tableId: '100', columnId: '101', operator: 'eq', valueSource: 'principal_id' }],
    updatedAt: '2026-08-17T10:00:00'
  }));
  Object.defineProperty(window, '$message', {
    configurable: true,
    value: { error: vi.fn(), success: vi.fn(), warning: vi.fn() }
  });
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = null;
});

describe('metadata semantic model editors', () => {
  it('creates a metric with a stable key and calculation logic', async () => {
    wrapper = mount(MetadataMetrics, { props: { datasetId: '10' }, global: { stubs } });
    await flushPromises();
    await button('新建指标').trigger('click');
    await wrapper.get('[data-testid="metric-key"]').setValue('gross_sales');
    await wrapper.get('[data-testid="metric-name"]').setValue('销售额');
    await wrapper.get('[data-testid="metric-description"]').setValue('含税销售额');
    await wrapper.get('[data-testid="metric-unit"]').setValue('元');
    await wrapper.get('[data-testid="metric-logic"]').setValue('SUM(orders.total_amount)');
    await button('保存').trigger('click');
    await flushPromises();

    expect(api.createMetric).toHaveBeenCalledWith('10', {
      metricKey: 'gross_sales',
      name: '销售额',
      description: '含税销售额',
      calculationLogic: 'SUM(orders.total_amount)',
      unit: '元',
      status: 'active'
    });
  });

  it('creates a dataset-scoped table relation from authorized metadata', async () => {
    wrapper = mount(MetadataRelations, {
      props: { datasetId: '10', metadata },
      global: { stubs }
    });
    await flushPromises();
    await button('新建关系').trigger('click');
    await wrapper.get('[data-testid="relation-condition"]').setValue('orders.customer_id = customers.id');
    await wrapper.get('[data-testid="relation-description"]').setValue('订单客户关系');
    await button('保存').trigger('click');
    await flushPromises();

    expect(api.createRelation).toHaveBeenCalledWith('10', {
      sourceTableId: '100',
      targetTableId: '200',
      joinType: 'left',
      joinCondition: 'orders.customer_id = customers.id',
      description: '订单客户关系',
      status: 'active'
    });
  });

  it('saves row policy changes with the server revision and structured rules', async () => {
    wrapper = mount(MetadataRowPolicy, {
      props: { datasetId: '10', metadata },
      global: { stubs }
    });
    await flushPromises();
    await wrapper.get('input[type="checkbox"]').setValue(true);
    await wrapper.get('[data-testid="row-policy-save"]').trigger('click');
    await flushPromises();

    expect(api.updateRowPolicy).toHaveBeenCalledWith('10', {
      revisionNo: 4,
      enabled: true,
      rules: [{ tableId: '100', columnId: '101', operator: 'eq', valueSource: 'principal_id' }]
    });
  });
});
