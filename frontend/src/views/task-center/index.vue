<script setup lang="ts">
import {
  computed,
  h,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
} from "vue";
import dayjs from "dayjs";
import type {
  DataTableColumns,
  DropdownOption,
  FormInst,
  FormRules,
  SelectOption,
} from "naive-ui";
import { NButton, NDropdown, NProgress, NSpace, NTag } from "naive-ui";
import SvgIcon from "@/components/custom/svg-icon.vue";
import { request } from "@/service/request";
import { useAuthStore } from "@/store/modules/auth";
import {
  createTask,
  createTaskRun,
  decideRunAcceptance,
  fetchAllowedAgents,
  fetchDataSources,
  fetchDatasets,
  fetchKnowledgeBases,
  fetchRunAcceptances,
  fetchRunSteps,
  fetchExecutionHistory,
  fetchSkills,
  fetchSystemUsers,
  fetchTaskArtifacts,
  fetchTaskAccessRules,
  fetchTaskParticipants,
  fetchTaskResources,
  fetchTaskVersion,
  fetchTaskVersions,
  fetchTaskRun,
  fetchTaskRunEvents,
  fetchTask,
  fetchTaskVisibility,
  fetchTaskRuns,
  fetchTools,
  fetchTasks,
  fetchWorkflowTemplates,
  cancelTaskRun,
  pauseTaskRun,
  removeTaskAccessRule,
  removeTaskParticipant,
  resumeTaskRun,
  retryTaskRun,
  startTaskRun,
  streamTaskRunEvents,
  putTaskAccessRule,
  putTaskParticipant,
  optimizeTaskInstruction,
  updateTask,
} from "@/service/api";
import { includeSqlToolDatasets, taskResourcePermission } from "./sql-tool-resources";
import type {
  AcceptanceView,
  AgentOptionView,
  ArtifactView,
  CreateTaskPayload,
  DataSourceView,
  DatasetView,
  ExecutionEventView,
  KnowledgeBaseView,
  RunStepView,
  SkillView,
  SystemUserView,
  TaskAccessRuleView,
  TaskParticipantType,
  TaskParticipantView,
  TaskResourceRequest,
  TaskResourceView,
  TaskRunView,
  TaskView,
  TaskVersionView,
  TaskVisibilityView,
  NhsV1ExecutionHistoryItem,
  PortalPromptOptimizeResult,
  ToolView,
  UpdateTaskPayload,
  WorkflowTemplateView,
} from "@/service/api";

type CreateMode = "single_agent" | "multi_agent_template";
type ViewMode = "list" | "kanban" | "quadrant";
type TaskSort = "updated" | "created" | "priority";
type MainViewTab = "tasks" | "history";

const props = withDefaults(defineProps<{
  clientMode?: boolean;
  projectId?: string | null;
  projectName?: string | null;
}>(), {
  clientMode: false,
  projectId: null,
  projectName: null,
});

const emit = defineEmits<{
  clearProjectContext: [];
}>();
const authStore = useAuthStore();

const VIEW_MODE_STORAGE_KEY = props.clientMode
  ? "agent-task-center:client-view-mode"
  : "agent-task-center:view-mode";

function initialViewMode(): ViewMode {
  const stored = window.localStorage.getItem(VIEW_MODE_STORAGE_KEY);
  return stored === "kanban" || stored === "quadrant" || stored === "list"
    ? stored
    : props.clientMode
      ? "kanban"
      : "list";
}

const pageLoading = ref(false);
const tasks = ref<TaskView[]>([]);
const workflows = ref<WorkflowTemplateView[]>([]);
const agents = ref<AgentOptionView[]>([]);
const users = ref<SystemUserView[]>([]);
const search = ref("");
const statusFilter = ref<string | null>(null);
const modeFilter = ref<CreateMode | null>(null);
const sortBy = ref<TaskSort>("updated");
const viewMode = ref<ViewMode>(initialViewMode());
const mainViewTab = ref<MainViewTab>("tasks");
const draggedTaskId = ref<string | null>(null);
const dragOverStatus = ref<string | null>(null);
const statusUpdatingTaskId = ref<string | null>(null);

const historyItems = ref<NhsV1ExecutionHistoryItem[]>([]);
const historyLoading = ref(false);
const historyError = ref("");
const historyPage = ref(1);
const historyTotal = ref(0);
const historyQuery = ref("");
const historyStatus = ref<string | null>(null);
const historyTaskId = ref<string | null>(null);
const historyStartAt = ref("");
const historyEndAt = ref("");
const historyPageSize = 20;
const historyLoaded = ref(false);
let historyRequestGeneration = 0;
let historyFilterTimer: number | null = null;

function userName(userId: string | null | undefined) {
  if (!userId) return '-';
  if (userId === authStore.userInfo.userId) return `${authStore.userInfo.userName || '当前用户'}（我）`;
  const user = users.value.find(item => item.userId === userId);
  return user ? `${user.nickName || user.userName} (@${user.userName})` : '未知成员';
}

const createVisible = ref(false);
const createSubmitting = ref(false);
const editingTaskId = ref<string | null>(null);
const createFormRef = ref<FormInst | null>(null);
const createForm = reactive({
  mode: "multi_agent_template" as CreateMode,
  title: "",
  objective: "",
  background: "",
  workflowVersionId: null as string | null,
  singleAgentVersionId: null as string | null,
  roleAgents: {} as Record<string, string | null>,
  visibility: "enterprise_shared" as TaskView["visibility"],
  category: "general" as TaskView["category"],
  riskLevel: "R1" as TaskView["riskLevel"],
  importance: false,
  urgency: false,
  tags: [] as string[],
  resourceKeys: [] as string[],
});
const createRules: FormRules = {
  title: [
    { required: true, message: "请输入任务名称", trigger: ["input", "blur"] },
    {
      max: 255,
      message: "任务名称不能超过 255 个字符",
      trigger: ["input", "blur"],
    },
  ],
  objective: [
    { required: true, message: "请输入任务目标", trigger: ["input", "blur"] },
    {
      max: 12000,
      message: "任务目标不能超过 12000 个字符",
      trigger: ["input", "blur"],
    },
  ],
  background: [
    {
      max: 12000,
      message: "背景说明不能超过 12000 个字符",
      trigger: ["input", "blur"],
    },
  ],
};

// Task objectives are the user-facing task instruction. Keep the AI suggestion
// in a separate review surface so an optimization can never silently overwrite
// a draft that may have changed while the request was running.
const taskOptimizeVisible = ref(false);
const taskOptimizeLoading = ref(false);
const taskOptimizeError = ref("");
const taskOptimizeOriginal = ref("");
const taskOptimizeResult = ref<PortalPromptOptimizeResult | null>(null);

const detailVisible = ref(false);
const activeTask = ref<TaskView | null>(null);
const taskVersions = ref<TaskVersionView[]>([]);
const versionsLoading = ref(false);
const versionsError = ref('');
const selectedVersion = ref<TaskVersionView | null>(null);
const versionDetailVisible = ref(false);
const runs = ref<TaskRunView[]>([]);
const runLoading = ref(false);
const selectedRun = ref<TaskRunView | null>(null);
const steps = ref<RunStepView[]>([]);
const stepLoading = ref(false);
const activeStep = ref<RunStepView | null>(null);
const runModalVisible = ref(false);
const runInput = ref("");
const runSubmitting = ref(false);
const artifacts = ref<ArtifactView[]>([]);
const acceptances = ref<AcceptanceView[]>([]);
const artifactLoading = ref(false);
const checkedArtifactIds = ref<Array<string | number>>([]);
const acceptanceVisible = ref(false);
const acceptanceSubmitting = ref(false);
const acceptanceResult = ref<AcceptanceView["result"]>("passed");
const acceptanceComment = ref("");
const taskResources = ref<TaskResourceView[]>([]);
const taskParticipants = ref<TaskParticipantView[]>([]);
const taskAccessRules = ref<TaskAccessRuleView[]>([]);
const taskEvents = ref<ExecutionEventView[]>([]);
const taskVisibility = ref<TaskVisibilityView | null>(null);
const governanceLoading = ref(false);
const governanceVisible = ref(false);
const participantUserId = ref<string | null>(null);
const participantType = ref<Exclude<TaskParticipantType, "owner">>("collaborator");
const aclSubjectType = ref<TaskAccessRuleView["subjectType"]>("user");
const aclSubjectId = ref<string | null>(null);
const aclAction = ref<TaskAccessRuleView["action"]>("view");
const aclEffect = ref<TaskAccessRuleView["effect"]>("allow");
const runActionLoading = ref(false);
const eventLoading = ref(false);
const eventStreamConnecting = ref(false);
const eventStreamError = ref<string | null>(null);
const eventStreamController = ref<AbortController | null>(null);
let eventStreamGeneration = 0;
let eventStreamRetryTimer: number | null = null;

const tools = ref<ToolView[]>([]);
const skills = ref<SkillView[]>([]);
const knowledgeBases = ref<KnowledgeBaseView[]>([]);
const dataSources = ref<DataSourceView[]>([]);
const datasets = ref<DatasetView[]>([]);

const publishedAgents = computed(() =>
  agents.value.filter(
    (agent) => agent.status === "active" && Boolean(agent.publishedVersionId),
  ),
);
const taskResourceOptions = computed<SelectOption[]>(() => [
  ...publishedAgents.value.map(agent => ({
    label: `Agent · ${agent.name}`,
    value: `agent_version:${agent.publishedVersionId}`,
  })),
  ...tools.value.filter(item => item.status === 'active' && item.available).map(item => ({
    label: `工具 · ${item.name}`,
    value: `tool:${item.id}`,
  })),
  ...skills.value.filter(item => item.status === 'active' && item.publishedVersionId).map(item => ({
    label: `Skill · ${item.name}`,
    value: `skill:${item.id}`,
  })),
  ...knowledgeBases.value.filter(item => item.providerType === 'postgres_pgvector').map(item => ({
    label: `知识库 · ${item.name}`,
    value: `knowledge_base:${item.id}`,
  })),
  ...dataSources.value.filter(item => item.status === 'active').map(item => ({
    label: `数据源 · ${item.name}`,
    value: `data_source:${item.id}`,
  })),
  ...datasets.value.filter(item => item.status === 'active').map(item => ({
    label: `数据集 · ${item.name}`,
    value: `dataset:${item.id}`,
  })),
]);

const participantTypeOptions: SelectOption[] = [
  { label: '执行人', value: 'assignee' },
  { label: '协作者', value: 'collaborator' },
  { label: '验收人', value: 'acceptor' },
  { label: '关注人', value: 'watcher' },
];
const aclSubjectOptions = computed<SelectOption[]>(() => users.value.map(user => ({
  label: `${user.nickName || user.userName} (@${user.userName})`,
  value: user.userId,
})));
const aclActionOptions: SelectOption[] = [
  { label: '查看', value: 'view' },
  { label: '评论', value: 'comment' },
  { label: '操作', value: 'operate' },
  { label: '管理', value: 'admin' },
];

const agentOptions = computed<SelectOption[]>(() =>
  publishedAgents.value.map((agent) => ({
    label: agent.name,
    value: agent.publishedVersionId!,
    description: `${agent.agentKey} · ${agent.engineType}`,
  })),
);
const workflowOptions = computed<SelectOption[]>(() =>
  workflows.value.map((workflow) => ({
    label: workflow.name,
    value: workflow.versionId,
  })),
);
const selectedWorkflow = computed(() =>
  workflows.value.find(
    (workflow) => workflow.versionId === createForm.workflowVersionId,
  ),
);

const statusOptions: SelectOption[] = [
  { label: "草稿", value: "draft" },
  { label: "就绪", value: "ready" },
  { label: "已排期", value: "scheduled" },
  { label: "运行中", value: "running" },
  { label: "等待中", value: "waiting" },
  { label: "已阻塞", value: "blocked" },
  { label: "返工中", value: "rework" },
  { label: "已完成", value: "completed" },
  { label: "已失败", value: "failed" },
  { label: "已取消", value: "cancelled" },
  { label: "已归档", value: "archived" },
];
const historyStatusOptions: SelectOption[] = [
  { label: "全部状态", value: "" },
  { label: "成功", value: "success" },
  { label: "运行中", value: "running" },
  { label: "排队中", value: "queued" },
  { label: "准备中", value: "preparing" },
  { label: "等待审批", value: "waiting_approval" },
  { label: "等待外部执行", value: "waiting_external" },
  { label: "已暂停", value: "paused" },
  { label: "失败", value: "failed" },
  { label: "已取消", value: "cancelled" },
  { label: "已完成", value: "completed" },
  { label: "已跳过", value: "skipped" },
];
const modeOptions: SelectOption[] = [
  { label: "单智能体", value: "single_agent" },
  { label: "固定多智能体", value: "multi_agent_template" },
];
const sortOptions: SelectOption[] = [
  { label: "最近更新", value: "updated" },
  { label: "创建时间", value: "created" },
  { label: "优先级", value: "priority" },
];
const viewOptions: DropdownOption[] = [
  { label: "列表视图", key: "list" },
  { label: "看板视图", key: "kanban" },
  { label: "四象限视图", key: "quadrant" },
];
const categoryOptions: SelectOption[] = [
  { label: "通用", value: "general" },
  { label: "研发", value: "development" },
  { label: "数据", value: "data" },
  { label: "知识", value: "knowledge" },
  { label: "运维", value: "operations" },
  { label: "文档", value: "document" },
];
const riskOptions: SelectOption[] = [
  { label: "R0 · 只读低风险", value: "R0" },
  { label: "R1 · 常规操作", value: "R1" },
  { label: "R2 · 敏感操作", value: "R2" },
  { label: "R3 · 高风险审批", value: "R3" },
];

const scopedTasks = computed(() =>
  props.projectId ? tasks.value.filter(task => task.projectId === props.projectId) : tasks.value,
);

const showHistoryTab = computed(() => !props.projectId);
const historyTaskOptions = computed<SelectOption[]>(() =>
  [...scopedTasks.value]
    .sort((left, right) => left.title.localeCompare(right.title, "zh-CN"))
    .map(task => ({ label: `${task.title} · #${task.id}`, value: task.id })),
);
const historyPageCount = computed(() =>
  Math.max(1, Math.ceil(historyTotal.value / historyPageSize)),
);

const filteredTasks = computed(() => {
  const keyword = search.value.trim().toLocaleLowerCase();
  const matchingTasks = scopedTasks.value.filter((task) => {
    const matchesKeyword =
      !keyword ||
      task.title.toLocaleLowerCase().includes(keyword) ||
      task.taskKey.toLocaleLowerCase().includes(keyword) ||
      task.objective.toLocaleLowerCase().includes(keyword);
    return (
      matchesKeyword &&
      (!statusFilter.value || task.status === statusFilter.value) &&
      (!modeFilter.value || task.orchestrationMode === modeFilter.value)
    );
  });

  return [...matchingTasks].sort((left, right) => {
    if (sortBy.value === "priority") {
      const leftPriority = Number(Boolean(left.importance)) * 2 + Number(Boolean(left.urgency));
      const rightPriority = Number(Boolean(right.importance)) * 2 + Number(Boolean(right.urgency));
      return rightPriority - leftPriority;
    }
    const leftTime = Date.parse(left.createdAt || "") || 0;
    const rightTime = Date.parse(right.createdAt || "") || 0;
    return sortBy.value === "created" ? rightTime - leftTime : rightTime - leftTime;
  });
});

const kanbanColumns = computed(() => {
  const preferredOrder = [
    "draft",
    "ready",
    "scheduled",
    "running",
    "waiting",
    "blocked",
    "rework",
    "completed",
    "failed",
    "cancelled",
    "archived",
  ];
  const presentStatuses = new Set(
    filteredTasks.value.map((task) => task.status),
  );
  const baselineStatuses = new Set(["draft", "ready", "running", "blocked", "completed"]);
  const managementStatuses = [
    ...preferredOrder.filter((status) => presentStatuses.has(status) || baselineStatuses.has(status)),
    ...[...presentStatuses].filter(status => !preferredOrder.includes(status)),
  ];
  const clientStatuses = ["ready", "running", "blocked"];
  const statuses = props.clientMode ? clientStatuses : managementStatuses;
  return statuses.map((status) => ({
    status,
    label: statusText(status),
    tasks: filteredTasks.value.filter((task) => task.status === status),
  }));
});

