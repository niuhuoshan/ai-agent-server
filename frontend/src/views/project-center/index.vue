<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import dayjs from 'dayjs';
import type { DataTableColumns, FormInst, FormRules, SelectOption } from 'naive-ui';
import { NButton, NSpace, NTag } from 'naive-ui';
import { useRouter } from 'vue-router';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  createProject,
  fetchAllowedAgents,
  fetchProjectMembers,
  fetchProjects,
  fetchSystemUsers,
  fetchTasks,
  putProjectMember,
  removeProjectMember,
  updateProject,
  updateProjectStatus
} from '@/service/api';
import type {
  AgentOptionView,
  ProjectMemberView,
  ProjectView,
  SaveProjectPayload,
  SystemUserView,
  TaskView
} from '@/service/api';
import { useAuthStore } from '@/store/modules/auth';

const props = withDefaults(defineProps<{
  clientMode?: boolean;
}>(), {
  clientMode: false
});

const authStore = useAuthStore();
const router = useRouter();

const loading = ref(false);
const projectsError = ref('');
const projects = ref<ProjectView[]>([]);
const projectTasksLoading = ref(false);
const projectTasksError = ref('');
const tasks = ref<TaskView[]>([]);
const agents = ref<AgentOptionView[]>([]);
const users = ref<SystemUserView[]>([]);
const search = ref('');
const statusFilter = ref<ProjectView['status'] | null>(null);
const clientViewMode = ref<'grid' | 'list'>('grid');

const formVisible = ref(false);
const formSubmitting = ref(false);
const formRef = ref<FormInst | null>(null);
const editingProject = ref<ProjectView | null>(null);
const projectForm = reactive({
  name: '',
  description: '',
  defaultAgentVersionId: null as string | null,
  workspaceAccess: 'read_write' as 'none' | 'read_only' | 'read_write',
  workspaceIsolation: 'project' as 'project' | 'member',
  allowFileUpload: true,
  allowArtifactDownload: true,
  allowExternalNetwork: false,
  workspaceRetentionDays: 90,
  workspaceStorageMb: 1024,
  notificationEnabled: true,
  notificationEvents: ['task_failed', 'approval_required', 'risk_triggered'] as string[],
  notificationChannels: ['inbox'] as string[],
  dailyDigest: false,
  quietHoursEnabled: false,
  quietHoursStart: '22:00',
  quietHoursEnd: '08:00',
  tags: [] as string[]
});
const projectRules: FormRules = {
  name: [
    { required: true, message: '请输入项目名称', trigger: ['input', 'blur'] },
    { max: 128, message: '项目名称不能超过 128 个字符', trigger: ['input', 'blur'] }
  ]
};

const detailVisible = ref(false);
const activeProject = ref<ProjectView | null>(null);
const members = ref<ProjectMemberView[]>([]);
const memberLoading = ref(false);
const memberError = ref('');
const memberSubmitting = ref(false);
const memberUserId = ref<string | null>(null);
const memberRole = ref<Exclude<ProjectMemberView['role'], 'owner'>>('member');

const statusOptions: SelectOption[] = [
  { label: '使用中', value: 'active' },
  { label: '已暂停', value: 'suspended' },
  { label: '已归档', value: 'archived' }
];
const memberRoleOptions: SelectOption[] = [
  { label: '项目管理员', value: 'manager' },
  { label: '成员', value: 'member' },
  { label: '只读访客', value: 'viewer' }
];
const workspaceAccessOptions: SelectOption[] = [
  { label: '不创建工作区', value: 'none' },
  { label: '只读工作区', value: 'read_only' },
  { label: '可读写工作区', value: 'read_write' }
];
const workspaceIsolationOptions: SelectOption[] = [
  { label: '项目成员共享', value: 'project' },
  { label: '成员独立空间', value: 'member' }
];
const notificationEventOptions = [
  { label: '任务完成', value: 'task_completed' },
  { label: '任务失败', value: 'task_failed' },
  { label: '需要审批', value: 'approval_required' },
  { label: '风险命中', value: 'risk_triggered' },
  { label: '资源或凭证异常', value: 'resource_unavailable' }
];
const notificationChannelOptions = [
  { label: '站内通知', value: 'inbox' },
  { label: '邮件', value: 'email' },
  { label: '企业即时消息', value: 'enterprise_im' }
];
const agentOptions = computed<SelectOption[]>(() =>
  agents.value
    .filter(agent => agent.status === 'active' && agent.publishedVersionId)
    .map(agent => ({ label: `${agent.name} · ${agent.agentKey}`, value: agent.publishedVersionId! }))
);
const userOptions = computed<SelectOption[]>(() =>
  users.value.map(user => ({ label: `${user.nickName || user.userName} · ${user.userName}`, value: user.userId }))
);
const filteredProjects = computed(() => {
  const keyword = search.value.trim().toLocaleLowerCase();
  return projects.value.filter(project => {
    const matchesKeyword =
      !keyword ||
      project.name.toLocaleLowerCase().includes(keyword) ||
      project.projectKey.toLocaleLowerCase().includes(keyword) ||
      project.description?.toLocaleLowerCase().includes(keyword);
    return matchesKeyword && (!statusFilter.value || project.status === statusFilter.value);
  });
});
const clientProjects = computed(() => {
  const statusOrder: Record<ProjectView['status'], number> = { active: 0, suspended: 1, archived: 2 };
  return [...filteredProjects.value].sort((left, right) => {
    const statusDifference = statusOrder[left.status] - statusOrder[right.status];
    if (statusDifference !== 0) return statusDifference;
    return dayjs(right.updatedAt || right.createdAt).valueOf() - dayjs(left.updatedAt || left.createdAt).valueOf();
  });
});
const tasksByProject = computed(() => {
  const grouped = new Map<string, TaskView[]>();
  tasks.value.forEach(task => {
    if (!task.projectId) return;
    const projectTasks = grouped.get(task.projectId) || [];
    projectTasks.push(task);
    grouped.set(task.projectId, projectTasks);
  });
  grouped.forEach(projectTasks => projectTasks.sort((left, right) => dayjs(right.createdAt).valueOf() - dayjs(left.createdAt).valueOf()));
  return grouped;
});
const summary = computed(() => ({
  total: projects.value.length,
  active: projects.value.filter(item => item.status === 'active').length,
  suspended: projects.value.filter(item => item.status === 'suspended').length,
  archived: projects.value.filter(item => item.status === 'archived').length
}));

