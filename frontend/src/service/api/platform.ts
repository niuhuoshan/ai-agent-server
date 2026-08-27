import { request } from '../request';
import { getAuthorization } from '../request/shared';
import { getServiceBaseURL } from '@/utils/service';

export type ApprovalStatus = 'pending' | 'approved' | 'rejected' | 'revoked' | 'expired';
export type AuditDecision = 'allow' | 'deny' | 'approval_required' | 'success' | 'failure';
export type NotificationCategory = 'task' | 'approval' | 'run' | 'artifact' | 'acceptance' | 'system';
export type RiskLevel = 'R0' | 'R1' | 'R2' | 'R3';
export type RiskDisposition = 'allow' | 'approval_required' | 'deny';
export type RiskPolicyStatus = 'active' | 'disabled';

export interface ApprovalView {
  id: string;
  taskId: string;
  runId: string;
  stepId: string;
  riskLevel: 'R0' | 'R1' | 'R2' | 'R3';
  actionSummary: string;
  inputSummary: string | null;
  impactScope: string | null;
  status: ApprovalStatus;
  requestedBy: string;
  reviewerId: string | null;
  reviewComment: string | null;
  expiresAt: string | null;
  decidedAt: string | null;
  createdAt: string;
}

export interface ApprovalDecisionResult {
  approval: ApprovalView;
  replayed: boolean;
  runtimeResumed: boolean;
}

export interface AuditEventView {
  id: string;
  traceId: string | null;
  actorType: 'user' | 'service_account' | 'application' | 'agent' | 'system';
  actorId: string | null;
  action: string;
  resourceType: string | null;
  resourceId: string | null;
  taskId: string | null;
  runId: string | null;
  decision: AuditDecision;
  decisionReason: string | null;
  createdAt: string;
}

export interface NotificationView {
  id: string;
  category: NotificationCategory;
  level: 'info' | 'success' | 'warning' | 'error';
  title: string;
  content: string | null;
  resourceType: string | null;
  resourceId: string | null;
  readAt: string | null;
  createdAt: string;
}

export interface RiskPolicyView {
  id: string;
  policyKey: string;
  name: string;
  resourceType: string;
  action: string;
  riskLevel: RiskLevel;
  disposition: RiskDisposition;
  approvalRole: string | null;
  notifyEnabled: boolean;
  priority: number;
  description: string | null;
  status: RiskPolicyStatus;
  createdAt: string;
  updatedAt: string | null;
}

export interface SaveRiskPolicyPayload {
  policyKey: string;
  name: string;
  resourceType: string;
  action: string;
  riskLevel: RiskLevel;
  disposition: RiskDisposition;
  approvalRole?: string;
  notifyEnabled: boolean;
  priority: number;
  description?: string;
  status: RiskPolicyStatus;
}

export interface RiskPolicySearchParams {
  resourceType?: string;
  riskLevel?: RiskLevel;
  status?: RiskPolicyStatus;
  search?: string;
  limit?: number;
}

export interface AuditSearchParams {
  actorType?: string;
  actorId?: string;
  action?: string;
  resourceType?: string;
  resourceId?: string;
  taskId?: string;
  runId?: string;
  decision?: string;
  beforeId?: string;
  limit?: number;
}

export interface WorkflowRoleView {
  key: string;
  name: string;
}

export interface WorkflowNodeView {
  key: string;
  type: 'agent' | 'aggregate';
  role: string | null;
  sequence: number;
  dependsOn: string[];
  instruction: string;
}

export interface WorkflowTemplateView {
  workflowId: string;
  versionId: string;
  versionNo: number;
  key: 'supervisor_executor' | 'delivery_team';
  name: string;
  contentHash: string;
  maxParallelism: number;
  roles: WorkflowRoleView[];
  nodes: WorkflowNodeView[];
}

export type AgentEngineType = 'agentscope_java';

export interface AgentOptionView {
  id: string;
  agentKey: string;
  name: string;
  description: string | null;
  agentType: string;
  engineType: AgentEngineType;
  avatarUrl: string | null;
  systemAgent: boolean;
  defaultAgent: boolean;
  status: string;
  ownerId: string;
  sortOrder: number;
  publishedVersionId: string | null;
}

export interface TaskView {
  id: string;
  taskKey: string;
  projectId: string | null;
  title: string;
  objective: string;
  background: string | null;
  contextSnapshot: Record<string, unknown>;
  visibility: 'enterprise_shared' | 'restricted';
  category: 'development' | 'data' | 'knowledge' | 'operations' | 'document' | 'general';
  orchestrationMode: 'single_agent' | 'multi_agent_template' | 'human_in_loop' | 'hybrid';
  lifecycleLevel: 'L0_chat' | 'L1_short_task' | 'L2_workflow_task' | 'L3_recurring_task';
  riskLevel: 'R0' | 'R1' | 'R2' | 'R3';
  status: string;
  importance: number;
  urgency: number;
  ownerId: string;
  ownerPrincipalType: string;
  startAt: string | null;
  currentVersionId: string;
  latestRunId: string | null;
  acceptanceMode: 'rule' | 'human' | 'combined';
  acceptanceConfig: Record<string, unknown>;
  budget: Record<string, unknown>;
  externalRefs: Record<string, unknown>;
  tags: string[];
  createdAt: string;
}

export interface CreateTaskPayload {
  idempotencyKey: string;
  title: string;
  objective: string;
  background?: string;
  projectId?: string;
  agentVersionId: string;
  workflowVersionId?: string;
  workflowAgentVersions?: Record<string, string>;
  visibility: 'enterprise_shared' | 'restricted';
  category: TaskView['category'];
  orchestrationMode: 'single_agent' | 'multi_agent_template';
  lifecycleLevel: 'L1_short_task' | 'L2_workflow_task';
  riskLevel: TaskView['riskLevel'];
  acceptanceMode: 'human';
  importance: number;
  urgency: number;
  contextSnapshot: Record<string, never>;
  resources: TaskResourceRequest[];
  acceptanceSnapshot: Record<string, never>;
  inputSnapshot: Record<string, never>;
  budget: Record<string, never>;
  externalRefs: Record<string, never>;
  tags: string[];
}

export type TaskResourceType = 'agent_version' | 'tool' | 'skill' | 'knowledge_base' | 'data_source' | 'dataset' | 'artifact' | 'connector';
export type TaskResourcePermission = 'read' | 'query' | 'use' | 'write' | 'admin';

export interface TaskResourceRequest {
  resourceType: TaskResourceType;
  resourceId: string;
  permission: TaskResourcePermission;
  required?: boolean;
  grantSource?: 'user' | 'project' | 'agent' | 'template';
  grantSnapshot?: Record<string, unknown>;
}

export interface TaskResourceView extends TaskResourceRequest {
  id: string;
  taskId: string;
  required: boolean;
  grantSource: NonNullable<TaskResourceRequest['grantSource']>;
  grantSnapshot: Record<string, unknown>;
  createdAt: string;
}

export type TaskParticipantType = 'owner' | 'assignee' | 'collaborator' | 'acceptor' | 'watcher';

export interface TaskParticipantView {
  id: string;
  taskId: string;
  userId: string;
  type: TaskParticipantType;
  source: string;
  status: string;
  createdAt: string;
}

export interface TaskAccessRuleView {
  id: string;
  taskId: string;
  subjectType: 'user' | 'platform_role' | 'service_account';
  subjectId: string | null;
  subjectKey: string | null;
  action: 'view' | 'comment' | 'operate' | 'admin';
  effect: 'allow' | 'deny';
  expiresAt: string | null;
  createdAt: string;
}

export interface TaskVisibilityView {
  taskId: string;
  visibility: TaskView['visibility'];
  ownerId: string;
  participants: TaskParticipantView[];
  accessRules: TaskAccessRuleView[];
}

export interface TaskMutationResult {
  task: TaskView;
  taskVersionId: string;
  replayed: boolean;
}

export interface TaskVersionView {
  id: string;
  taskId: string;
  versionNo: number;
  title: string;
  objective: string;
  agentVersionId: string;
  workflowVersionId: string | null;
  contextSnapshot: Record<string, unknown>;
  resourceSnapshot: Record<string, unknown>;
  acceptanceSnapshot: Record<string, unknown>;
  inputSnapshot: Record<string, unknown>;
  contentHash: string;
  createdBy: string;
  createdAt: string;
}

export interface UpdateTaskPayload {
  title: string;
  objective: string;
  background?: string;
  projectId?: string;
  agentVersionId: string;
  workflowVersionId?: string;
  workflowAgentVersions?: Record<string, string>;
  visibility: TaskView['visibility'];
  category: TaskView['category'];
  orchestrationMode: 'single_agent' | 'multi_agent_template';
  lifecycleLevel: 'L1_short_task' | 'L2_workflow_task';
  riskLevel: TaskView['riskLevel'];
  acceptanceMode: TaskView['acceptanceMode'];
  importance: number;
  urgency: number;
  startAt?: string;
  contextSnapshot: Record<string, unknown>;
  resources: TaskResourceRequest[];
  acceptanceSnapshot: Record<string, unknown>;
  inputSnapshot: Record<string, unknown>;
  budget: Record<string, unknown>;
  externalRefs: Record<string, unknown>;
  tags: string[];
}

export interface TaskRunView {
  id: string;
  taskId: string;
  taskVersionId: string;
  workflowVersionId: string | null;
  traceId: string;
  status: string;
  attemptNo: number;
  parentRunId: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  waitReason: string | null;
  errorCode: string | null;
  errorSummary: string | null;
  cancelReason: string | null;
  createdBy: string;
  createdAt: string;
}

export interface RunStepView {
  id: string;
  runId: string;
  key: string;
  type: 'agent' | 'aggregate';
  role: string | null;
  sequence: number;
  status: string;
  agentVersionId: string | null;
  dependsOn: string[];
  inputSummary: string | null;
  outputSummary: string | null;
  output: unknown;
  waitReason: string | null;
  errorCode: string | null;
  errorSummary: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  retryCount: number;
}

export interface TaskRunActionResult {
  run: TaskRunView;
  replayed: boolean;
}

export interface ConversationView {
  id: string;
  projectId: string | null;
  taskId: string | null;
  agentId: string | null;
  agentVersionId: string | null;
  branchId: string | null;
  parentConversationId: string | null;
  forkMessageId: string | null;
  contextCutoffSequence: number | null;
  title: string | null;
  visibility: 'private';
  status: string;
  lastMessageAt: string | null;
  createdAt: string;
}

export interface ActiveConversationView {
  conversation_id: string | null;
}

export interface ConversationMessageView {
  id: string;
  conversationId: string;
  sequenceNo: number;
  traceId: string | null;
  role: 'user' | 'assistant' | 'tool' | 'system';
  content: string | null;
  agentId: string | null;
  agentVersionId: string | null;
  modelId: string | null;
  status: string;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  createdAt: string;
}

export interface ExecutionEventView {
  eventId: string;
  traceId: string;
  conversationId: string | null;
  runId: string | null;
  stepId: string | null;
  cursor: number;
  eventType: string;
  eventStatus: string;
  summary: string | null;
  payload: Record<string, unknown>;
  sensitiveLevel: string;
  occurredAt: string;
  /** Safe allow-listed fields for tool/event presentation. */
  projection?: Record<string, unknown>;
}

/** Nhs V1 chat chunk projected from the same durable execution cursor. */
export interface NhsSseChunk {
  type?: string;
  event_id?: string;
  cursor?: number;
  trace_id?: string;
  conversation_id?: string | number;
  run_id?: string | number;
  step_id?: string | number;
  event_status?: string;
  [key: string]: unknown;
}

export interface NhsChatCompletionResponse {
  content: string;
  intent: string;
  confidence: number | null;
  reasoning: string | null;
  model: string | null;
  trace_id: string | null;
  conversation_id?: string | number;
  turn_id?: string | number;
  status?: string;
}

export interface CreateConversationPayload {
  title?: string;
  projectId?: string;
  agentId?: string;
  agentVersionId?: string;
}

export interface CreateConversationTurnPayload {
  idempotencyKey: string;
  input: string;
  agentId?: string;
  agentVersionId?: string;
  attachmentIds: string[];
}

export interface CreateConversationBranchPayload {
  forkMessageId: string;
  idempotencyKey: string;
}

export interface RetryConversationTurnPayload {
  idempotencyKey: string;
}

export interface ConversationBranchView {
  conversation: ConversationView;
  turn: ConversationTurnView;
  forkMessageId: string;
  contextCutoffSequence: number;
  replayed: boolean;
}

export interface ConversationTurnView {
  id: string;
  conversationId: string;
  traceId: string;
  agentId: string;
  agentVersionId: string;
  status:
    | 'running'
    | 'stopping'
    | 'waiting_confirmation'
    | 'waiting_user_question'
    | 'succeeded'
    | 'failed'
    | 'cancelled';
  replayed: boolean;
  startedAt: string;
  finishedAt: string | null;
}

export interface RuntimeConfirmationView {
  confirmationId: string;
  conversationTurnId: string | null;
  taskId: string | null;
  runId: string | null;
  stepId: string | null;
  status: 'awaiting_user' | 'confirmed' | 'cancelled' | 'expired';
  title: string;
  fields: Array<Record<string, unknown>>;
  ui: Record<string, unknown>;
  expiresAt: string;
  decidedAt: string | null;
  consumedAt: string | null;
}

export interface RuntimeConfirmationDecisionResult {
  confirmation: RuntimeConfirmationView;
  replayed: boolean;
  resumed: boolean;
}

export interface RuntimeConfirmationDecisionPayload {
  idempotencyKey: string;
  fields: Array<Record<string, unknown>>;
  comment?: string;
}

/** Nhs V1 global cancellation facts. Unlike a turn-only stop this also
 * cancels conversation task runs and sandbox/canvas leases. */
export interface ConversationCancellationView {
  conversation_id: string | number;
  trace_id: string | null;
  success: boolean;
  lane_released: boolean;
  session_locks_released: number;
  run_cancelled: boolean;
  canvas_stopped: number;
  task_runs_cancelled: number;
  status: string;
  reason: string;
  turn_id?: string | number;
}

export interface ConversationFinalizeView {
  finalized: boolean;
  conversationId: string | number;
  reason: string;
}

export interface ConversationAttachmentView {
  id: string;
  conversationId: string;
  turnId: string | null;
  originalName: string;
  mimeType: string;
  sizeBytes: number;
  sha256: string;
  status: 'ready' | 'bound' | 'deleted';
  createdAt: string;
}

/** Durable owner-scoped conversation history projected by the Nhs V1 route. */
export interface NhsV1ConversationHistoryView {
  conversation_id: string | number;
  messages: ConversationMessageView[];
  attachments: ConversationAttachmentView[];
  events: ExecutionEventView[];
  limit: number;
  offset: number;
  has_more: boolean;
  [key: string]: unknown;
}

export type CanvasContentType =
  | 'markdown'
  | 'html'
  | 'code'
  | 'mermaid'
  | 'pdf'
  | 'csv'
  | 'image'
  | 'compare';

export interface CanvasView {
  id: string;
  conversationId: string;
  title: string;
  contentType: CanvasContentType;
  content: string;
  workspacePath: string | null;
  sourceMessageId: string | null;
  currentVersion: number;
  revision: number;
  contentSize: number;
  contentSha256: string;
  metadata: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}

export interface CanvasVersionView {
  id: string;
  canvasId: string;
  versionNo: number;
  title: string;
  contentType: CanvasContentType;
  content: string;
  workspacePath: string | null;
  metadata: Record<string, unknown>;
  contentSize: number;
  contentSha256: string;
  changeType: string;
  sourceVersionNo: number | null;
  createdBy: string;
  createdAt: string;
}

export interface SaveCanvasPayload {
  title: string;
  contentType: CanvasContentType;
  content: string;
  metadata?: Record<string, unknown>;
}

export interface UpdateCanvasPayload extends SaveCanvasPayload {
  expectedVersion: number;
}

export interface CanvasWorkspaceSaveView {
  canvasId: string;
  version: number;
  path: string;
  fileName: string;
  contentSize: number;
  overwritten: boolean;
  savedAt: string;
}

export type ConversationFeedbackRating = 'up' | 'down';
export type ConversationExportFormat = 'json' | 'markdown';
export type NhsV1TraceDataExportFormat = 'csv' | 'xlsx';

/** Nhs V1 global task execution history filters. */
export interface NhsV1ExecutionHistoryQuery {
  page?: number;
  page_size?: number;
  status?: string;
  task_id?: string | number;
  q?: string;
  start_at?: string;
  end_at?: string;
}

/** A task run projected by the Nhs V1 compatibility endpoint. */
export interface NhsV1ExecutionHistoryItem {
  id: string | number;
  task_id: string | number | null;
  task_name: string | null;
  user_id?: string | number | null;
  trace_id: string | null;
  status: string;
  error: string | null;
  started_at: string | null;
  finished_at: string | null;
  created_at: string;
  /** Optional fields retained for compatible deployments that enrich the projection. */
  creator_name?: string | null;
  username?: string | null;
  agent_id?: string | null;
  agent_name?: string | null;
}

export interface NhsV1ExecutionHistoryPage {
  items: NhsV1ExecutionHistoryItem[];
  total: number;
  page: number;
  page_size: number;
}

export type TaskExecutionHistoryItem = NhsV1ExecutionHistoryItem;
export type TaskExecutionHistoryPage = NhsV1ExecutionHistoryPage;

export interface NhsV1TraceStepView {
  step_number: number;
  event_type: string;
  agent_name: string;
  model: string | null;
  temperature: number | null;
  tool_name: string | null;
  tool_input: Record<string, unknown> | null;
  tool_output: unknown;
  raw_log: string | null;
  execution_time_ms: number | null;
  status: string;
  error_message: string | null;
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
  span_id: string | null;
  parent_span_id: string | null;
  meta_info: Record<string, unknown>;
  timestamp: string;
}

export interface NhsV1TraceHistoryView {
  id: string;
  trace_id: string;
  agent_id: string;
  conversation_id: string;
  project_name: string | null;
  username: string;
  query: string | null;
  summary: string | null;
  reasoning_content: string | null;
  status: string;
  agent_version: string | null;
  model_id: string | null;
  execution_time_ms: number;
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
  turn_count: number | null;
  created_at: string;
  agent_name: string | null;
  agent_display_name: string | null;
}

/** Nhs V1 conversation execution history filters and paged projection. */
export interface NhsV1ChatHistoryQuery {
  page?: number;
  page_size?: number;
  agent_id?: string | number;
  conversation_id?: string | number;
  username?: string;
  keyword?: string;
  status?: string;
  start_date?: string;
  end_date?: string;
  group_by_conversation?: boolean;
  /** Legacy aliases retained for older portal callers. */
  limit?: number;
  search?: string;
}

export interface NhsV1ChatHistoryItem extends NhsV1TraceHistoryView {
  agent_name: string | null;
  process_timeline?: unknown[] | null;
}

export interface NhsV1ChatHistoryPage {
  total: number;
  page: number;
  page_size: number;
  items: NhsV1ChatHistoryItem[];
}

