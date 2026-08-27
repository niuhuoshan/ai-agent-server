import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import TaskCenter from './index.vue';

const api = vi.hoisted(() => ({
  createTask: vi.fn(), createTaskRun: vi.fn(), decideRunAcceptance: vi.fn(), fetchAllowedAgents: vi.fn(),
  fetchDataSources: vi.fn(), fetchDatasets: vi.fn(), fetchKnowledgeBases: vi.fn(), fetchRunAcceptances: vi.fn(),
  fetchExecutionHistory: vi.fn(), fetchRunSteps: vi.fn(), fetchSkills: vi.fn(), fetchSystemUsers: vi.fn(), fetchTask: vi.fn(), fetchTaskArtifacts: vi.fn(),
  fetchTaskAccessRules: vi.fn(), fetchTaskParticipants: vi.fn(), fetchTaskResources: vi.fn(),
  fetchTaskRun: vi.fn(), fetchTaskRunEvents: vi.fn(), fetchTaskRuns: vi.fn(), fetchTasks: vi.fn(), fetchTaskVersion: vi.fn(),
  fetchTaskVersions: vi.fn(), fetchTaskVisibility: vi.fn(), fetchTools: vi.fn(), fetchWorkflowTemplates: vi.fn(),
  cancelTaskRun: vi.fn(), pauseTaskRun: vi.fn(), removeTaskAccessRule: vi.fn(), removeTaskParticipant: vi.fn(),
  resumeTaskRun: vi.fn(), retryTaskRun: vi.fn(), startTaskRun: vi.fn(), streamTaskRunEvents: vi.fn(),
  putTaskAccessRule: vi.fn(), putTaskParticipant: vi.fn(), optimizeTaskInstruction: vi.fn(), updateTask: vi.fn()
}));

vi.mock('@/service/api', () => api);
vi.mock('@/service/request', () => ({ request: vi.fn() }));
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ userInfo: { userId: '1', userName: 'admin' } })
}));

const task = {
  id: '42', taskKey: 'TASK-42', projectId: null, title: '交付任务', objective: '完成一期交付', background: null,
  contextSnapshot: {}, visibility: 'enterprise_shared', category: 'general', orchestrationMode: 'single_agent',
  lifecycleLevel: 'L1_short_task', riskLevel: 'R1', status: 'ready', importance: 0, urgency: 0, ownerId: '1',
  ownerPrincipalType: 'user', startAt: null, currentVersionId: '7', latestRunId: null, acceptanceMode: 'human',
  acceptanceConfig: {}, budget: {}, externalRefs: {}, tags: [], createdAt: '2026-08-17T01:00:00Z'
};
const version = {
  id: '7', taskId: '42', versionNo: 2, title: '交付任务', objective: '完成一期交付', agentVersionId: '11',
  workflowVersionId: null, contextSnapshot: {}, resourceSnapshot: { agentVersionId: '11', resources: [] },
  acceptanceSnapshot: {}, inputSnapshot: {}, contentHash: 'sha256-current-version', createdBy: '1',
  createdAt: '2026-08-17T02:00:00Z'
};
const run = {
  id: '9', taskId: '42', taskVersionId: '7', workflowVersionId: null, traceId: 'trace-9', status: 'succeeded',
  attemptNo: 1, parentRunId: null, startedAt: '2026-08-17T03:00:00Z', finishedAt: '2026-08-17T03:00:02Z',
  waitReason: null, errorCode: null, errorSummary: null, cancelReason: null, createdBy: '1', createdAt: '2026-08-17T03:00:00Z'
};

function success<T>(data: T) {
  return { data, error: null, response: { status: 200 } };
}

function failure(message: string, status: number) {
  const response = { status, data: { code: status, msg: message } };
  return { data: null, error: Object.assign(new Error(message), { response }), response };
}