const quadrants = computed(() => [
  {
    key: "important-urgent",
    title: "重要且紧急",
    hint: "优先处理",
    icon: "lucide:flame",
    tasks: filteredTasks.value.filter(
      (task) => Boolean(task.importance) && Boolean(task.urgency),
    ),
  },
  {
    key: "important",
    title: "重要不紧急",
    hint: "计划推进",
    icon: "lucide:calendar-clock",
    tasks: filteredTasks.value.filter(
      (task) => Boolean(task.importance) && !task.urgency,
    ),
  },
  {
    key: "urgent",
    title: "紧急不重要",
    hint: "快速处置",
    icon: "lucide:timer",
    tasks: filteredTasks.value.filter(
      (task) => !task.importance && Boolean(task.urgency),
    ),
  },
  {
    key: "normal",
    title: "不重要不紧急",
    hint: "按序安排",
    icon: "lucide:inbox",
    tasks: filteredTasks.value.filter(
      (task) => !task.importance && !task.urgency,
    ),
  },
]);

const taskSummary = computed(() => ({
  total: scopedTasks.value.length,
  active: scopedTasks.value.filter((task) =>
    ["running", "waiting"].includes(task.status),
  ).length,
  multi: scopedTasks.value.filter(
    (task) => task.orchestrationMode === "multi_agent_template",
  ).length,
  failed: scopedTasks.value.filter((task) => task.status === "failed").length,
}));

function formatTime(value: string | null) {
  return value ? dayjs(value).format("YYYY-MM-DD HH:mm:ss") : "-";
}

function toApiDateTime(value: string, endOfDay = false) {
  const raw = value.trim();
  if (!raw) return undefined;
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(raw)) {
    return `${raw}:${endOfDay ? "59" : "00"}`;
  }
  return raw;
}

function historyDuration(item: NhsV1ExecutionHistoryItem) {
  if (!item.started_at || !item.finished_at) return "-";
  const started = Date.parse(item.started_at);
  const finished = Date.parse(item.finished_at);
  if (!Number.isFinite(started) || !Number.isFinite(finished)) return "-";
  const elapsed = Math.max(0, finished - started);
  return elapsed < 1000
    ? `${elapsed} ms`
    : `${(elapsed / 1000).toFixed(elapsed < 10_000 ? 2 : 1)} s`;
}

function icon(name: string) {
  return () => h(SvgIcon, { icon: name, class: "text-16px" });
}

function statusText(status: string) {
  return (
    (
      {
        draft: "草稿",
        ready: "就绪",
        scheduled: "已排期",
        queued: "排队中",
        preparing: "准备中",
        pending: "待执行",
        running: "运行中",
        waiting: "等待中",
        blocked: "已阻塞",
        rework: "返工中",
        waiting_approval: "等待审批",
        waiting_external: "等待外部执行",
        paused: "已暂停",
        success: "成功",
        succeeded: "成功",
        completed: "已完成",
        failed: "失败",
        cancelled: "已取消",
        archived: "已归档",
        skipped: "已跳过",
      } as Record<string, string>
    )[status] || status
  );
}

function statusType(status: string) {
  if (["completed", "succeeded", "success"].includes(status)) return "success";
  if (["failed"].includes(status)) return "error";
  if (
    ["waiting", "waiting_approval", "waiting_external", "paused"].includes(
      status,
    )
  )
    return "warning";
  if (["running", "preparing"].includes(status)) return "info";
  return "default";
}

function riskType(risk: TaskView["riskLevel"]) {
  return ({ R0: "success", R1: "info", R2: "warning", R3: "error" } as const)[
    risk
  ];
}

function modeText(mode: TaskView["orchestrationMode"]) {
  return mode === "multi_agent_template"
    ? "固定多智能体"
    : mode === "single_agent"
      ? "单智能体"
      : mode;
}

function acceptanceText(result: AcceptanceView["result"]) {
  return {
    passed: "验收通过",
    rework: "退回返工",
    rejected: "拒绝交付",
    taken_over: "人工接管",
  }[result];
}

function acceptanceType(result: AcceptanceView["result"]) {
  return (
    {
      passed: "success",
      rework: "warning",
      rejected: "error",
      taken_over: "info",
    } as const
  )[result];
}

function agentName(versionId: string | null) {
  return (
    publishedAgents.value.find(
      (agent) => agent.publishedVersionId === versionId,
    )?.name || `版本 #${versionId}`
  );
}

function runProgress() {
  if (steps.value.length === 0) return 0;
  const completed = steps.value.filter((step) =>
    ["succeeded", "failed", "cancelled", "skipped"].includes(step.status),
  ).length;
  return Math.round((completed / steps.value.length) * 100);
}

const taskColumns: DataTableColumns<TaskView> = [
  {
    title: "任务",
    key: "title",
    minWidth: 280,
    render: (row) =>
      h("div", { class: "primary-cell" }, [
        h("strong", row.title),
        h("span", `${row.taskKey} · ${row.objective}`),
      ]),
  },
  {
    title: "编排方式",
    key: "orchestrationMode",
    width: 140,
    render: (row) =>
      h(
        NTag,
        {
          size: "small",
          bordered: false,
          type:
            row.orchestrationMode === "multi_agent_template"
              ? "info"
              : "default",
        },
        () => modeText(row.orchestrationMode),
      ),
  },
  {
    title: "状态",
    key: "status",
    width: 105,
    render: (row) =>
      h(NTag, { size: "small", type: statusType(row.status) }, () =>
        statusText(row.status),
      ),
  },
  {
    title: "风险",
    key: "riskLevel",
    width: 80,
    render: (row) =>
      h(
        NTag,
        { size: "small", type: riskType(row.riskLevel), bordered: false },
        () => row.riskLevel,
      ),
  },
  {
    title: "优先属性",
    key: "priority",
    width: 120,
    render: (row) => {
      const labels = [
        row.importance ? "重要" : "",
        row.urgency ? "紧急" : "",
      ].filter(Boolean);
      return labels.length ? labels.join(" / ") : "常规";
    },
  },
  {
    title: "负责人",
    key: "ownerId",
    minWidth: 180,
    render: (row) => userName(row.ownerId),
  },
  {
    title: "最近运行",
    key: "latestRunId",
    width: 120,
    render: (row) => (row.latestRunId ? `#${row.latestRunId}` : "-"),
  },
  {
    title: "创建时间",
    key: "createdAt",
    width: 170,
    render: (row) => formatTime(row.createdAt),
  },
  {
    title: "操作",
    key: "actions",
    width: 92,
    fixed: "right",
    render: (row) =>
      h(
        NButton,
        {
          size: "small",
          secondary: true,
          renderIcon: icon("lucide:panel-right-open"),
          onClick: () => openTask(row),
        },
        () => "详情",
      ),
  },
];

const runColumns: DataTableColumns<TaskRunView> = [
  {
    title: "尝试",
    key: "attemptNo",
    width: 72,
    render: (row) => `#${row.attemptNo}`,
  },
  {
    title: "状态",
    key: "status",
    width: 112,
    render: (row) =>
      h(NTag, { size: "small", type: statusType(row.status) }, () =>
        statusText(row.status),
      ),
  },
  {
    title: "编排",
    key: "workflowVersionId",
    minWidth: 140,
    render: (row) =>
      row.workflowVersionId
        ? `工作流版本 #${row.workflowVersionId}`
        : "单智能体",
  },
  {
    title: "开始时间",
    key: "startedAt",
    width: 170,
    render: (row) => formatTime(row.startedAt || row.createdAt),
  },
  {
    title: "等待 / 错误",
    key: "waitReason",
    minWidth: 190,
    ellipsis: { tooltip: true },
    render: (row) => row.waitReason || row.errorSummary || "-",
  },
  {
    title: "操作",
    key: "actions",
    width: 90,
    render: (row) =>
      h(
        NButton,
        {
          size: "small",
          text: true,
          type: "primary",
          onClick: () => selectRun(row),
        },
        () => (selectedRun.value?.id === row.id ? "当前" : "查看"),
      ),
  },
];

const historyColumns: DataTableColumns<NhsV1ExecutionHistoryItem> = [
  {
    title: "任务",
    key: "task_name",
    minWidth: 220,
    render: row => h("div", { class: "primary-cell" }, [
      h("strong", row.task_name || "已删除任务"),
      h("span", row.task_id == null ? "未关联任务" : `任务 #${row.task_id}`),
    ]),
  },
  {
    title: "状态",
    key: "status",
    width: 116,
    render: row => h(NTag, { size: "small", type: statusType(row.status) }, () => statusText(row.status)),
  },
  {
    title: "发起人",
    key: "user_id",
    width: 120,
    render: row => row.creator_name || row.username || (row.user_id == null ? "-" : `用户 #${row.user_id}`),
  },
  {
    title: "Trace",
    key: "trace_id",
    minWidth: 180,
    ellipsis: { tooltip: true },
    render: row => row.trace_id || "-",
  },
  {
    title: "执行时间",
    key: "created_at",
    width: 178,
    render: row => formatTime(row.created_at),
  },
  {
    title: "耗时",
    key: "duration",
    width: 100,
    render: row => historyDuration(row),
  },
  {
    title: "错误 / 等待原因",
    key: "error",
    minWidth: 220,
    ellipsis: { tooltip: true },
    render: row => row.error || "-",
  },
  {
    title: "操作",
    key: "actions",
    width: 168,
    fixed: "right",
    render: row => h(NSpace, { size: 4 }, {
      default: () => [
        h(NButton, {
          size: "small",
          quaternary: true,
          type: "primary",
          disabled: row.task_id == null,
          renderIcon: icon("lucide:panel-right-open"),
          onClick: () => openHistoryItem(row, false),
        }, () => "任务详情"),
        h(NButton, {
          size: "small",
          quaternary: true,
          type: "primary",
          disabled: row.task_id == null,
          renderIcon: icon("lucide:activity"),
          onClick: () => openHistoryItem(row, true),
        }, () => "运行详情"),
      ],
    }),
  },
];

const stepColumns: DataTableColumns<RunStepView> = [
  { title: "序号", key: "sequence", width: 64 },
  {
    title: "节点",
    key: "key",
    minWidth: 180,
    render: (row) =>
      h("div", { class: "primary-cell compact" }, [
        h("strong", row.key),
        h("span", row.role ? `${row.role} · ${row.type}` : row.type),
      ]),
  },
  {
    title: "状态",
    key: "status",
    width: 116,
    render: (row) =>
      h(NTag, { size: "small", type: statusType(row.status) }, () =>
        statusText(row.status),
      ),
  },
  {
    title: "依赖",
    key: "dependsOn",
    minWidth: 150,
    render: (row) => (row.dependsOn.length ? row.dependsOn.join(", ") : "无"),
  },
  {
    title: "输出 / 等待",
    key: "outputSummary",
    minWidth: 220,
    ellipsis: { tooltip: true },
    render: (row) =>
      row.outputSummary || row.waitReason || row.errorSummary || "-",
  },
  { title: "重试", key: "retryCount", width: 64 },
  {
    title: "操作",
    key: "actions",
    width: 76,
    render: (row) =>
      h(
        NButton,
        { size: "tiny", text: true, onClick: () => (activeStep.value = row) },
        () => "检查",
      ),
  },
];

const artifactColumns: DataTableColumns<ArtifactView> = [
  { type: "selection", disabled: (row) => row.status !== "active" },
  {
    title: "交付物",
    key: "name",
    minWidth: 220,
    render: (row) =>
      h("div", { class: "primary-cell compact" }, [
        h("strong", row.name),
        h(
          "span",
          `${row.artifactType} · v${row.versionNo} · ${row.storageType}`,
        ),
      ]),
  },
  {
    title: "敏感级别",
    key: "sensitiveLevel",
    width: 100,
    render: (row) =>
      h(NTag, { size: "small", bordered: false }, () => row.sensitiveLevel),
  },
  {
    title: "类型",
    key: "mimeType",
    minWidth: 140,
    render: (row) => row.mimeType || "-",
  },
  {
    title: "大小",
    key: "sizeBytes",
    width: 100,
    render: (row) => (row.sizeBytes == null ? "-" : `${row.sizeBytes} B`),
  },
  {
    title: "创建时间",
    key: "createdAt",
    width: 170,
    render: (row) => formatTime(row.createdAt),
  },
];

const acceptanceColumns: DataTableColumns<AcceptanceView> = [
  {
    title: "结论",
    key: "result",
    width: 110,
    render: (row) =>
      h(NTag, { size: "small", type: acceptanceType(row.result) }, () =>
        acceptanceText(row.result),
      ),
  },
  {
    title: "交付物",
    key: "artifactIds",
    minWidth: 150,
    render: (row) => row.artifactIds.map((id) => `#${id}`).join(", "),
  },
  {
    title: "意见",
    key: "comment",
    minWidth: 200,
    ellipsis: { tooltip: true },
    render: (row) => row.comment || "-",
  },
  {
    title: "轮次",
    key: "reworkNo",
    width: 72,
    render: (row) => `#${row.reworkNo}`,
  },
  {
    title: "审核人",
    key: "reviewerId",
    minWidth: 180,
    render: (row) => userName(row.reviewerId),
  },
  {
    title: "时间",
    key: "createdAt",
    width: 170,
    render: (row) => formatTime(row.createdAt),
  },
];

const participantColumns: DataTableColumns<TaskParticipantView> = [
  {
    title: '成员',
    key: 'userId',
    minWidth: 170,
    render: row => userName(row.userId),
  },
  { title: '关系', key: 'type', width: 110, render: row => {
    const label = participantTypeOptions.find(item => item.value === row.type)?.label;
    return typeof label === 'string' ? label : row.type;
  } },
  { title: '来源', key: 'source', width: 100 },
  {
    title: '操作',
    key: 'actions',
    width: 78,
    render: row => h(NButton, { size: 'tiny', text: true, type: 'error', disabled: row.type === 'owner', onClick: () => deleteParticipant(row) }, () => '移除'),
  },
];

const accessRuleColumns: DataTableColumns<TaskAccessRuleView> = [
  { title: '主体', key: 'subjectId', minWidth: 150, render: row => row.subjectKey || userName(row.subjectId) },
  { title: '动作', key: 'action', width: 90 },
  {
    title: '效果',
    key: 'effect',
    width: 80,
    render: row => h(NTag, { size: 'small', type: row.effect === 'allow' ? 'success' : 'error' }, () => row.effect === 'allow' ? '允许' : '拒绝'),
  },
  { title: '到期', key: 'expiresAt', width: 170, render: row => formatTime(row.expiresAt) },
  { title: '操作', key: 'actions', width: 78, render: row => h(NButton, { size: 'tiny', text: true, type: 'error', onClick: () => deleteAccessRule(row) }, () => '移除') },
];

const resourceColumns: DataTableColumns<TaskResourceView> = [
  { title: '资源类型', key: 'resourceType', width: 130 },
  { title: '资源 ID', key: 'resourceId', width: 120 },
  { title: '权限', key: 'permission', width: 90 },
  { title: '必须', key: 'required', width: 70, render: row => row.required ? '是' : '否' },
  { title: '授权来源', key: 'grantSource', width: 100 },
  { title: '创建时间', key: 'createdAt', width: 170, render: row => formatTime(row.createdAt) },
];

const versionColumns: DataTableColumns<TaskVersionView> = [
  {
    title: '版本',
    key: 'versionNo',
    width: 90,
    render: row => h(NSpace, { size: 6, align: 'center' }, {
      default: () => [h('span', `v${row.versionNo}`), ...(activeTask.value?.currentVersionId === row.id
        ? [h(NTag, { size: 'tiny', type: 'success', bordered: false }, () => '当前')] : [])]
    })
  },
  { title: '标题', key: 'title', minWidth: 180, ellipsis: { tooltip: true } },
  { title: '内容摘要', key: 'objective', minWidth: 240, ellipsis: { tooltip: true } },
  { title: '内容哈希', key: 'contentHash', minWidth: 210, ellipsis: { tooltip: true } },
  { title: '创建人', key: 'createdBy', width: 120, render: row => userName(row.createdBy) },
  { title: '创建时间', key: 'createdAt', width: 170, render: row => formatTime(row.createdAt) },
  {
    title: '快照', key: 'actions', width: 80,
    render: row => h(NButton, { size: 'tiny', text: true, type: 'primary', onClick: () => inspectVersion(row) }, () => '查看')
  }
];