export interface NhsV1HistoryDeletionView {
  trace_id?: string;
  conversation_id: string | number;
  deleted: boolean;
  already_deleted: boolean;
  physical_delete: false;
}

export interface NhsV1HistoryBatchDeletionView {
  conversation_ids: Array<string | number>;
  deleted_count: number;
  inserted_count: number;
  physical_delete: false;
}

export interface NhsV1TraceLogView {
  trace_id: string;
  total_steps: number;
  steps: NhsV1TraceStepView[];
  history: NhsV1TraceHistoryView;
}

export interface ConversationFeedbackPayload {
  messageId: string;
  turnId?: string;
  rating: ConversationFeedbackRating;
  reason?: string;
  comment?: string;
  traceId?: string;
}

export interface ConversationFeedbackView {
  id: string;
  conversationId: string;
  messageId: string;
  turnId: string | null;
  rating: ConversationFeedbackRating;
  reason: string | null;
  comment: string | null;
  traceId: string | null;
  createdAt: string;
  updatedAt: string;
}

export type ConversationResourceScopeKey =
  | 'agent_ids'
  | 'agent_version_ids'
  | 'dataset_ids'
  | 'knowledge_base_ids'
  | 'tool_ids'
  | 'skill_ids';

export type ConversationResourceScopeValue = string | number;
export type ConversationResourceScope = Partial<
  Record<ConversationResourceScopeKey, ConversationResourceScopeValue[]>
>;

export interface ConversationResourceScopeView {
  conversationId: string;
  revision: number;
  resources: ConversationResourceScope;
  updatedAt: string | null;
}

export interface SaveConversationResourceScopePayload {
  expectedRevision: number;
  resources: ConversationResourceScope;
}

export interface WorkspaceFileEntry {
  name: string;
  path: string;
  is_dir: boolean;
  size: number;
  mtime: number;
  mime_type?: string;
}

export interface WorkspaceFilePreview {
  path: string;
  content: string;
  size: number;
  mime_type: string;
}

export interface WorkspaceTrashEntry {
  id: string;
  original_path: string;
  is_dir: boolean;
  deleted_at: string;
}

export interface WorkspaceRecentFiles {
  items: WorkspaceFileEntry[];
}

export interface WorkspaceBrowserPreferences {
  view?: 'list' | 'grid';
  sort?: 'name' | 'mtime' | 'size';
  order?: 'asc' | 'desc';
  include_subdirs?: boolean;
  type_filter?: string;
}

export interface ConvertConversationPayload {
  idempotencyKey: string;
  title: string;
  objective: string;
  background?: string;
  projectId?: string;
  agentVersionId: string;
  workflowVersionId?: string;
  workflowAgentVersions?: Record<string, string>;
  visibility: 'enterprise_shared' | 'restricted';
  category: TaskView['category'];
  orchestrationMode: 'single_agent' | 'multi_agent_template';
  lifecycleLevel: 'L1_short_task' | 'L2_workflow_task';
  riskLevel: TaskView['riskLevel'];
  acceptanceMode: 'human';
  importance: number;
  urgency: number;
  contextSnapshot: Record<string, unknown>;
  resources: TaskResourceRequest[];
  acceptanceSnapshot: Record<string, unknown>;
  inputSnapshot: Record<string, unknown>;
  budget: Record<string, unknown>;
  externalRefs: Record<string, unknown>;
  tags: string[];
  draftHash?: string;
}

export interface TaskDraftView {
  conversationId: string;
  draftHash: string;
  draft: ConvertConversationPayload;
  confirmationRequired: boolean;
}

export interface TaskConversionResult {
  taskId: string;
  taskVersionId: string;
  replayed: boolean;
}

export interface ModelView {
  id: string;
  modelKey: string;
  displayName: string;
  providerType: string;
  modelName: string;
  modelType: 'chat' | 'embedding' | 'multimodal' | 'rerank';
  endpointUrl: string;
  apiKeyConfigured: boolean;
  contextSize: number | null;
  maxOutputTokens: number | null;
  reasoningConfig: Record<string, unknown>;
  status: string;
  capabilities: Record<string, unknown>;
  createTime: string;
  updateTime: string | null;
}

export interface SaveModelPayload {
  modelKey?: string;
  displayName: string;
  providerType: 'openai' | 'openai-compatible';
  modelName: string;
  modelType: ModelView['modelType'];
  endpointUrl?: string;
  apiKey?: string;
  contextSize?: number;
  maxOutputTokens?: number;
  reasoningConfig: Record<string, unknown>;
  capabilities: Record<string, unknown>;
  status: 'active' | 'disabled';
}

export interface ModelConnectionView {
  success: boolean;
  message: string;
  responseSummary: string | null;
  latencyMs: number;
}

export interface ModelOptionView {
  modelName: string;
  displayName: string;
}

export interface ModelReferenceView {
  kind: string;
  agentId: string;
  agentName: string;
  versionId: string;
  versionNo: number;
  versionStatus: string;
  slots: string[];
}

export interface ProjectView {
  id: string;
  projectKey: string;
  name: string;
  description: string | null;
  status: 'active' | 'suspended' | 'archived';
  ownerId: string;
  defaultAgentVersionId: string | null;
  workspacePolicy: Record<string, unknown>;
  notificationPolicy: Record<string, unknown>;
  tags: string[];
  archivedAt: string | null;
  createdAt: string;
  updatedAt: string | null;
}

export interface SaveProjectPayload {
  name: string;
  description?: string;
  defaultAgentVersionId?: string;
  workspacePolicy: Record<string, unknown>;
  notificationPolicy: Record<string, unknown>;
  tags: string[];
}

export interface ProjectMutationResult {
  project: ProjectView;
  replayed: boolean;
}

export interface ProjectMemberView {
  id: string;
  projectId: string;
  userId: string;
  role: 'owner' | 'manager' | 'member' | 'viewer';
  status: string;
  joinedAt: string;
}

export interface ArtifactView {
  id: string;
  projectId: string | null;
  taskId: string;
  runId: string;
  stepId: string | null;
  artifactType: string;
  name: string;
  versionNo: number;
  storageType: string;
  storageRef: string;
  mimeType: string | null;
  sizeBytes: number | null;
  contentHash: string;
  sensitiveLevel: string;
  visibility: string;
  status: string;
  metadata: Record<string, unknown>;
  createdBy: string;
  createdAt: string;
}

export interface AcceptanceView {
  id: string;
  taskId: string;
  runId: string;
  artifactIds: string[];
  acceptanceType: string;
  result: 'passed' | 'rework' | 'rejected' | 'taken_over';
  ruleResult: Record<string, unknown>;
  comment: string | null;
  reviewerId: string;
  reviewerPrincipalType: string;
  reworkNo: number;
  createdAt: string;
}

export interface AcceptanceDecisionResult {
  acceptance: AcceptanceView;
  taskStatus: string;
  replayed: boolean;
}

export interface MemoryView {
  id: string;
  memoryKey: string;
  scopeType: 'user' | 'project' | 'task';
  scopeId: string;
  memoryType: string;
  content: string;
  sourceType: string;
  sourceId: string | null;
  confidence: number | null;
  sensitiveLevel: string;
  reviewStatus: 'pending' | 'approved' | 'rejected';
  expiresAt: string | null;
  metadata: Record<string, unknown>;
  revisionNo: string;
  reviewedBy: string | null;
  reviewedAt: string | null;
  reviewComment: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string | null;
}

export interface SaveMemoryPayload {
  memoryKey?: string;
  memoryType: string;
  content: string;
  sourceType: string;
  sourceId?: string;
  confidence?: number;
  sensitiveLevel: string;
  expiresAt?: string;
  metadata: Record<string, unknown>;
}

export interface ConnectorView {
  id: string;
  connectorKey: string;
  name: string;
  providerType: 'api' | 'mcp' | 'search';
  scope: 'global' | 'personal';
  ownerId: string | null;
  ownedByCurrentUser: boolean;
  manageable: boolean;
  endpointUrl: string;
  credentialRef: string | null;
  config: Record<string, unknown>;
  status: 'active' | 'disabled';
  lastCheckAt: string | null;
  lastError: string | null;
  revision: string;
  lastDiscoveryId: string | null;
  createdAt: string;
  updatedAt: string | null;
}

export interface SaveConnectorPayload {
  connectorKey?: string;
  name: string;
  providerType: ConnectorView['providerType'];
  scope: ConnectorView['scope'];
  endpointUrl: string;
  credentialRef?: string;
  config: Record<string, unknown>;
  status: ConnectorView['status'];
}

export interface McpWizardValidationView {
  step: number;
  valid: boolean;
  nextStep: number | null;
  namespace: string;
  diagnostics: string[];
}

export interface McpConnectionTestPayload {
  connectorId?: string;
  name: string;
  endpointUrl: string;
  credentialRef?: string;
  config: Record<string, unknown>;
}

export interface McpImportEntryView {
  sourceKey: string;
  suggestedConnectorKey: string;
  suggestedName: string;
  endpointUrl: string | null;
  transport: 'streamable_http' | 'sse';
  authType: 'none' | 'bearer' | 'header';
  authHeader: string | null;
  credentialRef: string | null;
  credentialRequired: boolean;
  importable: boolean;
  diagnostics: string[];
}

export interface McpServersImportPreviewView {
  entries: McpImportEntryView[];
}

export interface McpServerImportPayload {
  document: Record<string, unknown>;
  sourceKey: string;
  connectorKey: string;
  name: string;
  scope: ConnectorView['scope'];
  credentialRef?: string;
  status: ConnectorView['status'];
}

export interface McpDiscoveryView {
  id: string;
  connectorId: string;
  connectorRevision: string;
  status: 'pending' | 'running' | 'succeeded' | 'failed';
  protocolVersion: string | null;
  serverInfo: Record<string, unknown>;
  toolCount: number;
  contentHash: string | null;
  errorSummary: string | null;
  startedBy: string;
  startedAt: string;
  completedAt: string | null;
}

export interface McpConnectionTestView {
  success: boolean;
  protocolVersion: string;
  serverName: string;
  toolCount: number;
  tools: Array<{
    externalName: string;
    name: string;
    description: string | null;
  }>;
  latencyMs: number;
  checkedAt: string;
}

export interface McpRuntimeHealthView {
  connectorId: string;
  healthStatus: 'unknown' | 'healthy' | 'degraded' | 'unavailable';
  circuitState: 'closed' | 'open' | 'half_open';
  consecutiveFailures: number;
  totalConnections: number;
  totalReconnections: number;
  totalInvocations: number;
  totalSuccesses: number;
  totalFailures: number;
  activeMountCount: number;
  lastSuccessAt: string | null;
  lastFailureAt: string | null;
  lastReconnectAt: string | null;
  circuitOpenUntil: string | null;
  lastLatencyMs: number | null;
  lastErrorSummary: string | null;
  updatedAt: string | null;
}

export interface McpRuntimeMountView {
  id: string;
  connectorId: string;
  connectorRevision: string;
  scopeType: 'session' | 'run';
  userId: string;
  conversationId: string | null;
  taskId: string | null;
  runId: string | null;
  stepId: string | null;
  sessionId: string;
  executionId: string;
  traceId: string;
  status: 'mounting' | 'mounted' | 'idle' | 'degraded' | 'closed' | 'expired' | 'abandoned';
  connectionAttempts: number;
  reconnectCount: number;
  invocationCount: number;
  failureCount: number;
  openedAt: string;
  lastUsedAt: string | null;
  closedAt: string | null;
  lastErrorSummary: string | null;
}

export interface McpUsageDetailView {
  id: string;
  mountId: string;
  connectorId: string;
  connectorRevision: string;
  toolId: string;
  externalToolName: string;
  userId: string;
  conversationId: string | null;
  taskId: string | null;
  runId: string | null;
  stepId: string | null;
  sessionId: string;
  executionId: string;
  traceId: string;
  status: 'success' | 'provider_error' | 'transport_error' | 'circuit_open';
  attemptCount: number;
  latencyMs: number;
  requestBytes: number;
  responseBytes: number | null;
  errorSummary: string | null;
  startedAt: string;
  completedAt: string;
}

export interface McpRuntimeOverviewView {
  health: McpRuntimeHealthView;
  mounts: McpRuntimeMountView[];
  usage: McpUsageDetailView[];
}

export interface McpAgentUsageView {
  id: string;
  name: string;
  displayName: string;
  isEnabled: boolean;
  active: boolean;
  versionCount: number;
}

export interface McpConnectorUsageView {
  connectorId: string;
  boundAgentCount: number;
  activeAgentCount: number;
  boundVersionCount: number;
  agents: McpAgentUsageView[];
}

export interface ToolView {
  id: string;
  toolKey: string;
  name: string;
  description: string | null;
  connectorId: string | null;
  toolType: 'builtin' | 'api' | 'mcp' | 'search' | 'sql' | 'sandbox';
  riskLevel: 'R0' | 'R1' | 'R2' | 'R3';
  parameterSchema: Record<string, unknown>;
  executionPolicy: Record<string, unknown>;
  externalName: string | null;
  status: 'active' | 'disabled' | 'deprecated';
  versionNo: number;
  discoveryId: string | null;
  remoteSchemaHash: string | null;
  available: boolean;
  usageCount: number;
  createdAt: string;
  updatedAt: string | null;
  runtimeExecution: 'local' | 'configured' | 'unavailable';
}

export interface BuiltinToolDescriptor {
  name: string;
  description: string;
  source: string;
  registered: boolean;
  execution: 'local' | 'unavailable';
  readOnly: boolean;
  riskLevel: 'R0' | 'R2';
  parameterSchema: Record<string, unknown>;
}

export interface SaveToolPayload {
  toolKey?: string;
  name: string;
  description?: string;
  connectorId?: string;
  toolType: Exclude<ToolView['toolType'], 'mcp'>;
  riskLevel: ToolView['riskLevel'];
  parameterSchema: Record<string, unknown>;
  executionPolicy: Record<string, unknown>;
  externalName?: string;
  status: ToolView['status'];
}

export interface ToolOnlineTestPayload {
  arguments: Record<string, unknown>;
  confirmRisk: boolean;
}

export interface ToolOnlineTestView {
  toolId: string;
  ok: boolean;
  data: unknown;
  error: string | null;
  status:
    | 'succeeded'
    | 'provider_error'
    | 'transport_error'
    | 'query_error'
    | 'tool_unavailable'
    | 'authorization_error'
    | 'conflict'
    | 'invalid_arguments';
  retryable: boolean;
  latencyMs: number;
  checkedAt: string;
}

export interface SkillView {
  id: string;
  skillKey: string;
  name: string;
  description: string | null;
  scopeType: 'system' | 'project' | 'user';
  scopeId: string | null;
  ownerId: string;
  status: string;
  revision: string;
  publishedVersionId: string | null;
  publishedVersionNo: number | null;
  publishedContentHash: string | null;
  createdAt: string;
  updatedAt: string | null;
  metadata: Record<string, unknown>;
}

export interface SkillVersionView {
  id: string;
  skillId: string;
  versionNo: number;
  content: string;
  contentHash: string;
  manifest: Record<string, unknown>;
  runtimeRequirements: Record<string, unknown>;
  status: 'draft' | 'published' | 'archived';
  publishedAt: string | null;
  createdBy: string;
  createdAt: string;
}

export interface SkillDependencyInstallView {
  skillId: string;
  versionId: string;
  versionNo: number;
  dependencies: Record<string, string[]>;
  dependencyHash: string;
  status: 'not_installed' | 'queued' | 'running' | 'succeeded' | 'failed' | 'blocked' | 'skipped' | string;
  attemptNo: number;
  requestedAt: string | null;
  completedAt: string | null;
  installRoot: string | null;
  message: string | null;
  installerEnabled: boolean;
}

export interface SkillFileView {
  id: string;
  skillId: string;
  versionId: string;
  path: string;
  fileKind: 'file' | 'directory';
  content: string | null;
  binary: boolean;
  contentBase64: string | null;
  contentHash: string;
  sizeBytes: number;
  createdAt: string;
  updatedAt: string | null;
}

export interface SkillPublicationFileNode {
  name: string;
  path: string;
  is_dir: boolean;
  size: number;
  children: SkillPublicationFileNode[];
}

export interface SkillPublicationView {
  publication_id: string | null;
  version_id: string | null;
  skill_id: string;
  platform_skill_id: string | null;
  name: string | null;
  description: string | null;
  publication_status: 'UNPUBLISHED' | 'PENDING' | 'PUBLISHED' | 'REJECTED' | 'WITHDRAWN';
  version_number: number | null;
  version_status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | 'SUPERSEDED' | null;
  current_public_version: number | null;
  pending_version: number | null;
  last_review_comment: string | null;
  content_sha256: string | null;
  file_count: number | null;
  total_size: number | null;
  submitted_by: string | null;
  submitted_at: string | null;
  reviewed_by: string | null;
  reviewed_at: string | null;
  review_comment: string | null;
  withdrawn_by: string | null;
  withdrawn_at: string | null;
  skill_md_content: string | null;
  file_tree: SkillPublicationFileNode[];
}

export interface SaveSkillPayload {
  skillKey?: string;
  name: string;
  description?: string;
  scopeType: SkillView['scopeType'];
  scopeId?: string;
  content: string;
  manifest: Record<string, unknown>;
  runtimeRequirements: Record<string, unknown>;
}

export interface LegacyExecutionArchiveView {
  id: string;
  migrationRunId: string;
  sourceSystem: string;
  sourceTraceId: string | null;
  sourceExecutionId: string | null;
  sourceAgentId: string | null;
  sourceUserId: string | null;
  sourceConversationId: string | null;
  sourceStatus: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  summary: string | null;
  contentHash: string;
  createdAt: string;
}

export interface EmbedSessionView {
  id: string;
  agentVersionId: string;
  status: string;
  expiresAt: string;
  createdAt: string;
}

export interface EmbedStreamEvent {
  event: string;
  id?: string;
  data: Record<string, unknown>;
}

