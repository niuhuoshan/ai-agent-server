import { request } from '../request';
import { getAuthorization } from '../request/shared';
import { getServiceBaseURL } from '@/utils/service';

export interface PortalQuotaStatus {
  period: string;
  period_start: string;
  period_end: string;
  used_tokens: number;
  limit_tokens: number | null;
  remaining_tokens: number | null;
  source: string;
  source_label: string | null;
  action_on_exceed: string;
  is_admin_bypass: boolean;
  policy_enabled: boolean;
}

export interface PortalQuotaPolicy {
  scope_type: 'system' | 'user' | 'role';
  scope_id: string | null;
  enabled: boolean;
  limit_tokens: number | null;
  action_on_exceed: string;
  inherit: boolean;
  effective?: PortalQuotaStatus;
}

export interface PortalResourceCard {
  key: string;
  label: string;
  value: number;
  unit: string;
  tab: string;
  status: string;
}

export type PortalRoutingMode = 'auto' | 'expert';

export interface PortalPreferences {
  pinned_group_ids?: string[];
  card_order?: string[];
  expanded_group_ids?: string[];
  question_clicks?: Record<string, number>;
  pinned_kb_dataset_ids?: string[];
  markdown_theme?: string;
  routing_mode: PortalRoutingMode;
  expert_agent_id: string;
  routing_configured: boolean;
}

export interface PortalWorkbenchItem {
  id: string;
  business_key?: string;
  type?: string;
  title: string;
  subtitle?: string | null;
  occurred_at?: string | null;
  status?: string;
  severity?: string;
  action?: string;
  target?: Record<string, unknown>;
  needs_attention?: boolean;
}

export interface PortalWorkbenchHome {
  mode: 'new_user' | 'quiet' | 'active' | string;
  attention: PortalWorkbenchItem[];
  latest_results: PortalWorkbenchItem[];
  resume_items: PortalWorkbenchItem[];
  recent_tasks: PortalWorkbenchItem[];
  favorite_agents: PortalWorkbenchItem[];
  recommended_scenarios: PortalWorkbenchItem[];
  running_items: PortalWorkbenchItem[];
  next_scheduled_item: PortalWorkbenchItem | null;
  personal_resources: PortalResourceCard[];
  source_status: Record<string, string>;
  generated_at: string;
}

export interface PortalDataPortalHome {
  attention: {
    failed_runs_today: number;
    latest_failed_run: Record<string, unknown> | null;
    digests_today: number;
    latest_digest_at: string | null;
    active_subscriptions: number;
    completed_subscriptions_today: number;
  };
  recent_analysis: PortalDataPortalActivity[];
  report_summary: {
    subscribed: number;
    pinned: number;
    favorite: number;
    shared: number;
    recent: number;
    items: PortalDataPortalReport[];
  };
  generated_at: string;
}

export interface PortalDataPortalActivity {
  type: 'digest' | 'report_run' | 'conversation' | string;
  id: string | number;
  report_id?: string | number;
  run_id?: string | number;
  conversation_id?: string | number;
  title: string;
  subtitle?: string | null;
  status?: string;
  occurred_at?: string | null;
  action?: 'open_digest' | 'open_report' | 'continue_analysis' | 'open_conversation' | string;
}

export interface PortalDataPortalReport {
  id: string | number;
  title: string;
  owner_name?: string | null;
  is_owner?: boolean;
  is_favorite?: boolean;
  pinned?: boolean;
  pinned_at?: string | null;
  description?: string | null;
  tags?: string[];
  last_run_at?: string | null;
  last_error?: string | null;
  subscription_status?: string | null;
  subscription_cron_expr?: string | null;
  subscription_next_run_at?: string | null;
}

export function fetchPortalPreferences() {
  return request<PortalPreferences>({
    url: '/api/portal/portal-prefs',
    method: 'get'
  });
}

export function updatePortalRoutingPreference(data: {
  routing_mode: PortalRoutingMode;
  expert_agent_id?: string;
}) {
  return request<Pick<PortalPreferences, 'routing_mode' | 'expert_agent_id' | 'routing_configured'>>({
    url: '/api/portal/portal-prefs/routing',
    method: 'put',
    data
  });
}

/** Nhs V1 data-menu contracts consumed by the private data portal. */
export interface NhsDatasetQuestion {
  label: string;
  query: string;
  type?: string;
  click_count?: number;
  last_clicked_at?: string;
}

export interface NhsDatasetColumn {
  name: string;
  term?: string;
  type?: string;
  description?: string;
}

export interface NhsDatasetRelatedData {
  dataset?: string;
  dataset_id?: string | number;
  display_name?: string;
  tables?: string[];
  table_descriptions?: Array<{ name: string; description?: string }>;
  table_physical_names?: Record<string, string>;
  table_columns?: Record<string, NhsDatasetColumn[]>;
}

export interface NhsDatasetGroup {
  id: string;
  title: string;
  summary: string;
  tags?: string[];
  metrics?: Array<Record<string, unknown>>;
  questions: NhsDatasetQuestion[];
  followups?: NhsDatasetQuestion[];
  related_data?: NhsDatasetRelatedData[];
  total_click_count?: number;
  updated_at?: string;
}