async function loadTasks() {
  pageLoading.value = true;
  const { data, error } = await fetchTasks();
  if (!error) tasks.value = data;
  pageLoading.value = false;
}

async function loadExecutionHistory(resetPage = false) {
  if (!showHistoryTab.value) return;
  clearHistoryFilterTimer();
  if (resetPage) {
    historyPage.value = 1;
    historyItems.value = [];
    historyTotal.value = 0;
  }
  const generation = ++historyRequestGeneration;
  historyLoading.value = true;
  historyError.value = "";
  try {
    const result = await fetchExecutionHistory({
      page: historyPage.value,
      page_size: historyPageSize,
      status: historyStatus.value || undefined,
      task_id: historyTaskId.value || undefined,
      q: historyQuery.value.trim() || undefined,
      start_at: toApiDateTime(historyStartAt.value),
      end_at: toApiDateTime(historyEndAt.value, true),
    });
    if (generation !== historyRequestGeneration) return;
    if (result.error) {
      historyError.value = errorMessage(result.error, "执行历史加载失败");
      if (resetPage) {
        historyItems.value = [];
        historyTotal.value = 0;
      }
      return;
    }
    historyItems.value = result.data.items || [];
    historyTotal.value = Number(result.data.total || 0);
    historyLoaded.value = true;
  } catch (error) {
    if (generation !== historyRequestGeneration) return;
    historyError.value = errorMessage(error, "执行历史加载失败");
    if (resetPage) {
      historyItems.value = [];
      historyTotal.value = 0;
    }
  } finally {
    if (generation === historyRequestGeneration) {
      historyLoading.value = false;
    }
  }
}

function clearHistoryFilterTimer() {
  if (historyFilterTimer !== null) {
    window.clearTimeout(historyFilterTimer);
    historyFilterTimer = null;
  }
}

function scheduleHistoryReload() {
  clearHistoryFilterTimer();
  historyFilterTimer = window.setTimeout(() => {
    historyFilterTimer = null;
    void loadExecutionHistory(true);
  }, 260);
}

function resetHistoryFilters() {
  clearHistoryFilterTimer();
  historyQuery.value = "";
  historyStatus.value = null;
  historyTaskId.value = null;
  historyStartAt.value = "";
  historyEndAt.value = "";
  if (mainViewTab.value === "history") scheduleHistoryReload();
}

async function openHistoryItem(item: NhsV1ExecutionHistoryItem, focusRun: boolean) {
  if (item.task_id == null) {
    window.$message?.warning("该执行记录没有关联任务");
    return;
  }
  const taskId = String(item.task_id);
  let task = tasks.value.find(value => value.id === taskId);
  if (!task) {
    const result = await fetchTask(taskId);
    if (result.error) {
      window.$message?.error(errorMessage(result.error, "关联任务加载失败"));
      return;
    }
    task = result.data;
  }
  await openTask(task);
  if (!focusRun) return;
  const run = runs.value.find(value => String(value.id) === String(item.id));
  if (run) {
    await selectRun(run);
  } else {
    const result = await fetchTaskRun(task.id, String(item.id));
    if (result.error) {
      window.$message?.warning("该运行详情暂时不可用");
      return;
    }
    await selectRun(result.data);
  }
}

async function loadCatalogs() {
  if (props.clientMode) {
    const [workflowResult, agentResult] = await Promise.all([
      fetchWorkflowTemplates(),
      fetchAllowedAgents(),
    ]);
    if (!workflowResult.error) workflows.value = workflowResult.data;
    if (!agentResult.error) agents.value = agentResult.data;
    if (!createForm.workflowVersionId && workflows.value.length) {
      createForm.workflowVersionId = workflows.value[0].versionId;
    }
    return;
  }
  const [workflowResult, agentResult, userResult, toolResult, skillResult, knowledgeResult, sourceResult, datasetResult] = await Promise.all([
    fetchWorkflowTemplates(),
    fetchAllowedAgents(),
    fetchSystemUsers(undefined, 1, 200),
    fetchTools(false),
    fetchSkills(false),
    fetchKnowledgeBases(undefined, false),
    fetchDataSources(),
    fetchDatasets(),
  ]);
  if (!workflowResult.error) workflows.value = workflowResult.data;
  if (!agentResult.error) agents.value = agentResult.data;
  if (!userResult.error) users.value = userResult.data.rows;
  if (!toolResult.error) tools.value = toolResult.data;
  if (!skillResult.error) skills.value = skillResult.data;
  if (!knowledgeResult.error) knowledgeBases.value = knowledgeResult.data;
  if (!sourceResult.error) dataSources.value = sourceResult.data;
  if (!datasetResult.error) datasets.value = datasetResult.data;
  if (!createForm.workflowVersionId && workflows.value.length) {
    createForm.workflowVersionId = workflows.value[0].versionId;
  }
}

function resetCreateForm() {
  Object.assign(createForm, {
    mode: "multi_agent_template",
    title: "",
    objective: "",
    background: "",
    workflowVersionId: workflows.value[0]?.versionId || null,
    singleAgentVersionId: null,
    roleAgents: {},
    visibility: "enterprise_shared",
    category: "general",
    riskLevel: "R1",
    importance: false,
    urgency: false,
    tags: [],
    resourceKeys: [],
  });
}

function openCreate() {
  editingTaskId.value = null;
  resetCreateForm();
  createVisible.value = true;
}

function errorStatus(error: unknown) {
  const response = (error as { response?: { status?: unknown } } | null)?.response;
  return typeof response?.status === 'number' ? response.status : null;
}

function errorMessage(error: unknown, fallback: string) {
  const value = error as { response?: { data?: { message?: unknown; msg?: unknown } }; message?: unknown } | null;
  const backend = value?.response?.data?.message || value?.response?.data?.msg;
  return typeof backend === 'string' && backend.trim()
    ? backend
    : typeof value?.message === 'string' && value.message.trim()
      ? value.message
      : fallback;
}

function taskOptimizeMessage(error: unknown) {
  const status = errorStatus(error);
  const response = (error as {
    response?: { data?: { code?: number | string } };
  } | null)?.response;
  if (status === 501 || Number(response?.data?.code) === 501) {
    return "任务指令优化当前不可用：请先在模型中心配置并启用对话模型";
  }
  return errorMessage(error, "任务指令优化失败");
}

async function optimizeTaskObjective() {
  const source = createForm.objective.trim();
  if (!source || taskOptimizeLoading.value) return;
  taskOptimizeOriginal.value = source;
  taskOptimizeResult.value = null;
  taskOptimizeError.value = "";
  taskOptimizeVisible.value = true;
  taskOptimizeLoading.value = true;
  try {
    const result = await optimizeTaskInstruction(source);
    if (result.error) {
      taskOptimizeError.value = taskOptimizeMessage(result.error);
      return;
    }
    taskOptimizeResult.value = result.data;
  } catch (error) {
    taskOptimizeError.value = taskOptimizeMessage(error);
  } finally {
    taskOptimizeLoading.value = false;
  }
}

function applyTaskOptimization() {
  const suggestion = taskOptimizeResult.value?.optimized_content;
  if (!suggestion) return;
  if (
    createForm.objective.trim() !== taskOptimizeOriginal.value
    && !window.confirm("任务目标已在优化期间发生变化，确认用优化建议覆盖吗？")
  ) {
    return;
  }
  createForm.objective = suggestion;
  taskOptimizeVisible.value = false;
  window.$message?.success("优化建议已应用到任务目标");
}

async function openEdit(task: TaskView = activeTask.value as TaskView) {
  if (!task) return;
  editingTaskId.value = task.id;
  const [versionResult, resourceResult] = await Promise.all([
    fetchTaskVersion(task.id, task.currentVersionId),
    fetchTaskResources(task.id),
  ]);
  if (versionResult.error) {
    window.$message?.error(errorMessage(versionResult.error, '任务版本加载失败'));
    editingTaskId.value = null;
    return;
  }
  const version = versionResult.data;
  const snapshot = version.resourceSnapshot || {};
  const workflowBindings = snapshot.workflowAgentVersions as Record<string, string> | undefined;
  const resourceList = resourceResult.error ? [] : resourceResult.data;
  if (resourceResult.error) {
    window.$message?.error(errorMessage(resourceResult.error, '任务资源加载失败'));
    editingTaskId.value = null;
    return;
  }
  taskResources.value = resourceList;
  Object.assign(createForm, {
    mode: task.orchestrationMode === 'single_agent' ? 'single_agent' : 'multi_agent_template',
    title: task.title,
    objective: task.objective,
    background: task.background || '',
    workflowVersionId: version.workflowVersionId || null,
    singleAgentVersionId: version.agentVersionId,
    roleAgents: workflowBindings || {},
    visibility: task.visibility,
    category: task.category,
    riskLevel: task.riskLevel,
    importance: Boolean(task.importance),
    urgency: Boolean(task.urgency),
    tags: [...task.tags],
    resourceKeys: resourceList.map(resource => `${resource.resourceType}:${resource.resourceId}`),
  });
  createVisible.value = true;
}

function validateAgentSelection() {
  if (createForm.mode === "single_agent") {
    if (!createForm.singleAgentVersionId) {
      window.$message?.warning("请选择已发布的 Agent 版本");
      return false;
    }
    return true;
  }
  const workflow = selectedWorkflow.value;
  if (!workflow) {
    window.$message?.warning("请选择固定工作流模板");
    return false;
  }
  const missingRole = workflow.roles.find(
    (role) => !createForm.roleAgents[role.key],
  );
  if (missingRole) {
    window.$message?.warning(
      `请为“${missingRole.name}”绑定已发布的 Agent 版本`,
    );
    return false;
  }
  return true;
}

async function submitTask() {
  await createFormRef.value?.validate();
  if (!validateAgentSelection()) return;
  const workflow = selectedWorkflow.value;
  const workflowAgentVersions =
    createForm.mode === "multi_agent_template" && workflow
      ? Object.fromEntries(
          workflow.roles.map((role) => [
            role.key,
            createForm.roleAgents[role.key]!,
          ]),
        )
      : undefined;
  const primaryVersionId =
    createForm.mode === "multi_agent_template" && workflow
      ? workflowAgentVersions![workflow.roles[0].key]
      : createForm.singleAgentVersionId!;
  const resourceKeys = includeSqlToolDatasets(createForm.resourceKeys, tools.value);
  const resources: TaskResourceRequest[] = resourceKeys.flatMap(key => {
    const separator = key.indexOf(':');
    if (separator < 1) return [];
    const resourceType = key.slice(0, separator) as TaskResourceRequest['resourceType'];
    const resourceId = key.slice(separator + 1);
    const previous = editingTaskId.value
      ? taskResources.value.find(item => item.resourceType === resourceType && item.resourceId === resourceId)
      : null;
    return [{
      resourceType,
      resourceId,
      permission: taskResourcePermission(resourceType),
      required: true,
      grantSource: previous?.grantSource || 'user',
      grantSnapshot: previous?.grantSnapshot || {},
    }];
  });
  const payload: CreateTaskPayload = {
    idempotencyKey: `ui-task:${window.crypto.randomUUID()}`,
    title: createForm.title.trim(),
    objective: createForm.objective.trim(),
    background: createForm.background.trim() || undefined,
    projectId: props.projectId || undefined,
    agentVersionId: primaryVersionId,
    workflowVersionId:
      createForm.mode === "multi_agent_template"
        ? workflow!.versionId
        : undefined,
    workflowAgentVersions,
    visibility: createForm.visibility,
    category: createForm.category,
    orchestrationMode: createForm.mode,
    lifecycleLevel:
      createForm.mode === "multi_agent_template"
        ? "L2_workflow_task"
        : "L1_short_task",
    riskLevel: createForm.riskLevel,
    acceptanceMode: "human",
    importance: createForm.importance ? 1 : 0,
    urgency: createForm.urgency ? 1 : 0,
    contextSnapshot: {},
    resources,
    acceptanceSnapshot: {},
    inputSnapshot: {},
    budget: {},
    externalRefs: {},
    tags: createForm.tags,
  };
  createSubmitting.value = true;
  if (editingTaskId.value) {
    const updatePayload: UpdateTaskPayload = {
      title: createForm.title.trim(),
      objective: createForm.objective.trim(),
      background: createForm.background.trim() || undefined,
      projectId: activeTask.value?.projectId || props.projectId || undefined,
      agentVersionId: primaryVersionId,
      workflowVersionId: createForm.mode === 'multi_agent_template' ? workflow!.versionId : undefined,
      workflowAgentVersions,
      visibility: createForm.visibility,
      category: createForm.category,
      orchestrationMode: createForm.mode,
      lifecycleLevel: createForm.mode === 'multi_agent_template' ? 'L2_workflow_task' : 'L1_short_task',
      riskLevel: createForm.riskLevel,
      acceptanceMode: 'human',
      importance: createForm.importance ? 1 : 0,
      urgency: createForm.urgency ? 1 : 0,
      contextSnapshot: activeTask.value?.contextSnapshot || {},
      resources,
      acceptanceSnapshot: activeTask.value?.acceptanceConfig || {},
      inputSnapshot: {},
      budget: activeTask.value?.budget || {},
      externalRefs: activeTask.value?.externalRefs || {},
      tags: createForm.tags,
    };
    const updated = await updateTask(editingTaskId.value, updatePayload);
    if (updated.error) {
      const status = errorStatus(updated.error);
      window.$message?.error(status === 409 ? '任务定义已被其他人修改，请刷新后重试' : status === 403 ? '没有编辑该任务的权限' : errorMessage(updated.error, '任务保存失败'));
      if (status === 409) {
        await loadTasks();
        if (activeTask.value) {
          const refreshed = tasks.value.find(item => item.id === activeTask.value?.id) || activeTask.value;
          await openTask(refreshed);
        }
      }
    } else {
      window.$message?.success(updated.data.replayed ? '已返回原任务版本' : `任务已保存，版本 v${updated.data.taskVersionId}`);
      createVisible.value = false;
      editingTaskId.value = null;
      await loadTasks();
      await openTask(updated.data.task);
    }
    createSubmitting.value = false;
    return;
  }
  const { data, error } = await createTask(payload);
  if (!error) {
    window.$message?.success(
      data.replayed ? "已返回幂等任务结果" : "任务已创建",
    );
    createVisible.value = false;
    await loadTasks();
    await openTask(data.task);
  }
  createSubmitting.value = false;
}

async function openTask(task: TaskView) {
  stopTaskEventStream();
  activeTask.value = task;
  detailVisible.value = true;
  selectedRun.value = null;
  steps.value = [];
  artifacts.value = [];
  acceptances.value = [];
  checkedArtifactIds.value = [];
  activeStep.value = null;
  taskEvents.value = [];
  taskVisibility.value = null;
  taskVersions.value = [];
  versionsError.value = '';
  selectedVersion.value = null;
  governanceVisible.value = false;
  await loadRuns(true);
  await Promise.all([loadTaskGovernance(), loadTaskVersions()]);
}

async function loadTaskVersions() {
  if (!activeTask.value) return;
  versionsLoading.value = true;
  versionsError.value = '';
  const result = await fetchTaskVersions(activeTask.value.id);
  if (result.error) versionsError.value = errorMessage(result.error, '任务版本加载失败');
  else taskVersions.value = result.data;
  versionsLoading.value = false;
}

async function inspectVersion(version: TaskVersionView) {
  if (!activeTask.value) return;
  const result = await fetchTaskVersion(activeTask.value.id, version.id);
  if (result.error) {
    window.$message?.error(errorMessage(result.error, '版本快照加载失败'));
    return;
  }
  selectedVersion.value = result.data;
  versionDetailVisible.value = true;
}