function formatTime(value: string | null) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-';
}

function statusText(status: ProjectView['status']) {
  return { active: '使用中', suspended: '已暂停', archived: '已归档' }[status];
}

function statusType(status: ProjectView['status']) {
  return ({ active: 'success', suspended: 'warning', archived: 'default' } as const)[status];
}

function taskStatusText(status: string) {
  return ({
    draft: '草稿',
    ready: '就绪',
    scheduled: '已排期',
    queued: '排队中',
    preparing: '准备中',
    running: '运行中',
    waiting: '等待中',
    blocked: '已阻塞',
    rework: '返工中',
    waiting_approval: '等待审批',
    waiting_external: '等待外部执行',
    paused: '已暂停',
    completed: '已完成',
    succeeded: '成功',
    failed: '失败',
    cancelled: '已取消',
    archived: '已归档'
  } as Record<string, string>)[status] || status;
}

function taskStatusType(status: string) {
  if (['completed', 'succeeded'].includes(status)) return 'success';
  if (status === 'failed') return 'error';
  if (['waiting', 'waiting_approval', 'waiting_external', 'paused', 'blocked'].includes(status)) return 'warning';
  if (['running', 'preparing', 'queued'].includes(status)) return 'info';
  return 'default';
}

function memberRoleText(role: ProjectMemberView['role']) {
  return { owner: '负责人', manager: '项目管理员', member: '成员', viewer: '只读访客' }[role];
}

function userName(userId: string) {
  if (props.clientMode && userId === authStore.userInfo.userId) return `${authStore.userInfo.userName}（我）`;
  const user = users.value.find(item => item.userId === userId);
  return user ? `${user.nickName || user.userName} (${user.userName})` : `用户 #${userId}`;
}

function projectInitial(project: ProjectView) {
  return Array.from(project.name.trim())[0]?.toLocaleUpperCase() || '项';
}

function projectAgentName(project: ProjectView) {
  const agent = agents.value.find(item => item.publishedVersionId === project.defaultAgentVersionId);
  return agent?.name || (project.defaultAgentVersionId ? '已配置智能体' : '未设置智能体');
}

function projectWorkspaceText(project: ProjectView) {
  const accessMode = project.workspacePolicy?.accessMode;
  return ({ none: '无文件空间', read_only: '只读空间', read_write: '协作空间' } as Record<string, string>)[String(accessMode)] || '项目空间';
}

function tasksForProject(projectId: string) {
  return tasksByProject.value.get(projectId) || [];
}

function activeTaskCount(projectId: string) {
  return tasksForProject(projectId).filter(task =>
    ['queued', 'preparing', 'running', 'waiting', 'waiting_approval', 'waiting_external', 'blocked', 'rework', 'paused'].includes(task.status)
  ).length;
}

function openProjectTasks(project: ProjectView) {
  detailVisible.value = false;
  void router.push({
    path: props.clientMode ? '/app' : '/task-center',
    query: props.clientMode
      ? { view: 'tasks', projectId: project.id, projectName: project.name }
      : { projectId: project.id, projectName: project.name }
  });
}

function canManageClientProject(project: ProjectView) {
  return !props.clientMode || project.ownerId === authStore.userInfo.userId;
}

function setClientViewMode(mode: 'grid' | 'list') {
  clientViewMode.value = mode;
  window.localStorage.setItem('agent-client-project-view', mode);
}

function errorText(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}

const projectColumns: DataTableColumns<ProjectView> = [
  {
    title: '项目',
    key: 'name',
    minWidth: 280,
    render: row =>
      h('div', { class: 'primary-cell' }, [h('strong', row.name), h('span', `${row.projectKey} · ${row.description || '无说明'}`)])
  },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: row => h(NTag, { size: 'small', type: statusType(row.status) }, () => statusText(row.status))
  },
  { title: '负责人', key: 'ownerId', width: 160, render: row => userName(row.ownerId) },
  {
    title: '默认 Agent',
    key: 'defaultAgentVersionId',
    minWidth: 170,
    render: row => {
      const agent = agents.value.find(item => item.publishedVersionId === row.defaultAgentVersionId);
      return agent?.name || (row.defaultAgentVersionId ? `版本 #${row.defaultAgentVersionId}` : '未设置');
    }
  },
  { title: '标签', key: 'tags', minWidth: 180, render: row => row.tags.join(', ') || '-' },
  { title: '更新时间', key: 'updatedAt', width: 170, render: row => formatTime(row.updatedAt || row.createdAt) },
  {
    title: '操作',
    key: 'actions',
    width: 160,
    fixed: 'right',
    render: row =>
      h(NSpace, { size: 8 }, () => [
        h(NButton, { size: 'small', text: true, type: 'primary', onClick: () => openDetail(row) }, () => '成员'),
        h(NButton, { size: 'small', text: true, disabled: row.status === 'archived', onClick: () => openEdit(row) }, () => '编辑')
      ])
  }
];

const memberColumns: DataTableColumns<ProjectMemberView> = [
  { title: '用户', key: 'userId', minWidth: 220, render: row => userName(row.userId) },
  {
    title: '角色',
    key: 'role',
    width: 130,
    render: row => h(NTag, { size: 'small', bordered: false }, () => memberRoleText(row.role))
  },
  { title: '加入时间', key: 'joinedAt', width: 170, render: row => formatTime(row.joinedAt) },
  {
    title: '操作',
    key: 'actions',
    width: 90,
    render: row =>
      props.clientMode || row.role === 'owner'
        ? '-'
        : h(NButton, { size: 'tiny', text: true, type: 'error', onClick: () => confirmRemoveMember(row) }, () => '移除')
  }
];

function objectValue(value: unknown) {
  return value && !Array.isArray(value) && typeof value === 'object' ? value as Record<string, unknown> : {};
}