export interface NhsDatasetMenu {
  dataset_count: number;
  dataset_menu_hash: string;
  generated_at: string;
  groups: NhsDatasetGroup[];
  markdown: string;
  is_fallback: boolean;
  has_datasets: boolean;
  from_cache: boolean;
  llm_generation_failed: boolean;
  llm_error_message?: string | null;
}

export interface NhsDatasetQuestionRefresh {
  questions: NhsDatasetQuestion[];
  refresh_disabled_reason?: string | null;
}

export interface NhsDatasetClickPayload {
  query: string;
  label?: string;
  group_id?: string;
  dataset_menu_hash?: string;
}

export interface NhsDatasetRefreshPayload {
  group_title: string;
  tables: string[];
  dataset_menu_hash?: string;
  group_id?: string;
  exclude_questions?: Array<{ label?: string; query?: string }>;
  purpose?: 'questions' | 'followups';
}

export interface NhsDatasetRecommendPayload {
  table: string;
  physical_table_name?: string;
  dataset_name?: string;
  columns?: Array<{ name: string; term?: string; type?: string; description?: string }>;
}

export function fetchNhsDatasetMenu(refresh = false) {
  return request<NhsDatasetMenu>({
    url: '/api/v1/chat/dataset-menu',
    method: 'get',
    params: refresh ? { refresh: true } : undefined
  });
}

export function recordNhsDatasetQuestionClick(data: NhsDatasetClickPayload) {
  return request<{ success: boolean }>({
    url: '/api/v1/chat/dataset-menu/click',
    method: 'post',
    data
  });
}

export function clearNhsDatasetQuestionClick(query: string) {
  return request<{ success: boolean }>({
    url: '/api/v1/chat/dataset-menu/click/clear',
    method: 'post',
    data: { query }
  });
}

export function refreshNhsDatasetGroupQuestions(data: NhsDatasetRefreshPayload) {
  return request<NhsDatasetQuestionRefresh>({
    url: '/api/v1/chat/dataset-menu/refresh-group-questions',
    method: 'post',
    data
  });
}

export function recommendNhsDatasetTableQuestions(data: NhsDatasetRecommendPayload) {
  return request<NhsDatasetQuestionRefresh>({
    url: '/api/v1/chat/dataset-menu/recommend-table-questions',
    method: 'post',
    data
  });
}

/** A durable local ChatBI/Few-shot example. RAGFlow is intentionally not required. */
export interface PortalExample {
  id: string | number;
  trace_id?: string | null;
  agent_id?: string | null;
  dataset_id?: number | null;
  user_query: string;
  refined_query?: string | null;
  context_summary?: string | null;
  sql_text: string;
  sql_metadata?: Record<string, unknown> | null;
  category: 'general' | 'knowledge' | 'data_query' | string;
  enhance_status?: 'not_requested' | 'pending' | 'running' | 'succeeded' | 'failed' | string;
  ai_answer?: string | null;
  feedback_type?: string | null;
  review_status: 'pending' | 'approved' | 'rejected' | 'deprecated' | string;
  error_message?: string | null;
  use_count?: number | null;
  local_sync_status?: 'pending' | 'syncing' | 'synced' | 'failed' | string;
  local_sync_error?: string | null;
  local_synced_at?: string | null;
  created_by?: string | number | null;
  created_at?: string | null;
  updated_at?: string | null;
}

export interface PortalExampleRevision {
  id: string | number;
  revision_no?: string | number | null;
  example_id: string | number;
  action: 'created' | 'updated' | 'enhanced' | 'reviewed' | 'synced' | 'deleted' | string;
  review_status: string;
  user_query: string;
  refined_query?: string | null;
  context_summary?: string | null;
  sql_text: string;
  sql_metadata?: Record<string, unknown> | null;
  category?: string | null;
  enhance_status?: string | null;
  local_sync_status?: string | null;
  actor_type?: string | null;
  actor_id?: string | number | null;
  reason?: string | null;
  content_hash?: string | null;
  created_at?: string | null;
}

export interface PortalExamplesPage {
  total: number;
  items: PortalExample[];
  page: number;
  size: number;
}

export interface PortalExampleListParams {
  id?: string | number;
  agent_id?: string;
  dataset_id?: number;
  status?: string;
  category?: string;
  search?: string;
  page?: number;
  size?: number;
}

export interface PortalExampleUpdatePayload {
  user_query?: string;
  refined_query?: string | null;
  context_summary?: string | null;
  sql_text?: string;
  sql_metadata?: Record<string, unknown>;
  category?: string;
}

export interface PortalExamplesSyncResult {
  status: 'success' | 'partial_failure' | string;
  total: number;
  synced: number;
  failed: number;
  errors?: Array<{ id?: number; message?: string }>;
  index?: string;
}

export function fetchPortalExamples(params?: PortalExampleListParams) {
  return request<PortalExamplesPage>({ url: '/api/portal/examples', method: 'get', params });
}

export function fetchPortalExample(id: string | number) {
  return request<PortalExample>({ url: `/api/portal/examples/${id}`, method: 'get' });
}

export function fetchPortalExampleHistory(id: string | number) {
  return request<PortalExampleRevision[]>({ url: `/api/portal/examples/${id}/history`, method: 'get' });
}

export function updatePortalExample(id: string | number, data: PortalExampleUpdatePayload) {
  return request<PortalExample>({ url: `/api/portal/examples/${id}`, method: 'put', data });
}