async function loadTaskGovernance() {
  if (!activeTask.value) return;
  governanceLoading.value = true;
  const [resourceResult, participantResult, accessResult, visibilityResult] = await Promise.all([
    fetchTaskResources(activeTask.value.id),
    fetchTaskParticipants(activeTask.value.id),
    fetchTaskAccessRules(activeTask.value.id),
    fetchTaskVisibility(activeTask.value.id),
  ]);
  if (!resourceResult.error) taskResources.value = resourceResult.data;
  if (!participantResult.error) taskParticipants.value = participantResult.data;
  if (!accessResult.error) taskAccessRules.value = accessResult.data;
  if (!visibilityResult.error) {
    taskVisibility.value = visibilityResult.data;
  }
  governanceLoading.value = false;
}

async function loadTaskEvents() {
  if (!activeTask.value || !selectedRun.value) return;
  eventLoading.value = true;
  const { data, error } = await fetchTaskRunEvents(activeTask.value.id, selectedRun.value.id, 0, 200);
  if (!error) {
    taskEvents.value = data;
    eventStreamError.value = null;
  }
  eventLoading.value = false;
}

function clearEventStreamRetry() {
  if (eventStreamRetryTimer !== null) {
    window.clearTimeout(eventStreamRetryTimer);
    eventStreamRetryTimer = null;
  }
}

function stopTaskEventStream() {
  eventStreamGeneration += 1;
  clearEventStreamRetry();
  eventStreamController.value?.abort();
  eventStreamController.value = null;
  eventStreamConnecting.value = false;
  eventStreamError.value = null;
}

function appendTaskEvent(event: ExecutionEventView) {
  if (!activeTask.value || !selectedRun.value) return;
  if (event.runId && event.runId !== selectedRun.value.id) return;
  const existing = new Map(taskEvents.value.map(item => [item.eventId, item]));
  existing.set(event.eventId, event);
  taskEvents.value = [...existing.values()].sort((left, right) => left.cursor - right.cursor);
}

function isRunLive(status: string) {
  return [
    'queued',
    'preparing',
    'running',
    'waiting_approval',
    'waiting_external',
    'paused',
  ].includes(status);
}

function scheduleEventStreamReconnect(taskId: string, runId: string, generation: number) {
  clearEventStreamRetry();
  eventStreamRetryTimer = window.setTimeout(() => {
    eventStreamRetryTimer = null;
    if (
      generation !== eventStreamGeneration ||
      !detailVisible.value ||
      activeTask.value?.id !== taskId ||
      selectedRun.value?.id !== runId ||
      !selectedRun.value ||
      !isRunLive(selectedRun.value.status)
    ) {
      return;
    }
    void connectTaskEventStream(taskId, runId, generation);
  }, 1500);
}

async function connectTaskEventStream(
  taskId: string,
  runId: string,
  generation = eventStreamGeneration,
) {
  if (
    generation !== eventStreamGeneration ||
    !detailVisible.value ||
    activeTask.value?.id !== taskId ||
    selectedRun.value?.id !== runId ||
    !selectedRun.value ||
    !isRunLive(selectedRun.value.status)
  ) {
    return;
  }
  const controller = new AbortController();
  eventStreamController.value = controller;
  eventStreamConnecting.value = true;
  const lastEvent = taskEvents.value[taskEvents.value.length - 1];
  const cursor = lastEvent?.cursor ?? 0;
  try {
    await streamTaskRunEvents(
      taskId,
      runId,
      cursor,
      event => {
        if (generation === eventStreamGeneration) appendTaskEvent(event);
      },
      controller.signal,
    );
    if (controller.signal.aborted || generation !== eventStreamGeneration) return;
    eventStreamConnecting.value = false;
    eventStreamError.value = null;
    // The server normally keeps the stream open; reconnect if it closes cleanly.
    scheduleEventStreamReconnect(taskId, runId, generation);
  } catch (error) {
    if (controller.signal.aborted || generation !== eventStreamGeneration) return;
    eventStreamConnecting.value = false;
    eventStreamError.value = error instanceof Error ? error.message : '事件流连接失败';
    // Polling remains active while the stream is unavailable.
    scheduleEventStreamReconnect(taskId, runId, generation);
  } finally {
    if (eventStreamController.value === controller) {
      eventStreamController.value = null;
      eventStreamConnecting.value = false;
    }
  }
}

async function refreshTaskEvents() {
  stopTaskEventStream();
  await loadTaskEvents();
  if (activeTask.value && selectedRun.value && isRunLive(selectedRun.value.status)) {
    void connectTaskEventStream(activeTask.value.id, selectedRun.value.id);
  }
}

async function loadRuns(selectLatest = false) {
  if (!activeTask.value) return;
  runLoading.value = true;
  const { data, error } = await fetchTaskRuns(activeTask.value.id);
  if (!error) {
    runs.value = data;
    if (selectedRun.value) {
      selectedRun.value =
        data.find((run) => run.id === selectedRun.value?.id) || null;
    }
    if (selectLatest && data.length) {
      const latest =
        data.find((run) => run.id === activeTask.value?.latestRunId) || data[0];
      await selectRun(latest);
    }
    if (selectedRun.value) {
      if (!isRunLive(selectedRun.value.status)) {
        stopTaskEventStream();
      } else if (
        detailVisible.value &&
        !eventStreamConnecting.value &&
        !eventStreamController.value &&
        eventStreamRetryTimer === null
      ) {
        void connectTaskEventStream(activeTask.value.id, selectedRun.value.id);
      }
    }
  }
  runLoading.value = false;
}

async function selectRun(run: TaskRunView) {
  stopTaskEventStream();
  selectedRun.value = run;
  activeStep.value = null;
  checkedArtifactIds.value = [];
  taskEvents.value = [];
  eventStreamError.value = null;
  await Promise.all([loadSteps(), loadDelivery(), loadTaskEvents()]);
  if (activeTask.value && isRunLive(run.status)) {
    void connectTaskEventStream(activeTask.value.id, run.id);
  }
}

async function loadSteps() {
  if (!activeTask.value || !selectedRun.value) return;
  stepLoading.value = true;
  const { data, error } = await fetchRunSteps(
    activeTask.value.id,
    selectedRun.value.id,
  );
  if (!error) {
    steps.value = data;
    if (activeStep.value)
      activeStep.value =
        data.find((step) => step.id === activeStep.value?.id) || null;
  }
  stepLoading.value = false;
}

async function loadDelivery() {
  if (!activeTask.value || !selectedRun.value) return;
  artifactLoading.value = true;
  const [artifactResult, acceptanceResultValue] = await Promise.all([
    fetchTaskArtifacts(activeTask.value.id, selectedRun.value.id),
    fetchRunAcceptances(activeTask.value.id, selectedRun.value.id),
  ]);
  if (!artifactResult.error) {
    artifacts.value = artifactResult.data;
    const currentIds = new Set(artifactResult.data.map((item) => item.id));
    checkedArtifactIds.value = checkedArtifactIds.value.filter((id) =>
      currentIds.has(String(id)),
    );
  }
  if (!acceptanceResultValue.error)
    acceptances.value = acceptanceResultValue.data;
  artifactLoading.value = false;
}

function openAcceptance(result: AcceptanceView["result"]) {
  if (checkedArtifactIds.value.length === 0) {
    window.$message?.warning("请先选择本次验收涉及的交付物");
    return;
  }
  acceptanceResult.value = result;
  acceptanceComment.value = "";
  acceptanceVisible.value = true;
}

async function submitAcceptance() {
  if (
    !activeTask.value ||
    !selectedRun.value ||
    checkedArtifactIds.value.length === 0
  )
    return;
  if (acceptanceResult.value !== "passed" && !acceptanceComment.value.trim()) {
    window.$message?.warning("返工、拒绝或人工接管必须填写原因");
    return;
  }
  acceptanceSubmitting.value = true;
  const { data, error } = await decideRunAcceptance(
    activeTask.value.id,
    selectedRun.value.id,
    {
      idempotencyKey: `ui-acceptance:${window.crypto.randomUUID()}`,
      artifactIds: checkedArtifactIds.value.map(String),
      result: acceptanceResult.value,
      comment: acceptanceComment.value.trim() || undefined,
      ruleResult: {},
    },
  );
  if (!error) {
    window.$message?.success(
      data.replayed
        ? "已返回原验收结果"
        : acceptanceText(data.acceptance.result),
    );
    acceptanceVisible.value = false;
    checkedArtifactIds.value = [];
    await Promise.all([loadTasks(), loadRuns(false), loadDelivery()]);
  }
  acceptanceSubmitting.value = false;
}

function openRunModal() {
  runInput.value = activeTask.value?.objective || "";
  runModalVisible.value = true;
}

async function submitRun() {
  if (!activeTask.value || !runInput.value.trim()) {
    window.$message?.warning("请输入本次运行输入");
    return;
  }
  runSubmitting.value = true;
  const created = await createTaskRun(
    activeTask.value.id,
    `ui-run:${window.crypto.randomUUID()}`,
    runInput.value.trim(),
  );
  if (!created.error) {
    const started = await startTaskRun(
      activeTask.value.id,
      created.data.run.id,
    );
    if (!started.error) {
      window.$message?.success("运行已启动");
      runModalVisible.value = false;
      selectedRun.value = started.data.run;
      await loadRuns(false);
      await Promise.all([loadSteps(), loadDelivery()]);
    }
  }
  runSubmitting.value = false;
}

async function startQueuedRun(run: TaskRunView) {
  if (!activeTask.value) return;
  const { data, error } = await startTaskRun(activeTask.value.id, run.id);
  if (!error) {
    selectedRun.value = data.run;
    window.$message?.success(data.replayed ? "运行已在处理中" : "运行已启动");
    await loadRuns(false);
    await Promise.all([loadSteps(), loadDelivery()]);
  }
}

async function applyRunAction(action: 'cancel' | 'pause' | 'resume' | 'retry', run: TaskRunView) {
  if (!activeTask.value || runActionLoading.value) return;
  runActionLoading.value = true;
  const taskId = activeTask.value.id;
  const result = action === 'cancel'
    ? await cancelTaskRun(taskId, run.id, '由任务中心发起')
    : action === 'pause'
      ? await pauseTaskRun(taskId, run.id, '由任务中心暂停')
      : action === 'resume'
        ? await resumeTaskRun(taskId, run.id)
        : await retryTaskRun(taskId, run.id, `ui-retry:${window.crypto.randomUUID()}`);
  if (!result.error) {
    selectedRun.value = result.data.run;
    window.$message?.success(result.data.replayed ? '已返回原操作结果' : `${action === 'cancel' ? '取消' : action === 'pause' ? '暂停' : action === 'resume' ? '恢复' : '重试'}操作已提交`);
    await Promise.all([loadTasks(), loadRuns(false), loadSteps(), loadDelivery(), loadTaskGovernance()]);
  }
  runActionLoading.value = false;
}

async function addParticipant() {
  if (!activeTask.value || !participantUserId.value) {
    window.$message?.warning('请选择任务参与人');
    return;
  }
  const { error } = await putTaskParticipant(activeTask.value.id, participantUserId.value, participantType.value);
  if (!error) {
    participantUserId.value = null;
    await loadTaskGovernance();
    window.$message?.success('参与人已更新');
  }
}

async function deleteParticipant(row: TaskParticipantView) {
  if (!activeTask.value || row.type === 'owner') return;
  const { error } = await removeTaskParticipant(activeTask.value.id, row.userId, row.type as Exclude<TaskParticipantType, 'owner'>);
  if (!error) {
    await loadTaskGovernance();
    window.$message?.success('参与人已移除');
  }
}

async function addAccessRule() {
  if (!activeTask.value || !aclSubjectId.value) {
    window.$message?.warning('请选择 ACL 用户');
    return;
  }
  const { error } = await putTaskAccessRule(activeTask.value.id, {
    subjectType: aclSubjectType.value,
    subjectId: aclSubjectId.value,
    subjectKey: null,
    action: aclAction.value,
    effect: aclEffect.value,
    expiresAt: null,
  });
  if (!error) {
    aclSubjectId.value = null;
    await loadTaskGovernance();
    window.$message?.success('任务 ACL 已更新');
  }
}

async function deleteAccessRule(row: TaskAccessRuleView) {
  if (!activeTask.value) return;
  const { error } = await removeTaskAccessRule(activeTask.value.id, row.id);
  if (!error) {
    await loadTaskGovernance();
    window.$message?.success('任务 ACL 已移除');
  }
}

function startTaskDrag(event: DragEvent, task: TaskView) {
  if (statusUpdatingTaskId.value) {
    event.preventDefault();
    return;
  }
  draggedTaskId.value = task.id;
  event.dataTransfer?.setData("text/plain", task.id);
  if (event.dataTransfer) event.dataTransfer.effectAllowed = "move";
}

function finishTaskDrag() {
  draggedTaskId.value = null;
  dragOverStatus.value = null;
}

async function moveTaskToStatus(targetStatus: string) {
  const taskId = draggedTaskId.value;
  dragOverStatus.value = null;
  if (!taskId) return;
  const task = tasks.value.find((item) => item.id === taskId);
  if (!task || task.status === targetStatus || statusUpdatingTaskId.value) {
    finishTaskDrag();
    return;
  }

  const sourceStatus = task.status;
  statusUpdatingTaskId.value = task.id;
  task.status = targetStatus;
  const { data, error } = await request<TaskView>({
    url: `/platform/tasks/${task.id}/status`,
    method: "patch",
    data: { status: targetStatus },
  });
  if (error) {
    task.status = sourceStatus;
    window.$message?.warning("状态调整未生效，任务已恢复到原列");
  } else {
    Object.assign(task, data);
    if (activeTask.value?.id === task.id) activeTask.value = task;
    window.$message?.success(`任务已移至“${statusText(targetStatus)}”`);
  }
  statusUpdatingTaskId.value = null;
  finishTaskDrag();
}

function resetFilters() {
  search.value = "";
  statusFilter.value = null;
  modeFilter.value = null;
  sortBy.value = "updated";
}

const viewModeLabel = computed(() =>
  viewMode.value === "kanban"
    ? "看板视图"
    : viewMode.value === "quadrant"
      ? "四象限视图"
      : "列表视图",
);

function selectViewMode(value: string | number) {
  if (value === "list" || value === "kanban" || value === "quadrant") {
    viewMode.value = value;
  }
}

function kanbanStatusLabel(status: string) {
  return props.clientMode && status === "running" ? "进行中" : statusText(status);
}

function taskOwnerInitial(task: TaskView) {
  const owner = users.value.find(item => item.userId === task.ownerId);
  const label = owner?.nickName || owner?.userName ||
    (task.ownerId === authStore.userInfo.userId ? authStore.userInfo.userName : task.ownerId) ||
    "U";
  return String(label).slice(0, 1).toUpperCase();
}

watch(viewMode, (value) =>
  window.localStorage.setItem(VIEW_MODE_STORAGE_KEY, value),
);

watch(
  () => createForm.workflowVersionId,
  () => {
    const workflow = selectedWorkflow.value;
    createForm.roleAgents = workflow
      ? Object.fromEntries(workflow.roles.map((role) => [role.key, createForm.roleAgents[role.key] || null]))
      : {};
  },
  { immediate: true },
);

watch(
  () => createForm.resourceKeys,
  resourceKeys => {
    const complete = includeSqlToolDatasets(resourceKeys, tools.value);
    if (complete.length !== resourceKeys.length) createForm.resourceKeys = complete;
  },
  { deep: true },
);

watch(detailVisible, (visible) => {
  if (!visible) {
    stopTaskEventStream();
  }
});

watch(mainViewTab, tab => {
  if (tab === "history" && showHistoryTab.value && !historyLoaded.value) {
    void loadExecutionHistory(true);
  }
});

watch(
  [historyQuery, historyStatus, historyTaskId, historyStartAt, historyEndAt],
  () => {
    if (mainViewTab.value === "history") scheduleHistoryReload();
  },
);