const passthrough = { template: '<div><slot name="header" /><slot /></div>' };
const stubs: Record<string, any> = {
  SvgIcon: true,
  NButton: { props: ['disabled'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot name="icon" /><slot /></button>' },
  NDataTable: { props: ['data'], template: '<div><div v-for="row in data" :key="row.id">{{ row.title }} {{ row.contentHash }}</div></div>' },
  NDrawer: { props: ['show'], template: '<div v-if="show"><slot /></div>' },
  NDrawerContent: passthrough,
  NModal: { props: ['show', 'title'], template: '<div v-if="show"><h2>{{ title }}</h2><slot /><slot name="footer" /></div>' },
  NAlert: passthrough, NCollapse: passthrough, NCollapseItem: passthrough, NDescriptions: passthrough,
  NDescriptionsItem: passthrough, NEmpty: passthrough,
  NForm: {
    setup(_: any, ctx: any) {
      ctx.expose({ validate: async () => undefined });
      return (_props: any, slots: any) => slots.default?.();
    }
  },
  NFormItem: { template: '<div><slot name="label" /><slot /></div>' },
  NInput: true, NSelect: true, NSpin: passthrough, NSpace: passthrough, NTag: passthrough,
  NButtonGroup: passthrough, NTooltip: passthrough, NDropdown: passthrough, NProgress: true,
  NCheckbox: true, NRadioGroup: true, NRadioButton: true, NDynamicTags: true, NCode: true,
  NTimeline: passthrough, NTimelineItem: passthrough
};

beforeEach(() => {
  vi.resetAllMocks();
  window.localStorage.setItem('agent-task-center:view-mode', 'kanban');
  api.fetchTasks.mockResolvedValue(success([task]));
  api.fetchWorkflowTemplates.mockResolvedValue(success([]));
  api.fetchAllowedAgents.mockResolvedValue(success([]));
  api.fetchSystemUsers.mockResolvedValue(success({ rows: [], total: 0 }));
  api.fetchTools.mockResolvedValue(success([]));
  api.fetchSkills.mockResolvedValue(success([]));
  api.fetchKnowledgeBases.mockResolvedValue(success([]));
  api.fetchDataSources.mockResolvedValue(success([]));
  api.fetchDatasets.mockResolvedValue(success([]));
  api.fetchExecutionHistory.mockResolvedValue(success({ items: [], total: 0, page: 1, page_size: 20 }));
  api.fetchTask.mockResolvedValue(success(task));
  api.fetchTaskRun.mockResolvedValue(success(run));
  api.fetchTaskRuns.mockResolvedValue(success([]));
  api.fetchTaskResources.mockResolvedValue(success([]));
  api.fetchTaskParticipants.mockResolvedValue(success([]));
  api.fetchTaskAccessRules.mockResolvedValue(success([]));
  api.fetchTaskVisibility.mockResolvedValue(success({ taskId: '42', visibility: 'enterprise_shared', ownerId: '1', participants: [], accessRules: [] }));
  api.fetchTaskVersions.mockResolvedValue(success([version]));
  api.fetchTaskVersion.mockResolvedValue(success(version));
  api.optimizeTaskInstruction.mockResolvedValue(success({
    status: 'succeeded', model_id: 'model-1', model_name: '测试模型', provider: 'local',
    provider_model: 'test-model', elapsed_ms: 12, optimized_content: '优化后的任务目标'
  }));
});

describe('task definition version flow', () => {
  it('opens a shared task and renders its immutable version hash', async () => {
    const wrapper = mount(TaskCenter, { global: { stubs, mocks: { $t: (key: string) => key } } });
    await flushPromises();
    await wrapper.get('.task-card').trigger('click');
    await flushPromises();

    expect(api.fetchTaskVersions).toHaveBeenCalledWith('42');
    expect(wrapper.text()).toContain('sha256-current-version');
    expect(wrapper.text()).toContain('任务版本历史');
    wrapper.unmount();
  });

  it('loads the current snapshot before opening the edit wizard', async () => {
    const wrapper = mount(TaskCenter, { global: { stubs, mocks: { $t: (key: string) => key } } });
    await flushPromises();
    await wrapper.get('.task-card').trigger('click');
    await flushPromises();
    const edit = wrapper.findAll('button').find(button => button.text().includes('编辑定义'));
    expect(edit).toBeTruthy();
    await edit!.trigger('click');
    await flushPromises();

    expect(api.fetchTaskVersion).toHaveBeenCalledWith('42', '7');
    expect(wrapper.text()).toContain('编辑任务定义');
    wrapper.unmount();
  });

  it('keeps the editor open and reports a revision conflict instead of claiming success', async () => {
    const message = { error: vi.fn(), success: vi.fn(), warning: vi.fn() };
    Object.defineProperty(window, '$message', { configurable: true, value: message });
    api.updateTask.mockResolvedValue(failure('任务定义已被并发修改', 409));
    const wrapper = mount(TaskCenter, { global: { stubs, mocks: { $t: (key: string) => key } } });
    await flushPromises();
    await wrapper.get('.task-card').trigger('click');
    await flushPromises();
    await wrapper.findAll('button').find(button => button.text().includes('编辑定义'))!.trigger('click');
    await flushPromises();
    await wrapper.findAll('button').find(button => button.text().includes('保存并生成新版本'))!.trigger('click');
    await flushPromises();

    expect(api.updateTask).toHaveBeenCalledWith('42', expect.objectContaining({ title: '交付任务' }));
    expect(message.error).toHaveBeenCalledWith('任务定义已被其他人修改，请刷新后重试');
    expect(wrapper.text()).toContain('编辑任务定义');
    wrapper.unmount();
  });

  it('previews task instruction optimization and only changes the draft after explicit apply', async () => {
    const wrapper = mount(TaskCenter, { global: { stubs, mocks: { $t: (key: string) => key } } });
    await flushPromises();
    await wrapper.get('.task-card').trigger('click');
    await flushPromises();
    await wrapper.findAll('button').find(button => button.text().includes('编辑定义'))!.trigger('click');
    await flushPromises();

    const optimize = wrapper.findAll('button').find(button => button.text().includes('AI 优化'));
    expect(optimize).toBeTruthy();
    await optimize!.trigger('click');
    await flushPromises();

    expect(api.optimizeTaskInstruction).toHaveBeenCalledWith('完成一期交付');
    expect(wrapper.text()).toContain('当前任务目标');
    expect(wrapper.text()).toContain('完成一期交付');
    expect(wrapper.text()).toContain('优化后的任务目标');

    await wrapper.findAll('button').find(button => button.text().includes('应用到任务目标'))!.trigger('click');
    expect(wrapper.text()).not.toContain('AI 优化任务指令');
    wrapper.unmount();
  });
});