export function auditPortalExample(id: string | number, status: 'approved' | 'rejected' | 'deprecated') {
  return request<PortalExample>({ url: '/api/portal/examples/audit', method: 'post', data: { id, status } });
}

export function enhancePortalExample(id: string | number) {
  return request<PortalExample>({ url: `/api/portal/examples/${id}/enhance`, method: 'post' });
}

export function syncPortalExamples() {
  return request<PortalExamplesSyncResult>({ url: '/api/portal/examples/sync-all', method: 'post' });
}

export function syncPortalExample(id: string | number) {
  return request<PortalExample>({ url: `/api/portal/examples/sync/${id}`, method: 'post' });
}

export function deletePortalExample(id: string | number) {
  return request<void>({ url: `/api/portal/examples/${id}`, method: 'delete' });
}

export function fetchPortalWorkbenchHome() {
  return request<PortalWorkbenchHome>({ url: '/api/portal/workbench/home', method: 'get' });
}

export function fetchPortalDataPortalHome() {
  return request<PortalDataPortalHome>({ url: '/api/portal/data-portal/home', method: 'get' });
}

export function fetchPortalQuota() {
  return request<PortalQuotaStatus>({ url: '/api/portal/quota/me', method: 'get' });
}

export function fetchPortalQuotaPolicy(scope: 'system' | 'user' | 'role', scopeId?: string) {
  const suffix = scope === 'system' ? 'system' : `${scope}s/${scopeId}`;
  return request<PortalQuotaPolicy>({ url: `/api/portal/quota/${suffix}`, method: 'get' });
}

export function updatePortalQuotaPolicy(
  scope: 'system' | 'user' | 'role',
  scopeId: string | undefined,
  data: { enabled: boolean; limitTokens: number | null }
) {
  const suffix = scope === 'system' ? 'system' : `${scope}s/${scopeId}`;
  return request<PortalQuotaPolicy>({ url: `/api/portal/quota/${suffix}`, method: 'put', data });
}

export function deletePortalQuotaPolicy(scope: 'system' | 'user' | 'role', scopeId?: string) {
  const suffix = scope === 'system' ? 'system' : `${scope}s/${scopeId}`;
  return request<void>({ url: `/api/portal/quota/${suffix}`, method: 'delete' });
}

export function fetchPortalInbox(params?: { limit?: number; offset?: number; unread_only?: boolean }) {
  return request<Array<Record<string, unknown>>>({
    url: '/api/portal/inbox',
    method: 'get',
    params: { limit: 20, offset: 0, unread_only: false, ...params }
  });
}

export function fetchPortalUnreadCount() {
  return request<{ count: number }>({ url: '/api/portal/inbox/unread-count', method: 'get' });
}

export function fetchPortalDashboardStats(period: 'today' | 'week' | 'month' = 'today') {
  return request<Record<string, unknown>>({
    url: '/api/portal/dashboard/user-stats',
    method: 'get',
    params: { period }
  });
}

export interface PortalPromptMetadata {
  id: string;
  name: string;
  display_name: string;
  source: string;
  category: string;
  description: string;
  versions: Array<{ version_number: number; status: string; comment?: string; updated_at?: string }>;
  created_by?: number | null;
  is_system?: boolean;
}

export interface PortalPromptDetail {
  id: string;
  source: string;
  content: string;
  version_number?: number | null;
  version_note?: string;
  variables: string[];
}

export interface PortalPromptHistory {
  id: string;
  change_type: string;
  changed_by?: number | null;
  created_at?: string;
  description?: string;
  new_value: string;
  old_value?: string;
  version_number: number;
  status: string;
}

export interface PortalPromptExecutionMetadata {
  status: 'succeeded';
  model_id: string | number;
  model_name: string;
  provider: string;
  provider_model: string;
  elapsed_ms: number;
}

export interface PortalPromptTestResult extends PortalPromptExecutionMetadata {
  output: string;
  raw_output: string;
  rendered_prompt: string;
  interpolated_prompt: string;
  latency_ms: number;
}

export interface PortalPromptOptimizeResult extends PortalPromptExecutionMetadata {
  optimized_content: string;
}

export function fetchPortalPrompts() {
  return request<PortalPromptMetadata[]>({ url: '/api/portal/prompts', method: 'get' });
}

export function fetchPortalPromptDetail(source: string, targetId: string, version?: number) {
  return request<PortalPromptDetail>({
    url: '/api/portal/prompts/detail',
    method: 'get',
    params: { source, target_id: targetId, version }
  });
}

export function fetchPortalPromptHistory(source: string, targetId: string) {
  return request<PortalPromptHistory[]>({
    url: '/api/portal/prompts/history',
    method: 'get',
    params: { source, target_id: targetId }
  });
}

export function savePortalPrompt(data: { source: string; targetId: string; content: string; versionNote?: string }) {
  return request<{ status: string; version_number?: number }>({
    url: '/api/portal/prompts/save',
    method: 'post',
    data: {
      source: data.source,
      targetId: data.targetId,
      content: data.content,
      versionNote: data.versionNote
    }
  });
}

export function restorePortalPrompt(data: { source: string; targetId: string; versionNumber: number }) {
  return request<{
    status: string;
    source_version_number: number;
    version_number: number;
    version_id: string | number;
  }>({
    url: '/api/portal/prompts/restore',
    method: 'post',
    data: {
      source: data.source,
      targetId: data.targetId,
      versionNumber: data.versionNumber
    }
  });
}

