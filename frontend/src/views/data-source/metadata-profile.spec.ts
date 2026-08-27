import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MetadataProfile from './metadata-profile.vue';

const api = vi.hoisted(() => ({
  applyPreview: vi.fn(),
  cancelJob: vi.fn(),
  createJob: vi.fn(),
  createPreview: vi.fn(),
  fetchJob: vi.fn(),
  fetchJobs: vi.fn(),
  fetchProfile: vi.fn(),
  fetchProfiles: vi.fn(),
  fetchRelated: vi.fn(),
  fetchPreview: vi.fn(),
  fetchStats: vi.fn(),
  resumeJob: vi.fn(),
  updateIgnore: vi.fn()
}));

const dialog = vi.hoisted(() => ({ warning: vi.fn() }));

vi.mock('naive-ui', async importOriginal => ({
  ...(await importOriginal<typeof import('naive-ui')>()),
  useDialog: () => dialog
}));

vi.mock('@/service/api', () => ({
  applyMetadataSmartImportPreview: api.applyPreview,
  cancelMetadataProfileJob: api.cancelJob,
  createMetadataProfileJob: api.createJob,
  createMetadataSmartImportPreview: api.createPreview,
  fetchMetadataProfileJob: api.fetchJob,
  fetchMetadataProfileJobs: api.fetchJobs,
  fetchMetadataRelatedTables: api.fetchRelated,
  fetchMetadataSmartImportPreview: api.fetchPreview,
  fetchMetadataTableProfile: api.fetchProfile,
  fetchMetadataTableProfiles: api.fetchProfiles,
  fetchMetadataTableProfileStats: api.fetchStats,
  resumeMetadataProfileJob: api.resumeJob,
  updateMetadataTableProfileIgnore: api.updateIgnore
}));

function success<T>(data: T) {
  return { data, error: null, response: { status: 200 } };
}

function failure(status: number, msg: string) {
  const response = { status, data: { code: status, msg } };
  return { data: null, error: Object.assign(new Error(msg), { response }), response };
}

const runningJob = {
  id: '31', datasetId: '10', dataSourceId: '20', mode: 'incremental', status: 'running',
  totalTables: 4, completedTables: 2, failedTables: 0, progressPercent: '50',
  currentTableId: '101', cancelRequested: false, resumeOfJobId: null,
  attemptNo: 1, maxAttempts: 3, revisionNo: 2, errorMessage: null, requestedBy: '9',
  createdAt: '2026-08-17T10:00:00', startedAt: '2026-08-17T10:00:01', finishedAt: null,
  updatedAt: '2026-08-17T10:01:00'
} as const;

const profile = {
  profileId: '201', datasetId: '10', tableId: '101', jobId: '31', schemaName: 'public',
  tableName: 'orders', displayName: '订单', term: '订单事实', description: '订单业务事实表',
  tableType: 'BASE TABLE', status: 'success', columnCount: 3, sampleRowCount: 3,
  confidenceScore: '91', confidenceReason: '字段与主外键语义完整', tags: ['交易'],
  temporaryClassification: 'business', ignored: false, ignoreDecision: 'auto_include',
  revisionNo: 2, createdAt: '2026-08-17T10:00:00', updatedAt: '2026-08-17T10:01:00'
} as const;