export interface ChatCodeExecutionView {
  executionId: string;
  conversationId: string;
  traceId: string;
  language: 'python' | 'bash' | 'sh';
  status: string;
  exitCode: number | null;
  outputBytes: number;
  truncated: boolean;
  failureCode: string | null;
  failureMessage: string | null;
  queuedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface ChatCodeStreamEvent {
  event: 'started' | 'output' | 'finished' | 'timeout' | 'error' | 'stopped' | string;
  id?: string;
  data: Record<string, unknown>;
}

export interface RuntimeStatusView {
  runtimeType: string;
  available: boolean;
  state: string;
  message: string;
}

export interface AgentView extends AgentOptionView {
  engineConfig: Record<string, unknown>;
  createTime: string;
  updateTime: string | null;
}

export interface SaveAgentPayload {
  agentKey?: string;
  name: string;
  description?: string;
  agentType: 'general' | 'assistant' | 'knowledge' | 'data' | 'coding' | 'supervisor';
  avatarUrl?: string;
  defaultAgent: boolean;
  sortOrder: number;
  engineType: AgentEngineType;
  engineConfig: Record<string, unknown>;
}

export interface AgentResourceBindingPayload {
  resourceId: string;
  permission: 'use' | 'invoke' | 'read';
  config: Record<string, unknown>;
}

export interface SaveAgentVersionPayload {
  systemPrompt: string;
  modelId: string | null;
  synthesisModelId?: string;
  runtimeConfig: Record<string, unknown>;
  welcomeConfig: Record<string, unknown>;
  routingTags: string[];
  tools: AgentResourceBindingPayload[];
  skills: AgentResourceBindingPayload[];
  knowledgeBases: AgentResourceBindingPayload[];
}

export interface AgentOnboardingResult {
  agent: AgentView;
  version: AgentVersionView;
  onboardingStep: string;
  replayed: boolean;
  templateFallback: boolean;
}

export interface AgentVersionBindingView {
  id: string;
  resourceType: string;
  resourceId: string;
  permission: string;
  config: Record<string, unknown>;
}

export interface AgentVersionView {
  id: string;
  agentId: string;
  versionNo: number;
  systemPrompt: string;
  modelId: string | null;
  synthesisModelId: string | null;
  runtimeConfig: Record<string, unknown>;
  welcomeConfig: Record<string, unknown>;
  routingTags: string[];
  status: string;
  contentHash: string;
  publishedAt: string | null;
  createdBy: string;
  createdAt: string;
  bindings: AgentVersionBindingView[];
}

export interface AgentWelcomeCardView {
  icon: string;
  title: string;
  subtitle: string;
  prompt: string;
}

/** Nhs-compatible per-Agent execution history projection. */
export interface AgentExecutionHistoryView {
  id: string;
  trace_id: string | null;
  agent_id: string;
  conversation_id: string | null;
  username: string | null;
  query: string | null;
  summary: string | null;
  status: string;
  agent_version: string | null;
  model_id: string | null;
  execution_time_ms: number | null;
  created_at: string;
  turn_count: number | null;
  agent_display_name: string | null;
}

export interface AgentVersionPublishResult {
  version: AgentVersionView;
  replayed: boolean;
}

export interface KnowledgeBaseView {
  id: string;
  knowledgeKey: string;
  name: string;
  description: string | null;
  providerType: string;
  visibility: 'private' | 'enterprise_shared' | 'restricted';
  status: 'active' | 'disabled';
  config: Record<string, unknown>;
  ownerId: string;
  revision: string;
  createdAt: string;
  updatedAt: string | null;
}

export interface SaveKnowledgeBasePayload {
  knowledgeKey?: string;
  expectedRevision?: string;
  name: string;
  description?: string;
  visibility: KnowledgeBaseView['visibility'];
  status?: KnowledgeBaseView['status'];
  config: Record<string, unknown>;
}

export interface KnowledgeDocumentView {
  id: string;
  knowledgeBaseId: string;
  directoryId?: string | null;
  documentKey: string;
  name: string;
  contentHash: string;
  mimeType: string | null;
  sizeBytes: string | null;
  parserType: string | null;
  status: string;
  chunkCount: number | null;
  metadata: Record<string, unknown>;
  errorSummary: string | null;
  revision: string;
  catalogRevision?: string | null;
  tags?: string[];
  remark?: string | null;
  parseStartedAt: string | null;
  processedAt: string | null;
  createdAt: string;
  updatedAt: string | null;
}

export interface KnowledgeDirectoryView {
  id: string;
  knowledgeBaseId: string;
  parentId: string | null;
  directoryKey: string;
  name: string;
  documentCount: number;
  childDirectoryCount: number;
  revision: string;
  createdAt: string;
  updatedAt: string | null;
}

export interface KnowledgeDirectoryAclView {
  id: string;
  knowledgeBaseId: string;
  directoryId: string | null;
  userId: string;
  permission: 'read' | 'write';
  effect: 'allow' | 'deny';
  inheritChildren: boolean;
  revision: string;
  createdAt: string;
  updatedAt: string | null;
}

export interface PutKnowledgeDirectoryAclPayload {
  directoryId?: string | null;
  userId: string;
  permission: KnowledgeDirectoryAclView['permission'];
  effect: KnowledgeDirectoryAclView['effect'];
  inheritChildren: boolean;
  expectedRevision?: string;
}

export interface KnowledgeTreeView {
  directories: KnowledgeDirectoryView[];
  documents: KnowledgeDocumentView[];
}

export interface SaveKnowledgeDirectoryPayload {
  name: string;
  parentId?: string | null;
  expectedRevision?: string;
}

export interface UpdateKnowledgeDocumentPayload {
  name?: string;
  directoryId?: string | null;
  tags?: string[];
  remark?: string | null;
  expectedRevision: string;
}

export interface KnowledgeChunkView {
  id: string;
  knowledgeBaseId: string;
  documentId: string;
  chunkNo: number;
  content: string;
  contentHash: string;
  tokenCount: number | null;
  status: string;
  metadata: Record<string, unknown>;
  createdAt: string;
}

export interface KnowledgeParseJobView {
  jobId: string;
  documentId: string;
  documentRevision: string;
  status: string;
}

export interface KnowledgeCitationView {
  id: string;
  chunkId: string;
  knowledgeBaseId: string;
  documentId: string;
  documentName: string;
  chunkNo: number;
  similarity: number;
  content: string;
  metadata: Record<string, unknown>;
}

export interface KnowledgeRetrievalView {
  status: string;
  content: string;
  citations: KnowledgeCitationView[];
}

export interface KnowledgeMetricsView {
  status: 'ok' | 'empty' | string;
  source: string;
  periodStart: string;
  periodEnd: string;
  summary: Record<string, unknown>;
  corpus: Record<string, unknown>;
  dailyTrend: Array<Record<string, unknown>>;
  knowledgeBases: Array<Record<string, unknown>>;
}

export type DataSourceDatabaseType = 'postgresql' | 'mysql' | 'oracle' | 'sqlserver' | 'clickhouse';

export interface DataSourceView {
  id: string;
  sourceKey: string;
  name: string;
  dbType: DataSourceDatabaseType;
  endpointUrl: string;
  databaseName: string;
  credentialConfigured: boolean;
  status: 'active' | 'disabled';
  config: Record<string, unknown>;
  revisionNo: number;
  connectionTimeoutMs: number;
  statementTimeoutMs: number;
  maxRows: number;
  maxResultBytes: number;
  lastTestStatus: string | null;
  lastTestAt: string | null;
  lastTestError: string | null;
  lastMetadataSyncAt: string | null;
  lastMetadataSyncError: string | null;
  createTime: string;
  updateTime: string | null;
}

export interface SaveDataSourcePayload {
  sourceKey?: string;
  revisionNo?: number;
  name: string;
  dbType: DataSourceDatabaseType;
  endpointUrl: string;
  databaseName: string;
  credentialRef?: string;
  config: Record<string, unknown>;
  status: DataSourceView['status'];
  connectionTimeoutMs: number;
  statementTimeoutMs: number;
  maxRows: number;
  maxResultBytes: number;
}

export interface DataSourceConnectionView {
  success: boolean;
  message: string;
  latencyMs: number;
  testedAt: string;
}

export interface DatasetView {
  id: string;
  dataSourceId: string;
  datasetKey: string;
  name: string;
  description: string | null;
  status: 'active' | 'disabled';
  schemaNames: string[];
  revisionNo: number;
  lastSyncAt: string | null;
  lastSyncError: string | null;
  ownerId: string;
  createTime: string;
  updateTime: string | null;
}

export interface DatasetDeleteImpactView {
  datasetId: string;
  categories: Array<{
    category: string;
    count: number;
  }>;
  blockingTotal: number;
  deletable: boolean;
}

export interface SaveDatasetPayload {
  dataSourceId?: string;
  datasetKey?: string;
  revisionNo?: number;
  name: string;
  description?: string;
  schemaNames: string[];
  status: DatasetView['status'];
}

export interface DataColumnView {
  id: string;
  columnKey: string;
  physicalName: string;
  displayName: string | null;
  dataType: string;
  description: string | null;
  primary: boolean;
  sensitive: boolean;
  status: string;
  metadataPresent: boolean;
}

export interface DataTableView {
  id: string;
  tableKey: string;
  physicalSchema: string;
  physicalName: string;
  displayName: string | null;
  description: string | null;
  tableType: string;
  status: string;
  metadataPresent: boolean;
  columns: DataColumnView[];
}

export interface UpdateDataTablePayload {
  displayName: string;
  description?: string;
  status: 'active' | 'inactive';
}

export interface UpdateDataColumnPayload {
  displayName: string;
  description?: string;
  sensitive: boolean;
  status: 'active' | 'inactive';
}

export interface MetadataSyncView {
  datasetId: string;
  tableCount: number;
  columnCount: number;
  synchronizedAt: string;
}

export type MetadataImportSourceType = 'ddl' | 'yaml';
export type MetadataImportPreviewStatus = 'draft' | 'applied' | 'expired';
export type MetadataImportItemType = 'table' | 'metric' | 'relationship';
export type MetadataImportItemAction = 'create' | 'update';
export type MetadataImportItemStatus = 'available' | 'applied' | 'skipped';

export interface MetadataImportDiagnosticView {
  level: 'error' | 'warning' | 'info' | string | null;
  code: string | null;
  message: string;
  resourceKey: string | null;
}

export interface MetadataImportItemView {
  id: string;
  itemType: MetadataImportItemType;
  resourceKey: string;
  action: MetadataImportItemAction;
  status: MetadataImportItemStatus;
  currentHash: string | null;
  contentHash: string;
  proposal: Record<string, unknown>;
  appliedResourceId: string | null;
  errorMessage: string | null;
}

export interface MetadataImportPreviewView {
  id: string;
  datasetId: string;
  sourceType: MetadataImportSourceType;
  status: MetadataImportPreviewStatus;
  datasetRevision: number;
  revisionNo: number;
  tableCount: number;
  columnCount: number;
  diagnostics: MetadataImportDiagnosticView[];
  expiresAt: string;
  createdBy: string;
  createdAt: string;
  appliedBy: string | null;
  appliedAt: string | null;
  items: MetadataImportItemView[];
}

export interface CreateMetadataImportPreviewPayload {
  format: MetadataImportSourceType;
  content: string;
}

export interface ApplyMetadataImportPreviewPayload {
  revisionNo: number;
  itemIds: string[];
}

export interface MetadataImportApplyView {
  previewId: string;
  status: MetadataImportPreviewStatus;
  datasetRevision: number;
  revisionNo: number;
  appliedItemIds: string[];
  skippedItemIds: string[];
  appliedAt: string;
}

export interface DataMetricView {
  id: string;
  datasetId: string;
  metricKey: string;
  name: string;
  description: string | null;
  calculationLogic: string;
  unit: string | null;
  status: 'active' | 'inactive';
  versionNo: number;
  createdBy?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface CreateDataMetricPayload {
  metricKey: string;
  name: string;
  description?: string;
  calculationLogic: string;
  unit?: string;
  status: DataMetricView['status'];
}

export interface UpdateDataMetricPayload {
  versionNo: number;
  name: string;
  description?: string;
  calculationLogic: string;
  unit?: string;
  status: DataMetricView['status'];
}

export interface DataRelationView {
  id: string;
  datasetId: string;
  sourceTableId: string;
  targetTableId: string;
  sourceTableName: string | null;
  targetTableName: string | null;
  joinType: 'inner' | 'left' | 'right' | 'full';
  joinCondition: string;
  description: string | null;
  status: 'active' | 'inactive';
  revisionNo: number;
  createdBy?: string | null;
  createdAt?: string | null;
  updatedBy?: string | null;
  updatedAt?: string | null;
}

export interface CreateDataRelationPayload {
  sourceTableId: string;
  targetTableId: string;
  joinType: DataRelationView['joinType'];
  joinCondition: string;
  description?: string;
  status: DataRelationView['status'];
}

export interface UpdateDataRelationPayload extends CreateDataRelationPayload {
  revisionNo: number;
}

export type DatasetRowPolicyOperator = 'eq' | 'ne';

export type DatasetRowPolicyValueSource = 'principal_id' | 'principal_username';

export interface DatasetRowPolicyRule {
  tableId: string;
  columnId: string;
  operator: DatasetRowPolicyOperator;
  valueSource: DatasetRowPolicyValueSource;
}

export interface DatasetRowPolicyView {
  datasetId: string;
  revisionNo: number;
  enabled: boolean;
  rules: DatasetRowPolicyRule[];
  updatedAt: string | null;
}

export interface UpdateDatasetRowPolicyPayload {
  revisionNo: number;
  enabled: boolean;
  rules: DatasetRowPolicyRule[];
}

export interface MetadataChangeView {
  id: string;
  datasetId: string;
  resourceType: 'metric' | 'relationship' | 'row_policy' | string;
  resourceId: string | null;
  action: 'create' | 'update' | 'archive' | string;
  beforeJson: string | null;
  afterJson: string | null;
  beforeHash: string | null;
  afterHash: string | null;
  actorId: string;
  createdAt: string;
}

export type MetadataProfileJobMode = 'full' | 'incremental';
export type MetadataProfileJobStatus = 'queued' | 'running' | 'done' | 'error' | 'cancelled';
export type MetadataProfileJobTableStatus = 'pending' | 'running' | 'success' | 'failed';
export type MetadataDecimal = string;

export interface MetadataProfileJobTableView {
  id: string;
  jobId: string;
  tableId: string;
  schemaName: string | null;
  tableName: string | null;
  status: MetadataProfileJobTableStatus;
  sequenceNo: number;
  attemptNo: number;
  profileId: string | null;
  errorMessage: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  updatedAt: string;
}

export interface MetadataProfileJobView {
  id: string;
  datasetId: string;
  dataSourceId: string;
  mode: MetadataProfileJobMode;
  status: MetadataProfileJobStatus;
  totalTables: number;
  completedTables: number;
  failedTables: number;
  progressPercent: MetadataDecimal;
  currentTableId: string | null;
  cancelRequested: boolean;
  resumeOfJobId: string | null;
  attemptNo: number;
  maxAttempts: number;
  revisionNo: number;
  errorMessage: string | null;
  requestedBy: string;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  updatedAt: string;
}

export interface MetadataProfileJobDetailView {
  job: MetadataProfileJobView;
  tables: MetadataProfileJobTableView[];
}

export interface CreateMetadataProfileJobPayload {
  mode: MetadataProfileJobMode;
  tableIds?: string[];
}

export type MetadataTemporaryClassification = 'business' | 'temporary' | 'backup' | 'staging' | 'system';
export type MetadataProfileIgnoreDecision = 'auto_include' | 'auto_ignore' | 'manual_include' | 'manual_ignore';

export interface MetadataTableProfileSummaryView {
  profileId: string | null;
  datasetId: string;
  jobId: string | null;
  tableId: string;
  schemaName: string;
  tableName: string;
  displayName: string | null;
  term: string | null;
  description: string | null;
  tableType: string;
  status: 'pending' | 'running' | 'success' | 'failed';
  columnCount: number | null;
  sampleRowCount: number | null;
  confidenceScore: MetadataDecimal | null;
  confidenceReason: string | null;
  temporaryClassification: MetadataTemporaryClassification | null;
  tags: string[];
  ignored: boolean | null;
  ignoreDecision: MetadataProfileIgnoreDecision | null;
  revisionNo: number | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface MetadataColumnProfileView {
  columnId: string;
  physicalName: string;
  displayName: string | null;
  term: string | null;
  description: string | null;
  dataType: string;
  primary: boolean;
  sensitive: boolean;
  nonNullSampleCount: number;
  distinctSampleCount: number;
  examples: string[];
}

export interface MetadataProfileSampleValueView {
  columnId: string;
  columnName: string;
  displayName: string | null;
  value: string | null;
  valueType: string;
  redacted: boolean;
  truncated: boolean;
}

export interface MetadataProfileSampleRowView {
  rowNo: number;
  values: MetadataProfileSampleValueView[];
}

export interface MetadataRelatedTableView {
  id: string;
  datasetId: string;
  profileJobId: string;
  sourceTableId: string;
  sourceTableName: string | null;
  sourceColumnId: string;
  sourceColumnName: string | null;
  targetTableId: string;
  targetTableName: string | null;
  targetColumnId: string;
  targetColumnName: string | null;
  confidenceScore: MetadataDecimal;
  joinType: string;
  joinCondition: string;
  reason: string | null;
  status: string;
}

export interface MetadataTableProfileDetailView {
  summary: MetadataTableProfileSummaryView;
  ddl: string | null;
  rowCountEstimate: number | string | null;
  sampleRedacted: boolean;
  columns: MetadataColumnProfileView[];
  samples: MetadataProfileSampleRowView[];
  related: MetadataRelatedTableView[];
}

export interface MetadataTableProfilePageView {
  items: MetadataTableProfileSummaryView[];
  total: number;
  page: number;
  pageSize: number;
  pages: number;
}

export interface MetadataProfileTagStatView {
  name: string;
  count: number;
}

export interface MetadataTableProfileStatsView {
  totalProfiles: number;
  tableCount: number;
  viewCount: number;
  ignoredCount: number;
  temporaryCount: number;
  averageConfidence: MetadataDecimal;
  lastProfiledAt: string | null;
  tags: MetadataProfileTagStatView[];
}

export interface MetadataTableProfileSearchParams {
  page?: number;
  pageSize?: number;
  query?: string;
  tag?: string;
  ignored?: boolean;
  classification?: MetadataTemporaryClassification;
  status?: MetadataTableProfileSummaryView['status'];
  sortBy?: 'default' | 'confidence' | 'confidence_score' | 'name' | 'table_name' | 'term' | 'created' | 'created_at';
  sortOrder?: 'asc' | 'desc';
}

export interface MetadataSmartImportItemView {
  id: string;
  itemType: 'table' | 'relationship';
  resourceId: string;
  status: 'available' | 'applied' | 'skipped';
  contentHash: string;
  tableProposal: MetadataTableImportProposalView | null;
  relationshipProposal: MetadataRelationshipImportProposalView | null;
  appliedResourceId: string | null;
  errorMessage: string | null;
}

export interface MetadataColumnImportProposalView {
  columnId: string;
  expected: MetadataColumnGovernanceSnapshotView;
  displayName: string | null;
  description: string | null;
  sensitive: boolean;
  status: string;
}

export interface MetadataTableGovernanceSnapshotView {
  displayName: string | null;
  description: string | null;
  status: string;
  metadataPresent: boolean;
  stateHash: string;
}

export interface MetadataColumnGovernanceSnapshotView {
  columnId: string;
  displayName: string | null;
  description: string | null;
  sensitive: boolean;
  status: string;
  metadataPresent: boolean;
  stateHash: string;
}

export interface MetadataTableImportProposalView {
  profileId: string;
  profileRevision: number;
  tableId: string;
  sourceHash: string;
  schemaName: string;
  physicalName: string;
  expected: MetadataTableGovernanceSnapshotView;
  displayName: string | null;
  description: string | null;
  status: string;
  columnUpdates: MetadataColumnImportProposalView[];
}

export interface MetadataRelationshipImportProposalView {
  recommendationId: string;
  sourceTableId: string;
  sourceColumnId: string;
  targetTableId: string;
  targetColumnId: string;
  sourceProfileId: string;
  sourceProfileRevision: number;
  sourceStructureHash: string;
  targetProfileId: string;
  targetProfileRevision: number;
  targetStructureHash: string;
  sourceTableStateHash: string;
  sourceColumnStateHash: string;
  targetTableStateHash: string;
  targetColumnStateHash: string;
  joinType: string;
  joinCondition: string;
  description: string | null;
}

export interface MetadataSmartImportPreviewView {
  id: string;
  datasetId: string;
  profileJobId: string;
  status: 'draft' | 'applied' | 'expired';
  datasetRevision: number;
  revisionNo: number;
  expiresAt: string;
  createdBy: string;
  createdAt: string;
  appliedBy: string | null;
  appliedAt: string | null;
  items: MetadataSmartImportItemView[];
}

export interface MetadataSmartImportApplyView {
  preview: MetadataSmartImportPreviewView;
  appliedItems: MetadataSmartImportAppliedItemView[];
}

export interface MetadataSmartImportAppliedItemView {
  itemId: string;
  itemType: 'table' | 'relationship';
  sourceResourceId: string;
  appliedResourceId: string;
}

export interface DataQueryPayload {
  datasetId: string;
  taskId?: string;
  runId?: string;
  conversationId?: string;
  userQuery: string;
  sql: string;
}

export interface DataQueryValidationView {
  datasetId: string;
  tables: string[];
  columns: string[];
  sqlHash: string;
  maxRows: number;
  statementTimeoutMs: number;
  maxResultBytes: number;
}

export interface DataQueryResultView {
  queryId: string;
  columns: string[];
  rows: unknown[][];
  rowCount: number;
  resultBytes: number;
  truncated: boolean;
  elapsedMs: number;
  resultHash: string | null;
}

export interface ReportView {
  id: string;
  reportKey: string;
  name: string;
  datasetId: string;
  sqlTemplate: string;
  paramsSchemaJson: string;
  visibility: 'private' | 'enterprise_shared' | 'restricted';
  ownerId: string;
  status: 'draft' | 'active' | 'disabled' | 'archived';
  createdAt: string;
  updatedAt: string | null;
}

export interface ReportRunView {
  id: string;
  reportId: string;
  triggerType: string;
  resolvedParamsJson: string;
  executedSql: string;
  resultArtifactId: string | null;
  resultHash: string | null;
  rowCount: number | null;
  status: 'running' | 'succeeded' | 'failed';
  errorSummary: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  createdAt: string;
}

export interface ReportSubscriptionView {
  id: string;
  reportId: string;
  triggerId: string | null;
  scheduleType: 'cron' | 'interval';
  cronExpr: string | null;
  intervalMinutes: number | null;
  timezone: string;
  paramsJson: string;
  notifyPolicyJson: string;
  status: 'active' | 'paused';
  maxAttempts: number;
  lastRunAt: string | null;
  nextRunAt: string | null;
  createdAt: string;
}

export interface CreateReportSubscriptionPayload {
  scheduleType: ReportSubscriptionView['scheduleType'];
  cronExpr?: string;
  intervalMinutes?: number;
  timezone?: string;
  paramsJson?: string;
  notifyPolicyJson?: string;
  maxAttempts?: number;
}

export interface CreateReportPayload {
  reportKey: string;
  name: string;
  datasetId: string;
  sqlTemplate: string;
  paramsSchemaJson?: string;
  visibility: ReportView['visibility'];
}

export interface UpdateReportPayload {
  name: string;
  datasetId: string;
  sqlTemplate: string;
  paramsSchemaJson?: string;
  status: Exclude<ReportView['status'], 'archived'>;
  visibility: ReportView['visibility'];
}

export interface PermissionRulePayload {
  resourceType: string;
  resourceId?: string;
  resourceKey?: string;
  action: string;
  effect: 'allow' | 'deny' | 'approval_required';
  policy: Record<string, unknown>;
  reason?: string;
}

export interface PermissionRuleView extends PermissionRulePayload {
  id: string;
  status: string;
  expiresAt: string | null;
  resourceState: 'active' | 'inactive' | 'missing' | 'unresolved';
}

export interface PermissionProfileView {
  id: string;
  profileKey: string;
  name: string;
  description: string | null;
  profileType: 'system' | 'custom';
  versionNo: number;
  status: string;
  createdBy: string;
  createdAt: string;
  entries: PermissionRuleView[];
}

export interface PermissionBindingView {
  id: string;
  userId: string;
  bindingType: 'profile' | 'snapshot';
  profileId: string | null;
  profileVersion: number | null;
  sourceUserId: string | null;
  status: string;
  createdBy: string;
  createdAt: string;
  snapshotRules: PermissionRuleView[];
}

export interface PermissionSummaryView {
  userId: string;
  binding: PermissionBindingView | null;
  baseRules: PermissionRuleView[];
  overrides: PermissionRuleView[];
  temporaryGrants: PermissionRuleView[];
}

export interface PermissionDiffView {
  sourceUserId: string;
  targetUserId: string;
  missingOnTarget: PermissionRuleView[];
  targetOnly: PermissionRuleView[];
  changed: PermissionRuleView[];
  excludedFromCopy: PermissionRuleView[];
}

export interface PermissionCopyResult {
  copyRecordId: string;
  sourceUserId: string;
  targetUserId: string;
  copyMode: string;
  beforeBindingId: string | null;
  afterBindingId: string | null;
  createdProfileId: string | null;
  createdProfileVersion: number | null;
  addedRuleCount: number;
  retainedRuleCount: number;
  excludedRules: PermissionRuleView[];
  replayed: boolean;
}

export interface PermissionCopyRecordView {
  id: string;
  sourceUserId: string;
  targetUserId: string;
  sourceProfileId: string | null;
  sourceProfileVersion: number | null;
  copyMode: 'copy_base' | 'append_missing' | 'replace_base' | 'save_template';
  beforeBindingId: string | null;
  afterBindingId: string | null;
  diff: Record<string, unknown>;
  excluded: Record<string, unknown>;
  idempotencyKey: string;
  createdBy: string;
  createdAt: string;
}

export interface ServiceAccountView {
  id: string;
  accountKey: string;
  name: string;
  description: string | null;
  ownerId: string;
  status: 'active' | 'disabled' | 'revoked';
  lastUsedAt: string | null;
  expiresAt: string | null;
  metadata: Record<string, unknown>;
  createdAt: string;
}

export interface ServiceAccountGrantView {
  id: string;
  serviceAccountId: string;
  resourceType: string;
  resourceId: string | null;
  resourceKey: string | null;
  action: string;
  effect: 'allow' | 'deny';
  reason: string;
  expiresAt: string | null;
  revokedAt: string | null;
  createdAt: string;
}

export interface SaveServiceAccountPayload {
  accountKey?: string;
  name: string;
  description?: string;
  ownerId: string;
  expiresAt?: string;
  metadata: Record<string, unknown>;
}

export interface ApiApplicationView {
  id: string;
  appKey: string;
  name: string;
  appType: 'embed' | 'open_api' | 'webhook' | 'internal';
  status: 'active' | 'disabled' | 'revoked';
  ownerId: string;
  callbackUrl: string | null;
  scopes: string[];
  config: Record<string, unknown>;
  expiresAt: string | null;
  createdAt: string;
}

export interface SaveApiApplicationPayload {
  appKey?: string;
  name: string;
  appType?: ApiApplicationView['appType'];
  ownerId: string;
  callbackUrl?: string;
  scopes: string[];
  config?: Record<string, unknown>;
  expiresAt?: string;
}

export interface ApiCredentialView {
  id: string;
  applicationId: string;
  serviceAccountId: string;
  keyPrefix: string;
  scopes: string[];
  status: string;
  lastUsedAt: string | null;
  expiresAt: string | null;
  createdAt: string;
}

export interface IssuedApiCredentialView {
  credential: ApiCredentialView;
  secret: string;
}

export interface AutomationTriggerView {
  id: string;
  triggerKey: string;
  name: string;
  triggerType: 'manual' | 'cron' | 'webhook';
  taskId: string;
  taskVersionId: string;
  taskRevisionNo: string;
  serviceAccountId: string;
  cronExpression: string | null;
  timezone: string | null;
  status: 'active' | 'paused' | 'error' | 'archived';
  misfirePolicy: 'skip' | 'fire_once' | 'catch_up' | null;
  maxCatchupCount: number | null;
  maxAttempts: number | null;
  inputTemplate: string | null;
  lastRunAt: string | null;
  nextRunAt: string | null;
  revisionNo: string;
  config: Record<string, unknown>;
  createdAt: string;
}

export interface CreateAutomationTriggerPayload {
  triggerKey: string;
  name: string;
  triggerType: 'manual' | 'cron' | 'webhook';
  taskId: string;
  taskVersionId: string;
  serviceAccountId: string;
  cronExpression?: string;
  timezone?: string;
  misfirePolicy?: 'skip' | 'fire_once' | 'catch_up';
  maxCatchupCount?: number;
  maxAttempts?: number;
  inputTemplate?: string;
  config: Record<string, unknown>;
}

export interface UpdateAutomationTriggerPayload {
  name: string;
  taskId: string;
  taskVersionId: string;
  serviceAccountId: string;
  cronExpression?: string;
  timezone?: string;
  misfirePolicy?: 'skip' | 'fire_once' | 'catch_up';
  maxCatchupCount?: number;
  maxAttempts?: number;
  inputTemplate?: string;
  status: AutomationTriggerView['status'];
  revisionNo: string;
  config: Record<string, unknown>;
}

export interface SystemUserView {
  userId: string;
  userName: string;
  nickName: string;
  email: string | null;
  phoneNumber?: string | null;
  deptId?: string | null;
  gender?: string | null;
  remark?: string | null;
  status: string;
  createTime: string;
  deptName: string | null;
}

export interface SystemRoleView {
  roleId: string;
  roleName: string;
  roleKey: string;
  status: string;
}

export interface SystemUserDetailView {
  user: SystemUserView | null;
  roleIds: string[];
  roles: SystemRoleView[];
  postIds?: string[];
}

export interface SaveSystemUserPayload {
  userId?: string;
  deptId?: string | null;
  userName: string;
  nickName: string;
  email?: string;
  phoneNumber?: string;
  gender?: string;
  password?: string;
  status?: string;
  remark?: string;
  roleIds: string[];
  postIds?: string[];
}

export interface PageResult<T> {
  rows: T[];
  total: number;
}

export function fetchApprovals(status?: ApprovalStatus, limit = 100) {
  return request<ApprovalView[]>({
    url: '/platform/approvals',
    method: 'get',
    params: { status, limit }
  });
}

export function decideApproval(
  approvalId: string,
  decision: 'approve' | 'reject',
  idempotencyKey: string,
  comment?: string
) {
  return request<ApprovalDecisionResult>({
    url: `/platform/approvals/${approvalId}/${decision}`,
    method: 'post',
    data: { idempotencyKey, comment: comment || undefined }
  });
}

export function fetchAuditEvents(params: AuditSearchParams) {
  return request<AuditEventView[]>({
    url: '/platform/audit-events',
    method: 'get',
    params
  });
}

export function fetchNotifications(category?: NotificationCategory, unreadOnly = false, beforeId?: string) {
  return request<NotificationView[]>({
    url: '/platform/notifications',
    method: 'get',
    params: { category, unreadOnly, beforeId, limit: 100 }
  });
}

export function fetchNotificationUnreadCount() {
  return request<number>({ url: '/platform/notifications/unread-count', method: 'get' });
}

export function markNotificationRead(notificationId: string) {
  return request<NotificationView>({
    url: `/platform/notifications/${notificationId}/read`,
    method: 'post'
  });
}

export function markAllNotificationsRead() {
  return request<number>({ url: '/platform/notifications/read-all', method: 'post' });
}

export function fetchRiskPolicies(params: RiskPolicySearchParams = {}) {
  return request<RiskPolicyView[]>({
    url: '/platform/risk-policies',
    method: 'get',
    params: { ...params, limit: params.limit || 100 }
  });
}

export function createRiskPolicy(data: SaveRiskPolicyPayload) {
  return request<RiskPolicyView>({ url: '/platform/risk-policies', method: 'post', data });
}

export function updateRiskPolicy(policyId: string, data: SaveRiskPolicyPayload) {
  return request<RiskPolicyView>({ url: `/platform/risk-policies/${policyId}`, method: 'put', data });
}

export function updateRiskPolicyStatus(policyId: string, status: RiskPolicyStatus) {
  return request<RiskPolicyView>({
    url: `/platform/risk-policies/${policyId}/status`,
    method: 'patch',
    data: { status }
  });
}

export function deleteRiskPolicy(policyId: string) {
  return request<void>({ url: `/platform/risk-policies/${policyId}`, method: 'delete' });
}

export function fetchWorkflowTemplates() {
  return request<WorkflowTemplateView[]>({ url: '/platform/workflows', method: 'get' });
}

export function fetchAllowedAgents(limit = 200) {
  return request<AgentOptionView[]>({
    url: '/platform/agents/allowed',
    method: 'get',
    params: { limit }
  });
}

export function fetchTasks(limit = 100) {
  return request<TaskView[]>({ url: '/platform/tasks', method: 'get', params: { limit } });
}

export function fetchTask(taskId: string) {
  return request<TaskView>({ url: `/platform/tasks/${taskId}`, method: 'get' });
}

export function createTask(payload: CreateTaskPayload) {
  return request<TaskMutationResult>({ url: '/platform/tasks', method: 'post', data: payload });
}

export function updateTask(taskId: string, payload: UpdateTaskPayload) {
  return request<TaskMutationResult>({ url: `/platform/tasks/${taskId}`, method: 'put', data: payload });
}

export function fetchTaskVersions(taskId: string, limit = 50) {
  return request<TaskVersionView[]>({
    url: `/platform/tasks/${taskId}/versions`,
    method: 'get',
    params: { limit }
  });
}

export function fetchTaskVersion(taskId: string, versionId: string) {
  return request<TaskVersionView>({ url: `/platform/tasks/${taskId}/versions/${versionId}`, method: 'get' });
}

export function fetchTaskRuns(taskId: string, limit = 100) {
  return request<TaskRunView[]>({
    url: `/platform/tasks/${taskId}/runs`,
    method: 'get',
    params: { limit }
  });
}

export function fetchTaskRun(taskId: string, runId: string) {
  return request<TaskRunView>({
    url: `/platform/tasks/${taskId}/runs/${runId}`,
    method: 'get'
  });
}

export function fetchRunSteps(taskId: string, runId: string) {
  return request<RunStepView[]>({
    url: `/platform/tasks/${taskId}/runs/${runId}/steps`,
    method: 'get'
  });
}

export function createTaskRun(taskId: string, idempotencyKey: string, input: string) {
  return request<TaskRunActionResult>({
    url: `/platform/tasks/${taskId}/runs`,
    method: 'post',
    data: { idempotencyKey, input }
  });
}

export function startTaskRun(taskId: string, runId: string) {
  return request<TaskRunActionResult>({
    url: `/platform/tasks/${taskId}/runs/${runId}/start`,
    method: 'post'
  });
}

export function cancelTaskRun(taskId: string, runId: string, reason?: string) {
  return request<TaskRunActionResult>({
    url: `/platform/tasks/${taskId}/runs/${runId}/cancel`,
    method: 'post',
    data: reason ? { reason } : {}
  });
}

export function pauseTaskRun(taskId: string, runId: string, reason?: string) {
  return request<TaskRunActionResult>({
    url: `/platform/tasks/${taskId}/runs/${runId}/pause`,
    method: 'post',
    data: reason ? { reason } : {}
  });
}

export function resumeTaskRun(taskId: string, runId: string) {
  return request<TaskRunActionResult>({
    url: `/platform/tasks/${taskId}/runs/${runId}/resume`,
    method: 'post'
  });
}

export function retryTaskRun(taskId: string, runId: string, idempotencyKey: string, autoStart = true) {
  return request<TaskRunActionResult>({
    url: `/platform/tasks/${taskId}/runs/${runId}/retry`,
    method: 'post',
    data: { idempotencyKey, autoStart }
  });
}

export function fetchTaskRunEvents(taskId: string, runId: string, cursor = 0, limit = 200) {
  return request<ExecutionEventView[]>({
    url: `/platform/tasks/${taskId}/runs/${runId}/events`,
    method: 'get',
    params: { cursor, limit }
  });
}

export async function streamTaskRunEvents(
  taskId: string,
  runId: string,
  cursor: number,
  onEvent: (event: ExecutionEventView) => void,
  signal: AbortSignal
) {
  const response = await fetch(platformRawUrl(
    `/platform/tasks/${taskId}/runs/${runId}/events/stream?cursor=${encodeURIComponent(cursor)}`
  ), {
    headers: platformRawHeaders('text/event-stream'),
    signal
  });
  if (!response.ok) throw new Error(await rawResponseMessage(response));
  if (!response.body) throw new Error('浏览器未提供事件流响应体');

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  while (!signal.aborted) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value, { stream: !done });
    const frames = buffer.split(/\r?\n\r?\n/);
    buffer = frames.pop() || '';
    for (const frame of frames) {
      const data = frame.split(/\r?\n/)
        .filter(line => line.startsWith('data:'))
        .map(line => line.slice(5).trimStart())
        .join('\n');
      if (data) onEvent(JSON.parse(data) as ExecutionEventView);
    }
    if (done) break;
  }
}

export function fetchTaskVisibility(taskId: string) {
  return request<TaskVisibilityView>({ url: `/platform/tasks/${taskId}/visibility`, method: 'get' });
}

export function fetchTaskParticipants(taskId: string, limit = 100) {
  return request<TaskParticipantView[]>({
    url: `/platform/tasks/${taskId}/participants`,
    method: 'get',
    params: { limit }
  });
}

export function putTaskParticipant(taskId: string, userId: string, type: Exclude<TaskParticipantType, 'owner'>) {
  return request<TaskParticipantView>({
    url: `/platform/tasks/${taskId}/participants/${userId}`,
    method: 'put',
    data: { type }
  });
}

export function removeTaskParticipant(taskId: string, userId: string, type: Exclude<TaskParticipantType, 'owner'>) {
  return request<void>({
    url: `/platform/tasks/${taskId}/participants/${userId}/${type}`,
    method: 'delete'
  });
}

export function fetchTaskResources(taskId: string) {
  return request<TaskResourceView[]>({ url: `/platform/tasks/${taskId}/resources`, method: 'get' });
}

export function fetchTaskAccessRules(taskId: string, limit = 100) {
  return request<TaskAccessRuleView[]>({
    url: `/platform/tasks/${taskId}/access-rules`,
    method: 'get',
    params: { limit }
  });
}

export function putTaskAccessRule(
  taskId: string,
  payload: Omit<TaskAccessRuleView, 'id' | 'taskId' | 'createdAt'>
) {
  return request<TaskAccessRuleView>({ url: `/platform/tasks/${taskId}/access-rules`, method: 'post', data: payload });
}

export function removeTaskAccessRule(taskId: string, ruleId: string) {
  return request<void>({ url: `/platform/tasks/${taskId}/access-rules/${ruleId}`, method: 'delete' });
}

export function fetchTaskArtifacts(taskId: string, runId?: string) {
  return request<ArtifactView[]>({
    url: `/platform/tasks/${taskId}/artifacts`,
    method: 'get',
    params: { runId, limit: 200 }
  });
}

export function fetchRunAcceptances(taskId: string, runId: string) {
  return request<AcceptanceView[]>({
    url: `/platform/tasks/${taskId}/runs/${runId}/acceptances`,
    method: 'get',
    params: { limit: 100 }
  });
}

export function decideRunAcceptance(
  taskId: string,
  runId: string,
  payload: {
    idempotencyKey: string;
    artifactIds: string[];
    result: AcceptanceView['result'];
    comment?: string;
    ruleResult: Record<string, unknown>;
  }
) {
  return request<AcceptanceDecisionResult>({
    url: `/platform/tasks/${taskId}/runs/${runId}/acceptances`,
    method: 'post',
    data: payload
  });
}

export function fetchConversations(limit = 50, search?: string) {
  return request<ConversationView[]>({
    url: '/platform/conversations',
    method: 'get',
    params: { limit, search: search?.trim() || undefined }
  });
}

export function fetchActiveConversation() {
  return request<ActiveConversationView>({
    url: '/api/v1/chat/active',
    method: 'get'
  });
}

export function setActiveConversation(conversationId: string) {
  return request<{ status: string }>({
    url: '/api/v1/chat/active',
    method: 'post',
    data: { conversation_id: conversationId }
  });
}

export function createConversation(payload: CreateConversationPayload) {
  return request<ConversationView>({ url: '/platform/conversations', method: 'post', data: payload });
}

export function createConversationBranch(
  conversationId: string,
  payload: CreateConversationBranchPayload
) {
  return request<ConversationView>({
    url: `/platform/conversations/${conversationId}/branches`,
    method: 'post',
    data: payload
  });
}

export function regenerateConversationMessage(
  conversationId: string,
  messageId: string,
  payload: CreateConversationBranchPayload
) {
  return request<ConversationBranchView>({
    url: `/platform/conversations/${conversationId}/messages/${messageId}/regenerate`,
    method: 'post',
    data: payload
  });
}

export function retryConversationTurn(
  conversationId: string,
  traceId: string,
  payload: RetryConversationTurnPayload,
) {
  return request<ConversationTurnView>({
    url: `/platform/conversations/${conversationId}/traces/${encodeURIComponent(traceId)}/retry`,
    method: 'post',
    data: payload,
  });
}

export function deleteConversation(conversationId: string) {
  return request<void>({ url: `/platform/conversations/${conversationId}`, method: 'delete' });
}

export function fetchConversationCanvases(conversationId: string) {
  return request<CanvasView[]>({
    url: `/platform/conversations/${conversationId}/canvases`,
    method: 'get'
  });
}

export function createConversationCanvas(conversationId: string, payload: SaveCanvasPayload) {
  return request<CanvasView>({
    url: `/platform/conversations/${conversationId}/canvases`,
    method: 'post',
    data: payload
  });
}

export function fetchConversationCanvas(conversationId: string, canvasId: string) {
  return request<CanvasView>({
    url: `/platform/conversations/${conversationId}/canvases/${canvasId}`,
    method: 'get'
  });
}

export function updateConversationCanvas(
  conversationId: string,
  canvasId: string,
  payload: UpdateCanvasPayload
) {
  return request<CanvasView>({
    url: `/platform/conversations/${conversationId}/canvases/${canvasId}`,
    method: 'put',
    data: payload
  });
}

export function deleteConversationCanvas(conversationId: string, canvasId: string, expectedVersion: number) {
  return request<void>({
    url: `/platform/conversations/${conversationId}/canvases/${canvasId}`,
    method: 'delete',
    params: { expectedVersion }
  });
}

export function fetchConversationCanvasVersions(conversationId: string, canvasId: string) {
  return request<CanvasVersionView[]>({
    url: `/platform/conversations/${conversationId}/canvases/${canvasId}/versions`,
    method: 'get'
  });
}

export function restoreConversationCanvasVersion(
  conversationId: string,
  canvasId: string,
  versionNo: number,
  expectedVersion: number
) {
  return request<CanvasView>({
    url: `/platform/conversations/${conversationId}/canvases/${canvasId}/versions/${versionNo}/restore`,
    method: 'post',
    data: { expectedVersion }
  });
}

export function saveConversationCanvasToWorkspace(
  conversationId: string,
  canvasId: string,
  payload: { path?: string; overwrite: boolean; expectedVersion: number }
) {
  return request<CanvasWorkspaceSaveView>({
    url: `/platform/conversations/${conversationId}/canvases/${canvasId}/save-to-workspace`,
    method: 'post',
    data: payload
  });
}

export function submitConversationFeedback(conversationId: string, payload: ConversationFeedbackPayload) {
  return request<ConversationFeedbackView>({
    url: `/platform/conversations/${conversationId}/feedback`,
    method: 'post',
    data: payload
  });
}

export function fetchConversationResourceScope(conversationId: string) {
  return request<ConversationResourceScopeView>({
    url: `/platform/conversations/${conversationId}/resource-scope`,
    method: 'get'
  });
}

export function updateConversationResourceScope(
  conversationId: string,
  payload: SaveConversationResourceScopePayload
) {
  return request<ConversationResourceScopeView>({
    url: `/platform/conversations/${conversationId}/resource-scope`,
    method: 'put',
    data: payload
  });
}

export function startConversationTurn(conversationId: string, payload: CreateConversationTurnPayload) {
  return request<ConversationTurnView>({
    url: `/platform/conversations/${conversationId}/messages`,
    method: 'post',
    data: payload
  });
}

export function fetchConversationTurn(conversationId: string, turnId: string) {
  return request<ConversationTurnView>({
    url: `/platform/conversations/${conversationId}/turns/${turnId}`,
    method: 'get'
  });
}

export function fetchActiveConversationTurn(conversationId: string) {
  return request<ConversationTurnView | null>({
    url: `/platform/conversations/${conversationId}/turns/active`,
    method: 'get'
  });
}

export function confirmRuntimeConfirmation(
  confirmationKey: string,
  payload: RuntimeConfirmationDecisionPayload
) {
  return request<RuntimeConfirmationDecisionResult>({
    url: `/platform/runtime-confirmations/${encodeURIComponent(confirmationKey)}/confirm`,
    method: 'post',
    data: payload
  });
}

export function fetchRuntimeConfirmation(confirmationKey: string) {
  return request<RuntimeConfirmationView>({
    url: `/platform/runtime-confirmations/${encodeURIComponent(confirmationKey)}`,
    method: 'get',
  });
}

export function cancelRuntimeConfirmation(
  confirmationKey: string,
  payload: RuntimeConfirmationDecisionPayload
) {
  return request<RuntimeConfirmationDecisionResult>({
    url: `/platform/runtime-confirmations/${encodeURIComponent(confirmationKey)}/cancel`,
    method: 'post',
    data: payload
  });
}

export function stopConversationTurn(conversationId: string, turnId: string, reason?: string) {
  return request<ConversationTurnView>({
    url: `/platform/conversations/${conversationId}/turns/${turnId}/stop`,
    method: 'post',
    data: reason ? { reason } : {}
  });
}

export function cancelConversationGlobally(
  conversationId: string,
  traceId?: string,
  reason?: string
) {
  return request<ConversationCancellationView>({
    url: '/api/v1/chat/cancel',
    method: 'post',
    data: {
      conversation_id: conversationId,
      ...(traceId ? { trace_id: traceId } : {}),
      ...(reason ? { reason } : {})
    }
  });
}

export function finalizeNhsConversation(conversationId: string) {
  return request<ConversationFinalizeView>({
    url: `/api/v1/chat/conversation/${encodeURIComponent(conversationId)}/finalize`,
    method: 'post'
  });
}

export function fetchConversationAttachments(conversationId: string, limit = 50) {
  return request<ConversationAttachmentView[]>({
    url: `/platform/conversations/${conversationId}/attachments`,
    method: 'get',
    params: { limit }
  });
}

export function uploadConversationAttachment(conversationId: string, file: File) {
  const form = new FormData();
  form.append('file', file);
  return request<ConversationAttachmentView>({
    url: `/platform/conversations/${conversationId}/attachments`,
    method: 'post',
    data: form
  });
}

export function fetchConversationMessages(conversationId: string, afterSequence = 0, limit = 500) {
  return request<ConversationMessageView[]>({
    url: `/platform/conversations/${conversationId}/messages`,
    method: 'get',
    params: { afterSequence, limit }
  });
}

export function fetchNhsV1ConversationHistory(
  conversationId: string,
  limit = 50,
  offset = 0
) {
  return request<NhsV1ConversationHistoryView>({
    url: `/api/v1/chat/conversation/${encodeURIComponent(conversationId)}`,
    method: 'get',
    params: { limit, offset }
  });
}

export function fetchNhsV1ChatHistory(params: NhsV1ChatHistoryQuery = {}) {
  return request<NhsV1ChatHistoryPage | NhsV1ChatHistoryItem[]>({
    url: '/api/v1/chat/history',
    method: 'get',
    params
  });
}

export function deleteNhsV1HistoryTrace(traceId: string) {
  return request<NhsV1HistoryDeletionView>({
    url: `/api/v1/chat/history/${encodeURIComponent(traceId)}`,
    method: 'delete'
  });
}

export function deleteNhsV1HistoryBatch(conversationIds: Array<string | number>) {
  return request<NhsV1HistoryBatchDeletionView>({
    url: '/api/v1/chat/history/batch-delete',
    method: 'post',
    data: { conversation_ids: conversationIds }
  });
}

export function fetchConversationEvents(conversationId: string, cursor = 0, limit = 200) {
  return request<ExecutionEventView[]>({
    url: `/platform/conversations/${conversationId}/events`,
    method: 'get',
    params: { cursor, limit }
  });
}

export function fetchNhsV1TraceLogs(traceId: string) {
  return request<NhsV1TraceLogView>({
    url: `/api/v1/chat/logs/${encodeURIComponent(traceId)}`,
    method: 'get'
  });
}

/**
 * Loads the owner-authorized global task-run projection used by the Nhs V1
 * task center. The backend applies the administrator/owner visibility rule.
 */
export function fetchExecutionHistory(params: NhsV1ExecutionHistoryQuery = {}) {
  return request<NhsV1ExecutionHistoryPage>({
    url: '/api/v1/tasks/execution-history',
    method: 'get',
    params
  });
}

/** Explicit Nhs naming for callers that keep V1 compatibility APIs grouped. */
export const fetchNhsV1ExecutionHistory = fetchExecutionHistory;
export const fetchTaskExecutionHistory = fetchExecutionHistory;

export function previewConversationTask(conversationId: string, payload: ConvertConversationPayload) {
  return request<TaskDraftView>({
    url: `/platform/conversations/${conversationId}/task-draft`,
    method: 'post',
    data: payload
  });
}

export function convertConversationToTask(conversationId: string, payload: ConvertConversationPayload) {
  return request<TaskConversionResult>({
    url: `/platform/conversations/${conversationId}/convert-task`,
    method: 'post',
    data: payload
  });
}

export function fetchWorkspaceFiles(path = '') {
  return request<WorkspaceFileEntry[]>({
    url: '/api/v1/chat/fs/list',
    method: 'get',
    params: { path }
  });
}

export function previewWorkspaceFile(path: string) {
  return request<WorkspaceFilePreview>({
    url: '/api/v1/chat/fs/preview',
    method: 'get',
    params: { path }
  });
}

export function writeWorkspaceFile(path: string, content: string) {
  return request<WorkspaceFileEntry>({
    url: '/api/v1/chat/fs/write',
    method: 'put',
    data: { path, content }
  });
}

export function createWorkspaceEntry(parentPath: string, name: string, kind: 'file' | 'directory') {
  return request<WorkspaceFileEntry>({
    url: '/api/v1/chat/fs/create-entry',
    method: 'post',
    data: { parent_path: parentPath, name, kind }
  });
}

export function renameWorkspaceEntry(path: string, newName: string) {
  return request<WorkspaceFileEntry>({
    url: '/api/v1/chat/fs/rename-entry',
    method: 'post',
    data: { path, new_name: newName }
  });
}

export function deleteWorkspaceEntry(path: string) {
  return request<{ trash_id: string; path: string; deleted: boolean }>({
    url: '/api/v1/chat/fs/delete-entry',
    method: 'post',
    data: { path }
  });
}

export function fetchWorkspaceTrash() {
  return request<WorkspaceTrashEntry[]>({ url: '/api/v1/chat/fs/trash', method: 'get' });
}

export function restoreWorkspaceEntry(trashId: string) {
  return request<{ trash_id: string; path: string; restored: boolean }>({
    url: '/api/v1/chat/fs/restore-entry',
    method: 'post',
    data: { trash_id: trashId }
  });
}

export function purgeWorkspaceEntry(trashId: string) {
  return request<{ trash_id: string; purged: boolean }>({
    url: '/api/v1/chat/fs/purge-entry',
    method: 'post',
    data: { trash_id: trashId }
  });
}

export function emptyWorkspaceTrash() {
  return request<{ purged_count: number; emptied: boolean }>({
    url: '/api/v1/chat/fs/empty-trash',
    method: 'post'
  });
}

export function uploadWorkspaceFile(path: string, file: File) {
  const form = new FormData();
  form.append('file', file);
  return request<WorkspaceFileEntry>({
    url: '/api/v1/chat/fs/upload',
    method: 'post',
    params: { path },
    data: form
  });
}

export function searchWorkspaceFiles(query: string, path = '') {
  return request<WorkspaceFileEntry[]>({
    url: '/api/v1/chat/fs/search',
    method: 'get',
    params: { query, path }
  });
}

export function fetchRecentWorkspaceFiles(path?: string, limit = 20) {
  return request<WorkspaceRecentFiles>({
    url: '/api/v1/chat/fs/recent-files',
    method: 'get',
    params: { path: path || undefined, limit }
  });
}

export function updateRecentWorkspaceFiles(items: WorkspaceFileEntry[]) {
  return request<WorkspaceRecentFiles>({
    url: '/api/v1/chat/fs/recent-files',
    method: 'put',
    data: { items }
  });
}

export function fetchWorkspaceBrowserPreferences() {
  return request<WorkspaceBrowserPreferences>({
    url: '/api/v1/chat/fs/browser-prefs',
    method: 'get'
  });
}

export function updateWorkspaceBrowserPreferences(payload: WorkspaceBrowserPreferences) {
  return request<WorkspaceBrowserPreferences>({
    url: '/api/v1/chat/fs/browser-prefs',
    method: 'put',
    data: payload
  });
}

export function fetchModels(modelType?: ModelView['modelType'], includeInactive = false) {
  return request<ModelView[]>({
    url: '/platform/models',
    method: 'get',
    params: { modelType, includeInactive, limit: 200 }
  });
}

export function createModel(payload: SaveModelPayload & { modelKey: string }) {
  return request<ModelView>({ url: '/platform/models', method: 'post', data: payload });
}

export function updateModel(modelId: string, payload: SaveModelPayload) {
  const { modelKey: _modelKey, ...data } = payload;
  return request<ModelView>({ url: `/platform/models/${modelId}`, method: 'put', data });
}

export function deleteModel(modelId: string) {
  return request<void>({ url: `/platform/models/${modelId}`, method: 'delete' });
}

export function testModel(modelId: string) {
  return request<ModelConnectionView>({ url: `/platform/models/${modelId}/test`, method: 'post' });
}

export function testModelConfig(payload: Omit<SaveModelPayload, 'modelKey' | 'displayName' | 'status'> & { existingModelId?: string }) {
  return request<ModelConnectionView>({ url: '/platform/models/test-config', method: 'post', data: payload });
}

export function discoverModels(payload: {
  existingModelId?: string;
  providerType: SaveModelPayload['providerType'];
  endpointUrl?: string;
  apiKey?: string;
}) {
  return request<ModelOptionView[]>({ url: '/platform/models/discover', method: 'post', data: payload });
}

export function fetchModelReferences(modelId: string) {
  return request<ModelReferenceView[]>({ url: `/platform/models/${modelId}/references`, method: 'get' });
}

export function fetchAgents(search?: string, includeArchived = false) {
  return request<AgentView[]>({
    url: '/platform/agents',
    method: 'get',
    params: { search, includeArchived, limit: 200 }
  });
}

export function createAgent(payload: SaveAgentPayload & { agentKey: string }) {
  return request<AgentView>({ url: '/platform/agents', method: 'post', data: payload });
}

export function onboardAgent(payload: {
  onboardingKey: string;
  agent: SaveAgentPayload & { agentKey: string };
  version: SaveAgentVersionPayload;
}) {
  return request<AgentOnboardingResult>({ url: '/platform/agents/onboarding', method: 'post', data: payload });
}

export function reorderAgents(items: Array<{ id: string; sortOrder: number }>) {
  return request<void>({ url: '/platform/agents/reorder', method: 'post', data: { items } });
}

export function updateAgent(agentId: string, payload: SaveAgentPayload) {
  const { agentKey: _agentKey, ...data } = payload;
  return request<AgentView>({ url: `/platform/agents/${agentId}`, method: 'put', data });
}

export function updateAgentStatus(agentId: string, status: 'active' | 'disabled' | 'archived') {
  return request<AgentView>({ url: `/platform/agents/${agentId}/status`, method: 'patch', data: { status } });
}

export function deleteAgent(agentId: string) {
  return request<void>({ url: `/platform/agents/${agentId}`, method: 'delete' });
}

export function fetchAgentVersions(agentId: string) {
  return request<AgentVersionView[]>({ url: `/platform/agents/${agentId}/versions`, method: 'get' });
}

export function fetchAgentWelcomeCards(agentId: string) {
  return request<{ cards: AgentWelcomeCardView[] }>({
    url: `/platform/agents/${agentId}/welcome-cards`,
    method: 'get'
  });
}

export function fetchAgentExecutionHistory(agentId: string, limit = 100) {
  return request<AgentExecutionHistoryView[]>({
    url: `/platform/agents/${agentId}/executions`,
    method: 'get',
    params: { limit }
  });
}

export function createAgentVersion(agentId: string, payload: SaveAgentVersionPayload) {
  return request<AgentVersionView>({ url: `/platform/agents/${agentId}/versions`, method: 'post', data: payload });
}

export function updateAgentVersion(agentId: string, versionId: string, payload: SaveAgentVersionPayload) {
  return request<AgentVersionView>({
    url: `/platform/agents/${agentId}/versions/${versionId}`,
    method: 'put',
    data: payload
  });
}

export function cloneAgentVersion(agentId: string, versionId: string) {
  return request<AgentVersionView>({
    url: `/platform/agents/${agentId}/versions/${versionId}/clone`,
    method: 'post'
  });
}

export function deleteAgentVersion(agentId: string, versionId: string) {
  return request<void>({ url: `/platform/agents/${agentId}/versions/${versionId}`, method: 'delete' });
}

export function publishAgentVersion(agentId: string, versionId: string) {
  return request<AgentVersionPublishResult>({
    url: `/platform/agents/${agentId}/versions/${versionId}/publish`,
    method: 'post'
  });
}

export function archiveAgentVersion(agentId: string, versionId: string) {
  return request<AgentVersionView>({
    url: `/platform/agents/${agentId}/versions/${versionId}/archive`,
    method: 'post'
  });
}

export function fetchProjects(status?: ProjectView['status']) {
  return request<ProjectView[]>({ url: '/platform/projects', method: 'get', params: { status, limit: 200 } });
}

export function createProject(payload: SaveProjectPayload & { idempotencyKey: string }) {
  return request<ProjectMutationResult>({ url: '/platform/projects', method: 'post', data: payload });
}

export function updateProject(projectId: string, payload: SaveProjectPayload) {
  return request<ProjectView>({ url: `/platform/projects/${projectId}`, method: 'put', data: payload });
}

export function updateProjectStatus(projectId: string, status: ProjectView['status']) {
  return request<ProjectView>({ url: `/platform/projects/${projectId}/status`, method: 'patch', data: { status } });
}

export function fetchProjectMembers(projectId: string) {
  return request<ProjectMemberView[]>({
    url: `/platform/projects/${projectId}/members`,
    method: 'get',
    params: { limit: 500 }
  });
}

export function putProjectMember(projectId: string, userId: string, role: Exclude<ProjectMemberView['role'], 'owner'>) {
  return request<ProjectMemberView>({
    url: `/platform/projects/${projectId}/members/${userId}`,
    method: 'put',
    data: { role }
  });
}

export function removeProjectMember(projectId: string, userId: string) {
  return request<void>({ url: `/platform/projects/${projectId}/members/${userId}`, method: 'delete' });
}

export function fetchMemories(scopeType: MemoryView['scopeType'], scopeId: string, search?: string) {
  return request<MemoryView[]>({
    url: `/platform/memories/${scopeType}/${scopeId}`,
    method: 'get',
    params: { search, limit: 500 }
  });
}

export function createMemory(scopeType: MemoryView['scopeType'], scopeId: string, payload: SaveMemoryPayload & { memoryKey: string }) {
  return request<MemoryView>({ url: `/platform/memories/${scopeType}/${scopeId}`, method: 'post', data: payload });
}

export function updateMemory(memoryId: string, expectedRevision: string, payload: SaveMemoryPayload) {
  const { memoryKey: _memoryKey, ...data } = payload;
  return request<MemoryView>({
    url: `/platform/memories/${memoryId}`,
    method: 'put',
    data: { ...data, expectedRevision }
  });
}

export function reviewMemory(memoryId: string, expectedRevision: string, decision: 'approved' | 'rejected', comment?: string) {
  return request<MemoryView>({
    url: `/platform/memories/${memoryId}/review`,
    method: 'post',
    data: { expectedRevision, decision, comment: comment || undefined }
  });
}

export function deleteMemory(memoryId: string, expectedRevision: string) {
  return request<void>({
    url: `/platform/memories/${memoryId}`,
    method: 'delete',
    params: { expectedRevision }
  });
}

export function fetchConnectors(includeInactive = true, scope?: ConnectorView['scope']) {
  return request<ConnectorView[]>({
    url: '/platform/connectors',
    method: 'get',
    params: { includeInactive, scope, limit: 200 }
  });
}

export function previewMcpServersImport(document: Record<string, unknown>) {
  return request<McpServersImportPreviewView>({
    url: '/platform/connectors/mcp/import/preview',
    method: 'post',
    data: { document }
  });
}

export function importMcpServer(payload: McpServerImportPayload) {
  return request<ConnectorView>({
    url: '/platform/connectors/mcp/import',
    method: 'post',
    data: payload
  });
}

export function createConnector(payload: SaveConnectorPayload & { connectorKey: string }) {
  return request<ConnectorView>({ url: '/platform/connectors', method: 'post', data: payload });
}

export function updateConnector(connectorId: string, expectedRevision: string, payload: SaveConnectorPayload) {
  const { connectorKey: _connectorKey, ...data } = payload;
  return request<ConnectorView>({
    url: `/platform/connectors/${connectorId}`,
    method: 'put',
    data: { ...data, expectedRevision }
  });
}

export function deleteConnector(connectorId: string, expectedRevision: string) {
  return request<void>({
    url: `/platform/connectors/${connectorId}`,
    method: 'delete',
    params: { expectedRevision }
  });
}

export function discoverConnector(connectorId: string) {
  return request<McpDiscoveryView>({ url: `/platform/connectors/${connectorId}/discover`, method: 'post' });
}

export function testConnector(connectorId: string) {
  return request<McpConnectionTestView>({ url: `/platform/connectors/${connectorId}/test`, method: 'post' });
}

export function testConnectorDraft(payload: McpConnectionTestPayload) {
  return request<McpConnectionTestView>({ url: '/platform/connectors/mcp/test', method: 'post', data: payload });
}

export function validateMcpWizard(payload: {
  step: number;
  connectorKey: string;
  name: string;
  endpointUrl: string;
  namespace: string;
  transport: 'streamable_http' | 'sse';
  authType: 'none' | 'bearer' | 'header';
  credentialRef?: string;
  config: Record<string, unknown>;
}) {
  return request<McpWizardValidationView>({
    url: '/platform/connectors/mcp/wizard/validate', method: 'post', data: payload
  });
}

export function publishMcpWizard(connectorId: string, expectedRevision: string, namespace: string) {
  return request<ConnectorView>({
    url: `/platform/connectors/${connectorId}/mcp/wizard/publish`,
    method: 'post', data: { expectedRevision, namespace }
  });
}

export function fetchConnectorDiscoveries(connectorId: string) {
  return request<McpDiscoveryView[]>({
    url: `/platform/connectors/${connectorId}/discoveries`,
    method: 'get',
    params: { limit: 20 }
  });
}

export function fetchConnectorRuntime(connectorId: string) {
  return request<McpRuntimeOverviewView>({
    url: `/platform/connectors/${connectorId}/runtime`,
    method: 'get',
    params: { mountLimit: 20, usageLimit: 50 }
  });
}

export function fetchMcpConnectorUsage(connectorId: string) {
  return request<McpConnectorUsageView>({
    url: `/platform/connectors/${connectorId}/usage`,
    method: 'get'
  });
}

export function fetchTools(includeInactive = true) {
  return request<ToolView[]>({ url: '/platform/tools', method: 'get', params: { includeInactive, limit: 500 } });
}

export function fetchConnectorTools(connectorId: string, includeInactive = true) {
  return request<ToolView[]>({
    url: '/platform/tools',
    method: 'get',
    params: { toolType: 'mcp', connectorId, includeInactive, limit: 500 }
  });
}

export function fetchAvailableTools() {
  return request<ToolView[]>({ url: '/platform/tools/available', method: 'get', params: { limit: 500 } });
}

export function fetchBuiltinTools() {
  return request<BuiltinToolDescriptor[]>({ url: '/platform/tools/builtins', method: 'get' });
}

export function createTool(payload: SaveToolPayload & { toolKey: string }) {
  return request<ToolView>({ url: '/platform/tools', method: 'post', data: payload });
}

export function createToolVersion(toolKey: string, payload: SaveToolPayload) {
  const { toolKey: _toolKey, ...data } = payload;
  return request<ToolView>({ url: `/platform/tools/key/${toolKey}/versions`, method: 'post', data });
}

export function updateToolStatus(toolId: string, expectedStatus: ToolView['status'], status: ToolView['status']) {
  return request<ToolView>({
    url: `/platform/tools/${toolId}/status`,
    method: 'patch',
    data: { expectedStatus, status }
  });
}

export function testTool(toolId: string, payload: ToolOnlineTestPayload) {
  return request<ToolOnlineTestView>({ url: `/platform/tools/${toolId}/test`, method: 'post', data: payload });
}

export function deleteTool(toolId: string) {
  return request<void>({ url: `/platform/tools/${toolId}`, method: 'delete' });
}

export function fetchSkills(includeInactive = true) {
  return request<SkillView[]>({ url: '/platform/skills', method: 'get', params: { includeInactive, limit: 500 } });
}

export function createSkill(payload: SaveSkillPayload & { skillKey: string }) {
  return request<SkillView>({ url: '/platform/skills', method: 'post', data: payload });
}

export function updateSkill(skillId: string, name: string, description: string | undefined, expectedRevision: string) {
  return request<SkillView>({
    url: `/platform/skills/${skillId}`,
    method: 'put',
    data: { name, description, expectedRevision }
  });
}

export function updateSkillStatus(
  skillId: string,
  expectedStatus: 'active' | 'disabled',
  status: 'active' | 'disabled',
  expectedRevision: string
) {
  return request<SkillView>({
    url: `/platform/skills/${skillId}/status`,
    method: 'patch',
    data: { expectedStatus, status, expectedRevision }
  });
}

export function deleteSkill(skillId: string, expectedRevision: string) {
  return request<void>({
    url: `/platform/skills/${skillId}`,
    method: 'delete',
    params: { expectedRevision }
  });
}

export function fetchSkillVersions(skillId: string) {
  return request<SkillVersionView[]>({ url: `/platform/skills/${skillId}/versions`, method: 'get' });
}

export function fetchSkillDependencyInstall(skillId: string, versionId: string) {
  return request<SkillDependencyInstallView>({
    url: `/platform/skills/${skillId}/versions/${versionId}/dependencies`, method: 'get'
  });
}

export function installSkillDependencies(skillId: string, versionId: string) {
  return request<SkillDependencyInstallView>({
    url: `/platform/skills/${skillId}/versions/${versionId}/dependencies/install`, method: 'post'
  });
}

export function createSkillVersion(
  skillId: string,
  payload: { content: string; manifest: Record<string, unknown>; runtimeRequirements: Record<string, unknown>; expectedRevision: string }
) {
  return request<SkillVersionView>({ url: `/platform/skills/${skillId}/versions`, method: 'post', data: payload });
}

export function cloneSkillVersion(skillId: string, versionId: string, expectedRevision: string) {
  return request<SkillVersionView>({
    url: `/platform/skills/${skillId}/versions/${versionId}/clone`,
    method: 'post',
    data: { expectedRevision }
  });
}

export function deleteSkillVersion(skillId: string, versionId: string, expectedRevision: string) {
  return request<void>({
    url: `/platform/skills/${skillId}/versions/${versionId}`,
    method: 'delete',
    params: { expectedRevision }
  });
}

export function publishSkillVersion(skillId: string, versionId: string, expectedRevision: string) {
  return request<SkillVersionView>({
    url: `/platform/skills/${skillId}/versions/${versionId}/publish`,
    method: 'post',
    data: { expectedRevision }
  });
}

export function archiveSkillVersion(skillId: string, versionId: string, expectedRevision: string) {
  return request<SkillVersionView>({
    url: `/platform/skills/${skillId}/versions/${versionId}/archive`,
    method: 'patch',
    data: { expectedRevision }
  });
}

export function fetchSkillFiles(skillId: string, versionId: string) {
  return request<SkillFileView[]>({ url: `/platform/skills/${skillId}/versions/${versionId}/files`, method: 'get' });
}

export function fetchSkillFile(skillId: string, versionId: string, path: string) {
  return request<SkillFileView>({
    url: `/platform/skills/${skillId}/versions/${versionId}/files/content`,
    method: 'get',
    params: { path }
  });
}

export function putSkillFile(skillId: string, versionId: string, path: string, content: string) {
  return request<SkillFileView>({
    url: `/platform/skills/${skillId}/versions/${versionId}/files`,
    method: 'put',
    data: { path, content }
  });
}

export function createSkillFileEntry(
  skillId: string,
  versionId: string,
  path: string,
  kind: 'file' | 'directory'
) {
  return request<SkillFileView>({
    url: `/platform/skills/${skillId}/versions/${versionId}/files`,
    method: 'post',
    data: { path, kind }
  });
}

export function uploadSkillFile(skillId: string, versionId: string, path: string, file: File) {
  const form = new FormData();
  form.append('file', file);
  return request<SkillFileView>({
    url: `/platform/skills/${skillId}/versions/${versionId}/files/upload`,
    method: 'post',
    params: { path },
    data: form
  });
}

export function deleteSkillFile(skillId: string, versionId: string, path: string) {
  return request<void>({
    url: `/platform/skills/${skillId}/versions/${versionId}/files`,
    method: 'delete',
    params: { path }
  });
}

export function fetchSkillPublicationStatus(skillId: string) {
  return request<SkillPublicationView>({
    url: `/api/portal/skills/personal/${skillId}/publication-status`,
    method: 'get'
  });
}

export function submitSkillPublication(skillId: string) {
  return request<SkillPublicationView>({
    url: `/api/portal/skills/personal/${skillId}/publication-requests`,
    method: 'post'
  });
}

export function withdrawSkillPublication(skillId: string) {
  return request<SkillPublicationView>({
    url: `/api/portal/skills/personal/${skillId}/publication-requests/withdraw`,
    method: 'post'
  });
}

export function fetchPendingSkillPublications(limit = 100) {
  return request<SkillPublicationView[]>({
    url: '/api/portal/skills/publication-requests',
    method: 'get',
    params: { limit }
  });
}

export function fetchSkillPublicationDetail(versionId: string) {
  return request<SkillPublicationView>({
    url: `/api/portal/skills/publication-requests/${versionId}`,
    method: 'get'
  });
}

export function approveSkillPublication(versionId: string) {
  return request<SkillPublicationView>({
    url: `/api/portal/skills/publication-requests/${versionId}/approve`,
    method: 'post'
  });
}

export function rejectSkillPublication(versionId: string, comment: string) {
  return request<SkillPublicationView>({
    url: `/api/portal/skills/publication-requests/${versionId}/reject`,
    method: 'post',
    data: { comment }
  });
}

export function uploadSkillArchive(skillId: string, versionId: string, file: File) {
  const form = new FormData();
  form.append('file', file);
  return request<SkillFileView[]>({
    url: `/platform/skills/${skillId}/versions/${versionId}/files/upload-archive`,
    method: 'post',
    data: form
  });
}

export async function downloadSkillArchive(skillId: string, versionId: string) {
  const response = await fetch(platformRawUrl(
    `/platform/skills/${skillId}/versions/${versionId}/files/archive`
  ), { headers: platformRawHeaders('application/zip') });
  return {
    blob: await rawDownloadBlob(response),
    fileName: responseFileName(response.headers.get('content-disposition'))
      || `skill-${skillId}-version-${versionId}.zip`
  };
}

export function fetchLegacyExecutionArchives(params: {
  traceId?: string;
  executionId?: string;
  sourceStatus?: string;
  beforeId?: string;
  limit?: number;
}) {
  return request<LegacyExecutionArchiveView[]>({
    url: '/platform/migration/legacy-executions',
    method: 'get',
    params
  });
}

export function fetchRuntimeStatus() {
  return request<RuntimeStatusView>({ url: '/platform/runtime/status', method: 'get' });
}

export async function createEmbedSession(
  apiKey: string,
  payload: { agentVersionId: string; externalUserKey: string; expiresInMinutes?: number }
) {
  const response = await fetch(platformRawUrl('/open/v1/embed/sessions'), {
    method: 'post',
    headers: machineRawHeaders(apiKey, 'application/json'),
    body: JSON.stringify(payload)
  });
  const body = await rawJsonResponse<{ data: EmbedSessionView } | EmbedSessionView>(response);
  if (isOpenApiEnvelope<EmbedSessionView>(body)) return body.data;
  return body;
}

export async function streamEmbedChat(
  apiKey: string,
  sessionId: string,
  payload: { idempotencyKey: string; input: string },
  onEvent: (event: EmbedStreamEvent) => void,
  signal: AbortSignal
) {
  const response = await fetch(platformRawUrl(`/open/v1/embed/sessions/${encodeURIComponent(sessionId)}/messages`), {
    method: 'post',
    headers: machineRawHeaders(apiKey, 'text/event-stream', true),
    body: JSON.stringify(payload),
    signal
  });
  if (!response.ok) throw new Error(await rawResponseMessage(response));
  if (!response.body) throw new Error('浏览器未提供 Embed 事件流响应体');
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  while (!signal.aborted) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value, { stream: !done });
    const frames = buffer.split(/\r?\n\r?\n/);
    buffer = frames.pop() || '';
    for (const frame of frames) {
      let event = 'message';
      let id: string | undefined;
      const dataLines: string[] = [];
      for (const line of frame.split(/\r?\n/)) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('id:')) id = line.slice(3).trim();
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart());
      }
      const rawData = dataLines.join('\n');
      if (rawData) {
        const parsed = JSON.parse(rawData) as Record<string, unknown>;
        onEvent({ event, id, data: parsed });
      }
    }
    if (done) break;
  }
}