export function testPortalPrompt(data: {
  content: string;
  variables?: Record<string, unknown>;
  userInput?: string;
  model?: string;
}) {
  return request<PortalPromptTestResult>({ url: '/api/portal/prompts/test', method: 'post', data });
}

export function optimizePortalPrompt(content: string, model?: string) {
  return request<PortalPromptOptimizeResult>({
    url: '/api/portal/prompts/optimize',
    method: 'post',
    data: model ? { content, model } : { content }
  });
}

/** Optimizes a task execution instruction through the Nhs-compatible route. */
export function optimizeTaskInstruction(content: string, model?: string) {
  return request<PortalPromptOptimizeResult>({
    url: '/api/portal/prompts/optimize/task-instruction',
    method: 'post',
    data: model ? { content, model } : { content }
  });
}

export interface PortalSlashCommand {
  id: number;
  label: string;
  command: string;
  sort_order: number;
  created_at?: string;
  updated_at?: string;
}

export function fetchPortalSlashCommands(limit = 200) {
  return request<PortalSlashCommand[]>({ url: '/api/portal/slash-commands', method: 'get', params: { limit } });
}

export function createPortalSlashCommand(data: { label: string; command: string; sortOrder?: number }) {
  return request<PortalSlashCommand>({
    url: '/api/portal/slash-commands',
    method: 'post',
    data: { ...data, sortOrder: data.sortOrder ?? 0 }
  });
}

export function updatePortalSlashCommand(id: number, data: { label: string; command: string; sortOrder?: number }) {
  return request<PortalSlashCommand>({
    url: `/api/portal/slash-commands/${id}`,
    method: 'put',
    data: { ...data, sortOrder: data.sortOrder ?? 0 }
  });
}

export function deletePortalSlashCommand(id: number) {
  return request<void>({ url: `/api/portal/slash-commands/${id}`, method: 'delete' });
}

export function reorderPortalSlashCommands(items: Array<{ id: number; sortOrder: number }>) {
  return request<void>({ url: '/api/portal/slash-commands/reorder', method: 'post', data: { items } });
}

export interface PortalMemorySummary {
  id: string;
  conversation_id?: string;
  summary?: string;
  content?: string;
  created_at?: string;
  updated_at?: string;
  metadata?: Record<string, unknown>;
  [key: string]: unknown;
}

export interface PortalDailySummary {
  id?: string | null;
  date: string;
  summary: string;
  session_count: number;
  user_id: string;
  stored: boolean;
  updated_at?: string | null;
}

export interface PortalDailySummaryDetail {
  summary: PortalDailySummary;
  sessions: PortalMemorySummary[];
}

export interface PortalMemoryCapabilities {
  enabled?: boolean;
  relational_store: { available: boolean; provider?: string };
  relational_search?: { available: boolean; provider?: string; owner_scoped?: boolean };
  session_summaries: { available: boolean };
  daily_summaries: { available: boolean; rebuild?: boolean };
  relational_consolidation?: { available: boolean; mode?: string };
  long_term_memory: { available: boolean };
  intelligent_consolidation: { available: boolean; reason?: string };
}

export interface PortalMemoryConsolidation {
  mode: string;
  days_processed: number;
  daily_summaries_created: number;
  daily_summaries_updated: number;
  intelligent_rewrite: boolean;
}

export interface PortalMemoryConfig {
  provider: string;
  default_search_limit: number;
  search_knn_top_k: number;
  consolidation_mode: string;
  enabled: boolean;
  summary_enabled: boolean;
  embedding_enabled: boolean;
  embedding_model_id?: string | null;
  embedding_dimension?: number | null;
  vector_weight: number;
  consolidation_threshold: number;
  base_half_life_days: number;
  summary_ttl_days: number;
  redis_vector_enabled: boolean;
  stored: boolean;
  revision: number;
}

export interface PortalMemoryConfigUpdate {
  enabled: boolean;
  summary_enabled: boolean;
  embedding_enabled: boolean;
  embedding_model_id?: string | null;
  embedding_dimension?: number | null;
  search_knn_top_k: number;
  vector_weight: number;
  consolidation_threshold: number;
  base_half_life_days: number;
  summary_ttl_days: number;
  expected_revision?: number;
}

export interface PortalMemoryIndexStatus {
  available: boolean;
  provider: string;
  owner_scoped: boolean;
  document_count: number;
  automatically_maintained: boolean;
  rebuild_required: boolean;
  checked_at: string;
  verified?: boolean;
  rebuilt?: boolean;
  message?: string;
  indexed?: number;
  capped?: boolean;
  model_id?: string | null;
  dimension?: number;
  vector?: PortalMemoryProviderProbe;
}

export interface PortalMemoryProviderProbe {
  available: boolean;
  provider: string;
  compatible_with?: string;
  vector_extension_present?: boolean;
  embedding_enabled?: boolean;
  embedding_model_id?: string | null;
  embedding_dimension?: number | null;
  embedded_document_count?: number;
  dimensions?: number;
  sample?: number[];
  latency_ms?: number;
  message?: string;
}

export interface PortalMemorySearchResult {
  id: string;
  memory_key: string;
  memory_type: string;
  content: string;
  source_type?: string | null;
  source_id?: string | null;
  sensitive_level?: string | null;
  metadata?: Record<string, unknown>;
  updated_at?: string | null;
  lexical_score?: number;
  vector_score?: number;
  final_score?: number;
  score?: number;
}