const passthrough = { template: '<div><slot /><slot name="header" /><slot name="footer" /></div>' };
const stubs = {
  SvgIcon: true,
  NAlert: { template: '<div class="alert-stub"><slot /></div>' },
  NButton: { props: ['disabled', 'loading'], emits: ['click'], template: '<button type="button" :disabled="disabled || loading" @click="$emit(\'click\')"><slot name="icon" /><slot /></button>' },
  NCheckbox: { props: ['checked', 'disabled'], emits: ['update:checked'], template: '<label><input type="checkbox" :checked="checked" :disabled="disabled" @change="$emit(\'update:checked\', $event.target.checked)" /><slot /></label>' },
  NCode: { props: ['code'], template: '<pre>{{ code }}</pre>' },
  NCollapse: passthrough,
  NCollapseItem: passthrough,
  NDataTable: { props: ['data', 'loading'], template: '<div class="table-stub"><div v-for="row in data" :key="row.id || row.tableId || row.rowNo">{{ JSON.stringify(row) }}</div></div>' },
  NDescriptions: passthrough,
  NDescriptionsItem: { props: ['label'], template: '<div>{{ label }}<slot /></div>' },
  NEmpty: { props: ['description'], template: '<div>{{ description }}</div>' },
  NInput: { props: ['value'], template: '<input :value="value" />' },
  NModal: { props: ['show'], template: '<div v-if="show"><slot name="header" /><slot /><slot name="footer" /></div>' },
  NPagination: true,
  NProgress: { props: ['percentage'], template: '<div class="progress-stub">{{ percentage }}%</div>' },
  NSelect: true,
  NSpace: passthrough,
  NSpin: { template: '<div><slot /></div>' },
  NTabPane: passthrough,
  NTabs: passthrough,
  NTag: passthrough
};

let wrapper: ReturnType<typeof mount> | null = null;

beforeEach(() => {
  vi.resetAllMocks();
  window.localStorage.clear();
  Object.defineProperty(window, '$message', {
    configurable: true,
    value: { success: vi.fn(), error: vi.fn(), warning: vi.fn() }
  });
  api.fetchJobs.mockResolvedValue(success([runningJob]));
  api.fetchJob.mockResolvedValue(success({
    job: runningJob,
    tables: [{
      id: '41', jobId: '31', tableId: '101', schemaName: 'public', tableName: 'orders',
      status: 'running', sequenceNo: 1, attemptNo: 1, profileId: null, errorMessage: null,
      startedAt: '2026-08-17T10:01:00', finishedAt: null, updatedAt: '2026-08-17T10:01:00'
    }]
  }));
  api.fetchStats.mockResolvedValue(success({
    totalProfiles: 1, tableCount: 1, viewCount: 0, ignoredCount: 0, temporaryCount: 0,
    averageConfidence: '91', lastProfiledAt: '2026-08-17T10:01:00', tags: [{ name: '交易', count: 1 }]
  }));
  api.fetchProfiles.mockResolvedValue(success({ items: [profile], total: 1, page: 1, pageSize: 20, pages: 1 }));
  api.fetchRelated.mockResolvedValue(success([]));
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = null;
});

async function mountPage() {
  wrapper = mount(MetadataProfile, { props: { datasetId: '10' }, global: { stubs } });
  await flushPromises();
  return wrapper;
}