export async function startChatCodeExecution(
  payload: { language: string; code: string; conversationId: string },
  onEvent: (event: ChatCodeStreamEvent) => void,
  signal: AbortSignal
) {
  const headers = platformRawHeaders('text/event-stream');
  headers.set('Content-Type', 'application/json');
  const response = await fetch(platformRawUrl('/api/v1/chat/code-executions/stream'), {
    method: 'post',
    headers,
    body: JSON.stringify({
      language: payload.language,
      code: payload.code,
      conversation_id: payload.conversationId
    }),
    signal
  });
  await consumeChatCodeStream(response, onEvent, signal);
}

export async function resumeChatCodeExecution(
  executionId: string,
  conversationId: string,
  cursor: number,
  onEvent: (event: ChatCodeStreamEvent) => void,
  signal: AbortSignal
) {
  const headers = platformRawHeaders('text/event-stream');
  if (cursor > 0) headers.set('Last-Event-ID', String(cursor));
  const response = await fetch(platformRawUrl(
    `/api/v1/chat/code-executions/${encodeURIComponent(executionId)}/stream`
    + `?conversation_id=${encodeURIComponent(conversationId)}&cursor=${encodeURIComponent(cursor)}`
  ), { headers, signal });
  await consumeChatCodeStream(response, onEvent, signal);
}