export function fetchPortalMemoryCapabilities() {
  return request<PortalMemoryCapabilities>({ url: '/api/portal/memory/my/capabilities', method: 'get' });
}

export function consolidatePortalMemory() {
  return request<PortalMemoryConsolidation>({ url: '/api/portal/memory/my/consolidate', method: 'post' });
}

export function fetchPortalMemoryConfig() {
  return request<PortalMemoryConfig>({ url: '/api/portal/memory/configs', method: 'get' });
}

export function updatePortalMemoryConfig(payload: number | PortalMemoryConfigUpdate) {
  return request<PortalMemoryConfig>({
    url: '/api/portal/memory/configs',
    method: 'put',
    data: typeof payload === 'number' ? { default_search_limit: payload } : payload
  });
}

export function fetchPortalMemoryIndexStatus() {
  return request<PortalMemoryIndexStatus>({ url: '/api/portal/memory/index/status', method: 'get' });
}

export function verifyPortalMemoryIndex() {
  return request<PortalMemoryIndexStatus>({ url: '/api/portal/memory/index/rebuild', method: 'post' });
}

export function testPortalMemorySearch(query: string, limit: number) {
  return request<PortalMemorySearchResult[]>({
    url: '/api/portal/memory/search-test',
    method: 'post',
    data: { query, limit }
  });
}

export function testPortalMemoryRedisVector() {
  return request<PortalMemoryProviderProbe>({ url: '/api/portal/memory/redis-vector-test', method: 'get' });
}

export function testPortalMemoryEmbedding() {
  return request<PortalMemoryProviderProbe>({ url: '/api/portal/memory/test-embedding', method: 'post' });
}

export function fetchPortalMemorySummaries(params?: { keyword?: string; limit?: number }) {
  return request<PortalMemorySummary[]>({ url: '/api/portal/memory/my/summaries', method: 'get', params });
}

export function fetchPortalMemorySummariesForUser(userId: string, params?: { keyword?: string; limit?: number }) {
  return request<PortalMemorySummary[]>({
    url: '/api/portal/memory/summaries',
    method: 'get',
    params: { ...params, user_id: userId }
  });
}

export function fetchPortalMemorySummaryDetail(conversationId: string, historyLimit = 30) {
  return request<{
    summary: PortalMemorySummary | null;
    history: Array<Record<string, unknown>>;
    has_history: boolean;
  }>({
    url: `/api/portal/memory/my/summaries/${encodeURIComponent(conversationId)}`,
    method: 'get',
    params: { historyLimit }
  });
}

export function fetchPortalMemorySummaryDetailForUser(userId: string, conversationId: string, historyLimit = 30) {
  return request<{
    summary: PortalMemorySummary | null;
    history: Array<Record<string, unknown>>;
    has_history: boolean;
  }>({
    url: `/api/portal/memory/summaries/${encodeURIComponent(userId)}/${encodeURIComponent(conversationId)}`,
    method: 'get',
    params: { historyLimit }
  });
}

export function deletePortalMemorySummary(conversationId: string) {
  return request<void>({
    url: `/api/portal/memory/my/summaries/${encodeURIComponent(conversationId)}`,
    method: 'delete'
  });
}

export function deletePortalMemorySummaryForUser(userId: string, conversationId: string) {
  return request<void>({
    url: `/api/portal/memory/summaries/${encodeURIComponent(userId)}/${encodeURIComponent(conversationId)}`,
    method: 'delete'
  });
}

export function clearPortalSessionMemory() {
  return request<Record<string, unknown>>({ url: '/api/portal/memory/my/session-memory', method: 'delete' });
}

export function clearPortalSessionMemoryForUser(userId: string) {
  return request<Record<string, unknown>>({
    url: `/api/portal/memory/users/${encodeURIComponent(userId)}`,
    method: 'delete'
  });
}

export function fetchPortalLongTermMemory() {
  return request<Record<string, string>>({ url: '/api/portal/memory/my/ltm', method: 'get' });
}

export function savePortalLongTermMemory(key: string, value: string) {
  return request<void>({ url: '/api/portal/memory/my/ltm', method: 'put', data: { key, value } });
}

export function deletePortalLongTermMemory(key: string) {
  return request<void>({ url: `/api/portal/memory/my/ltm/${encodeURIComponent(key)}`, method: 'delete' });
}

export function fetchPortalDailySummaries(params?: {
  keyword?: string;
  date_from?: string;
  date_to?: string;
  limit?: number;
}) {
  return request<PortalDailySummary[]>({ url: '/api/portal/memory/my/daily-summaries', method: 'get', params });
}

export function fetchPortalDailySummariesForUser(
  userId: string,
  params?: { keyword?: string; date_from?: string; date_to?: string; limit?: number }
) {
  return request<PortalDailySummary[]>({
    url: '/api/portal/memory/daily-summaries',
    method: 'get',
    params: { ...params, user_id: userId }
  });
}

export function fetchPortalDailySummaryDetail(day: string) {
  return request<PortalDailySummaryDetail>({
    url: `/api/portal/memory/my/daily-summaries/${encodeURIComponent(day)}`,
    method: 'get'
  });
}