function numberValue(value: unknown, fallback: number) {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function booleanValue(value: unknown, fallback: boolean) {
  return typeof value === 'boolean' ? value : fallback;
}

function enumValue<T extends string>(value: unknown, values: readonly T[], fallback: T): T {
  return typeof value === 'string' && values.includes(value as T) ? value as T : fallback;
}

function stringList(value: unknown, fallback: string[]) {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [...fallback];
}

async function loadProjects() {
  loading.value = true;
  projectsError.value = '';
  const { data, error } = await fetchProjects();
  if (error) projectsError.value = errorText(error, '项目加载失败，请稍后重试。');
  else projects.value = data;
  loading.value = false;
}

async function loadProjectTasks() {
  if (!props.clientMode) return;
  projectTasksLoading.value = true;
  projectTasksError.value = '';
  const { data, error } = await fetchTasks(500);
  if (error) {
    projectTasksError.value = errorText(error, '项目任务加载失败，请稍后重试。');
  } else {
    tasks.value = data;
  }
  projectTasksLoading.value = false;
}

async function loadCatalogs() {
  if (props.clientMode) {
    const agentResult = await fetchAllowedAgents();
    if (!agentResult.error) agents.value = agentResult.data;
    return;
  }
  const [agentResult, userResult] = await Promise.all([fetchAllowedAgents(), fetchSystemUsers(undefined, 1, 200)]);
  if (!agentResult.error) agents.value = agentResult.data;
  if (!userResult.error) users.value = userResult.data.rows;
}

function openCreate() {
  editingProject.value = null;
  Object.assign(projectForm, {
    name: '', description: '', defaultAgentVersionId: null,
    workspaceAccess: 'read_write', workspaceIsolation: 'project',
    allowFileUpload: true, allowArtifactDownload: true, allowExternalNetwork: false,
    workspaceRetentionDays: 90, workspaceStorageMb: 1024,
    notificationEnabled: true,
    notificationEvents: ['task_failed', 'approval_required', 'risk_triggered'],
    notificationChannels: ['inbox'], dailyDigest: false,
    quietHoursEnabled: false, quietHoursStart: '22:00', quietHoursEnd: '08:00',
    tags: []
  });
  formVisible.value = true;
}

function openEdit(project: ProjectView) {
  if (!canManageClientProject(project)) {
    window.$message?.warning('只有项目负责人可以编辑项目');
    return;
  }
  editingProject.value = project;
  const workspace = project.workspacePolicy || {};
  const notification = project.notificationPolicy || {};
  const quietHours = objectValue(notification.quietHours);
  Object.assign(projectForm, {
    name: project.name,
    description: project.description || '',
    defaultAgentVersionId: project.defaultAgentVersionId,
    workspaceAccess: enumValue(workspace.accessMode, ['none', 'read_only', 'read_write'] as const, 'read_write'),
    workspaceIsolation: enumValue(workspace.isolationMode, ['project', 'member'] as const, 'project'),
    allowFileUpload: booleanValue(workspace.allowFileUpload, true),
    allowArtifactDownload: booleanValue(workspace.allowArtifactDownload, true),
    allowExternalNetwork: booleanValue(workspace.allowExternalNetwork, false),
    workspaceRetentionDays: numberValue(workspace.retentionDays, 90),
    workspaceStorageMb: numberValue(workspace.maxStorageMb, 1024),
    notificationEnabled: booleanValue(notification.enabled, true),
    notificationEvents: stringList(notification.events, ['task_failed', 'approval_required', 'risk_triggered']),
    notificationChannels: stringList(notification.channels, ['inbox']),
    dailyDigest: booleanValue(notification.dailyDigest, false),
    quietHoursEnabled: booleanValue(quietHours.enabled, false),
    quietHoursStart: typeof quietHours.start === 'string' ? quietHours.start : '22:00',
    quietHoursEnd: typeof quietHours.end === 'string' ? quietHours.end : '08:00',
    tags: [...project.tags]
  });
  formVisible.value = true;
}

async function submitProject() {
  await formRef.value?.validate();
  const existingWorkspace = editingProject.value?.workspacePolicy || {};
  const existingNotification = editingProject.value?.notificationPolicy || {};
  const existingQuietHours = objectValue(existingNotification.quietHours);
  const workspacePolicy = {
    ...existingWorkspace,
    accessMode: projectForm.workspaceAccess,
    isolationMode: projectForm.workspaceIsolation,
    allowFileUpload: projectForm.allowFileUpload,
    allowArtifactDownload: projectForm.allowArtifactDownload,
    allowExternalNetwork: projectForm.allowExternalNetwork,
    retentionDays: numberValue(projectForm.workspaceRetentionDays, 90),
    maxStorageMb: numberValue(projectForm.workspaceStorageMb, 1024)
  };
  const notificationPolicy = {
    ...existingNotification,
    enabled: projectForm.notificationEnabled,
    events: projectForm.notificationEvents,
    channels: projectForm.notificationChannels,
    dailyDigest: projectForm.dailyDigest,
    quietHours: {
      ...existingQuietHours,
      enabled: projectForm.quietHoursEnabled,
      start: projectForm.quietHoursStart,
      end: projectForm.quietHoursEnd
    }
  };
  const payload: SaveProjectPayload = {
    name: projectForm.name.trim(),
    description: projectForm.description.trim() || undefined,
    defaultAgentVersionId: projectForm.defaultAgentVersionId || undefined,
    workspacePolicy,
    notificationPolicy,
    tags: projectForm.tags
  };
  formSubmitting.value = true;
  const result = editingProject.value
    ? await updateProject(editingProject.value.id, payload)
    : await createProject({ ...payload, idempotencyKey: `ui-project:${window.crypto.randomUUID()}` });
  if (!result.error) {
    window.$message?.success(editingProject.value ? '项目已更新' : '项目已创建');
    formVisible.value = false;
    await loadProjects();
  }
  formSubmitting.value = false;
}

async function openDetail(project: ProjectView) {
  activeProject.value = project;
  detailVisible.value = true;
  memberUserId.value = null;
  memberError.value = '';
  await loadMembers();
}

async function loadMembers() {
  if (!activeProject.value) return;
  memberLoading.value = true;
  const { data, error } = await fetchProjectMembers(activeProject.value.id);
  if (error) {
    members.value = [];
    memberError.value = errorText(error, '项目成员加载失败，请稍后重试。');
  } else {
    members.value = data;
    memberError.value = '';
  }
  memberLoading.value = false;
}

async function saveMember() {
  if (!activeProject.value || !memberUserId.value) {
    window.$message?.warning('请选择成员');
    return;
  }
  memberSubmitting.value = true;
  const { error } = await putProjectMember(activeProject.value.id, memberUserId.value, memberRole.value);
  if (!error) {
    window.$message?.success('项目成员已保存');
    memberUserId.value = null;
    await loadMembers();
  }
  memberSubmitting.value = false;
}

function confirmRemoveMember(member: ProjectMemberView) {
  if (!activeProject.value) return;
  window.$dialog?.warning({
    title: '移除项目成员',
    content: `确认将 ${userName(member.userId)} 移出当前项目？`,
    positiveText: '移除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const { error } = await removeProjectMember(activeProject.value!.id, member.userId);
      if (!error) {
        window.$message?.success('成员已移除');
        await loadMembers();
      }
    }
  });
}