export function fetchChatCodeExecution(executionId: string) {
  return request<ChatCodeExecutionView>({
    url: `/api/v1/chat/code-executions/${executionId}`,
    method: 'get'
  });
}

export function fetchChatCodeExecutions(conversationId: string, limit = 20) {
  return request<ChatCodeExecutionView[]>({
    url: '/api/v1/chat/code-executions',
    method: 'get',
    params: { conversation_id: conversationId, limit }
  });
}

export function stopChatCodeExecution(executionId: string, conversationId: string) {
  return request<ChatCodeExecutionView>({
    url: `/api/v1/chat/code-executions/${executionId}/stop`,
    method: 'post',
    data: { conversation_id: conversationId }
  });
}

async function consumeChatCodeStream(
  response: Response,
  onEvent: (event: ChatCodeStreamEvent) => void,
  signal: AbortSignal
) {
  if (!response.ok) throw new Error(await rawResponseMessage(response));
  if (!response.body) throw new Error('浏览器未提供代码执行事件流响应体');
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  while (!signal.aborted) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value, { stream: !done });
    const frames = buffer.split(/\r?\n\r?\n/);
    buffer = frames.pop() || '';
    for (const frame of frames) {
      let event = 'message';
      let id: string | undefined;
      const dataLines: string[] = [];
      for (const line of frame.split(/\r?\n/)) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('id:')) id = line.slice(3).trim();
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart());
      }
      const rawData = dataLines.join('\n');
      if (rawData) onEvent({ event, id, data: JSON.parse(rawData) as Record<string, unknown> });
    }
    if (done) break;
  }
}