export function fetchPortalDailySummaryDetailForUser(userId: string, day: string) {
  return request<PortalDailySummaryDetail>({
    url: `/api/portal/memory/daily-summaries/${encodeURIComponent(userId)}/${encodeURIComponent(day)}`,
    method: 'get'
  });
}

export function deletePortalDailySummary(day: string) {
  return request<void>({ url: `/api/portal/memory/my/daily-summaries/${encodeURIComponent(day)}`, method: 'delete' });
}

export function deletePortalDailySummaryForUser(userId: string, day: string) {
  return request<void>({
    url: `/api/portal/memory/daily-summaries/${encodeURIComponent(userId)}/${encodeURIComponent(day)}`,
    method: 'delete'
  });
}

export function rebuildPortalDailySummary(day: string) {
  return request<PortalDailySummary>({
    url: `/api/portal/memory/my/daily-summaries/${encodeURIComponent(day)}/rebuild`,
    method: 'post'
  });
}

export function rebuildPortalDailySummaryForUser(userId: string, day: string) {
  return request<PortalDailySummary>({
    url: `/api/portal/memory/daily-summaries/${encodeURIComponent(userId)}/${encodeURIComponent(day)}/rebuild`,
    method: 'post'
  });
}

export interface PortalChatBIBrief {
  id: string | number;
  title: string;
  markdown: string;
  artifact?: Record<string, unknown> | null;
  result_id?: string | null;
}

export type PortalChatBIAggregation = 'sum' | 'avg' | 'min' | 'max' | 'count';
export type PortalChatBIChartType = 'none' | 'bar' | 'line' | 'pie';

export interface PortalChatBIEvidence {
  evidence_id?: string;
  type?: string;
  producer?: string;
  status: 'success_non_empty' | 'success_empty' | 'missing' | string;
  payload_digest?: string;
  result_hash?: string;
  source_ref?: string;
  freshness?: string;
  observed_at?: string | null;
  source_as_of?: string | null;
  expires_at?: string | null;
  permission?: Record<string, unknown>;
  detail?: Record<string, unknown>;
}

export interface PortalChatBIChartConfig {
  type: PortalChatBIChartType;
  dimension: string | null;
  measures: string[];
  aggregation: PortalChatBIAggregation;
}

export interface PortalChatBIPivotConfig {
  row_dimensions: string[];
  column_dimension: string | null;
  value_column: string | null;
  aggregation: PortalChatBIAggregation;
}

export interface PortalChatBIChartData {
  categories: string[];
  category_values?: unknown[];
  series: Array<{ name: string; values: Array<number | string | null> }>;
  truncated?: boolean;
}

export interface PortalChatBIPivotData {
  columns: string[];
  rows: unknown[][];
  row_count: number;
  column_count: number;
  truncated?: boolean;
}

export interface PortalChatBIPresentation {
  revision: number;
  chart: PortalChatBIChartConfig;
  pivot: PortalChatBIPivotConfig;
  chart_data: PortalChatBIChartData;
  pivot_data: PortalChatBIPivotData;
}

export interface PortalChatBIResultStackItem {
  query_id: string | number;
  result_id: string | number;
  parent_result_id?: string | number | null;
  conversation_id: string | number;
  dataset_id: string | number;
  dataset_ids?: Array<string | number>;
  question: string;
  row_count: number;
  created_at: string;
  evidence_status: string;
  revision: number;
}

export interface PortalChatBITask {
  task_id: string;
  operation: string;
  query: string;
  depends_on?: string[];
  status: string;
  result_query_id?: string | number | null;
  error?: string | null;
}

export interface PortalChatBITaskPlan {
  plan_id: string;
  status: string;
  dataset_id?: string | number;
  dataset_ids?: Array<string | number>;
  conversation_id?: string | number | null;
  question?: string;
  created_at?: string;
  started_at?: string | null;
  finished_at?: string | null;
  tasks: PortalChatBITask[];
}

export interface PortalChatBITaskPlanEventPage {
  plan_id: string;
  events: PortalChatBIStreamEvent[];
  next_cursor: number;
  has_more: boolean;
}

export interface PortalChatBIQuery {
  status: 'succeeded' | 'clarify' | 'failed' | 'rejected' | string;
  conversation_id: string | number;
  trace_id: string;
  query_id?: string | number | null;
  result_id?: string | number | null;
  parent_result_id?: string | number | null;
  dataset_id: string | number;
  dataset_ids?: Array<string | number>;
  dataset_name?: string;
  dataset_names?: string[];
  federated?: boolean;
  federation?: Record<string, unknown>;
  question: string;
  title?: string;
  analysis?: string;
  clarification?: string | null;
  error?: string | null;
  sql?: string | null;
  columns?: string[];
  rows?: unknown[][];
  row_count?: number;
  result_bytes?: number;
  truncated?: boolean;
  elapsed_ms?: number;
  analysis_context?: Record<string, unknown>;
  evidence?: PortalChatBIEvidence;
  presentation?: PortalChatBIPresentation;
  repair_attempts?: Array<Record<string, unknown>>;
  task_plan?: PortalChatBITaskPlan;
  created_at: string;
}