const pollTimer = window.setInterval(async () => {
  if (!detailVisible.value || !selectedRun.value) return;
  if (
    ![
      "queued",
      "preparing",
      "running",
      "waiting_approval",
      "waiting_external",
    ].includes(selectedRun.value.status)
  ) {
    return;
  }
  await loadRuns(false);
  await Promise.all([loadSteps(), loadDelivery()]);
}, 3000);

onMounted(async () => {
  await Promise.all([loadTasks(), loadCatalogs()]);
});

onBeforeUnmount(() => {
  window.clearInterval(pollTimer);
  clearHistoryFilterTimer();
  stopTaskEventStream();
});
</script>

<template>
  <div class="task-center-container" :class="{ 'client-mode': props.clientMode }">
    <header v-if="!props.clientMode" class="page-header">
      <div>
        <h2 class="page-title">{{ $t("page.taskCenter.title") }}</h2>
        <p class="page-desc">{{ $t("page.taskCenter.desc") }}</p>
      </div>
      <NSpace>
        <NButton
          secondary
          :loading="mainViewTab === 'history' ? historyLoading : pageLoading"
          @click="mainViewTab === 'history' ? loadExecutionHistory(true) : loadTasks()"
        >
          <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
          刷新
        </NButton>
        <NButton type="primary" @click="openCreate">
          <template #icon><SvgIcon icon="lucide:plus" /></template>
          新建任务
        </NButton>
      </NSpace>
    </header>

    <section v-if="props.clientMode && props.projectId" class="project-context" aria-label="当前项目筛选">
      <div class="project-context-main">
        <span class="project-context-icon"><SvgIcon icon="lucide:folder-kanban" /></span>
        <div>
          <strong>{{ props.projectName || '当前项目' }}</strong>
          <span>仅显示此项目的任务，新任务也会自动归入此项目</span>
        </div>
      </div>
      <NButton quaternary circle aria-label="清除项目筛选" @click="emit('clearProjectContext')">
        <template #icon><SvgIcon icon="lucide:x" /></template>
      </NButton>
    </section>

    <section v-if="!props.clientMode" class="summary-band" aria-label="任务摘要">
      <div>
        <span>全部任务</span><strong>{{ taskSummary.total }}</strong>
      </div>
      <div>
        <span>运行或等待</span><strong>{{ taskSummary.active }}</strong>
      </div>
      <div>
        <span>多智能体任务</span><strong>{{ taskSummary.multi }}</strong>
      </div>
      <div>
        <span>失败任务</span><strong class="error-number">{{ taskSummary.failed }}</strong>
      </div>
    </section>

    <nav v-if="showHistoryTab" class="main-view-tabs app-tab-list app-tab-list--line" aria-label="任务中心视图">
      <button
        type="button"
        class="main-view-tab app-tab"
        :class="{ 'is-active': mainViewTab === 'tasks' }"
        @click="mainViewTab = 'tasks'"
      >
        <SvgIcon icon="lucide:list-checks" />
        任务列表
      </button>
      <button
        type="button"
        class="main-view-tab app-tab"
        :class="{ 'is-active': mainViewTab === 'history' }"
        @click="mainViewTab = 'history'"
      >
        <SvgIcon icon="lucide:history" />
        执行历史
        <span v-if="historyTotal > 0" class="main-view-tab-count">{{ historyTotal }}</span>
      </button>
    </nav>

    <template v-if="mainViewTab === 'tasks' || !showHistoryTab">
    <div class="toolbar">
      <NInput
        v-model:value="search"
        clearable
        placeholder="搜索任务名称、编号或目标"
        class="search-control"
      >
        <template #prefix><SvgIcon icon="lucide:search" /></template>
      </NInput>
      <NSelect
        v-model:value="statusFilter"
        clearable
        placeholder="任务状态"
        :options="statusOptions"
        class="filter-control"
      />
      <NSelect
        v-if="!props.clientMode"
        v-model:value="modeFilter"
        clearable
        placeholder="编排方式"
        :options="modeOptions"
        class="filter-control"
      />
      <NSelect
        v-else
        v-model:value="sortBy"
        :options="sortOptions"
        class="filter-control sort-control"
        aria-label="排序方式"
      />
      <NButton v-if="!props.clientMode" quaternary @click="resetFilters">重置</NButton>
      <NDropdown
        v-if="props.clientMode"
        trigger="click"
        :options="viewOptions"
        @select="selectViewMode"
      >
        <NButton secondary class="client-view-control" aria-label="切换任务视图">
          <template #icon><SvgIcon icon="lucide:columns-3" /></template>
          {{ viewModeLabel }}
          <SvgIcon icon="lucide:chevron-down" class="view-control-chevron" />
        </NButton>
      </NDropdown>
      <NButtonGroup v-else class="view-switch" aria-label="任务视图">
        <NTooltip>
          <template #trigger>
            <NButton
              :type="viewMode === 'list' ? 'primary' : 'default'"
              :secondary="viewMode !== 'list'"
              aria-label="列表视图"
              @click="viewMode = 'list'"
            >
              <template #icon><SvgIcon icon="lucide:list" /></template>
            </NButton>
          </template>
          列表
        </NTooltip>
        <NTooltip>
          <template #trigger>
            <NButton
              :type="viewMode === 'kanban' ? 'primary' : 'default'"
              :secondary="viewMode !== 'kanban'"
              aria-label="状态看板"
              @click="viewMode = 'kanban'"
            >
              <template #icon><SvgIcon icon="lucide:columns-3" /></template>
            </NButton>
          </template>
          状态看板
        </NTooltip>
        <NTooltip>
          <template #trigger>
            <NButton
              :type="viewMode === 'quadrant' ? 'primary' : 'default'"
              :secondary="viewMode !== 'quadrant'"
              aria-label="四象限视图"
              @click="viewMode = 'quadrant'"
            >
              <template #icon><SvgIcon icon="lucide:grid-2x2" /></template>
            </NButton>
          </template>
          四象限
        </NTooltip>
      </NButtonGroup>
      <span v-if="!props.clientMode" class="result-count">{{ filteredTasks.length }} 条结果</span>
      <NTooltip v-if="props.clientMode">
        <template #trigger>
          <NButton
            secondary
            circle
            :loading="pageLoading"
            class="client-refresh-task"
            aria-label="刷新任务"
            @click="loadTasks"
          >
            <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
          </NButton>
        </template>
        刷新任务
      </NTooltip>
      <NButton v-if="props.clientMode" type="primary" class="client-create-task" @click="openCreate">
        <template #icon><SvgIcon icon="lucide:plus" /></template>新建任务
      </NButton>
    </div>

    <div v-if="viewMode === 'list'" class="task-list-shell">
      <NDataTable
        :columns="taskColumns"
        :data="filteredTasks"
        :loading="pageLoading"
        :row-key="(row) => row.id"
        :scroll-x="1330"
        :max-height="620"
        :pagination="{ pageSize: 20 }"
        striped
      />
    </div>

    <div
      v-else-if="viewMode === 'kanban'"
      class="kanban-shell"
      :aria-busy="pageLoading"
    >
      <section class="kanban-board" aria-label="任务状态看板">
        <div
          v-for="column in kanbanColumns"
          :key="column.status"
          class="kanban-column" :class="[
            { 'is-drop-target': dragOverStatus === column.status },
          ]"
          @dragover.prevent="dragOverStatus = column.status"
          @dragleave.self="dragOverStatus = null"
          @drop.prevent="moveTaskToStatus(column.status)"
        >
          <header class="kanban-column-header">
            <div class="kanban-column-title">
              <span class="column-status-dot" :class="`status-${column.status}`" aria-hidden="true" />
              <strong>{{ props.clientMode ? kanbanStatusLabel(column.status) : column.label }}</strong>
              <span class="column-count">{{ column.tasks.length }}</span>
            </div>
            <div class="kanban-column-actions">
              <button
                type="button"
                class="column-action"
                :aria-label="`在${props.clientMode ? kanbanStatusLabel(column.status) : column.label}中新建任务`"
                title="新建任务"
                @click.stop="openCreate"
              >
                <SvgIcon icon="lucide:plus" />
              </button>
              <button
                type="button"
                class="column-action"
                :aria-label="`筛选${props.clientMode ? kanbanStatusLabel(column.status) : column.label}任务`"
                title="筛选此状态"
                @click.stop="statusFilter = statusFilter === column.status ? null : column.status"
              >
                <SvgIcon icon="lucide:ellipsis" />
              </button>
            </div>
          </header>
          <div class="kanban-card-list">
            <article
              v-for="task in column.tasks"
              :key="task.id"
              class="task-card" :class="[
                {
                  'is-dragging': draggedTaskId === task.id,
                  'is-updating': statusUpdatingTaskId === task.id,
                },
              ]"
              :draggable="!statusUpdatingTaskId"
              tabindex="0"
              @dragstart="startTaskDrag($event, task)"
              @dragend="finishTaskDrag"
              @click="openTask(task)"
              @keydown.enter="openTask(task)"
            >
              <div class="task-card-heading">
                <strong>{{ task.title }}</strong>
                <SvgIcon
                  v-if="statusUpdatingTaskId === task.id"
                  icon="lucide:loader-circle"
                  class="card-spinner"
                />
              </div>
              <p v-if="!props.clientMode">{{ task.objective }}</p>
              <div class="task-card-meta">
                <span class="task-card-key">{{ task.taskKey }}</span>
                <NTag
                  v-if="!props.clientMode"
                  size="tiny"
                  :type="riskType(task.riskLevel)"
                  :bordered="false"
                >
                  {{ task.riskLevel }}
                </NTag>
              </div>
              <div class="task-card-footer">
                <div v-if="task.importance || task.urgency" class="task-priority-flags">
                  <span v-if="task.importance"><SvgIcon icon="lucide:flag" />重要</span>
                  <span v-if="task.urgency"><SvgIcon icon="lucide:clock-3" />紧急</span>
                </div>
                <span class="task-owner-avatar" :title="userName(task.ownerId)">{{ taskOwnerInitial(task) }}</span>
              </div>
            </article>
            <div v-if="column.tasks.length === 0" class="kanban-empty" aria-live="polite">
              <SvgIcon icon="lucide:inbox" />
              <strong>暂无任务</strong>
              <span>拖动任务到此处</span>
            </div>
          </div>
        </div>
      </section>
      <div v-if="pageLoading" class="kanban-loading" aria-label="正在加载任务">
        <SvgIcon icon="lucide:loader-circle" />
      </div>
    </div>

    <NSpin v-else :show="pageLoading" class="quadrant-spin">
      <section class="quadrant-board" aria-label="任务优先级四象限">
        <article
          v-for="quadrant in quadrants"
          :key="quadrant.key"
          class="quadrant" :class="[`quadrant-${quadrant.key}`]"
        >
          <header class="quadrant-header">
            <div class="quadrant-title">
              <SvgIcon :icon="quadrant.icon" />
              <div>
                <strong>{{ quadrant.title }}</strong>
                <span>{{ quadrant.hint }}</span>
              </div>
            </div>
            <span class="quadrant-count" :aria-label="`${quadrant.tasks.length} 个任务`">
              {{ quadrant.tasks.length }}
            </span>
          </header>
          <div class="quadrant-tasks">
            <button
              v-for="task in quadrant.tasks"
              :key="task.id"
              type="button"
              class="quadrant-task"
              @click="openTask(task)"
            >
              <span class="quadrant-task-status">
                <NTag
                  size="tiny"
                  :type="statusType(task.status)"
                  :bordered="false"
                >{{ statusText(task.status) }}</NTag>
              </span>
              <span class="quadrant-task-title">{{ task.title }}</span>
              <span class="quadrant-task-key">{{ task.taskKey }}</span>
              <span class="quadrant-task-avatar" :title="userName(task.ownerId)">
                {{ taskOwnerInitial(task) }}
              </span>
            </button>
            <div v-if="quadrant.tasks.length === 0" class="quadrant-empty">
              <SvgIcon icon="lucide:inbox" />
              <span>当前没有任务</span>
            </div>
          </div>
        </article>
      </section>
    </NSpin>

    <NEmpty
      v-if="!pageLoading && filteredTasks.length === 0 && (!props.clientMode || viewMode !== 'kanban')"
      description="没有符合条件的任务"
      class="empty-state"
    >
      <template #extra>
        <NButton size="small" @click="openCreate">新建任务</NButton>
      </template>
    </NEmpty>
    </template>

    <section v-if="showHistoryTab && mainViewTab === 'history'" class="history-view">
      <div class="history-toolbar">
        <NInput
          v-model:value="historyQuery"
          clearable
          placeholder="搜索任务名称、Trace 或错误摘要"
          class="history-search"
          @keyup.enter="loadExecutionHistory(true)"
        >
          <template #prefix><SvgIcon icon="lucide:search" /></template>
        </NInput>
        <NSelect
          v-model:value="historyStatus"
          clearable
          placeholder="执行状态"
          :options="historyStatusOptions"
          class="history-filter"
        />
        <NSelect
          v-model:value="historyTaskId"
          clearable
          filterable
          placeholder="关联任务"
          :options="historyTaskOptions"
          class="history-task-filter"
        />
        <input
          v-model="historyStartAt"
          type="datetime-local"
          class="history-date-input"
          aria-label="执行开始时间"
          title="执行开始时间"
        />
        <input
          v-model="historyEndAt"
          type="datetime-local"
          class="history-date-input"
          aria-label="执行结束时间"
          title="执行结束时间"
        />
        <NButton quaternary @click="resetHistoryFilters">重置</NButton>
        <NButton
          secondary
          :loading="historyLoading"
          title="刷新执行历史"
          @click="loadExecutionHistory(true)"
        >
          <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
          刷新
        </NButton>
        <span class="history-total">共 {{ historyTotal }} 条</span>
      </div>

      <NAlert v-if="historyError" type="error" :show-icon="true" class="history-alert">
        <div class="history-alert-content">
          <span>{{ historyError }}</span>
          <NButton size="small" secondary @click="loadExecutionHistory(true)">重试</NButton>
        </div>
      </NAlert>

      <NSpin :show="historyLoading && historyItems.length === 0" class="history-loading">
        <div v-if="historyLoading && historyItems.length === 0" class="history-loading-copy">
          正在加载执行历史...
        </div>
        <NDataTable
          v-if="historyItems.length > 0"
          :columns="historyColumns"
          :data="historyItems"
          :loading="historyLoading"
          :row-key="row => String(row.id)"
          :scroll-x="1100"
          :max-height="620"
          striped
        />
      </NSpin>

      <NEmpty
        v-if="!historyLoading && !historyError && historyItems.length === 0"
        description="暂无匹配的执行历史"
        class="history-empty"
      />

      <div v-if="historyTotal > 0" class="history-footer">
        <span>第 {{ historyPage }} / {{ historyPageCount }} 页</span>
        <NPagination
          v-model:page="historyPage"
          :page-count="historyPageCount"
          :page-size="historyPageSize"
          :disabled="historyLoading"
          show-quick-jumper
          @update:page="loadExecutionHistory(false)"
        />
      </div>
    </section>

    <NModal
      v-model:show="createVisible"
      preset="card"
      :title="editingTaskId ? '编辑任务定义' : '新建任务'"
      class="create-modal"
      :style="{ width: 'min(920px, calc(100vw - 32px))' }"
      :bordered="false"
      :mask-closable="!createSubmitting"
    >
      <NForm
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        label-placement="top"
      >
        <NFormItem label="编排方式">
          <NRadioGroup v-model:value="createForm.mode" name="create-mode">
            <NRadioButton value="multi_agent_template">
              固定多智能体
            </NRadioButton>
            <NRadioButton value="single_agent">单智能体</NRadioButton>
          </NRadioGroup>
        </NFormItem>

        <div class="form-grid two-columns">
          <NFormItem label="任务名称" path="title">
            <NInput
              v-model:value="createForm.title"
              maxlength="255"
              show-count
              placeholder="例如：完成支付模块交付"
            />
          </NFormItem>
          <NFormItem label="可见范围">
            <NSelect
              v-model:value="createForm.visibility"
              :options="[
                { label: '企业共享', value: 'enterprise_shared' },
                { label: '受限访问', value: 'restricted' },
              ]"
            />
          </NFormItem>
        </div>

        <NFormItem path="objective">
          <template #label>
            <div class="flex items-center justify-between gap-8px w-full">
              <span>任务目标</span>
              <NButton
                size="tiny"
                secondary
                :loading="taskOptimizeLoading"
                :disabled="!createForm.objective.trim()"
                @click="optimizeTaskObjective"
              >
                <template #icon><SvgIcon icon="lucide:sparkles" /></template>
                AI 优化
              </NButton>
            </div>
          </template>
          <NInput
            v-model:value="createForm.objective"
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 6 }"
            maxlength="12000"
            show-count
            placeholder="写明可验证的交付目标和完成边界"
          />
        </NFormItem>
        <NFormItem label="背景与约束" path="background">
          <NInput
            v-model:value="createForm.background"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
            maxlength="12000"
            placeholder="补充上下文、限制条件和已有材料"
          />
        </NFormItem>

        <section
          v-if="createForm.mode === 'multi_agent_template'"
          class="workflow-config"
        >
          <NFormItem label="固定工作流模板">
            <NSelect
              v-model:value="createForm.workflowVersionId"
              :options="workflowOptions"
              placeholder="选择工作流模板"
              :disabled="workflows.length === 0"
            />
          </NFormItem>

          <NAlert
            v-if="workflows.length === 0"
            type="warning"
            :show-icon="true"
          >
            当前没有可用的已发布固定工作流模板。
          </NAlert>

          <template v-if="selectedWorkflow">
            <div class="workflow-meta">
              <span>版本 {{ selectedWorkflow.versionNo }}</span>
              <span>最大并行度 {{ selectedWorkflow.maxParallelism }}</span>
              <span>{{ selectedWorkflow.nodes.length }} 个固定节点</span>
            </div>
            <div class="workflow-nodes">
              <div
                v-for="node in selectedWorkflow.nodes"
                :key="node.key"
                class="workflow-node"
              >
                <span class="node-sequence">{{ node.sequence }}</span>
                <div>
                  <strong>{{ node.key }}</strong>
                  <small>{{ node.role || "汇总节点" }} · 依赖
                    {{ node.dependsOn.join(", ") || "无" }}</small>
                </div>
              </div>
            </div>
            <div class="role-bindings">
              <div
                v-for="role in selectedWorkflow.roles"
                :key="role.key"
                class="role-row"
              >
                <div>
                  <strong>{{ role.name }}</strong>
                  <span>{{ role.key }}</span>
                </div>
                <NSelect
                  v-model:value="createForm.roleAgents[role.key]"
                  filterable
                  :options="agentOptions"
                  :placeholder="`为${role.name}选择已发布 Agent`"
                  class="role-select"
                />
              </div>
            </div>
          </template>
        </section>

        <NFormItem v-else label="执行 Agent">
          <NSelect
            v-model:value="createForm.singleAgentVersionId"
            filterable
            :options="agentOptions"
            placeholder="选择已发布 Agent"
          />
        </NFormItem>

        <div class="form-grid three-columns">
          <NFormItem label="任务分类">
            <NSelect
              v-model:value="createForm.category"
              :options="categoryOptions"
            />
          </NFormItem>
          <NFormItem label="风险等级">
            <NSelect
              v-model:value="createForm.riskLevel"
              :options="riskOptions"
            />
          </NFormItem>
          <NFormItem label="优先属性">
            <NSpace :size="18" class="checkbox-row">
              <NCheckbox v-model:checked="createForm.importance">
                重要
              </NCheckbox>
              <NCheckbox v-model:checked="createForm.urgency">紧急</NCheckbox>
            </NSpace>
          </NFormItem>
        </div>
        <NFormItem label="本次任务资源授权">
          <NSelect
            v-model:value="createForm.resourceKeys"
            multiple
            filterable
            clearable
            :options="taskResourceOptions"
            placeholder="选择本次运行允许使用的工具、Skill、知识库或数据集"
          />
        </NFormItem>
        <NFormItem label="标签">
          <NDynamicTags v-model:value="createForm.tags" :max="32" />
        </NFormItem>
      </NForm>
      <template #footer>
        <div class="modal-actions">
          <NButton :disabled="createSubmitting" @click="createVisible = false">
            取消
          </NButton>
          <NButton
            type="primary"
            :loading="createSubmitting"
            @click="submitTask"
          >
            <template #icon><SvgIcon icon="lucide:check" /></template>
            {{ editingTaskId ? '保存并生成新版本' : '创建任务' }}
          </NButton>
        </div>
      </template>
    </NModal>

    <NDrawer
      v-model:show="detailVisible"
      width="min(860px, calc(100vw - 16px))"
      placement="right"
    >
      <NDrawerContent v-if="activeTask" closable>
        <template #header>
          <div class="drawer-header">
            <div class="drawer-title">
              <strong>{{ activeTask.title }}</strong>
              <span>{{ activeTask.taskKey }}</span>
            </div>
            <NButton type="primary" size="small" @click="openRunModal">
              <template #icon><SvgIcon icon="lucide:play" /></template>
              新建运行
            </NButton>
            <NButton
              v-if="!props.clientMode"
              size="small"
              secondary
              @click="openEdit()"
            >
              <template #icon><SvgIcon icon="lucide:pencil" /></template>
              编辑定义
            </NButton>
          </div>
        </template>

        <section class="detail-section task-overview">
          <p>{{ activeTask.objective }}</p>
          <div class="detail-tags">
            <NTag :type="statusType(activeTask.status)" size="small">
              {{
                statusText(activeTask.status)
              }}
            </NTag>
            <NTag
              :type="riskType(activeTask.riskLevel)"
              size="small"
              :bordered="false"
            >
              {{ activeTask.riskLevel }}
            </NTag>
            <NTag size="small" :bordered="false">
              {{
                modeText(activeTask.orchestrationMode)
              }}
            </NTag>
            <NTag
              v-for="tag in activeTask.tags"
              :key="tag"
              size="small"
              :bordered="false"
            >
              {{ tag }}
            </NTag>
          </div>
          <NDescriptions
            :column="3"
            size="small"
            label-placement="top"
            class="task-descriptions"
          >
            <NDescriptionsItem label="负责人">
              {{ userName(activeTask.ownerId) }}
            </NDescriptionsItem>
            <NDescriptionsItem label="可见范围">
              {{
                (taskVisibility?.visibility || activeTask.visibility) === "enterprise_shared"
                  ? "企业共享"
                  : "受限访问"
              }}
            </NDescriptionsItem>
            <NDescriptionsItem label="共享对象">
              {{ taskVisibility ? `${taskVisibility.participants.length} 位参与人 · ${taskVisibility.accessRules.length} 条 ACL` : "正在读取共享规则" }}
            </NDescriptionsItem>
            <NDescriptionsItem label="创建时间">
              {{
                formatTime(activeTask.createdAt)
              }}
            </NDescriptionsItem>
          </NDescriptions>
        </section>

        <section class="detail-section">
          <div class="section-heading">
            <div>
              <h3>任务版本历史</h3>
              <span>每次保存都会生成不可变版本，运行始终绑定创建时的版本</span>
            </div>
            <NButton quaternary circle title="刷新版本历史" :loading="versionsLoading" @click="loadTaskVersions">
              <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
            </NButton>
          </div>
          <NAlert v-if="versionsError" type="error" :show-icon="true" class="run-alert">
            {{ versionsError }}
          </NAlert>
          <NDataTable
            :columns="versionColumns"
            :data="taskVersions"
            :loading="versionsLoading"
            :row-key="row => row.id"
            :scroll-x="980"
            :max-height="280"
            size="small"
          />
          <NEmpty v-if="!versionsLoading && !versionsError && taskVersions.length === 0" description="暂无版本历史" class="section-empty" />
        </section>

        <section class="detail-section">
          <div class="section-heading">
            <div>
              <h3>运行历史</h3>
              <span>{{ runs.length }} 次不可变执行记录</span>
            </div>
            <NButton
              quaternary
              circle
              title="刷新运行历史"
              :loading="runLoading"
              @click="loadRuns(false)"
            >
              <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
            </NButton>
          </div>
          <NDataTable
            :columns="runColumns"
            :data="runs"
            :loading="runLoading"
            :row-key="(row) => row.id"
            :scroll-x="800"
            :max-height="250"
            size="small"
          />
          <NEmpty
            v-if="!runLoading && runs.length === 0"
            description="该任务尚未运行"
            class="section-empty"
          />
        </section>

        <section v-if="selectedRun" class="detail-section">
          <div class="section-heading run-heading">
            <div>
              <h3>运行 #{{ selectedRun.id }}</h3>
              <span>第 {{ selectedRun.attemptNo }} 次尝试 ·
                {{
                  formatTime(selectedRun.startedAt || selectedRun.createdAt)
                }}</span>
            </div>
            <NSpace align="center">
              <NButton
                v-if="selectedRun.status === 'queued'"
                size="small"
                type="primary"
                secondary
                @click="startQueuedRun(selectedRun)"
              >
                <template #icon><SvgIcon icon="lucide:play" /></template>
                启动
              </NButton>
              <NButton
                v-if="['running', 'preparing', 'waiting_approval', 'waiting_external'].includes(selectedRun.status)"
                size="small"
                secondary
                :loading="runActionLoading"
                @click="applyRunAction('pause', selectedRun)"
              >
                <template #icon><SvgIcon icon="lucide:pause" /></template>
                暂停
              </NButton>
              <NButton
                v-if="selectedRun.status === 'paused'"
                size="small"
                type="primary"
                secondary
                :loading="runActionLoading"
                @click="applyRunAction('resume', selectedRun)"
              >
                <template #icon><SvgIcon icon="lucide:play" /></template>
                恢复
              </NButton>
              <NButton
                v-if="['queued', 'preparing', 'running', 'waiting_approval', 'waiting_external', 'paused'].includes(selectedRun.status)"
                size="small"
                type="warning"
                secondary
                :loading="runActionLoading"
                @click="applyRunAction('cancel', selectedRun)"
              >
                <template #icon><SvgIcon icon="lucide:square" /></template>
                取消
              </NButton>
              <NButton
                v-if="['failed', 'cancelled'].includes(selectedRun.status)"
                size="small"
                type="primary"
                secondary
                :loading="runActionLoading"
                @click="applyRunAction('retry', selectedRun)"
              >
                <template #icon><SvgIcon icon="lucide:rotate-ccw" /></template>
                重试
              </NButton>
              <NButton
                quaternary
                circle
                title="查看运行事件"
                :loading="eventLoading"
                @click="refreshTaskEvents"
              >
                <template #icon><SvgIcon icon="lucide:activity" /></template>
              </NButton>
              <NTag v-if="eventStreamConnecting" size="small" type="info" :bordered="false">
                实时连接中
              </NTag>
              <NTag v-else-if="eventStreamError" size="small" type="warning" :bordered="false" :title="eventStreamError">
                实时流不可用，轮询中
              </NTag>
              <NTag :type="statusType(selectedRun.status)">
                {{
                  statusText(selectedRun.status)
                }}
              </NTag>
            </NSpace>
          </div>
          <NProgress
            v-if="steps.length"
            type="line"
            :percentage="runProgress()"
            :status="
              selectedRun.status === 'failed'
                ? 'error'
                : selectedRun.status === 'completed'
                  ? 'success'
                  : 'default'
            "
            :height="8"
            class="run-progress"
          />
          <NAlert
            v-if="selectedRun.waitReason"
            type="warning"
            :show-icon="true"
            class="run-alert"
          >
            {{ selectedRun.waitReason }}
          </NAlert>
          <NAlert
            v-if="selectedRun.errorSummary"
            type="error"
            :show-icon="true"
            class="run-alert"
          >
            {{ selectedRun.errorSummary }}
          </NAlert>
          <NDataTable
            :columns="stepColumns"
            :data="steps"
            :loading="stepLoading"
            :row-key="(row) => row.id"
            :scroll-x="850"
            :max-height="310"
            size="small"
          />
          <NCollapse v-if="taskEvents.length" class="event-history">
            <NCollapseItem :title="`运行事件（${taskEvents.length}）`" name="events">
              <NTimeline>
                <NTimelineItem
                  v-for="event in taskEvents"
                  :key="event.eventId"
                  :type="event.eventStatus === 'FAILED' ? 'error' : 'default'"
                  :title="event.eventType"
                  :time="formatTime(event.occurredAt)"
                >
                  {{ event.summary || event.eventId }}
                </NTimelineItem>
              </NTimeline>
            </NCollapseItem>
          </NCollapse>
        </section>

        <section class="detail-section">
          <div class="section-heading">
            <div>
              <h3>任务授权与参与人</h3>
              <span>{{ taskResources.length }} 项资源 · {{ taskParticipants.length }} 位参与人 · {{ taskAccessRules.length }} 条 ACL</span>
            </div>
            <NButton quaternary circle title="刷新授权" :loading="governanceLoading" @click="loadTaskGovernance">
              <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
            </NButton>
          </div>
          <div class="governance-toolbar">
            <NSelect v-model:value="participantUserId" filterable clearable :options="aclSubjectOptions" placeholder="选择参与人" />
            <NSelect v-model:value="participantType" :options="participantTypeOptions" />
            <NButton type="primary" secondary :disabled="!participantUserId" @click="addParticipant">添加参与人</NButton>
          </div>
          <NDataTable
            :columns="participantColumns"
            :data="taskParticipants"
            :loading="governanceLoading"
            :row-key="row => row.id"
            :scroll-x="680"
            :max-height="220"
            size="small"
          />
          <div class="governance-toolbar acl-toolbar">
            <NSelect v-model:value="aclSubjectId" filterable clearable :options="aclSubjectOptions" placeholder="ACL 用户" />
            <NSelect v-model:value="aclAction" :options="aclActionOptions" />
            <NSelect v-model:value="aclEffect" :options="[{ label: '允许', value: 'allow' }, { label: '拒绝', value: 'deny' }]" />
            <NButton secondary :disabled="!aclSubjectId" @click="addAccessRule">添加 ACL</NButton>
          </div>
          <NDataTable
            :columns="accessRuleColumns"
            :data="taskAccessRules"
            :loading="governanceLoading"
            :row-key="row => row.id"
            :scroll-x="720"
            :max-height="220"
            size="small"
          />
          <NDataTable
            v-if="governanceVisible"
            :columns="resourceColumns"
            :data="taskResources"
            :loading="governanceLoading"
            :row-key="row => row.id"
            :scroll-x="720"
            :max-height="220"
            size="small"
          />
          <NButton text size="small" @click="governanceVisible = !governanceVisible">
            {{ governanceVisible ? '收起资源快照' : '查看资源快照' }}
          </NButton>
        </section>

        <section v-if="selectedRun" class="detail-section">
          <div class="section-heading delivery-heading">
            <div>
              <h3>交付物与验收</h3>
              <span>{{ artifacts.length }} 个不可变交付版本 ·
                {{ acceptances.length }} 条验收事实</span>
            </div>
            <NSpace>
              <NButton
                size="small"
                type="success"
                secondary
                :disabled="checkedArtifactIds.length === 0"
                @click="openAcceptance('passed')"
              >
                <template #icon><SvgIcon icon="lucide:badge-check" /></template>
                通过
              </NButton>
              <NButton
                size="small"
                type="warning"
                secondary
                :disabled="checkedArtifactIds.length === 0"
                @click="openAcceptance('rework')"
              >
                <template #icon><SvgIcon icon="lucide:rotate-ccw" /></template>
                返工
              </NButton>
              <NButton
                size="small"
                type="error"
                secondary
                :disabled="checkedArtifactIds.length === 0"
                @click="openAcceptance('rejected')"
              >
                <template #icon><SvgIcon icon="lucide:ban" /></template>
                拒绝
              </NButton>
              <NButton
                size="small"
                secondary
                :disabled="checkedArtifactIds.length === 0"
                @click="openAcceptance('taken_over')"
              >
                <template #icon><SvgIcon icon="lucide:hand" /></template>
                接管
              </NButton>
              <NButton
                quaternary
                circle
                title="刷新交付物"
                :loading="artifactLoading"
                @click="loadDelivery"
              >
                <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
              </NButton>
            </NSpace>
          </div>
          <NDataTable
            v-model:checked-row-keys="checkedArtifactIds"
            :columns="artifactColumns"
            :data="artifacts"
            :loading="artifactLoading"
            :row-key="(row) => row.id"
            :scroll-x="860"
            :max-height="260"
            size="small"
          />
          <NEmpty
            v-if="!artifactLoading && artifacts.length === 0"
            description="该运行尚未产出交付物"
            class="section-empty"
          />

          <NCollapse v-if="acceptances.length" class="acceptance-history">
            <NCollapseItem
              :title="`验收历史（${acceptances.length}）`"
              name="acceptances"
            >
              <NDataTable
                :columns="acceptanceColumns"
                :data="acceptances"
                :row-key="(row) => row.id"
                :scroll-x="900"
                :max-height="220"
                size="small"
              />
            </NCollapseItem>
          </NCollapse>
        </section>

        <section v-if="activeStep" class="detail-section step-inspector">
          <div class="section-heading">
            <div>
              <h3>节点检查 · {{ activeStep.key }}</h3>
              <span>Agent 版本
                {{
                  activeStep.agentVersionId
                    ? agentName(activeStep.agentVersionId)
                    : "无"
                }}</span>
            </div>
            <NButton
              quaternary
              circle
              title="关闭节点检查"
              @click="activeStep = null"
            >
              <template #icon><SvgIcon icon="lucide:x" /></template>
            </NButton>
          </div>
          <NDescriptions :column="2" size="small" label-placement="top">
            <NDescriptionsItem label="输入摘要">
              {{
                activeStep.inputSummary || "-"
              }}
            </NDescriptionsItem>
            <NDescriptionsItem label="输出摘要">
              {{
                activeStep.outputSummary || "-"
              }}
            </NDescriptionsItem>
            <NDescriptionsItem label="开始时间">
              {{
                formatTime(activeStep.startedAt)
              }}
            </NDescriptionsItem>
            <NDescriptionsItem label="结束时间">
              {{
                formatTime(activeStep.finishedAt)
              }}
            </NDescriptionsItem>
          </NDescriptions>
          <NCollapse
            v-if="
              activeStep.output &&
                Object.keys(activeStep.output as object).length
            "
            class="output-collapse"
          >
            <NCollapseItem title="结构化输出" name="output">
              <NCode
                :code="JSON.stringify(activeStep.output, null, 2)"
                language="json"
                word-wrap
              />
            </NCollapseItem>
          </NCollapse>
        </section>
      </NDrawerContent>
    </NDrawer>

    <NModal
      v-model:show="versionDetailVisible"
      preset="card"
      title="任务版本快照"
      class="version-detail-modal"
      :style="{ width: 'min(760px, calc(100vw - 32px))' }"
      :bordered="false"
    >
      <template v-if="selectedVersion">
        <NDescriptions :column="2" size="small" label-placement="top">
          <NDescriptionsItem label="版本">v{{ selectedVersion.versionNo }}</NDescriptionsItem>
          <NDescriptionsItem label="内容哈希"><code>{{ selectedVersion.contentHash }}</code></NDescriptionsItem>
          <NDescriptionsItem label="创建人">{{ userName(selectedVersion.createdBy) }}</NDescriptionsItem>
          <NDescriptionsItem label="创建时间">{{ formatTime(selectedVersion.createdAt) }}</NDescriptionsItem>
          <NDescriptionsItem label="标题" :span="2">{{ selectedVersion.title }}</NDescriptionsItem>
          <NDescriptionsItem label="目标" :span="2">{{ selectedVersion.objective }}</NDescriptionsItem>
        </NDescriptions>
        <NCollapse class="version-snapshot-collapse">
          <NCollapseItem title="上下文快照" name="context"><NCode :code="JSON.stringify(selectedVersion.contextSnapshot, null, 2)" language="json" word-wrap /></NCollapseItem>
          <NCollapseItem title="资源快照" name="resources"><NCode :code="JSON.stringify(selectedVersion.resourceSnapshot, null, 2)" language="json" word-wrap /></NCollapseItem>
          <NCollapseItem title="验收与输入快照" name="acceptance"><NCode :code="JSON.stringify({ acceptance: selectedVersion.acceptanceSnapshot, input: selectedVersion.inputSnapshot }, null, 2)" language="json" word-wrap /></NCollapseItem>
        </NCollapse>
      </template>
    </NModal>

    <NModal
      v-model:show="taskOptimizeVisible"
      preset="card"
      title="AI 优化任务指令"
      class="task-optimize-modal"
      :style="{ width: 'min(1080px, calc(100vw - 32px))' }"
      :bordered="false"
    >
      <NAlert v-if="taskOptimizeError" type="error" :show-icon="true" class="run-alert">
        {{ taskOptimizeError }}
      </NAlert>
      <NGrid :cols="24" :x-gap="12" :y-gap="12" responsive="screen" item-responsive>
        <NGi span="24 m:12">
          <section class="task-optimize-panel">
            <div class="task-optimize-label">当前任务目标</div>
            <pre>{{ taskOptimizeOriginal }}</pre>
          </section>
        </NGi>
        <NGi span="24 m:12">
          <section class="task-optimize-panel">
            <div class="task-optimize-label flex items-center justify-between gap-8px">
              <span>优化建议</span>
              <span v-if="taskOptimizeResult" class="font-400 op-65">
                {{ taskOptimizeResult.model_name || taskOptimizeResult.provider_model }} · {{ taskOptimizeResult.elapsed_ms }} ms
              </span>
            </div>
            <div v-if="taskOptimizeLoading" class="task-optimize-loading">
              <NSpin size="small" />
              <span>正在生成优化建议</span>
            </div>
            <pre v-else-if="taskOptimizeResult">{{ taskOptimizeResult.optimized_content }}</pre>
            <div v-else-if="!taskOptimizeError" class="task-optimize-loading" />
          </section>
        </NGi>
      </NGrid>
      <template #footer>
        <div class="modal-actions">
          <NButton :disabled="taskOptimizeLoading" @click="taskOptimizeVisible = false">关闭</NButton>
          <NButton secondary :loading="taskOptimizeLoading" @click="optimizeTaskObjective">重新生成</NButton>
          <NButton
            type="primary"
            :disabled="!taskOptimizeResult || taskOptimizeLoading"
            @click="applyTaskOptimization"
          >
            应用到任务目标
          </NButton>
        </div>
      </template>
    </NModal>

    <NModal
      v-model:show="runModalVisible"
      preset="card"
      title="创建并启动运行"
      class="run-modal"
      :style="{ width: 'min(640px, calc(100vw - 32px))' }"
      :bordered="false"
      :mask-closable="!runSubmitting"
    >
      <NFormItem label="本次运行输入" required>
        <NInput
          v-model:value="runInput"
          type="textarea"
          :autosize="{ minRows: 5, maxRows: 12 }"
          maxlength="100000"
          show-count
          placeholder="输入本次执行的具体要求"
        />
      </NFormItem>
      <template #footer>
        <div class="modal-actions">
          <NButton :disabled="runSubmitting" @click="runModalVisible = false">
            取消
          </NButton>
          <NButton type="primary" :loading="runSubmitting" @click="submitRun">
            <template #icon><SvgIcon icon="lucide:play" /></template>
            创建并启动
          </NButton>
        </div>
      </template>
    </NModal>

    <NModal
      v-model:show="acceptanceVisible"
      preset="card"
      :title="acceptanceText(acceptanceResult)"
      class="acceptance-modal"
      :style="{ width: 'min(620px, calc(100vw - 32px))' }"
      :bordered="false"
      :mask-closable="!acceptanceSubmitting"
    >
      <NAlert
        :type="acceptanceType(acceptanceResult)"
        :show-icon="true"
        class="run-alert"
      >
        本次决定将记录为不可修改的验收事实，并同步推进任务状态。
      </NAlert>
      <NDescriptions
        :column="1"
        size="small"
        label-placement="left"
        class="acceptance-summary"
      >
        <NDescriptionsItem label="运行">
          #{{ selectedRun?.id }}
        </NDescriptionsItem>
        <NDescriptionsItem label="交付物">
          {{ checkedArtifactIds.map((id) => `#${id}`).join(", ") }}
        </NDescriptionsItem>
      </NDescriptions>
      <NFormItem label="验收意见" :required="acceptanceResult !== 'passed'">
        <NInput
          v-model:value="acceptanceComment"
          type="textarea"
          maxlength="4000"
          show-count
          :autosize="{ minRows: 3, maxRows: 8 }"
          placeholder="记录验收依据、返工要求或接管原因"
        />
      </NFormItem>
      <template #footer>
        <div class="modal-actions">
          <NButton
            :disabled="acceptanceSubmitting"
            @click="acceptanceVisible = false"
          >
            取消
          </NButton>
          <NButton
            :type="acceptanceType(acceptanceResult)"
            :loading="acceptanceSubmitting"
            @click="submitAcceptance"
          >
            <template #icon><SvgIcon icon="lucide:check" /></template>
            确认提交
          </NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped lang="scss">