export function fetchKnowledgeBases(search?: string, includeInactive = false) {
  return request<KnowledgeBaseView[]>({
    url: '/platform/knowledge-bases',
    method: 'get',
    params: { search, includeInactive, limit: 500 }
  });
}

export function createKnowledgeBase(payload: SaveKnowledgeBasePayload & { knowledgeKey: string }) {
  const { status: _status, expectedRevision: _expectedRevision, ...data } = payload;
  return request<KnowledgeBaseView>({ url: '/platform/knowledge-bases', method: 'post', data });
}

export function updateKnowledgeBase(baseId: string, payload: SaveKnowledgeBasePayload & { expectedRevision: string }) {
  const { knowledgeKey: _knowledgeKey, ...data } = payload;
  return request<KnowledgeBaseView>({ url: `/platform/knowledge-bases/${baseId}`, method: 'put', data });
}

export function deleteKnowledgeBase(baseId: string, expectedRevision: string) {
  return request<void>({
    url: `/platform/knowledge-bases/${baseId}`,
    method: 'delete',
    params: { expectedRevision }
  });
}

export function fetchKnowledgeDocuments(baseId: string) {
  return request<KnowledgeDocumentView[]>({
    url: `/platform/knowledge-bases/${baseId}/documents`,
    method: 'get',
    params: { limit: 500 }
  });
}