export interface PortalChatBIFederatedRun {
  run_id: string;
  status: string;
  conversation_id?: string | number | null;
  primary_dataset_id?: string | number | null;
  dataset_ids?: Array<string | number>;
  result_query_id?: string | number | null;
  row_count?: number;
  result_bytes?: number;
  truncated?: boolean;
  sources?: Array<Record<string, unknown>>;
  error?: string | null;
  created_at?: string;
  started_at?: string | null;
  finished_at?: string | null;
}

export interface PortalChatBIQueryRequest {
  dataset_id: string | number;
  dataset_ids?: Array<string | number>;
  question: string;
  conversation_id?: string | number;
  parent_result_id?: string | number;
  result_reference?: string;
}

export function createPortalChatBIQuery(data: PortalChatBIQueryRequest) {
  return request<PortalChatBIQuery>({ url: '/api/portal/chatbi/queries', method: 'post', data });
}

export interface PortalChatBIStreamEvent {
  type?: string;
  data?: Record<string, unknown>;
  id?: string;
  category?: string;
  title?: string;
  status?: string;
  details?: string;
  repair?: Record<string, unknown>;
  [key: string]: unknown;
}

/** Streams durable ChatBI plan, repair, result and terminal status events. */
export async function streamPortalChatBIQuery(
  data: PortalChatBIQueryRequest,
  onEvent: (event: PortalChatBIStreamEvent) => void,
  signal?: AbortSignal
) {
  const isHttpProxy = import.meta.env.DEV && import.meta.env.VITE_HTTP_PROXY === 'Y';
  const { baseURL } = getServiceBaseURL(import.meta.env, isHttpProxy);
  const headers = new Headers({
    Accept: 'text/event-stream',
    'Content-Type': 'application/json',
    clientid: import.meta.env.VITE_APP_CLIENT_ID
  });
  const authorization = getAuthorization();
  if (authorization) headers.set('Authorization', authorization);
  const response = await fetch(`${baseURL.replace(/\/$/, '')}/api/portal/chatbi/queries/stream`, {
    method: 'POST',
    headers,
    body: JSON.stringify(data),
    signal
  });
  if (!response.ok) {
    let message = `ChatBI 请求失败 (${response.status})`;
    try {
      const payload = (await response.json()) as { message?: string; msg?: string };
      message = payload.message || payload.msg || message;
    } catch {
      // Keep the HTTP status when the backend did not return a JSON envelope.
    }
    throw new Error(message);
  }
  if (!response.body) throw new Error('浏览器未提供 ChatBI 事件流响应体');

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  while (!signal?.aborted) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value, { stream: !done });
    const frames = buffer.split(/\r?\n\r?\n/);
    buffer = frames.pop() || '';
    for (const frame of frames) {
      const dataLines = frame
        .split(/\r?\n/)
        .filter(line => line.startsWith('data:'))
        .map(line => line.slice(5).trimStart());
      if (!dataLines.length) continue;
      const payload = dataLines.join('\n');
      if (payload === '[DONE]') return;
      onEvent(JSON.parse(payload) as PortalChatBIStreamEvent);
    }
    if (done) break;
  }
}

export function fetchPortalChatBIResultStack(conversationId: string | number, limit = 10) {
  return request<PortalChatBIResultStackItem[]>({
    url: `/api/portal/chatbi/conversations/${encodeURIComponent(String(conversationId))}/results`,
    method: 'get',
    params: { limit }
  });
}

export function fetchPortalChatBITaskPlan(planKey: string) {
  return request<PortalChatBITaskPlan>({
    url: `/api/portal/chatbi/task-plans/${encodeURIComponent(planKey)}`,
    method: 'get'
  });
}

export function fetchPortalChatBITaskPlanEvents(planKey: string, afterCursor = 0, limit = 100) {
  return request<PortalChatBITaskPlanEventPage>({
    url: `/api/portal/chatbi/task-plans/${encodeURIComponent(planKey)}/events`,
    method: 'get',
    params: { afterCursor, limit }
  });
}

export function updatePortalChatBIPresentation(
  queryId: string | number,
  data: {
    expected_revision: number;
    chart?: PortalChatBIChartConfig;
    pivot?: PortalChatBIPivotConfig;
  }
) {
  return request<PortalChatBIPresentation>({
    url: `/api/portal/chatbi/queries/${encodeURIComponent(String(queryId))}/presentation`,
    method: 'put',
    data
  });
}

export function createPortalChatBIDrilldown(
  queryId: string | number,
  data: { dimension: string; value: unknown; question?: string }
) {
  return request<PortalChatBIQuery>({
    url: `/api/portal/chatbi/queries/${encodeURIComponent(String(queryId))}/drilldowns`,
    method: 'post',
    data
  });
}

export function fetchPortalChatBIQueries(limit = 30) {
  return request<PortalChatBIQuery[]>({ url: '/api/portal/chatbi/queries', method: 'get', params: { limit } });
}

export function fetchPortalChatBIFederatedRun(runKey: string) {
  return request<PortalChatBIFederatedRun>({
    url: `/api/portal/chatbi/federated-runs/${encodeURIComponent(runKey)}`,
    method: 'get'
  });
}

export function fetchPortalChatBIQuery(id: string | number) {
  return request<PortalChatBIQuery>({
    url: `/api/portal/chatbi/queries/${encodeURIComponent(String(id))}`,
    method: 'get'
  });
}

export function createPortalChatBIBrief(data: {
  result_id: string;
  title?: string;
  export_word?: boolean;
  polish_with_llm?: boolean;
}) {
  return request<PortalChatBIBrief>({ url: '/api/portal/chatbi-briefs', method: 'post', data });
}