.task-center-container {
  min-height: 100%;
  padding: 18px;
  background: var(--n-color);
  --task-color: var(--n-color);
  --task-color-modal: var(--n-color-modal);
  --task-border-color: var(--n-border-color);
  --task-text-color: var(--n-text-color);
  --task-text-color-2: var(--n-text-color-2);
  --task-text-color-3: var(--n-text-color-3);
}

.task-center-container.client-mode {
  min-height: 100%;
  padding: 0;
  background: #f6f7f9;
}

.project-context {
  display: flex;
  min-height: 58px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 9px 28px 12px;
  border-bottom: 1px solid var(--n-border-color);
}

.project-context-main {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;

  > div {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 2px;
  }

  strong,
  span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  strong {
    font-size: 13px;
    font-weight: 650;
  }

  span {
    color: var(--n-text-color-3);
    font-size: 11px;
  }
}

.project-context-icon {
  display: inline-flex;
  width: 32px;
  height: 32px;
  flex: 0 0 32px;
  align-items: center;
  justify-content: center;
  border: 1px solid color-mix(in srgb, var(--n-primary-color) 28%, var(--n-border-color));
  border-radius: 7px;
  background: color-mix(in srgb, var(--n-primary-color) 8%, var(--n-color));
  color: var(--n-primary-color);
}