export function fetchKnowledgeTree(baseId: string) {
  return request<KnowledgeTreeView>({
    url: `/platform/knowledge-bases/${baseId}/tree`,
    method: 'get'
  });
}

export function fetchKnowledgeDirectories(baseId: string) {
  return request<KnowledgeDirectoryView[]>({
    url: `/platform/knowledge-bases/${baseId}/directories`,
    method: 'get'
  });
}

export function fetchKnowledgeDirectoryAcls(baseId: string, directoryId?: string | null) {
  return request<KnowledgeDirectoryAclView[]>({
    url: `/platform/knowledge-bases/${baseId}/directory-acls`,
    method: 'get',
    params: directoryId ? { directoryId, root: false } : { root: true }
  });
}

export function putKnowledgeDirectoryAcl(baseId: string, payload: PutKnowledgeDirectoryAclPayload) {
  return request<KnowledgeDirectoryAclView>({
    url: `/platform/knowledge-bases/${baseId}/directory-acls`,
    method: 'put',
    data: payload
  });
}

export function revokeKnowledgeDirectoryAcl(baseId: string, aclId: string, expectedRevision: string) {
  return request<void>({
    url: `/platform/knowledge-bases/${baseId}/directory-acls/${aclId}`,
    method: 'delete',
    params: { expectedRevision }
  });
}

export function createKnowledgeDirectory(baseId: string, payload: SaveKnowledgeDirectoryPayload) {
  const { expectedRevision: _expectedRevision, ...data } = payload;
  return request<KnowledgeDirectoryView>({
    url: `/platform/knowledge-bases/${baseId}/directories`,
    method: 'post',
    data
  });
}

export function updateKnowledgeDirectory(
  baseId: string,
  directoryId: string,
  payload: SaveKnowledgeDirectoryPayload & { expectedRevision: string }
) {
  return request<KnowledgeDirectoryView>({
    url: `/platform/knowledge-bases/${baseId}/directories/${directoryId}`,
    method: 'put',
    data: payload
  });
}

export function deleteKnowledgeDirectory(baseId: string, directoryId: string, expectedRevision: string) {
  return request<void>({
    url: `/platform/knowledge-bases/${baseId}/directories/${directoryId}`,
    method: 'delete',
    params: { expectedRevision }
  });
}

export function updateKnowledgeDocument(
  baseId: string,
  documentId: string,
  payload: UpdateKnowledgeDocumentPayload
) {
  return request<KnowledgeDocumentView>({
    url: `/platform/knowledge-bases/${baseId}/documents/${documentId}`,
    method: 'put',
    data: payload
  });
}

export function fetchKnowledgeChunks(baseId: string, documentId: string, offset = 0, limit = 50) {
  return request<KnowledgeChunkView[]>({
    url: `/platform/knowledge-bases/${baseId}/documents/${documentId}/chunks`,
    method: 'get',
    params: { offset, limit }
  });
}

export function uploadKnowledgeDocument(baseId: string, file: File, directoryId?: string | null) {
  const form = new FormData();
  form.append('file', file);
  return request<KnowledgeDocumentView>({
    url: `/platform/knowledge-bases/${baseId}/documents`,
    method: 'post',
    ...(directoryId ? { params: { directoryId } } : {}),
    data: form
  });
}

export function parseKnowledgeDocument(baseId: string, documentId: string) {
  return request<KnowledgeParseJobView>({
    url: `/platform/knowledge-bases/${baseId}/documents/${documentId}/parse`,
    method: 'post'
  });
}

export function deleteKnowledgeDocument(baseId: string, documentId: string) {
  return request<void>({
    url: `/platform/knowledge-bases/${baseId}/documents/${documentId}`,
    method: 'delete'
  });
}

export async function downloadKnowledgeDocument(baseId: string, documentId: string, inline = false) {
  const query = inline ? '?inline=true' : '';
  const response = await fetch(platformRawUrl(
    `/platform/knowledge-bases/${baseId}/documents/${documentId}/file${query}`
  ), {
    headers: platformRawHeaders('application/octet-stream, text/plain, application/pdf')
  });
  return {
    blob: await rawDownloadBlob(response),
    fileName: responseFileName(response.headers.get('content-disposition')) || `document-${documentId}`
  };
}

export function retrieveKnowledge(payload: {
  knowledgeBaseIds: string[];
  query: string;
  topK?: number;
  similarityThreshold?: number;
  vectorWeight?: number;
}) {
  return request<KnowledgeRetrievalView>({ url: '/platform/knowledge-bases/retrieve', method: 'post', data: payload });
}

export function fetchKnowledgeMetrics(days = 7, startDate?: string, endDate?: string) {
  return request<KnowledgeMetricsView>({
    url: '/platform/knowledge-bases/metrics/summary',
    method: 'get',
    params: { days, start_date: startDate, end_date: endDate }
  });
}

export function fetchDataSources() {
  return request<DataSourceView[]>({ url: '/platform/data-sources', method: 'get', params: { limit: 200 } });
}

export function createDataSource(payload: SaveDataSourcePayload & { sourceKey: string }) {
  const { revisionNo: _revisionNo, ...data } = payload;
  return request<DataSourceView>({ url: '/platform/data-sources', method: 'post', data });
}

export function updateDataSource(sourceId: string, payload: SaveDataSourcePayload & { revisionNo: number }) {
  const { sourceKey: _sourceKey, ...data } = payload;
  return request<DataSourceView>({ url: `/platform/data-sources/${sourceId}`, method: 'put', data });
}

export function deleteDataSource(sourceId: string) {
  return request<void>({ url: `/platform/data-sources/${sourceId}`, method: 'delete' });
}

export function testDataSource(sourceId: string) {
  return request<DataSourceConnectionView>({ url: `/platform/data-sources/${sourceId}/test`, method: 'post' });
}

export function fetchDatasets() {
  return request<DatasetView[]>({ url: '/platform/datasets', method: 'get', params: { limit: 200 } });
}

export function createDataset(payload: SaveDatasetPayload & { dataSourceId: string; datasetKey: string }) {
  const { revisionNo: _revisionNo, ...data } = payload;
  return request<DatasetView>({ url: '/platform/datasets', method: 'post', data });
}

export function updateDataset(datasetId: string, payload: SaveDatasetPayload & { revisionNo: number }) {
  const { dataSourceId: _dataSourceId, datasetKey: _datasetKey, ...data } = payload;
  return request<DatasetView>({ url: `/platform/datasets/${datasetId}`, method: 'put', data });
}

export function deleteDataset(datasetId: string) {
  return request<void>({ url: `/platform/datasets/${datasetId}`, method: 'delete' });
}

export function fetchDatasetDeleteImpact(datasetId: string) {
  return request<DatasetDeleteImpactView>({
    url: `/platform/datasets/${datasetId}/delete-impact`,
    method: 'get'
  });
}

export function fetchDatasetMetadata(datasetId: string) {
  return request<DataTableView[]>({ url: `/platform/datasets/${datasetId}/metadata`, method: 'get' });
}

export function updateDataTable(datasetId: string, tableId: string, payload: UpdateDataTablePayload) {
  return request<void>({
    url: `/platform/datasets/${datasetId}/tables/${tableId}`,
    method: 'put',
    data: payload
  });
}

export function updateDataColumn(datasetId: string, columnId: string, payload: UpdateDataColumnPayload) {
  return request<void>({
    url: `/platform/datasets/${datasetId}/columns/${columnId}`,
    method: 'put',
    data: payload
  });
}

export function syncDatasetMetadata(datasetId: string) {
  return request<MetadataSyncView>({ url: `/platform/datasets/${datasetId}/metadata/sync`, method: 'post' });
}

export async function downloadDatasetMetadataYaml(datasetId: string) {
  const response = await fetch(platformRawUrl(`/platform/datasets/${datasetId}/metadata.yaml`), {
    headers: platformRawHeaders('application/yaml, text/yaml, text/plain')
  });
  return {
    blob: await rawDownloadBlob(response),
    fileName: responseFileName(response.headers.get('content-disposition')) || `metadata-${datasetId}.yaml`
  };
}

export function createMetadataImportPreview(datasetId: string, data: CreateMetadataImportPreviewPayload) {
  return request<MetadataImportPreviewView>({
    url: `/platform/datasets/${datasetId}/metadata-import/previews`,
    method: 'post',
    data
  });
}

export function fetchMetadataImportPreview(datasetId: string, previewId: string) {
  return request<MetadataImportPreviewView>({
    url: `/platform/datasets/${datasetId}/metadata-import/previews/${previewId}`,
    method: 'get'
  });
}

export function applyMetadataImportPreview(
  datasetId: string,
  previewId: string,
  data: ApplyMetadataImportPreviewPayload
) {
  return request<MetadataImportApplyView>({
    url: `/platform/datasets/${datasetId}/metadata-import/previews/${previewId}/apply`,
    method: 'post',
    data
  });
}

export function fetchDatasetMetrics(datasetId: string) {
  return request<DataMetricView[]>({ url: `/platform/datasets/${datasetId}/metrics`, method: 'get' });
}

export function createDatasetMetric(datasetId: string, data: CreateDataMetricPayload) {
  return request<DataMetricView>({ url: `/platform/datasets/${datasetId}/metrics`, method: 'post', data });
}

export function updateDatasetMetric(datasetId: string, metricId: string, data: UpdateDataMetricPayload) {
  return request<DataMetricView>({ url: `/platform/datasets/${datasetId}/metrics/${metricId}`, method: 'put', data });
}

export function deleteDatasetMetric(datasetId: string, metricId: string) {
  return request<void>({ url: `/platform/datasets/${datasetId}/metrics/${metricId}`, method: 'delete' });
}

export function fetchDatasetRelations(datasetId: string) {
  return request<DataRelationView[]>({ url: `/platform/datasets/${datasetId}/relationships`, method: 'get' });
}

export function createDatasetRelation(datasetId: string, data: CreateDataRelationPayload) {
  return request<DataRelationView>({ url: `/platform/datasets/${datasetId}/relationships`, method: 'post', data });
}

export function updateDatasetRelation(datasetId: string, relationId: string, data: UpdateDataRelationPayload) {
  return request<DataRelationView>({ url: `/platform/datasets/${datasetId}/relationships/${relationId}`, method: 'put', data });
}

export function deleteDatasetRelation(datasetId: string, relationId: string) {
  return request<void>({ url: `/platform/datasets/${datasetId}/relationships/${relationId}`, method: 'delete' });
}

export function fetchDatasetRowPolicy(datasetId: string) {
  return request<DatasetRowPolicyView>({ url: `/platform/datasets/${datasetId}/row-policy`, method: 'get' });
}

export function updateDatasetRowPolicy(datasetId: string, data: UpdateDatasetRowPolicyPayload) {
  return request<DatasetRowPolicyView>({ url: `/platform/datasets/${datasetId}/row-policy`, method: 'put', data });
}

export function fetchDatasetMetadataChanges(datasetId: string, limit = 100) {
  return request<MetadataChangeView[]>({
    url: `/platform/datasets/${datasetId}/metadata-changes`,
    method: 'get',
    params: { limit }
  });
}

export function createMetadataProfileJob(datasetId: string, data: CreateMetadataProfileJobPayload) {
  return request<MetadataProfileJobView>({
    url: `/platform/datasets/${datasetId}/profile-jobs`,
    method: 'post',
    data
  });
}

export function fetchMetadataProfileJobs(datasetId: string, limit = 50) {
  return request<MetadataProfileJobView[]>({
    url: `/platform/datasets/${datasetId}/profile-jobs`,
    method: 'get',
    params: { limit }
  });
}

export function fetchMetadataProfileJob(datasetId: string, jobId: string) {
  return request<MetadataProfileJobDetailView>({
    url: `/platform/datasets/${datasetId}/profile-jobs/${jobId}`,
    method: 'get'
  });
}

export function cancelMetadataProfileJob(datasetId: string, jobId: string) {
  return request<MetadataProfileJobView>({
    url: `/platform/datasets/${datasetId}/profile-jobs/${jobId}/cancel`,
    method: 'post'
  });
}

export function resumeMetadataProfileJob(datasetId: string, jobId: string) {
  return request<MetadataProfileJobView>({
    url: `/platform/datasets/${datasetId}/profile-jobs/${jobId}/resume`,
    method: 'post'
  });
}

export function fetchMetadataTableProfiles(datasetId: string, params: MetadataTableProfileSearchParams) {
  return request<MetadataTableProfilePageView>({
    url: `/platform/datasets/${datasetId}/table-profiles`,
    method: 'get',
    params
  });
}

export function fetchMetadataTableProfileStats(datasetId: string) {
  return request<MetadataTableProfileStatsView>({
    url: `/platform/datasets/${datasetId}/table-profiles/stats`,
    method: 'get'
  });
}

export function fetchMetadataTableProfile(datasetId: string, tableId: string, relatedLimit = 15) {
  return request<MetadataTableProfileDetailView>({
    url: `/platform/datasets/${datasetId}/table-profiles/${tableId}`,
    method: 'get',
    params: { relatedLimit }
  });
}

export function updateMetadataTableProfileIgnore(
  datasetId: string,
  tableId: string,
  data: { revisionNo: number; ignored: boolean }
) {
  return request<MetadataTableProfileDetailView>({
    url: `/platform/datasets/${datasetId}/table-profiles/${tableId}/ignore`,
    method: 'put',
    data
  });
}

export function fetchMetadataRelatedTables(datasetId: string, tableId: string, limit = 15) {
  return request<MetadataRelatedTableView[]>({
    url: `/platform/datasets/${datasetId}/table-profiles/${tableId}/related`,
    method: 'get',
    params: { limit }
  });
}

export function createMetadataSmartImportPreview(
  datasetId: string,
  data: { profileJobId?: string; tableIds: string[] }
) {
  return request<MetadataSmartImportPreviewView>({
    url: `/platform/datasets/${datasetId}/smart-import/previews`,
    method: 'post',
    data
  });
}

export function fetchMetadataSmartImportPreview(datasetId: string, previewId: string) {
  return request<MetadataSmartImportPreviewView>({
    url: `/platform/datasets/${datasetId}/smart-import/previews/${previewId}`,
    method: 'get'
  });
}

export function applyMetadataSmartImportPreview(
  datasetId: string,
  previewId: string,
  data: { revisionNo: number; itemIds: string[] }
) {
  return request<MetadataSmartImportApplyView>({
    url: `/platform/datasets/${datasetId}/smart-import/previews/${previewId}/apply`,
    method: 'post',
    data
  });
}

export function validateDataQuery(payload: DataQueryPayload) {
  return request<DataQueryValidationView>({ url: '/platform/data-queries/validate', method: 'post', data: payload });
}

export function executeDataQuery(payload: DataQueryPayload) {
  return request<DataQueryResultView>({ url: '/platform/data-queries/execute', method: 'post', data: payload });
}

export function fetchReports(params?: { status?: ReportView['status']; search?: string }) {
  return request<ReportView[]>({ url: '/platform/reports', method: 'get', params: { ...params, limit: 500 } });
}

export function fetchReport(reportId: string) {
  return request<ReportView>({ url: `/platform/reports/${reportId}`, method: 'get' });
}

export function createReport(payload: CreateReportPayload) {
  return request<ReportView>({ url: '/platform/reports', method: 'post', data: payload });
}

export function updateReport(reportId: string, payload: UpdateReportPayload) {
  return request<ReportView>({ url: `/platform/reports/${reportId}`, method: 'put', data: payload });
}

export function archiveReport(reportId: string) {
  return request<void>({ url: `/platform/reports/${reportId}`, method: 'delete' });
}

export function executeReport(reportId: string, parameters: Record<string, unknown>) {
  return request<DataQueryResultView>({
    url: `/platform/reports/${reportId}/execute`,
    method: 'post',
    data: { parameters }
  });
}

export function fetchReportRuns(reportId: string) {
  return request<ReportRunView[]>({ url: `/platform/reports/${reportId}/runs`, method: 'get', params: { limit: 100 } });
}

export function fetchReportSubscriptions(reportId: string) {
  return request<ReportSubscriptionView[]>({ url: `/platform/reports/${reportId}/subscriptions`, method: 'get' });
}

export function createReportSubscription(
  reportId: string,
  payload: CreateReportSubscriptionPayload
) {
  return request<ReportSubscriptionView>({ url: `/platform/reports/${reportId}/subscriptions`, method: 'post', data: payload });
}

export function updateReportSubscriptionStatus(
  reportId: string,
  subscriptionId: string,
  status: ReportSubscriptionView['status']
) {
  return request<ReportSubscriptionView>({
    url: `/platform/reports/${reportId}/subscriptions/${subscriptionId}`,
    method: 'put',
    data: { status }
  });
}

export function deleteReportSubscription(reportId: string, subscriptionId: string) {
  return request<void>({
    url: `/platform/reports/${reportId}/subscriptions/${subscriptionId}`,
    method: 'delete'
  });
}

export function executeReportSubscription(reportId: string, subscriptionId: string) {
  return request<DataQueryResultView>({
    url: `/platform/reports/${reportId}/subscriptions/${subscriptionId}/run`,
    method: 'post'
  });
}

