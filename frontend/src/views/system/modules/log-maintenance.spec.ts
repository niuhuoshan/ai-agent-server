import { flushPromises, mount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import LogMaintenance from './log-maintenance.vue';

const api = vi.hoisted(() => ({
  executeCleanup: vi.fn(),
  fetchConfig: vi.fn(),
  fetchRuns: vi.fn(),
  fetchStorage: vi.fn(),
  previewCleanup: vi.fn(),
  updateConfig: vi.fn()
}));

vi.mock('@/service/api', () => ({
  executeLogCleanup: api.executeCleanup,
  fetchLogMaintenanceRuns: api.fetchRuns,
  fetchLogPartitionStatus: api.fetchStorage,
  fetchLogRetentionConfig: api.fetchConfig,
  previewLogCleanup: api.previewCleanup,
  updateLogRetentionConfig: api.updateConfig
}));

const config = {
  retentionDays: 90,
  minRetentionDays: 1,
  maxRetentionDays: 3650,
  revisionNo: 4,
  updatedBy: '1',
  updatedAt: '2026-08-17T01:00:00Z',
  changeReason: '初始策略',
  automaticSchedule: '02:00 Asia/Shanghai'
};

const storage = {
  databaseType: 'PostgreSQL 17',
  checkedAt: '2026-08-17T02:00:00Z',
  retentionDays: 90,
  cutoffAt: '2026-05-19T02:00:00Z',
  futureMonthsPrepared: 2,
  batchSize: 1000,
  maxRowsPerTablePerRun: 50000,
  tables: [
    {
      tableName: 'agent_audit_event',
      displayName: '审计日志',
      storageMode: 'regular',
      partitionKey: null,
      estimatedRows: 60000,
      sizeBytes: 1048576,
      oldestAt: '2026-01-01T00:00:00Z',
      newestAt: '2026-08-17T00:00:00Z',
      expiredRows: 12545,
      partitions: [
        {
          partitionName: 'agent_audit_event',
          boundExpression: '普通表',
          defaultPartition: false,
          estimatedRows: 60000,
          sizeBytes: 1048576,
          oldestAt: '2026-01-01T00:00:00Z',
          newestAt: '2026-08-17T00:00:00Z',
          expiredRows: 12545,
          removableCandidate: false
        }
      ]
    }
  ]
};

const runs = [
  {
    runId: 'run-previous',
    triggerType: 'manual',
    status: 'succeeded',
    retentionDays: 90,
    policyRevision: 4,
    cutoffAt: '2026-05-19T02:00:00Z',
    requestedBy: '1',
    confirmationExpiresAt: null,
    startedAt: '2026-08-16T02:00:00Z',
    finishedAt: '2026-08-16T02:01:00Z',
    summary: { deletedRows: 100, droppedRows: 0 },
    errorCode: null,
    errorMessage: null,
    createdAt: '2026-08-16T02:00:00Z'
  }
];

const preview = {
  runId: 'run-preview',
  confirmationToken: 'opaque-preview-token',
  confirmationExpiresAt: '2026-08-17T02:10:00Z',
  retentionDays: 90,
  policyRevision: 4,
  cutoffAt: '2026-05-19T02:00:00Z',
  expiredRows: 12545,
  removablePartitions: 0,
  maxRowsPerTablePerRun: 50000,
  mayRequireMultipleRuns: true,
  tables: storage.tables,
  warnings: ['普通表将使用微批删除']
};

const partialResult = {
  runId: 'run-preview',
  status: 'partial',
  triggerType: 'manual',
  retentionDays: 90,
  cutoffAt: '2026-05-19T02:00:00Z',
  createdPartitions: [],
  droppedPartitions: [],
  droppedRows: 200,
  deletedRows: 12345,
  remainingExpiredRows: true,
  startedAt: '2026-08-17T02:01:00Z',
  finishedAt: '2026-08-17T02:02:00Z',
  message: '本轮安全上限已用尽，仍有过期日志',
  tables: [
    {
      tableName: 'agent_audit_event',
      droppedPartitions: [],
      droppedRows: 200,
      deletedRows: 12345,
      remainingExpiredRows: true
    }
  ]
};

const message = {
  error: vi.fn(),
  success: vi.fn(),
  warning: vi.fn()
};

let wrapper: VueWrapper | null = null;

function success<T>(data: T) {
  return { data, error: null, response: { status: 200 } };
}

function failure(backendMessage: string, status = 403) {
  const response = { status, data: { code: status, message: backendMessage } };
  const error = Object.assign(new Error('request failed'), { response });
  return { data: null, error, response };
}

const passthrough = { template: '<div><slot /></div>' };
const stubs = {
  SvgIcon: true,
  NAlert: {
    props: ['type'],
    template: '<div class="alert-stub" :data-alert-type="type"><slot /></div>'
  },
  NButton: {
    props: ['disabled', 'loading', 'type'],
    emits: ['click'],
    template:
      '<button type="button" :disabled="disabled" :data-loading="loading" :data-kind="type" @click="$emit(\'click\')"><slot name="icon" /><slot /></button>'
  },
  NCheckbox: {
    props: ['checked', 'disabled'],
    emits: ['update:checked'],
    template:
      '<label><input type="checkbox" :checked="checked" :disabled="disabled" @change="$emit(\'update:checked\', $event.target.checked)" /><slot /></label>'
  },
  NDataTable: {
    props: ['data'],
    template:
      '<div class="data-table-stub"><span v-for="row in data" :key="row.runId || row.partitionName">{{ row.runId || row.displayName || row.partitionName }} {{ row.status || \'\' }}</span></div>'
  },
  NDescriptions: passthrough,
  NDescriptionsItem: {
    props: ['label'],
    template: '<div><span>{{ label }}</span><slot /></div>'
  },
  NEmpty: {
    props: ['description'],
    template: '<div>{{ description }}</div>'
  },
  NFormItemGi: {
    props: ['label'],
    template: '<label><span>{{ label }}</span><slot /></label>'
  },
  NGrid: passthrough,
  NInput: {
    props: ['value'],
    emits: ['update:value'],
    template:
      '<input data-testid="change-reason" :value="value || \'\'" @input="$emit(\'update:value\', $event.target.value)" />'
  },
  NInputNumber: {
    props: ['value'],
    emits: ['update:value'],
    template:
      '<input data-testid="retention-days" type="number" :value="value ?? \'\'" @input="$emit(\'update:value\', Number($event.target.value))" />'
  },
  NModal: {
    props: ['show', 'title'],
    template:
      '<div v-if="show" class="modal-stub" :data-title="title"><h2>{{ title }}</h2><slot /><slot name="footer" /></div>'
  },
  NSpace: passthrough,
  NSpin: passthrough,
  NTag: passthrough
};

async function mountPage() {
  wrapper = mount(LogMaintenance, {
    attachTo: document.body,
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
  api.fetchConfig.mockResolvedValue(success(config));
  api.fetchStorage.mockResolvedValue(success(storage));
  api.fetchRuns.mockResolvedValue(success(runs));
  api.previewCleanup.mockResolvedValue(success(preview));
  api.executeCleanup.mockResolvedValue(success(partialResult));
  api.updateConfig.mockResolvedValue(success(config));
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = null;
  document.body.replaceChildren();
});

describe('log maintenance page', () => {
  it.each([
    { route: 'config', mock: api.fetchConfig, error: '无权读取保留策略', survivors: ['PostgreSQL 17', 'run-previous'] },
    { route: 'storage', mock: api.fetchStorage, error: '无权读取存储状态', survivors: ['策略 v4', 'run-previous'] },
    { route: 'runs', mock: api.fetchRuns, error: '无权读取维护记录', survivors: ['策略 v4', 'PostgreSQL 17'] }
  ])('keeps successful sections visible when the $route route returns 403', async ({ mock, error, survivors }) => {
    mock.mockResolvedValueOnce(failure(error));

    const page = await mountPage();

    expect(page.text()).toContain(error);
    survivors.forEach(text => expect(page.text()).toContain(text));
  });

  it('reloads current configuration on an HTTP 409 revision conflict', async () => {
    const refreshed = { ...config, retentionDays: 100, revisionNo: 5, changeReason: '其他管理员已更新' };
    api.fetchConfig.mockResolvedValueOnce(success(config)).mockResolvedValue(success(refreshed));
    api.updateConfig.mockResolvedValue(failure('策略版本冲突', 409));
    const page = await mountPage();

    await page.get('[data-testid="retention-days"]').setValue(120);
    await page.get('[data-testid="change-reason"]').setValue('调整合规留存期');
    await button('保存').trigger('click');
    await flushPromises();

    expect(api.updateConfig).toHaveBeenCalledWith({
      retentionDays: 120,
      expectedRevision: 4,
      changeReason: '调整合规留存期'
    });
    expect(api.fetchConfig).toHaveBeenCalledTimes(2);
    expect(message.error).toHaveBeenCalledWith('策略版本冲突');
    expect(page.text()).toContain('策略 v5');
  });

  it('submits the preview token only after acknowledgement and renders a partial result', async () => {
    const page = await mountPage();

    await button('清理过期日志').trigger('click');
    await flushPromises();
    expect(api.previewCleanup).toHaveBeenCalledOnce();
    expect(button('确认不可逆清理').attributes('disabled')).toBeDefined();

    await page.get('.modal-stub[data-title="确认清理过期日志"] input[type="checkbox"]').setValue(true);
    expect(button('确认不可逆清理').attributes('disabled')).toBeUndefined();
    await button('确认不可逆清理').trigger('click');
    await flushPromises();

    expect(api.executeCleanup).toHaveBeenCalledWith('opaque-preview-token');
    const resultModal = page.get('.modal-stub[data-title="日志维护结果"]');
    expect(resultModal.text()).toContain('部分完成');
    expect(resultModal.text()).toContain('12,345 行');
    expect(resultModal.text()).toContain('本轮安全上限已用尽，仍有过期日志');
    expect(resultModal.get('.alert-stub').attributes('data-alert-type')).toBe('warning');
  });
});