describe('metadata profile console', () => {
  it('restores persisted job detail and displays only server progress', async () => {
    await mountPage();

    expect(api.fetchJobs).toHaveBeenCalledWith('10', 50);
    expect(api.fetchJob).toHaveBeenCalledWith('10', '31');
    expect(wrapper?.text()).toContain('执行中');
    expect(wrapper?.text()).toContain('完成 2 / 4');
    expect(wrapper?.text()).toContain('50%');
    expect(wrapper?.text()).toContain('orders');
  });

  it('renders profile facts and real labels independently from classification', async () => {
    await mountPage();

    expect(wrapper?.text()).toContain('订单业务事实表');
    expect(wrapper?.text()).toContain('交易');
    expect(wrapper?.text()).toContain('平均置信度');
    expect(wrapper?.text()).toContain('91%');
  });

  it('starts a real incremental job and immediately reloads its persisted detail', async () => {
    const queuedJob = { ...runningJob, id: '32', status: 'queued' as const, completedTables: 0, progressPercent: '0' };
    api.fetchJobs.mockResolvedValueOnce(success([]));
    api.createJob.mockResolvedValueOnce(success(queuedJob));
    api.fetchJob.mockResolvedValueOnce(success({ job: queuedJob, tables: [] }));
    await mountPage();
    const start = wrapper?.findAll('button').find(button => button.text().includes('增量画像'));
    await start?.trigger('click');
    await flushPromises();

    expect(api.createJob).toHaveBeenCalledWith('10', { mode: 'incremental' });
    expect(api.fetchJob).toHaveBeenCalledWith('10', '32');
    expect(wrapper?.text()).toContain('排队中');
  });

  it('shows an explicit forbidden state instead of an empty result', async () => {
    api.fetchProfiles.mockResolvedValueOnce(failure(403, 'forbidden'));
    await mountPage();

    expect(wrapper?.text()).toContain('没有查看或维护此数据集画像的权限');
  });

  it('restores a durable smart-import preview by its remembered server id', async () => {
    window.localStorage.setItem('agent:metadata-profile-preview:10', '51');
    api.fetchPreview.mockResolvedValue(success({
      id: '51', datasetId: '10', profileJobId: '31', status: 'draft', datasetRevision: 4,
      revisionNo: 1, expiresAt: '2026-08-18T10:00:00', createdBy: '9',
      createdAt: '2026-08-17T10:00:00', appliedBy: null, appliedAt: null,
      items: [{
        id: '61', itemType: 'table', resourceId: '201', status: 'available', contentHash: 'content-hash',
        appliedResourceId: null, errorMessage: null, relationshipProposal: null,
        tableProposal: {
          profileId: '201', profileRevision: 2, tableId: '101', sourceHash: 'source-hash-1234567890',
          schemaName: 'public', physicalName: 'orders',
          expected: { displayName: '旧订单', description: '旧说明', status: 'active', metadataPresent: true, stateHash: 'state-hash-1234567890' },
          displayName: '订单', description: '订单业务事实表', status: 'active',
          columnUpdates: [{
            columnId: '301',
            expected: { columnId: '301', displayName: null, description: null, sensitive: false, status: 'active', metadataPresent: true, stateHash: 'column-state-hash' },
            displayName: '订单号', description: '业务订单号', sensitive: false, status: 'active'
          }]
        }
      }]
    }));
    await mountPage();
    const restore = wrapper?.findAll('button').find(button => button.text().includes('继续导入预览'));
    await restore?.trigger('click');
    await flushPromises();

    expect(api.fetchPreview).toHaveBeenCalledWith('10', '51');
    expect(wrapper?.text()).toContain('数据集版本：4');
    expect(wrapper?.text()).toContain('旧订单 -> 订单');
    expect(wrapper?.text()).toContain('source-hash-1234');
  });

  it('shows source and target column ids for a relationship proposal', async () => {
    window.localStorage.setItem('agent:metadata-profile-preview:10', '52');
    api.fetchPreview.mockResolvedValue(success({
      id: '52', datasetId: '10', profileJobId: '31', status: 'draft', datasetRevision: 4,
      revisionNo: 1, expiresAt: '2026-08-18T10:00:00', createdBy: '9',
      createdAt: '2026-08-17T10:00:00', appliedBy: null, appliedAt: null,
      items: [{
        id: '62', itemType: 'relationship', resourceId: '401', status: 'available', contentHash: 'relation-hash',
        appliedResourceId: null, errorMessage: null, tableProposal: null,
        relationshipProposal: {
          recommendationId: '401', sourceTableId: '101', sourceColumnId: '301',
          targetTableId: '102', targetColumnId: '302',
          sourceProfileId: '501', sourceProfileRevision: 1, sourceStructureHash: 'source-structure',
          targetProfileId: '502', targetProfileRevision: 1, targetStructureHash: 'target-structure',
          sourceTableStateHash: 'source-table', sourceColumnStateHash: 'source-column',
          targetTableStateHash: 'target-table', targetColumnStateHash: 'target-column', joinType: 'left',
          joinCondition: 'orders.customer_id = customers.id', description: '订单客户关系'
        }
      }]
    }));
    await mountPage();
    const restore = wrapper?.findAll('button').find(button => button.text().includes('继续导入预览'));
    await restore?.trigger('click');
    await flushPromises();

    expect(wrapper?.text()).toContain('表 101 / 字段 301');
    expect(wrapper?.text()).toContain('表 102 / 字段 302');
  });
});