export async function streamConversationEvents(
  conversationId: string,
  cursor: number,
  onEvent: (event: ExecutionEventView) => void,
  signal: AbortSignal
) {
  const response = await fetch(platformRawUrl(
    `/platform/conversations/${conversationId}/events/stream?cursor=${encodeURIComponent(cursor)}`
  ), {
    headers: platformRawHeaders('text/event-stream'),
    signal
  });
  if (!response.ok) throw new Error(await rawResponseMessage(response));
  if (!response.body) throw new Error('浏览器未提供事件流响应体');

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  const emitFrame = (frame: string) => {
    const data = frame.split(/\r?\n/)
      .filter(line => line.startsWith('data:'))
      .map(line => line.slice(5).trimStart())
      .join('\n');
    if (!data || data === '[DONE]') return;
    try {
      onEvent(JSON.parse(data) as ExecutionEventView);
    } catch {
      // A truncated or non-JSON heartbeat must not tear down the reconnect loop.
    }
  };

  try {
    while (!signal.aborted) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value, { stream: !done });
      const frames = buffer.split(/\r?\n\r?\n/);
      buffer = frames.pop() || '';
      for (const frame of frames) emitFrame(frame);
      if (done) {
        if (buffer.trim()) emitFrame(buffer);
        break;
      }
    }
  } finally {
    try {
      await reader.cancel();
    } catch {
      // The server may already have closed the stream.
    }
  }
}

/**
 * Consumes the Nhs V1 compatibility stream and normalizes chunks for the
 * existing workspace event components.  The V1 endpoint is intentionally a
 * POST because the first request can create a turn; an empty input with a
 * conversation ID is treated as a cursor-only resume by the backend.
 */
export async function streamNhsConversationEvents(
  conversationId: string,
  cursor: number,
  onEvent: (event: ExecutionEventView) => void,
  signal: AbortSignal,
) {
  const headers = platformRawHeaders('text/event-stream');
  headers.set('Content-Type', 'application/json');
  headers.set('Last-Event-ID', String(cursor));
  const response = await fetch(platformRawUrl('/api/v1/chat/completions'), {
    method: 'post',
    headers,
    body: JSON.stringify({ conversation_id: conversationId, cursor }),
    signal,
  });
  if (!response.ok) throw new Error(await rawResponseMessage(response));
  if (!response.body) throw new Error('浏览器未提供 Nhs V1 事件流响应体');

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  const emitFrame = (frame: string) => {
    const data = frame.split(/\r?\n/)
      .filter(line => line.startsWith('data:'))
      .map(line => line.slice(5).trimStart())
      .join('\n');
    if (!data || data === '[DONE]') return;
    try {
      onEvent(normalizeNhsSseChunk(JSON.parse(data) as NhsSseChunk));
    } catch {
      // Ignore malformed or partial frames; the next cursor reconnect replays them.
    }
  };
  try {
    while (!signal.aborted) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value, { stream: !done });
      const frames = buffer.split(/\r?\n\r?\n/);
      buffer = frames.pop() || '';
      for (const frame of frames) emitFrame(frame);
      if (done) {
        if (buffer.trim()) emitFrame(buffer);
        break;
      }
    }
  } finally {
    try { await reader.cancel(); } catch { /* server already closed the stream */ }
  }
}

/** Calls the Nhs V1 JSON completion variant; failures retain the backend error envelope. */
export function completeNhsConversation(
  payload: Record<string, unknown>
) {
  return request<NhsChatCompletionResponse>({
    url: '/api/v1/chat/completions',
    method: 'post',
    headers: { Accept: 'application/json' },
    data: { ...payload, stream: false }
  });
}

function normalizeNhsSseChunk(chunk: NhsSseChunk): ExecutionEventView {
  const type = String(chunk.type || '');
  let eventType = type;
  if (!eventType && chunk.content !== undefined) eventType = 'text_delta';
  if (type === 'agent_reply') eventType = chunk.phase === 'end' ? 'run_finished' : 'run_started';
  if (type === 'model_call') eventType = chunk.phase === 'end' ? 'model_call_finished' : 'model_call_started';
  if (type === 'thinking') eventType = chunk.phase === 'end' ? 'thinking_finished' : 'thinking_started';
  if (type === 'reasoning_content') eventType = 'thinking_delta';
  if (type === 'permission_required' || type === 'business_confirmation') eventType = 'approval_required';
  if (type === 'permission_result') eventType = 'approval_resolved';
  if (type === 'external_execution_required') eventType = 'external_execution_required';
  if (type === 'external_execution_result') eventType = 'external_execution_resolved';
  if (type === 'error') eventType = String(chunk.code || 'failed');
  const summary = chunk.content !== undefined
    ? String(chunk.content ?? '')
    : String(chunk.message || chunk.details || chunk.summary || '');
  const payload = { ...chunk };
  if (type === 'reasoning_content') payload.delta = chunk.content;
  return {
    eventId: String(chunk.event_id || `nhs-${chunk.cursor ?? Date.now()}`),
    traceId: String(chunk.trace_id || ''),
    conversationId: chunk.conversation_id == null ? null : String(chunk.conversation_id),
    runId: chunk.run_id == null ? null : String(chunk.run_id),
    stepId: chunk.step_id == null ? null : String(chunk.step_id),
    cursor: Number(chunk.cursor || 0),
    eventType,
    eventStatus: String(chunk.event_status || chunk.status || 'success'),
    summary,
    payload,
    sensitiveLevel: 'owner',
    occurredAt: String(chunk.occurred_at || new Date().toISOString()),
    projection: {},
  };
}

export type DataQueryExportFormat = 'csv' | 'xlsx';

export async function downloadDataQueryExport(queryId: string, format: DataQueryExportFormat) {
  const accept = format === 'xlsx'
    ? 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    : 'text/csv';
  const response = await fetch(platformRawUrl(
    `/platform/data-queries/${queryId}/export?format=${encodeURIComponent(format)}`
  ), {
    headers: platformRawHeaders(accept)
  });
  return {
    blob: await rawDownloadBlob(response),
    fileName: responseFileName(response.headers.get('content-disposition')) || `query-${queryId}.${format}`
  };
}

export function downloadDataQueryCsv(queryId: string) {
  return downloadDataQueryExport(queryId, 'csv');
}

export async function downloadConversationAttachment(
  conversationId: string,
  attachment: Pick<ConversationAttachmentView, 'id' | 'originalName'>
) {
  const response = await fetch(platformRawUrl(
    `/platform/conversations/${conversationId}/attachments/${attachment.id}/content`
  ), { headers: platformRawHeaders('*/*') });
  return {
    blob: await rawDownloadBlob(response),
    fileName: responseFileName(response.headers.get('content-disposition')) || attachment.originalName
  };
}

export async function downloadConversationExport(
  conversationId: string,
  format: ConversationExportFormat
) {
  const response = await fetch(platformRawUrl(
    `/platform/conversations/${conversationId}/export?format=${encodeURIComponent(format)}`
  ), { headers: platformRawHeaders('*/*') });
  return {
    blob: await rawDownloadBlob(response),
    fileName: responseFileName(response.headers.get('content-disposition'))
      || `conversation-${conversationId}.${format === 'json' ? 'json' : 'md'}`
  };
}

export async function downloadNhsV1TraceData(
  traceId: string,
  format: NhsV1TraceDataExportFormat
) {
  const accept = format === 'csv'
    ? 'text/csv'
    : 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
  const response = await fetch(platformRawUrl(
    `/api/v1/chat/export/data/${encodeURIComponent(traceId)}?format=${encodeURIComponent(format)}`
  ), { headers: platformRawHeaders(accept) });
  return {
    blob: await rawDownloadBlob(response),
    fileName: responseFileName(response.headers.get('content-disposition'))
      || `export_${traceId}.${format}`
  };
}

function platformRawUrl(path: string) {
  const isHttpProxy = import.meta.env.DEV && import.meta.env.VITE_HTTP_PROXY === 'Y';
  const { baseURL } = getServiceBaseURL(import.meta.env, isHttpProxy);
  return `${baseURL.replace(/\/$/, '')}${path}`;
}

function platformRawHeaders(accept: string) {
  const headers = new Headers({
    Accept: accept,
    clientid: import.meta.env.VITE_APP_CLIENT_ID
  });
  const authorization = getAuthorization();
  if (authorization) headers.set('Authorization', authorization);
  return headers;
}

function machineRawHeaders(apiKey: string, accept: string, json = false) {
  const value = apiKey.trim();
  const headers = new Headers({
    Accept: accept,
    clientid: import.meta.env.VITE_APP_CLIENT_ID
  });
  if (json) headers.set('Content-Type', 'application/json');
  headers.set('Authorization', /^Bearer\s/i.test(value) ? value : `Bearer ${value}`);
  return headers;
}

function isOpenApiEnvelope<T>(value: unknown): value is { data: T } {
  return Boolean(value && typeof value === 'object' && 'data' in value);
}

async function rawJsonResponse<T>(response: Response): Promise<T> {
  if (!response.ok) throw new Error(await rawResponseMessage(response));
  return await response.json() as T;
}

async function rawResponseMessage(response: Response) {
  try {
    const payload = await response.json() as { msg?: string; message?: string };
    return payload.msg || payload.message || `请求失败 (${response.status})`;
  } catch {
    return `请求失败 (${response.status})`;
  }
}

async function rawDownloadBlob(response: Response) {
  if (!response.ok) throw new Error(await rawResponseMessage(response));
  const blob = await response.blob();
  const contentType = response.headers.get('content-type')?.toLowerCase() || '';
  if (contentType.includes('json') && !response.headers.has('content-disposition')) {
    let payload: unknown;
    try {
      payload = JSON.parse(await blob.text());
    } catch {
      return blob;
    }
    if (isFailedRawEnvelope(payload)) {
      const record = payload as { msg?: unknown; message?: unknown };
      const message = typeof record.msg === 'string'
        ? record.msg
        : typeof record.message === 'string' ? record.message : '请求失败';
      throw new Error(message);
    }
  }
  return blob;
}

function isFailedRawEnvelope(value: unknown): boolean {
  if (!value || typeof value !== 'object') return false;
  const record = value as Record<string, unknown>;
  if (!Object.prototype.hasOwnProperty.call(record, 'code')) return false;
  if (!Object.prototype.hasOwnProperty.call(record, 'msg')
    && !Object.prototype.hasOwnProperty.call(record, 'message')) return false;
  return String(record.code) !== String(import.meta.env.VITE_SERVICE_SUCCESS_CODE || '200');
}

function responseFileName(disposition: string | null) {
  if (!disposition) return null;
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  if (encoded) return decodeURIComponent(encoded.replace(/^"|"$/g, ''));
  return disposition.match(/filename="?([^";]+)"?/i)?.[1] || null;
}

export function fetchPermissionProfiles(status?: string) {
  return request<PermissionProfileView[]>({
    url: '/platform/iam/permission-profiles',
    method: 'get',
    params: { status, limit: 200 }
  });
}

export function createPermissionProfile(payload: {
  profileKey: string;
  name: string;
  description?: string;
  profileType: 'system' | 'custom';
  entries: PermissionRulePayload[];
}) {
  return request<PermissionProfileView>({ url: '/platform/iam/permission-profiles', method: 'post', data: payload });
}

export function createPermissionProfileVersion(
  profileId: string,
  payload: { name: string; description?: string; entries: PermissionRulePayload[] }
) {
  return request<PermissionProfileView>({
    url: `/platform/iam/permission-profiles/${profileId}/versions`,
    method: 'post',
    data: payload
  });
}

export function publishPermissionProfile(profileId: string) {
  return request<PermissionProfileView>({
    url: `/platform/iam/permission-profiles/${profileId}/status`,
    method: 'patch',
    data: { status: 'published' }
  });
}

export function fetchPermissionSummary(userId: string) {
  return request<PermissionSummaryView>({
    url: `/platform/iam/users/${userId}/permission-summary`,
    method: 'get'
  });
}

export function bindPermissionProfile(userId: string, profileId: string, profileVersion: number) {
  return request<PermissionBindingView>({
    url: `/platform/iam/users/${userId}/permission-binding`,
    method: 'put',
    data: { bindingType: 'profile', profileId, profileVersion, snapshotRules: [] }
  });
}

export function patchPermissionOverrides(
  userId: string,
  mutations: Array<{
    operation: 'upsert' | 'revoke';
    rule: PermissionRulePayload;
    expiresAt?: string;
  }>
) {
  return request<PermissionSummaryView>({
    url: `/platform/iam/users/${userId}/permission-overrides`,
    method: 'patch',
    data: { mutations }
  });
}

export function createTemporaryGrant(
  userId: string,
  payload: { rule: PermissionRulePayload; reason: string; approvalId?: string; expiresAt: string }
) {
  return request<PermissionRuleView>({
    url: `/platform/iam/users/${userId}/temporary-grants`,
    method: 'post',
    data: payload
  });
}

export function revokeTemporaryGrant(userId: string, grantId: string) {
  return request<void>({
    url: `/platform/iam/users/${userId}/temporary-grants/${grantId}`,
    method: 'delete'
  });
}

export function fetchPermissionDiff(userId: string, sourceUserId: string) {
  return request<PermissionDiffView>({
    url: `/platform/iam/users/${userId}/permission-diff`,
    method: 'get',
    params: { sourceUserId }
  });
}

export function copyUserPermissions(
  userId: string,
  payload: {
    idempotencyKey: string;
    sourceUserId: string;
    copyMode: 'copy_base' | 'append_missing' | 'replace_base' | 'save_template';
    templateKey?: string;
    templateName?: string;
  }
) {
  return request<PermissionCopyResult>({
    url: `/platform/iam/users/${userId}/permission-copy`,
    method: 'post',
    data: payload
  });
}

export function fetchPermissionCopyRecords(limit = 100) {
  return request<PermissionCopyRecordView[]>({
    url: '/platform/iam/permission-copy-records',
    method: 'get',
    params: { limit }
  });
}

export function fetchServiceAccounts(status?: string) {
  return request<ServiceAccountView[]>({
    url: '/platform/iam/service-accounts',
    method: 'get',
    params: { status, limit: 200 }
  });
}

export function createServiceAccount(payload: SaveServiceAccountPayload & { accountKey: string }) {
  return request<ServiceAccountView>({ url: '/platform/iam/service-accounts', method: 'post', data: payload });
}

export function fetchServiceAccountGrants(accountId: string) {
  return request<ServiceAccountGrantView[]>({
    url: `/platform/iam/service-accounts/${accountId}/grants`,
    method: 'get',
    params: { limit: 200 }
  });
}

export function createServiceAccountGrant(
  accountId: string,
  payload: {
    resourceType: string;
    resourceId?: string;
    resourceKey?: string;
    action: string;
    effect: 'allow' | 'deny';
    reason: string;
    expiresAt?: string;
  }
) {
  return request<ServiceAccountGrantView>({
    url: `/platform/iam/service-accounts/${accountId}/grants`,
    method: 'post',
    data: payload
  });
}

export function revokeServiceAccountGrant(accountId: string, grantId: string) {
  return request<void>({
    url: `/platform/iam/service-accounts/${accountId}/grants/${grantId}`,
    method: 'delete'
  });
}

export function updateServiceAccountStatus(accountId: string, status: 'active' | 'disabled' | 'revoked') {
  return request<ServiceAccountView>({
    url: `/platform/iam/service-accounts/${accountId}/status`,
    method: 'patch',
    data: { status }
  });
}

export function fetchApiApplications(status?: string) {
  return request<ApiApplicationView[]>({
    url: '/platform/iam/api-applications',
    method: 'get',
    params: { status, limit: 200 }
  });
}

export function createApiApplication(payload: SaveApiApplicationPayload & { appKey: string }) {
  return request<ApiApplicationView>({ url: '/platform/iam/api-applications', method: 'post', data: payload });
}

export function updateApiApplication(applicationId: string, payload: SaveApiApplicationPayload) {
  const { appKey: _appKey, appType: _appType, ...data } = payload;
  return request<ApiApplicationView>({
    url: `/platform/iam/api-applications/${applicationId}`,
    method: 'put',
    data
  });
}

export function updateApiApplicationStatus(applicationId: string, status: 'active' | 'disabled' | 'revoked') {
  return request<ApiApplicationView>({
    url: `/platform/iam/api-applications/${applicationId}/status`,
    method: 'patch',
    data: { status }
  });
}

export function fetchApiCredentials(applicationId: string) {
  return request<ApiCredentialView[]>({
    url: `/platform/iam/api-applications/${applicationId}/credentials`,
    method: 'get',
    params: { limit: 200 }
  });
}

export function issueApiCredential(
  applicationId: string,
  payload: { serviceAccountId: string; scopes: string[]; expiresAt?: string }
) {
  return request<IssuedApiCredentialView>({
    url: `/platform/iam/api-applications/${applicationId}/credentials`,
    method: 'post',
    data: payload
  });
}

export function revokeApiCredential(applicationId: string, credentialId: string) {
  return request<void>({
    url: `/platform/iam/api-applications/${applicationId}/credentials/${credentialId}`,
    method: 'delete'
  });
}

export function fetchAutomationTriggers(status?: AutomationTriggerView['status']) {
  return request<AutomationTriggerView[]>({
    url: '/platform/automation/triggers',
    method: 'get',
    params: { status, limit: 200 }
  });
}

export function fetchAutomationTrigger(triggerId: string) {
  return request<AutomationTriggerView>({
    url: `/platform/automation/triggers/${triggerId}`,
    method: 'get'
  });
}

export function createAutomationTrigger(payload: CreateAutomationTriggerPayload) {
  return request<AutomationTriggerView>({ url: '/platform/automation/triggers', method: 'post', data: payload });
}

export function updateAutomationTrigger(triggerId: string, payload: UpdateAutomationTriggerPayload) {
  return request<AutomationTriggerView>({
    url: `/platform/automation/triggers/${triggerId}`,
    method: 'put',
    data: payload
  });
}

export function fireAutomationTrigger(triggerId: string, idempotencyKey: string, input?: string) {
  return request<{ runId: string | null; replayed: boolean }>({
    url: `/platform/automation/triggers/${triggerId}/fire`,
    method: 'post',
    data: { idempotencyKey, input }
  });
}

export function fetchSystemUsers(userName?: string, pageNum = 1, pageSize = 100) {
  return request<PageResult<SystemUserView>>({
    url: '/system/user/list',
    method: 'get',
    params: { userName, pageNum, pageSize }
  });
}

export function fetchSystemUserDetail(userId?: string) {
  return request<SystemUserDetailView>({
    url: userId ? `/system/user/${userId}` : '/system/user/',
    method: 'get'
  });
}

export function createSystemUser(payload: SaveSystemUserPayload) {
  return request<void>({ url: '/system/user', method: 'post', data: payload });
}

export function updateSystemUser(payload: SaveSystemUserPayload & { userId: string }) {
  return request<void>({ url: '/system/user', method: 'put', data: payload });
}

export function changeSystemUserStatus(userId: string, status: '0' | '1') {
  return request<void>({ url: '/system/user/changeStatus', method: 'put', data: { userId, status } });
}