async function changeStatus(status: ProjectView['status']) {
  if (!activeProject.value || activeProject.value.status === status) return;
  const execute = async () => {
    const { data, error } = await updateProjectStatus(activeProject.value!.id, status);
    if (!error) {
      activeProject.value = data;
      window.$message?.success(`项目已${statusText(status)}`);
      await loadProjects();
    }
  };
  if (status === 'archived') {
    window.$dialog?.warning({
      title: '归档项目',
      content: '归档后项目不能再修改成员或配置，但历史任务仍保留。',
      positiveText: '确认归档',
      negativeText: '取消',
      onPositiveClick: execute
    });
  } else {
    await execute();
  }
}

onMounted(async () => {
  if (props.clientMode) {
    const storedViewMode = window.localStorage.getItem('agent-client-project-view');
    if (storedViewMode === 'grid' || storedViewMode === 'list') clientViewMode.value = storedViewMode;
  }
  await Promise.all([loadProjects(), loadCatalogs(), loadProjectTasks()]);
});
</script>

<template>
  <div class="project-center-container" :class="{ 'client-mode': props.clientMode }">
    <template v-if="props.clientMode">
      <section class="client-summary" aria-label="项目摘要">
        <div><span>全部</span><strong>{{ summary.total }}</strong></div>
        <div><span>使用中</span><strong>{{ summary.active }}</strong></div>
        <div><span>已暂停</span><strong>{{ summary.suspended }}</strong></div>
        <div><span>已归档</span><strong>{{ summary.archived }}</strong></div>
      </section>

      <div class="client-toolbar">
        <NInput v-model:value="search" clearable placeholder="搜索项目名称、标识或说明" class="client-search">
          <template #prefix><SvgIcon icon="lucide:search" /></template>
        </NInput>
        <NSelect v-model:value="statusFilter" clearable placeholder="全部状态" :options="statusOptions" class="client-filter" />
        <span class="client-result-count">{{ clientProjects.length }} 个项目</span>
        <NButtonGroup class="client-view-switch" aria-label="项目显示方式">
          <NTooltip>
            <template #trigger>
              <NButton
                quaternary
                :type="clientViewMode === 'grid' ? 'primary' : 'default'"
                aria-label="网格视图"
                :aria-pressed="clientViewMode === 'grid'"
                @click="setClientViewMode('grid')"
              >
                <template #icon><SvgIcon icon="lucide:layout-grid" /></template>
              </NButton>
            </template>
            网格视图
          </NTooltip>
          <NTooltip>
            <template #trigger>
              <NButton
                quaternary
                :type="clientViewMode === 'list' ? 'primary' : 'default'"
                aria-label="列表视图"
                :aria-pressed="clientViewMode === 'list'"
                @click="setClientViewMode('list')"
              >
                <template #icon><SvgIcon icon="lucide:list" /></template>
              </NButton>
            </template>
            列表视图
          </NTooltip>
        </NButtonGroup>
        <NTooltip>
          <template #trigger>
            <NButton
              secondary
              circle
              :loading="loading"
              class="client-refresh-button"
              aria-label="刷新项目"
              @click="loadProjects"
            >
              <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
            </NButton>
          </template>
          刷新项目
        </NTooltip>
        <NButton type="primary" class="client-create-button" @click="openCreate">
          <template #icon><SvgIcon icon="lucide:plus" /></template>新建项目
        </NButton>
      </div>

      <NAlert v-if="projectsError && projects.length" type="warning" class="client-inline-error">
        <div class="client-inline-error-content">
          <span>{{ projectsError }}</span>
          <NButton text type="warning" size="small" @click="loadProjects">重试</NButton>
        </div>
      </NAlert>

      <section v-if="projectsError && !projects.length" class="client-error-state" aria-live="polite">
        <NResult status="500" title="项目暂时不可用" :description="projectsError">
          <template #footer><NButton type="primary" :loading="loading" @click="loadProjects">重新加载</NButton></template>
        </NResult>
      </section>

      <section v-else-if="loading && !projects.length" class="client-project-grid" aria-label="正在加载项目">
        <div v-for="index in 6" :key="index" class="client-project-skeleton">
          <NSkeleton text :repeat="1" class="skeleton-title" />
          <NSkeleton text :repeat="2" />
          <NSkeleton text :repeat="1" class="skeleton-footer" />
        </div>
      </section>

      <template v-else-if="clientProjects.length">
        <section v-if="clientViewMode === 'grid'" class="client-project-grid" aria-label="项目网格">
          <article v-for="project in clientProjects" :key="project.id" class="client-project-card">
            <header class="project-card-header">
              <div class="project-card-identity">
                <span class="project-avatar" aria-hidden="true">{{ projectInitial(project) }}</span>
                <div class="project-card-heading">
                  <button type="button" class="project-card-title" @click="openDetail(project)">{{ project.name }}</button>
                  <span class="project-card-key">{{ project.projectKey }}</span>
                </div>
              </div>
              <NTag size="small" :type="statusType(project.status)">{{ statusText(project.status) }}</NTag>
            </header>

            <button type="button" class="project-card-description" @click="openDetail(project)">
              {{ project.description || '暂无项目说明，打开项目查看成员和工作区信息。' }}
            </button>

            <div class="project-card-meta">
              <div>
                <SvgIcon icon="lucide:list-checks" />
                <span>任务</span>
                <strong>
                  {{ projectTasksLoading ? '加载中' : projectTasksError ? '暂不可用' : `${tasksForProject(project.id).length} 个，${activeTaskCount(project.id)} 个进行中` }}
                </strong>
              </div>
              <div>
                <SvgIcon icon="lucide:bot" />
                <span>智能体</span>
                <strong>{{ projectAgentName(project) }}</strong>
              </div>
              <div>
                <SvgIcon icon="lucide:folder-open" />
                <span>空间</span>
                <strong>{{ projectWorkspaceText(project) }}</strong>
              </div>
            </div>

            <div v-if="project.tags.length" class="project-card-tags" aria-label="项目标签">
              <NTag v-for="tag in project.tags.slice(0, 3)" :key="tag" size="small" :bordered="false">{{ tag }}</NTag>
              <span v-if="project.tags.length > 3" class="project-more-tags">+{{ project.tags.length - 3 }}</span>
            </div>

            <footer class="project-card-footer">
              <span class="project-card-updated">更新于 {{ formatTime(project.updatedAt || project.createdAt) }}</span>
              <NSpace :size="4">
                <NTooltip v-if="canManageClientProject(project)">
                  <template #trigger>
                    <NButton quaternary circle size="small" aria-label="编辑项目" @click.stop="openEdit(project)">
                      <template #icon><SvgIcon icon="lucide:settings-2" /></template>
                    </NButton>
                  </template>
                  编辑项目
                </NTooltip>
                <NButton size="small" secondary type="primary" @click="openDetail(project)">查看项目</NButton>
              </NSpace>
            </footer>
          </article>
        </section>

        <section v-else class="client-project-list" aria-label="项目列表">
          <article v-for="project in clientProjects" :key="project.id" class="client-project-row">
            <span class="project-avatar" aria-hidden="true">{{ projectInitial(project) }}</span>
            <div class="project-row-main">
              <div class="project-row-title-line">
                <button type="button" class="project-row-title" @click="openDetail(project)">{{ project.name }}</button>
                <NTag size="small" :type="statusType(project.status)">{{ statusText(project.status) }}</NTag>
              </div>
              <span class="project-card-key">{{ project.projectKey }}</span>
              <p>{{ project.description || '暂无项目说明' }}</p>
            </div>
            <div class="project-row-meta">
              <span>{{ projectTasksError ? '任务暂不可用' : `${tasksForProject(project.id).length} 个任务` }}</span>
              <span>{{ projectTasksError ? '-' : `${activeTaskCount(project.id)} 个进行中` }}</span>
              <span>{{ formatTime(project.updatedAt || project.createdAt) }}</span>
            </div>
            <NSpace :size="4" class="project-row-actions">
              <NTooltip v-if="canManageClientProject(project)">
                <template #trigger>
                  <NButton quaternary circle size="small" aria-label="编辑项目" @click.stop="openEdit(project)">
                    <template #icon><SvgIcon icon="lucide:settings-2" /></template>
                  </NButton>
                </template>
                编辑项目
              </NTooltip>
              <NButton size="small" secondary type="primary" @click="openDetail(project)">查看</NButton>
            </NSpace>
          </article>
        </section>
      </template>

      <NEmpty v-else-if="projects.length" description="没有匹配的项目" class="client-empty-state">
        <template #extra><NButton secondary @click="search = ''; statusFilter = null">清除筛选</NButton></template>
      </NEmpty>
      <NEmpty v-else description="还没有项目" class="client-empty-state">
        <template #extra><NButton type="primary" @click="openCreate"><template #icon><SvgIcon icon="lucide:plus" /></template>新建项目</NButton></template>
      </NEmpty>
    </template>

    <template v-else>
      <header class="page-header">
        <div>
          <h2>项目中心</h2>
          <p>管理项目边界、负责人和显式成员角色。</p>
        </div>
        <NSpace>
          <NButton secondary :loading="loading" @click="loadProjects">
            <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>刷新
          </NButton>
          <NButton type="primary" @click="openCreate">
            <template #icon><SvgIcon icon="lucide:plus" /></template>新建项目
          </NButton>
        </NSpace>
      </header>

      <section class="summary-band" aria-label="项目摘要">
        <div><span>全部项目</span><strong>{{ summary.total }}</strong></div>
        <div><span>使用中</span><strong>{{ summary.active }}</strong></div>
        <div><span>已暂停</span><strong>{{ summary.suspended }}</strong></div>
        <div><span>已归档</span><strong>{{ summary.archived }}</strong></div>
      </section>

      <div class="toolbar">
        <NInput v-model:value="search" clearable placeholder="搜索项目名称、标识或说明" class="search-control">
          <template #prefix><SvgIcon icon="lucide:search" /></template>
        </NInput>
        <NSelect v-model:value="statusFilter" clearable placeholder="项目状态" :options="statusOptions" class="filter-control" />
        <span>{{ filteredProjects.length }} 条结果</span>
      </div>

      <NDataTable
        :columns="projectColumns"
        :data="filteredProjects"
        :loading="loading"
        :row-key="row => row.id"
        :scroll-x="1300"
        :max-height="620"
        :pagination="{ pageSize: 20 }"
        striped
      />
    </template>

    <NModal
      v-model:show="formVisible"
      preset="card"
      :title="editingProject ? '编辑项目' : '新建项目'"
      class="project-modal"
      :style="{ width: 'min(860px, calc(100vw - 32px))' }"
      :mask-closable="!formSubmitting"
    >
      <NForm ref="formRef" :model="projectForm" :rules="projectRules" label-placement="top">
        <NFormItem label="项目名称" path="name">
          <NInput v-model:value="projectForm.name" maxlength="128" show-count />
        </NFormItem>
        <NFormItem label="项目说明">
          <NInput v-model:value="projectForm.description" type="textarea" maxlength="12000" :autosize="{ minRows: 3, maxRows: 6 }" show-count />
        </NFormItem>
        <NFormItem label="默认 Agent">
          <NSelect v-model:value="projectForm.defaultAgentVersionId" clearable filterable :options="agentOptions" placeholder="可选" />
        </NFormItem>
        <section v-if="!props.clientMode" class="policy-section">
          <div class="policy-heading">
            <SvgIcon icon="lucide:folder-cog" />
            <div><h3>工作区</h3><p>设置项目文件空间的访问范围和资源上限。</p></div>
          </div>
          <div class="form-grid">
            <NFormItem label="访问权限"><NSelect v-model:value="projectForm.workspaceAccess" :options="workspaceAccessOptions" /></NFormItem>
            <NFormItem label="空间隔离"><NSelect v-model:value="projectForm.workspaceIsolation" :options="workspaceIsolationOptions" /></NFormItem>
            <NFormItem label="文件保留天数"><NInputNumber v-model:value="projectForm.workspaceRetentionDays" :min="1" :max="3650" /></NFormItem>
            <NFormItem label="存储上限（MB）"><NInputNumber v-model:value="projectForm.workspaceStorageMb" :min="10" :max="102400" /></NFormItem>
          </div>
          <NSpace vertical>
            <NCheckbox v-model:checked="projectForm.allowFileUpload">允许项目成员上传文件</NCheckbox>
            <NCheckbox v-model:checked="projectForm.allowArtifactDownload">允许下载任务产物</NCheckbox>
            <NCheckbox v-model:checked="projectForm.allowExternalNetwork">允许运行时访问外部网络</NCheckbox>
          </NSpace>
        </section>
        <section v-if="!props.clientMode" class="policy-section">
          <div class="policy-heading with-switch">
            <div class="policy-heading-main">
              <SvgIcon icon="lucide:bell-ring" />
              <div><h3>项目通知</h3><p>选择需要通知的事件、渠道和免打扰时段。</p></div>
            </div>
            <NSwitch v-model:value="projectForm.notificationEnabled">
              <template #checked>已启用</template><template #unchecked>已关闭</template>
            </NSwitch>
          </div>
          <template v-if="projectForm.notificationEnabled">
            <NFormItem label="通知事件">
              <NCheckboxGroup v-model:value="projectForm.notificationEvents">
                <NSpace wrap><NCheckbox v-for="option in notificationEventOptions" :key="option.value" :value="option.value" :label="option.label" /></NSpace>
              </NCheckboxGroup>
            </NFormItem>
            <NFormItem label="通知渠道">
              <NCheckboxGroup v-model:value="projectForm.notificationChannels">
                <NSpace wrap><NCheckbox v-for="option in notificationChannelOptions" :key="option.value" :value="option.value" :label="option.label" /></NSpace>
              </NCheckboxGroup>
            </NFormItem>
            <NSpace vertical>
              <NCheckbox v-model:checked="projectForm.dailyDigest">将普通通知合并为每日摘要</NCheckbox>
              <NCheckbox v-model:checked="projectForm.quietHoursEnabled">启用免打扰时段</NCheckbox>
            </NSpace>
            <div v-if="projectForm.quietHoursEnabled" class="quiet-hours">
              <NFormItem label="开始时间"><NTimePicker v-model:formatted-value="projectForm.quietHoursStart" value-format="HH:mm" format="HH:mm" :clearable="false" /></NFormItem>
              <NFormItem label="结束时间"><NTimePicker v-model:formatted-value="projectForm.quietHoursEnd" value-format="HH:mm" format="HH:mm" :clearable="false" /></NFormItem>
            </div>
          </template>
        </section>
        <NFormItem label="标签"><NDynamicTags v-model:value="projectForm.tags" :max="32" /></NFormItem>
      </NForm>
      <template #footer>
        <div class="modal-actions">
          <NButton :disabled="formSubmitting" @click="formVisible = false">取消</NButton>
          <NButton type="primary" :loading="formSubmitting" @click="submitProject">
            <template #icon><SvgIcon icon="lucide:check" /></template>保存
          </NButton>
        </div>
      </template>
    </NModal>

    <NDrawer
      v-model:show="detailVisible"
      width="min(760px, calc(100vw - 16px))"
      placement="right"
    >
      <NDrawerContent v-if="activeProject" closable>
        <template #header>
          <div class="drawer-header">
            <div><strong>{{ activeProject.name }}</strong><span>{{ activeProject.projectKey }}</span></div>
            <NTag :type="statusType(activeProject.status)">{{ statusText(activeProject.status) }}</NTag>
          </div>
        </template>

        <section class="detail-section">
          <p>{{ activeProject.description || '暂无项目说明' }}</p>
          <NDescriptions :column="2" size="small" label-placement="top">
            <NDescriptionsItem label="负责人">{{ userName(activeProject.ownerId) }}</NDescriptionsItem>
            <NDescriptionsItem label="创建时间">{{ formatTime(activeProject.createdAt) }}</NDescriptionsItem>
            <NDescriptionsItem label="默认智能体">{{ projectAgentName(activeProject) }}</NDescriptionsItem>
            <NDescriptionsItem label="工作空间">{{ projectWorkspaceText(activeProject) }}</NDescriptionsItem>
          </NDescriptions>
          <NSpace class="lifecycle-actions">
            <NButton v-if="props.clientMode" size="small" type="primary" @click="openProjectTasks(activeProject)">
              <template #icon><SvgIcon icon="lucide:list-checks" /></template>查看项目任务
            </NButton>
            <template v-if="activeProject.status !== 'archived' && canManageClientProject(activeProject)">
              <NButton v-if="activeProject.status !== 'active'" size="small" type="success" secondary @click="changeStatus('active')">恢复使用</NButton>
              <NButton v-if="activeProject.status !== 'suspended'" size="small" type="warning" secondary @click="changeStatus('suspended')">暂停项目</NButton>
              <NButton size="small" type="error" secondary @click="changeStatus('archived')">归档项目</NButton>
            </template>
          </NSpace>
        </section>

        <section v-if="props.clientMode" class="detail-section">
          <div class="section-heading">
            <div>
              <h3>项目任务</h3>
              <span>{{ tasksForProject(activeProject.id).length }} 个任务，{{ activeTaskCount(activeProject.id) }} 个进行中</span>
            </div>
            <NButton text type="primary" size="small" @click="openProjectTasks(activeProject)">查看全部</NButton>
          </div>
          <NAlert v-if="projectTasksError" type="warning" class="member-error">
            {{ projectTasksError }}
            <NButton text type="warning" size="small" @click="loadProjectTasks">重试</NButton>
          </NAlert>
          <div v-if="projectTasksLoading" class="project-task-skeletons" aria-label="正在加载项目任务">
            <NSkeleton v-for="index in 3" :key="index" text :repeat="1" />
          </div>
          <NEmpty
            v-else-if="!projectTasksError && !tasksForProject(activeProject.id).length"
            description="这个项目还没有任务"
            size="small"
          >
            <template #extra>
              <NButton size="small" type="primary" @click="openProjectTasks(activeProject)">
                <template #icon><SvgIcon icon="lucide:plus" /></template>新建任务
              </NButton>
            </template>
          </NEmpty>
          <div v-else-if="!projectTasksError" class="project-task-list">
            <button
              v-for="task in tasksForProject(activeProject.id).slice(0, 5)"
              :key="task.id"
              type="button"
              class="project-task-row"
              @click="openProjectTasks(activeProject)"
            >
              <span class="project-task-main"><strong>{{ task.title }}</strong><small>{{ task.taskKey }}</small></span>
              <NTag size="small" :type="taskStatusType(task.status)">{{ taskStatusText(task.status) }}</NTag>
              <time :datetime="task.createdAt">{{ formatTime(task.createdAt) }}</time>
            </button>
          </div>
        </section>

        <section class="detail-section">
          <div class="section-heading"><div><h3>项目成员</h3><span>{{ members.length }} 名显式成员</span></div></div>
          <NAlert v-if="memberError" type="error" class="member-error">{{ memberError }} <NButton text type="error" size="small" @click="loadMembers">重试</NButton></NAlert>
          <div v-if="!props.clientMode && activeProject.status !== 'archived'" class="member-toolbar">
            <NSelect v-model:value="memberUserId" filterable clearable :options="userOptions" placeholder="选择系统用户" />
            <NSelect v-model:value="memberRole" :options="memberRoleOptions" />
            <NButton type="primary" :loading="memberSubmitting" @click="saveMember">
              <template #icon><SvgIcon icon="lucide:user-plus" /></template>保存成员
            </NButton>
          </div>
          <NEmpty v-if="!memberLoading && !memberError && !members.length" description="暂无项目成员记录" />
          <NDataTable v-else :columns="memberColumns" :data="members" :loading="memberLoading" :row-key="row => row.id" :scroll-x="650" :max-height="480" size="small" />
        </section>
      </NDrawerContent>
    </NDrawer>
  </div>