export interface PortalChatBIMonitor {
  report_id: string | number;
  subscription_id: string | number | null;
  created: boolean;
  cron_expr?: string;
  next_run_at?: string;
  status?: string;
}

export function createPortalChatBIMonitor(data: {
  result_id: string;
  title?: string;
  schedule_type: 'daily' | 'weekly' | 'monthly';
  time_value: string;
  weekday: number;
  monthday: number;
  notify_on_success: boolean;
}) {
  return request<PortalChatBIMonitor>({ url: '/api/portal/chatbi-monitors', method: 'post', data });
}

export interface ScenarioResourceRequirement {
  type: string;
  name: string;
  required: boolean;
  description?: string;
}

export interface ScenarioTemplateSummary {
  id: string;
  name: string;
  category: string;
  description: string;
  tags: string[];
  recommended: boolean;
  targetDepartments: string[];
  deliveryTime?: string;
  maturity?: string;
  includedCapabilities: string[];
  deliverables: string[];
  businessGoals: string[];
  installSteps: string[];
  acceptanceCriteria: string[];
  requiredResources: ScenarioResourceRequirement[];
  sampleQuestions: string[];
}

export interface ScenarioTemplateDetail {
  summary: ScenarioTemplateSummary;
  manifest: Record<string, unknown>;
}

export interface ScenarioResourceOption {
  id: string;
  name: string;
  label: string;
  description?: string;
  status?: string;
  meta?: Record<string, unknown>;
}

export interface ScenarioResourceOptions {
  templateId: string;
  options: Record<string, ScenarioResourceOption[]>;
}

export interface ScenarioPrecheck {
  templateId: string;
  targetAgentName: string;
  canInstall: boolean;
  checks: Array<{ key: string; label: string; status: string; message: string }>;
}

export interface ScenarioTemplateInstance {
  id: string;
  templateId: string;
  templateName: string;
  status: string;
  owner?: string;
  agent: Record<string, unknown>;
  latestRun: Record<string, unknown>;
  resourceSummary: Array<Record<string, unknown>>;
  acceptanceCriteria: string[];
  sampleQuestions: string[];
  nextSteps: string[];
}

export interface ScenarioTemplateInstall {
  templateId: string;
  created: boolean;
  instance: Record<string, unknown>;
  run: Record<string, unknown>;
  agent: Record<string, unknown>;
  version: Record<string, unknown>;
  resourceBindings: Record<string, unknown>;
  missingResources: ScenarioResourceRequirement[];
  nextSteps: string[];
  enabledTools: string[];
  sampleQuestions: string[];
  resourceSummary: Array<Record<string, unknown>>;
}

export interface ScenarioTemplateUninstall {
  instanceId: string;
  templateId: string;
  status: 'succeeded' | 'failed' | string;
  agentStatus: 'disabled' | 'archived' | 'not_found' | string;
  previousStatus: string;
  idempotent: boolean;
  warning?: string | null;
  runId: string;
  reason: string;
}

export function getScenarioTemplates() {
  return request<ScenarioTemplateSummary[]>({ url: '/api/portal/scenario-templates', method: 'get' });
}

export function getScenarioTemplate(templateId: string) {
  return request<ScenarioTemplateDetail>({
    url: `/api/portal/scenario-templates/${encodeURIComponent(templateId)}`,
    method: 'get'
  });
}

export function getScenarioTemplateResourceOptions(templateId: string) {
  return request<ScenarioResourceOptions>({
    url: `/api/portal/scenario-templates/${encodeURIComponent(templateId)}/resource-options`,
    method: 'get'
  });
}

export function precheckScenarioTemplate(
  templateId: string,
  data: {
    instanceKey?: string;
    displayName?: string;
    description?: string;
    resourceBindings?: Record<string, unknown>;
    publish?: boolean;
    idempotencyKey?: string;
  }
) {
  return request<ScenarioPrecheck>({
    url: `/api/portal/scenario-templates/${encodeURIComponent(templateId)}/precheck`,
    method: 'post',
    data
  });
}

export function installScenarioTemplate(
  templateId: string,
  data: {
    instanceKey?: string;
    displayName?: string;
    description?: string;
    resourceBindings?: Record<string, unknown>;
    publish?: boolean;
    idempotencyKey?: string;
  }
) {
  return request<ScenarioTemplateInstall>({
    url: `/api/portal/scenario-templates/${encodeURIComponent(templateId)}/install`,
    method: 'post',
    data
  });
}

export function getScenarioTemplateInstances() {
  return request<ScenarioTemplateInstance[]>({ url: '/api/portal/scenario-templates/instances', method: 'get' });
}

export function getScenarioTemplateInstance(instanceId: string) {
  return request<ScenarioTemplateInstance>({
    url: `/api/portal/scenario-templates/instances/${encodeURIComponent(instanceId)}`,
    method: 'get'
  });
}

export function uninstallScenarioTemplateInstance(
  instanceId: string,
  data: { confirm: true; reason?: string; idempotencyKey?: string }
) {
  return request<ScenarioTemplateUninstall>({
    url: `/api/portal/scenario-templates/instances/${encodeURIComponent(instanceId)}/uninstall`,
    method: 'post',
    data
  });
}