.page-header,
.section-heading,
.modal-actions,
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-header {
  gap: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--n-border-color);
}

.page-title {
  margin: 0;
  color: var(--n-text-color);
  font-size: 20px;
  font-weight: 650;
  letter-spacing: 0;
}

.page-desc {
  margin: 5px 0 0;
  color: var(--n-text-color-3);
  font-size: 13px;
}

.summary-band {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 16px 0;
  border-block: 1px solid var(--n-border-color);
  background: var(--n-color-modal);

  > div {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    min-width: 0;
    padding: 13px 18px;
    border-right: 1px solid var(--n-border-color);

    &:last-child {
      border-right: 0;
    }
  }

  span {
    overflow: hidden;
    color: var(--n-text-color-3);
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  strong {
    color: var(--n-text-color);
    font-size: 22px;
    font-variant-numeric: tabular-nums;
  }

  .error-number {
    color: #d03050;
  }
}

.main-view-tabs {
  margin: 14px 0 16px;
}

.main-view-tab {
  align-items: center;

  .svg-icon {
    font-size: 16px;
  }
}

.main-view-tab-count {
  display: inline-flex;
  min-width: 20px;
  height: 20px;
  padding: 0 5px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--n-primary-color) 12%, var(--n-color));
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.history-view {
  min-width: 0;
  padding-bottom: 20px;
}

.client-mode .main-view-tabs {
  margin-inline: 28px;
}

.client-mode .history-view {
  margin: 18px 28px 28px;
}