</template>

<style scoped lang="scss">
.project-center-container { min-height: 100%; padding: 18px; }
.project-center-container.client-mode { padding: 0 28px 28px; }
.client-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); margin: 16px 0; border-block: 1px solid var(--n-border-color); }
.client-summary > div { display: flex; min-width: 0; align-items: baseline; justify-content: space-between; gap: 12px; padding: 12px 16px; border-right: 1px solid var(--n-border-color); }
.client-summary > div:last-child { border-right: 0; }
.client-summary span { color: var(--n-text-color-3); font-size: 12px; }
.client-summary strong { font-size: 20px; font-variant-numeric: tabular-nums; }
.client-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; }
.client-search { width: min(430px, 42vw); }
.client-filter { width: 150px; }
.client-result-count { margin-left: auto; color: var(--n-text-color-3); font-size: 12px; white-space: nowrap; }
.client-view-switch { flex: 0 0 auto; }
.client-inline-error { margin-bottom: 14px; }
.client-inline-error-content { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.client-error-state, .client-empty-state { padding: 54px 18px; }
.client-project-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
.client-project-card { display: flex; min-width: 0; min-height: 245px; padding: 16px; border: 1px solid var(--n-border-color); border-radius: 8px; background: var(--n-color); flex-direction: column; transition: border-color 140ms ease, box-shadow 140ms ease, transform 140ms ease; }
.client-project-card:hover { border-color: var(--n-primary-color); box-shadow: 0 6px 18px color-mix(in srgb, var(--n-text-color) 8%, transparent); transform: translateY(-1px); }
.project-card-header { display: flex; min-width: 0; align-items: flex-start; justify-content: space-between; gap: 12px; }
.project-card-identity { display: flex; min-width: 0; align-items: center; gap: 10px; }
.project-avatar { display: inline-flex; width: 34px; height: 34px; flex: 0 0 34px; align-items: center; justify-content: center; border: 1px solid color-mix(in srgb, var(--n-primary-color) 30%, var(--n-border-color)); border-radius: 8px; background: color-mix(in srgb, var(--n-primary-color) 9%, var(--n-color)); color: var(--n-primary-color); font-size: 15px; font-weight: 700; }
.project-card-heading { display: flex; min-width: 0; flex-direction: column; gap: 2px; }
.project-card-title, .project-row-title { overflow: hidden; padding: 0; border: 0; background: transparent; color: var(--n-text-color); cursor: pointer; font: inherit; font-weight: 650; text-align: left; text-overflow: ellipsis; white-space: nowrap; }
.project-card-title:hover, .project-row-title:hover { color: var(--n-primary-color); }
.project-card-key { overflow: hidden; color: var(--n-text-color-3); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.project-card-description { display: -webkit-box; min-height: 42px; margin: 18px 0 16px; overflow: hidden; padding: 0; border: 0; background: transparent; color: var(--n-text-color-2); cursor: pointer; font: inherit; font-size: 13px; line-height: 1.6; text-align: left; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.project-card-description:hover { color: var(--n-primary-color); }
.project-card-meta { display: grid; gap: 8px; margin-bottom: 12px; }
.project-card-meta > div { display: grid; grid-template-columns: 16px 42px minmax(0, 1fr); align-items: center; gap: 6px; min-width: 0; color: var(--n-text-color-3); font-size: 12px; }
.project-card-meta :deep(.svg-icon) { color: var(--n-text-color-3); font-size: 14px; }
.project-card-meta strong { overflow: hidden; color: var(--n-text-color-2); font-weight: 500; text-overflow: ellipsis; white-space: nowrap; }
.project-card-tags { display: flex; align-items: center; gap: 6px; min-height: 24px; overflow: hidden; }
.project-more-tags { color: var(--n-text-color-3); font-size: 11px; }
.project-card-footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: auto; padding-top: 14px; border-top: 1px solid var(--n-divider-color); }
.project-card-updated { overflow: hidden; color: var(--n-text-color-3); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.client-project-list { display: flex; flex-direction: column; border-top: 1px solid var(--n-border-color); }
.client-project-row { display: grid; grid-template-columns: 34px minmax(220px, 1.7fr) minmax(260px, 1fr) auto; align-items: center; gap: 14px; min-height: 82px; padding: 12px 4px; border-bottom: 1px solid var(--n-border-color); }
.client-project-row:hover { background: color-mix(in srgb, var(--n-primary-color) 3%, transparent); }
.project-row-main { min-width: 0; }
.project-row-title-line { display: flex; min-width: 0; align-items: center; gap: 8px; }
.project-row-title { max-width: 100%; }
.project-row-main p { display: -webkit-box; margin: 5px 0 0; overflow: hidden; color: var(--n-text-color-3); font-size: 12px; line-height: 1.45; text-overflow: ellipsis; -webkit-box-orient: vertical; -webkit-line-clamp: 1; }
.project-row-meta { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; min-width: 0; color: var(--n-text-color-3); font-size: 12px; }
.project-row-meta span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.project-row-actions { justify-content: flex-end; }
.client-project-skeleton { min-height: 245px; padding: 18px; border: 1px solid var(--n-border-color); border-radius: 8px; }
.client-project-skeleton .skeleton-title { margin-bottom: 24px; }
.client-project-skeleton .skeleton-footer { margin-top: 80px; }
.member-error { margin-bottom: 12px; }
.project-task-skeletons { display: grid; gap: 12px; padding: 6px 0; }
.project-task-list { border-top: 1px solid var(--n-border-color); }
.project-task-row { display: grid; width: 100%; min-height: 58px; grid-template-columns: minmax(0, 1fr) auto 132px; align-items: center; gap: 12px; padding: 10px 2px; border: 0; border-bottom: 1px solid var(--n-border-color); background: transparent; color: var(--n-text-color); cursor: pointer; font: inherit; text-align: left; }
.project-task-row:hover { color: var(--n-primary-color); }
.project-task-row:focus-visible { outline: 2px solid var(--n-primary-color); outline-offset: -2px; }
.project-task-main { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
.project-task-main strong, .project-task-main small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.project-task-main strong { font-size: 13px; font-weight: 600; }
.project-task-main small, .project-task-row time { color: var(--n-text-color-3); font-size: 11px; }
.project-task-row time { text-align: right; white-space: nowrap; }
.page-header, .toolbar, .modal-actions, .drawer-header, .section-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.page-header { padding-bottom: 16px; border-bottom: 1px solid var(--n-border-color); }
.page-header h2 { margin: 0; font-size: 20px; font-weight: 650; letter-spacing: 0; }
.page-header p { margin: 5px 0 0; color: var(--n-text-color-3); font-size: 13px; }
.summary-band { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); margin: 16px 0; border-block: 1px solid var(--n-border-color); }
.summary-band > div { display: flex; justify-content: space-between; align-items: baseline; padding: 13px 18px; border-right: 1px solid var(--n-border-color); }
.summary-band > div:last-child { border-right: 0; }
.summary-band span, .toolbar > span, .drawer-header span, .section-heading span { color: var(--n-text-color-3); font-size: 12px; }
.summary-band strong { font-size: 22px; font-variant-numeric: tabular-nums; }
.toolbar { justify-content: flex-start; margin-bottom: 14px; }
.toolbar > span { margin-left: auto; }
.search-control { width: min(420px, 42vw); }
.filter-control { width: 160px; }
:deep(.primary-cell) { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
:deep(.primary-cell strong), :deep(.primary-cell span) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
:deep(.primary-cell span) { color: var(--n-text-color-3); font-size: 12px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.policy-section { margin-bottom: 18px; padding: 16px 0; border-block: 1px solid var(--n-border-color); }
.policy-section + .policy-section { margin-top: -19px; border-top: 0; }
.policy-heading, .policy-heading-main { display: flex; align-items: flex-start; gap: 10px; }
.policy-heading { margin-bottom: 16px; }
.policy-heading.with-switch { justify-content: space-between; }
.policy-heading h3 { margin: 0 0 3px; font-size: 15px; }
.policy-heading p { margin: 0; color: var(--n-text-color-3); font-size: 12px; }
.policy-heading :deep(.svg-icon) { margin-top: 1px; font-size: 19px; color: var(--n-primary-color); }
.quiet-hours { display: grid; grid-template-columns: repeat(2, minmax(0, 180px)); gap: 16px; margin-top: 12px; }
.drawer-header { width: 100%; }
.drawer-header > div { display: flex; min-width: 0; flex-direction: column; }
.detail-section { padding: 18px 0; border-bottom: 1px solid var(--n-border-color); }
.detail-section:first-child { padding-top: 0; }
.detail-section p { margin: 0 0 14px; color: var(--n-text-color-2); white-space: pre-wrap; }
.detail-section h3 { margin: 0; font-size: 15px; letter-spacing: 0; }
.section-heading { margin-bottom: 12px; }
.section-heading > div { display: flex; flex-direction: column; }
.lifecycle-actions { margin-top: 16px; }
.member-toolbar { display: grid; grid-template-columns: minmax(230px, 1fr) 150px auto; gap: 10px; margin-bottom: 12px; }
@media (width <= 1240px) and (width > 720px) {
  .project-center-container.client-mode { padding-inline: 20px; }
  .client-toolbar { display: grid; grid-template-columns: minmax(220px, 1fr) 150px auto; }
  .client-search { width: 100%; }
  .client-view-switch { justify-self: start; }
  .client-refresh-button,
  .client-create-button { justify-self: end; }
}
@media (width <= 1040px) and (width > 720px) {
  .client-project-row { grid-template-columns: 34px minmax(0, 1fr) auto; align-items: start; }
  .project-row-meta { grid-column: 2 / -1; grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .project-row-actions { grid-column: 3; grid-row: 1; }
}
@media (max-width: 720px) {
  .project-center-container { padding: 12px; }
  .project-center-container.client-mode { padding: 0 12px 16px; }
  .client-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .client-summary > div:nth-child(2) { border-right: 0; }
  .client-summary > div:nth-child(-n + 2) { border-bottom: 1px solid var(--n-border-color); }
  .client-toolbar { align-items: stretch; flex-wrap: wrap; }
  .client-project-grid { grid-template-columns: minmax(0, 1fr); }
  .client-search, .client-filter { width: 100%; }
  .client-result-count { margin-left: 0; }
  .client-view-switch { margin-left: auto; }
  .client-project-row { grid-template-columns: 34px minmax(0, 1fr) auto; align-items: start; }
  .project-row-meta { grid-column: 2 / -1; grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .project-row-actions { grid-column: 3; grid-row: 1; }
  .project-task-row { grid-template-columns: minmax(0, 1fr) auto; }
  .project-task-row time { display: none; }
  .page-header { align-items: flex-start; flex-direction: column; }
  .summary-band { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .summary-band > div:nth-child(2) { border-right: 0; }
  .summary-band > div:nth-child(-n + 2) { border-bottom: 1px solid var(--n-border-color); }
  .toolbar { align-items: stretch; flex-wrap: wrap; }
  .search-control, .filter-control { width: 100%; }
  .toolbar > span { width: 100%; margin-left: 0; }
  .form-grid, .member-toolbar, .quiet-hours { grid-template-columns: 1fr; }
}
</style>
