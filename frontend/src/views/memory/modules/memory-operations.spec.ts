import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MemoryOperations from './memory-operations.vue';

const api = vi.hoisted(() => ({
  fetchConfig: vi.fn(),
  fetchIndexStatus: vi.fn(),
  probeEmbedding: vi.fn(),
  probeRedis: vi.fn(),
  search: vi.fn(),
  updateConfig: vi.fn(),
  verifyIndex: vi.fn()
}));

vi.mock('@/service/api', () => ({
  fetchPortalMemoryConfig: api.fetchConfig,
  fetchPortalMemoryIndexStatus: api.fetchIndexStatus,
  testPortalMemoryEmbedding: api.probeEmbedding,
  testPortalMemoryRedisVector: api.probeRedis,
  testPortalMemorySearch: api.search,
  updatePortalMemoryConfig: api.updateConfig,
  verifyPortalMemoryIndex: api.verifyIndex
}));

const config = {
  provider: 'postgresql_tsvector',
  default_search_limit: 50,
  consolidation_mode: 'relational_daily_summary',
  embedding_enabled: false,
  redis_vector_enabled: false,
  stored: true,
  revision: 3
};

const indexStatus = {
  available: true,
  provider: 'postgresql_tsvector',
  owner_scoped: true,
  document_count: 18,
  automatically_maintained: true,
  rebuild_required: false,
  checked_at: '2026-08-17T02:00:00Z'
};

const message = {
  success: vi.fn(),
  warning: vi.fn()
};

let wrapper: VueWrapper | null = null;

function success<T>(data: T) {
  return { data, error: null, response: { status: 200 } };
}

function failure(backendMessage: string, status = 503) {
  const response = { status, data: { code: status, msg: backendMessage } };
  const error = Object.assign(new Error('request failed'), { response });
  return { data: null, error, response };
}

const passthrough = { template: '<div><slot /></div>' };
const stubs = {
  SvgIcon: true,
  Alert: {
    props: ['type'],
    template: '<div class="alert-stub" :data-alert-type="type"><slot /></div>'
  },
  Button: {
    props: ['disabled', 'loading', 'type'],
    emits: ['click'],
    template:
      '<button type="button" :disabled="disabled" :data-loading="loading" :data-kind="type" @click="$emit(\'click\')"><slot name="icon" /><slot /></button>'
  },
  DataTable: {
    props: ['data'],
    template:
      '<div class="data-table-stub"><div v-for="row in data" :key="row.id">{{ row.memory_key }} {{ row.memory_type }} {{ row.content }}</div></div>'
  },
  Descriptions: passthrough,
  DescriptionsItem: {
    props: ['label'],
    template: '<div><span>{{ label }}</span><slot /></div>'
  },
  Empty: {
    props: ['description'],
    template: '<div>{{ description }}</div>'
  },
  Form: passthrough,
  FormItem: {
    props: ['label'],
    template: '<label><span>{{ label }}</span><slot /></label>'
  },
  Input: {
    inheritAttrs: false,
    props: ['value'],
    emits: ['update:value'],
    template: '<input v-bind="$attrs" :value="value || \'\'" @input="$emit(\'update:value\', $event.target.value)" />'
  },
  InputNumber: {
    inheritAttrs: false,
    props: ['value'],
    emits: ['update:value'],
    template:
      '<input v-bind="$attrs" type="number" :value="value ?? \'\'" @input="$emit(\'update:value\', Number($event.target.value))" />'
  },
  Space: passthrough,
  Spin: passthrough,
  Tag: {
    props: ['type'],
    template: '<span class="tag-stub" :data-tag-type="type"><slot /></span>'
  }
};

async function mountPage() {
  wrapper = mount(MemoryOperations, { global: { stubs } });
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
  api.fetchConfig.mockResolvedValue(success(config));
  api.fetchIndexStatus.mockResolvedValue(success(indexStatus));
  api.updateConfig.mockResolvedValue(success({ ...config, default_search_limit: 80, revision: 4 }));
  api.verifyIndex.mockResolvedValue(
    success({
      ...indexStatus,
      verified: true,
      message: 'PostgreSQL 全文索引校验完成'
    })
  );
  api.search.mockResolvedValue(
    success([
      {
        id: 'memory-1',
        memory_key: 'approval-style',
        memory_type: 'preference',
        content: '高风险操作需要人工审批',
        updated_at: '2026-08-17T01:00:00Z'
      }
    ])
  );
  api.probeRedis.mockResolvedValue(success(undefined));
  api.probeEmbedding.mockResolvedValue(success(undefined));
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = null;
});

describe('memory operations page', () => {
  it('loads real index facts and persists the default search limit', async () => {
    await mountPage();

    expect(api.fetchConfig).toHaveBeenCalledOnce();
    expect(api.fetchIndexStatus).toHaveBeenCalledOnce();
    expect(wrapper?.text()).toContain('postgresql_tsvector');
    expect(wrapper?.text()).toContain('18');

    await wrapper?.find('[data-testid="default-search-limit"]').setValue('80');
    await button('保存配置').trigger('click');
    await flushPromises();

    expect(api.updateConfig).toHaveBeenCalledWith(80);
    expect(message.success).toHaveBeenCalledWith('记忆检索配置已保存');
  });

  it('runs an owner-scoped search and renders the persisted result', async () => {
    await mountPage();

    await wrapper?.find('[data-testid="memory-search-query"]').setValue('审批偏好');
    await button('检索').trigger('click');
    await flushPromises();

    expect(api.search).toHaveBeenCalledWith('审批偏好', 20);
    expect(wrapper?.text()).toContain('approval-style');
    expect(wrapper?.text()).toContain('高风险操作需要人工审批');
  });

  it('keeps unavailable providers visible instead of reporting a false success', async () => {
    api.probeRedis.mockResolvedValue(failure('Redis Vector Provider 未配置'));
    api.probeEmbedding.mockResolvedValue(failure('Embedding 模型未配置'));
    await mountPage();

    await button('检测 Redis Vector').trigger('click');
    await button('检测 Embedding').trigger('click');
    await flushPromises();

    expect(wrapper?.text()).toContain('Redis Vector Provider 未配置');
    expect(wrapper?.text()).toContain('Embedding 模型未配置');
    expect(wrapper?.findAll('.tag-stub').filter(item => item.text() === '不可用')).toHaveLength(2);
    expect(message.success).not.toHaveBeenCalled();
  });

  it('verifies the maintained PostgreSQL index and displays the server result', async () => {
    await mountPage();

    await button('校验索引').trigger('click');
    await flushPromises();

    expect(api.verifyIndex).toHaveBeenCalledOnce();
    expect(wrapper?.text()).toContain('PostgreSQL 全文索引校验完成');
    expect(message.success).toHaveBeenCalledWith('PostgreSQL 全文索引校验完成');
  });
});