.history-toolbar {
  display: flex;
  min-width: 0;
  margin-bottom: 14px;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.history-search {
  width: min(360px, 100%);
}

.history-filter {
  width: 150px;
}

.history-task-filter {
  width: min(240px, 100%);
}

.history-date-input {
  height: 34px;
  min-width: 190px;
  padding: 0 10px;
  border: 1px solid var(--n-border-color);
  border-radius: 3px;
  background: var(--n-color);
  color: var(--n-text-color);
  font: inherit;
  font-size: 12px;
  outline: 0;

  &:focus {
    border-color: var(--n-primary-color);
    box-shadow: 0 0 0 2px color-mix(in srgb, var(--n-primary-color) 16%, transparent);
  }
}

.history-total {
  margin-left: auto;
  color: var(--n-text-color-3);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.history-alert {
  margin-bottom: 14px;
}

.history-alert-content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.history-loading {
  min-height: 180px;
}

.history-loading-copy {
  display: grid;
  min-height: 180px;
  color: var(--n-text-color-3);
  font-size: 13px;
  place-items: center;
}

.history-empty {
  padding: 58px 0;
}

.history-footer {
  display: flex;
  min-height: 44px;
  margin-top: 12px;
  align-items: center;
  justify-content: space-between;
  color: var(--n-text-color-3);
  font-size: 12px;
  gap: 12px;
}

.toolbar {
  justify-content: flex-start;
  gap: 10px;
  margin-bottom: 14px;
}

.client-mode .toolbar {
  min-height: 42px;
  margin: 0;
  padding: 0 28px;
  border-bottom: 0;
  background: transparent;
  gap: 10px;
}

.client-mode .task-list-shell {
  min-width: 0;
  margin: 18px 28px 28px;
}

.search-control {
  width: min(360px, 36vw);
}

.client-mode .search-control {
  width: min(334px, 30vw);
}

.filter-control {
  width: 160px;
}

.client-mode .filter-control {
  width: 124px;
}

.client-view-control {
  min-width: 122px;
  justify-content: space-between;
  gap: 8px;
}

.client-mode .client-view-control {
  min-width: 148px;
  margin-left: auto;
}

.view-control-chevron {
  color: var(--task-text-color-3);
  font-size: 13px;
}

.result-count {
  margin-left: auto;
  color: var(--n-text-color-3);
  font-size: 12px;
}

.view-switch {
  margin-left: 4px;
}

.kanban-board {
  display: grid;
  min-height: 520px;
  padding: 0 24px 18px;
  overflow-x: auto;
  grid-auto-columns: 260px;
  grid-auto-flow: column;
  gap: 12px;
  overscroll-behavior-x: contain;
  --n-color: var(--task-color);
  --n-color-modal: var(--task-color-modal);
  --n-border-color: var(--task-border-color);
  --n-text-color: var(--task-text-color);
  --n-text-color-2: var(--task-text-color-2);
  --n-text-color-3: var(--task-text-color-3);
}

.client-mode .kanban-board {
  min-height: calc(100dvh - 320px);
  margin-top: 36px;
  padding-inline: 28px;
  grid-template-columns: repeat(3, minmax(240px, 1fr));
  grid-auto-columns: unset;
  grid-auto-flow: unset;
  gap: 16px;
  justify-content: start;
  background: #f6f7f9;
}

.kanban-shell {
  position: relative;
}

.kanban-loading {
  position: absolute;
  inset: 0;
  display: grid;
  background: rgb(246 247 249 / 58%);
  color: var(--n-primary-color);
  pointer-events: none;
  place-items: center;

  .svg-icon {
    font-size: 26px;
    animation: spin 1s linear infinite;
  }
}

.kanban-column {
  display: flex;
  min-height: 500px;
  border: 1px solid var(--n-border-color);
  border-radius: 6px;
  background: color-mix(in srgb, var(--n-color) 94%, var(--n-text-color) 6%);
  flex-direction: column;
  transition:
    border-color 160ms ease,
    background-color 160ms ease;
}

.client-mode .kanban-column {
  min-height: 100%;
  border: 1px solid #e7e8ec;
  border-radius: 8px;
  background: #f1f2f4;
}

.kanban-column.is-drop-target {
  border-color: #18a058;
  background: color-mix(in srgb, var(--n-color) 90%, #18a058 10%);
}

.kanban-column-header {
  display: flex;
  min-height: 54px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--n-border-color);
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.kanban-column-title {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;

  strong {
    overflow: hidden;
    color: var(--n-text-color);
    font-size: 13px;
    font-weight: 650;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.column-status-dot {
  width: 8px;
  height: 8px;
  flex: 0 0 8px;
  border-radius: 50%;
  background: #9ca3af;
}

.status-ready { background: #4f8cff; }
.status-running { background: #6d5dfc; }
.status-blocked { background: #e57373; }
.status-completed { background: #49a078; }

.column-count {
  display: inline-flex;
  min-width: 21px;
  height: 21px;
  padding: 0 6px;
  border-radius: 999px;
  background: #e4e5e9;
  color: var(--n-text-color-3);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  align-items: center;
  justify-content: center;
}

.kanban-column-actions {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.column-action {
  display: inline-flex;
  width: 25px;
  height: 25px;
  padding: 0;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--n-text-color-3);
  cursor: pointer;
  align-items: center;
  justify-content: center;

  &:hover,
  &:focus-visible {
    background: #e5e6ea;
    color: var(--n-text-color);
    outline: 0;
  }

  .svg-icon {
    font-size: 15px;
  }
}

.kanban-card-list {
  display: flex;
  min-height: 180px;
  padding: 10px;
  flex: 1;
  flex-direction: column;
  gap: 10px;
}

.task-card {
  display: flex;
  min-height: 108px;
  padding: 13px;
  border: 1px solid var(--n-border-color);
  border-radius: 7px;
  background: var(--n-color);
  color: var(--n-text-color);
  cursor: grab;
  flex-direction: column;
  transition:
    border-color 140ms ease,
    opacity 140ms ease,
    transform 140ms ease,
    box-shadow 140ms ease;

  &:hover,
  &:focus-visible {
    border-color: color-mix(in srgb, #18a058 70%, var(--n-border-color));
    box-shadow: 0 3px 10px rgb(31 35 41 / 8%);
    outline: 0;
    transform: translateY(-1px);
  }

  &:active {
    cursor: grabbing;
  }

  &.is-dragging {
    opacity: 0.45;
  }

  &.is-updating {
    cursor: wait;
    opacity: 0.68;
  }

  p {
    display: -webkit-box;
    margin: 7px 0 10px;
    overflow: hidden;
    color: var(--n-text-color-3);
    font-size: 12px;
    line-height: 1.55;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }
}

.task-card-heading,
.task-card-meta,
.task-priority-flags {
  display: flex;
  align-items: center;
}

.task-card-heading {
  justify-content: space-between;
  gap: 8px;

  strong {
    display: -webkit-box;
    overflow: hidden;
    font-size: 14px;
    font-weight: 600;
    line-height: 1.4;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }
}

.task-card-meta {
  justify-content: flex-start;
  color: var(--n-text-color-3);
  font-size: 11px;
  gap: 8px;
}

.task-card-key {
  max-width: 72%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-card-footer {
  display: flex;
  min-height: 24px;
  margin-top: auto;
  align-items: flex-end;
  justify-content: space-between;
  gap: 8px;
}

.task-priority-flags {
  gap: 10px;

  span {
    display: inline-flex;
    align-items: center;
    color: var(--n-text-color-2);
    font-size: 11px;
    gap: 4px;
  }

  .svg-icon {
    font-size: 13px;
  }
}

.task-owner-avatar {
  display: inline-flex;
  margin-left: auto;
  width: 24px;
  height: 24px;
  flex: 0 0 24px;
  align-items: center;
  justify-content: center;
  border: 2px solid #fff;
  border-radius: 50%;
  background: #e6e7ff;
  color: #6156d9;
  font-size: 10px;
  font-weight: 700;
}

.card-spinner {
  flex: none;
  animation: spin 1s linear infinite;
}

.kanban-empty,
.quadrant-empty {
  display: grid;
  min-height: 180px;
  color: var(--n-text-color-3);
  font-size: 12px;
  place-items: center;
}

.kanban-empty {
  align-content: center;
  gap: 6px;
  text-align: center;

  .svg-icon {
    margin-bottom: 4px;
    color: #9198a6;
    font-size: 32px;
  }

  strong {
    color: var(--n-text-color-2);
    font-size: 13px;
    font-weight: 600;
  }

  span {
    color: var(--n-text-color-3);
    font-size: 11px;
  }
}

.quadrant-spin {
  display: block;
}

.quadrant-board {
  display: grid;
  min-height: 560px;
  margin: 16px 24px 24px;
  gap: 14px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  --n-color: var(--task-color);
  --n-color-modal: var(--task-color-modal);
  --n-border-color: var(--task-border-color);
  --n-text-color: var(--task-text-color);
  --n-text-color-2: var(--task-text-color-2);
  --n-text-color-3: var(--task-text-color-3);
}

.client-mode .quadrant-board {
  min-height: calc(100dvh - 250px);
  margin: 18px 28px 28px;
}

.quadrant {
  display: flex;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  border: 1px solid var(--n-border-color);
  border-radius: 9px;
  background: var(--n-color);
  flex-direction: column;
}

.quadrant-header,
.quadrant-title,
.quadrant-task-status {
  display: flex;
  align-items: center;
}

.quadrant-header {
  min-height: 70px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--n-border-color);
  background: #f5f6f8;
  justify-content: space-between;
  gap: 12px;
}

.quadrant-title {
  min-width: 0;
  gap: 9px;

  > .svg-icon {
    flex: none;
    color: currentcolor;
    font-size: 19px;
  }

  > div {
    display: flex;
    min-width: 0;
    flex-direction: column;
  }

  strong {
    font-size: 14px;
    font-weight: 650;
  }

  span {
    color: var(--n-text-color-3);
    font-size: 11px;
  }
}

.quadrant-important-urgent .quadrant-header {
  background: #fff1f1;
  color: #c24141;
}

.quadrant-important .quadrant-header {
  background: #fff7ec;
  color: #d97706;
}

.quadrant-urgent .quadrant-header {
  background: #f0faf2;
  color: #2f9e4f;
}

.quadrant-normal .quadrant-header {
  background: #f1f5ff;
  color: #4263d6;
}

.quadrant-count {
  display: inline-flex;
  width: 26px;
  height: 26px;
  flex: 0 0 26px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: rgb(255 255 255 / 72%);
  color: currentcolor;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  font-weight: 650;
}

.quadrant-tasks {
  display: flex;
  min-height: 0;
  overflow-y: auto;
  padding: 12px;
  flex: 1;
  flex-direction: column;
  gap: 8px;
}

.quadrant-task {
  display: grid;
  width: 100%;
  min-width: 0;
  min-height: 48px;
  padding: 10px 11px;
  border: 1px solid var(--n-border-color);
  border-radius: 6px;
  background: var(--n-color);
  color: var(--n-text-color);
  cursor: pointer;
  grid-template-columns: auto minmax(0, 1fr) minmax(92px, 0.7fr) 26px;
  align-items: center;
  gap: 10px;
  text-align: left;
  transition:
    border-color 140ms ease,
    transform 140ms ease;

  &:hover,
  &:focus-visible {
    border-color: color-mix(in srgb, #18a058 70%, var(--n-border-color));
    outline: 0;
    transform: translateY(-1px);
  }
}

.quadrant-task-key {
  overflow: hidden;
  color: var(--n-text-color-3);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.quadrant-task-title {
  overflow: hidden;
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.quadrant-task-status {
  justify-content: flex-start;
  min-width: 54px;

  :deep(.n-tag) {
    margin: 0;
    font-size: 10px;
  }
}

.quadrant-task-avatar {
  display: inline-flex;
  width: 24px;
  height: 24px;
  flex: 0 0 24px;
  align-items: center;
  justify-content: center;
  border: 2px solid #fff;
  border-radius: 50%;
  background: #e6e7ff;
  color: #6156d9;
  font-size: 10px;
  font-weight: 700;
}

.quadrant-empty {
  min-height: 220px;
  align-content: center;
  gap: 6px;
  text-align: center;

  .svg-icon {
    margin-bottom: 2px;
    color: #9aa1ad;
    font-size: 29px;
  }

  span {
    color: var(--n-text-color-2);
    font-size: 12px;
  }
}

.empty-state,
.section-empty {
  padding: 42px 0;
}

:deep(.primary-cell) {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;

  strong {
    overflow: hidden;
    color: var(--n-text-color);
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    overflow: hidden;
    color: var(--n-text-color-3);
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &.compact {
    gap: 1px;
  }
}

.form-grid {
  display: grid;
  gap: 16px;

  &.two-columns {
    grid-template-columns: minmax(0, 2fr) minmax(180px, 1fr);
  }

  &.three-columns {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

.workflow-config {
  margin-bottom: 18px;
  padding: 16px;
  border: 1px solid var(--n-border-color);
  border-radius: 6px;
  background: var(--n-color-modal);
}

.workflow-meta,
.detail-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.workflow-meta {
  margin: -4px 0 12px;

  span {
    color: var(--n-text-color-3);
    font-size: 12px;
  }
}

.workflow-nodes {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 14px;
}

.workflow-node {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 10px;
  padding: 9px 10px;
  border-left: 3px solid #18a058;
  background: color-mix(in srgb, var(--n-color) 92%, #18a058 8%);

  .node-sequence {
    width: 22px;
    color: #18a058;
    font-weight: 700;
    text-align: center;
  }

  div {
    display: flex;
    min-width: 0;
    flex-direction: column;
  }

  strong,
  small {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    color: var(--n-text-color-3);
  }
}

.role-bindings {
  border-top: 1px solid var(--n-border-color);
}

.role-row {
  display: grid;
  grid-template-columns: minmax(150px, 0.7fr) minmax(280px, 1.3fr);
  align-items: center;
  gap: 16px;
  padding: 11px 0;
  border-bottom: 1px solid var(--n-border-color);

  > div {
    display: flex;
    min-width: 0;
    flex-direction: column;
  }

  span {
    color: var(--n-text-color-3);
    font-size: 12px;
  }
}

.checkbox-row {
  min-height: 34px;
  align-items: center;
}

.drawer-header {
  display: flex;
  width: 100%;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.drawer-title {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;

  strong {
    overflow: hidden;
    max-width: 520px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    color: var(--n-text-color-3);
    font-size: 12px;
    font-weight: 400;
  }
}

.detail-section {
  padding: 18px 0;
  border-bottom: 1px solid var(--n-border-color);

  &:first-child {
    padding-top: 0;
  }

  h3 {
    margin: 0;
    color: var(--n-text-color);
    font-size: 15px;
    font-weight: 650;
    letter-spacing: 0;
  }
}

.task-overview p {
  margin: 0 0 12px;
  color: var(--n-text-color-2);
  line-height: 1.65;
  white-space: pre-wrap;
}

.task-descriptions {
  margin-top: 16px;
}

.section-heading {
  gap: 16px;
  margin-bottom: 12px;

  > div {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 3px;
  }

  span {
    color: var(--n-text-color-3);
    font-size: 12px;
  }
}

.run-heading {
  > div:last-child {
    flex-direction: row;
  }
}

.run-progress,
.run-alert {
  margin-bottom: 12px;
}

.delivery-heading {
  align-items: flex-start;

  > :last-child {
    justify-content: flex-end;
  }
}

.acceptance-history {
  margin-top: 14px;
}

.acceptance-summary {
  margin-bottom: 14px;
}

.task-optimize-panel {
  min-width: 0;
  min-height: 180px;
  padding: 12px;
  border: 1px solid var(--n-border-color);
  border-radius: 6px;
  background: var(--n-color-modal);

  pre {
    max-height: 360px;
    margin: 0;
    overflow: auto;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    font-family: var(--n-font-family-mono, ui-monospace, SFMono-Regular, Consolas, monospace);
    font-size: 13px;
    line-height: 1.6;
  }
}

.task-optimize-label {
  margin-bottom: 8px;
  color: var(--n-text-color-2);
  font-size: 13px;
  font-weight: 650;
}

.task-optimize-loading {
  display: flex;
  min-height: 120px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--n-text-color-3);
}

.governance-toolbar {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) 150px auto;
  gap: 10px;
  margin-bottom: 12px;
}

.acl-toolbar {
  grid-template-columns: minmax(180px, 1fr) 120px 120px auto;
  margin-top: 16px;
}

.event-history {
  margin-top: 14px;
}

.step-inspector {
  border-bottom: 0;
}

.output-collapse {
  margin-top: 12px;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (width <= 1240px) and (width > 760px) {
  .project-context {
    padding-inline: 20px;
  }

  .client-mode .toolbar {
    display: grid;
    grid-template-columns: minmax(180px, 1fr) repeat(2, minmax(104px, 124px));
  }

  .client-mode .search-control {
    width: 100%;
  }

  .client-mode .filter-control {
    width: 100%;
    min-width: 0;
  }

  .client-mode .client-view-control {
    margin-left: 0;
    justify-self: start;
  }

  .client-mode .client-refresh-task,
  .client-mode .client-create-task {
    justify-self: end;
  }
}

@media (max-width: 760px) {
  .task-center-container {
    padding: 12px;
  }

  .task-center-container.client-mode {
    padding: 0;
  }

  .client-mode .main-view-tabs {
    margin-inline: 12px;
  }

  .client-mode .history-view {
    margin: 12px;
  }

  .project-context-main span {
    white-space: normal;
  }

  .project-context {
    padding: 9px 12px 12px;
  }

  .page-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .summary-band {
    grid-template-columns: repeat(2, minmax(0, 1fr));

    > div:nth-child(2) {
      border-right: 0;
    }

    > div:nth-child(-n + 2) {
      border-bottom: 1px solid var(--n-border-color);
    }
  }

  .main-view-tabs {
    margin: 10px 0 12px;
  }

  .main-view-tab {
    flex: 1;
    justify-content: center;
    padding-inline: 8px;
  }

  .history-toolbar {
    align-items: stretch;
  }

  .history-search,
  .history-filter,
  .history-task-filter,
  .history-date-input {
    width: 100%;
    min-width: 0;
  }

  .history-toolbar > .n-button {
    flex: 1;
  }

  .history-total {
    width: 100%;
    margin-left: 0;
  }

  .history-footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .toolbar {
    align-items: stretch;
    flex-wrap: wrap;
  }

  .search-control,
  .filter-control {
    width: 100%;
  }

  .client-mode .toolbar {
    display: flex;
    min-height: 0;
    padding: 12px;
  }

  .client-mode .search-control,
  .client-mode .filter-control {
    width: min(100%, 240px);
    flex: 1 1 180px;
  }

  .client-mode .client-view-control {
    flex: 0 0 auto;
    margin-left: 0;
  }

  .client-mode .client-create-task {
    margin-left: auto;
  }

  .client-mode .task-list-shell {
    margin: 0 12px 16px;
  }

  .result-count {
    width: 100%;
    margin-left: 0;
  }

  .view-switch {
    margin-left: 0;
  }

  .kanban-board {
    min-height: 470px;
    padding-inline: 12px;
    grid-auto-columns: min(82vw, 280px);
  }

  .client-mode .kanban-board {
    min-height: calc(100dvh - 170px);
    margin-top: 12px;
    grid-template-columns: repeat(3, minmax(250px, 82vw));
    grid-auto-flow: column;
    grid-auto-columns: minmax(250px, 82vw);
    gap: 12px;
  }

  .quadrant-board {
    min-height: 0;
    margin: 12px;
    grid-template-columns: minmax(0, 1fr);
    gap: 10px;
  }

  .quadrant {
    min-height: 220px;
  }

  .quadrant-tasks {
    max-height: none;
    padding: 10px;
  }

  .quadrant-task {
    min-height: 44px;
    padding: 8px;
    grid-template-columns: auto minmax(0, 1fr) minmax(72px, 0.65fr) 24px;
    gap: 6px;
  }

  .quadrant-task-status {
    min-width: 0;
  }

  .quadrant-task-avatar {
    width: 22px;
    height: 22px;
    flex-basis: 22px;
  }

  .form-grid.two-columns,
  .form-grid.three-columns,
  .workflow-nodes,
  .role-row {
    grid-template-columns: 1fr;
  }

  .delivery-heading {
    align-items: stretch;
    flex-direction: column;

    > :last-child {
      justify-content: flex-start;
    }
  }

  .governance-toolbar,
  .acl-toolbar {
    grid-template-columns: 1fr;
  }
}
</style>
