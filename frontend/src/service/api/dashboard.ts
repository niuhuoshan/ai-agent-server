import { request } from '../request';

export type DashboardPeriod = 'today' | 'week' | 'month';

export interface UnavailableMetric {
  status: 'unavailable';
  source?: string;
  reason?: string;
}

export interface ExecutionSummary {
  total: number;
  success: number;
  errors: number;
  cancelled: number;
  success_rate: number;
  avg_latency_ms: number;
  source?: string;
}

export interface TokenUsageSummary {
  calls: number;
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
  coverage?: string;
  unavailable_sources?: string[];
}

export interface DashboardApiCallSummary {
  period: DashboardPeriod;
  total: number;
  success: number;
  errors: number;
  pending?: number;
  avg_response_time?: number;
  success_rate?: number;
  error_rate?: number;
  source?: string;
}

export interface DashboardStats {
  period: DashboardPeriod;
  scope: 'enterprise' | 'self' | string;
  principal_id?: number;
  principal_name?: string;
  as_of?: string;
  execution_runs?: ExecutionSummary;
  token_usage?: TokenUsageSummary;
  prompt_tokens?: number;
  completion_tokens?: number;
  total_tokens?: number;
  total_users?: number | UnavailableMetric;
  active_users?: number | UnavailableMetric;
  api_calls?: DashboardApiCallSummary;
  avg_response_time?: number;
  success_rate?: number;
  error_rate?: number;
  api_key_status?: string;
  last_call_time?: string | null;
}

export interface DashboardAgentHealth {
  success_rate: number;
  total_steps: number;
  total_tool_calls: number;
  avg_latency: number;
  source?: string;
}

export interface DashboardToolUsage {
  tool_id?: string | number;
  name: string;
  value: number;
  source?: string;
}

export interface DashboardPerformanceTrend {
  hour: string;
  timestamp?: string;
  avg_ms: number;
  steps: number;
  source?: string;
}

export interface DashboardAgentPerformance {
  agent_id?: string | number;
  name: string;
  version?: number;
  calls: number;
  avg_latency: number;
  success_rate: number;
  source?: string;
}

export interface DashboardError {
  run_id?: string | number;
  task_id?: string | number;
  trace_id?: string | null;
  agent?: string | null;
  step?: string | null;
  message?: string | null;
  time?: string | null;
  source?: string;
}

export interface DashboardAgentStats {
  period: DashboardPeriod;
  scope: 'enterprise' | 'self' | string;
  health_stats: DashboardAgentHealth;
  tool_usage: DashboardToolUsage[];
  performance_trend: DashboardPerformanceTrend[];
  recent_errors: DashboardError[];
  agent_performance: DashboardAgentPerformance[];
  api_metrics?: DashboardApiCallSummary;
}

export interface DashboardActivityUser {
  user_id?: string | number;
  user_name?: string;
  real_name?: string;
  last_active?: string | null;
  source?: string;
}

export interface DashboardActivityRun {
  id?: string | number;
  run_id?: string | number;
  task_id?: string | number;
  created_by?: string | number;
  trace_id?: string | null;
  status?: string;
  task_title?: string | null;
  agent_name?: string | null;
  created_at?: string | null;
  started_at?: string | null;
  finished_at?: string | null;
  source?: string;
}

export interface DashboardApiCall {
  id?: string | number;
  user_name?: string | null;
  endpoint?: string | null;
  method?: string | null;
  status_code?: number | null;
  process_time_ms?: number;
  outcome?: string | null;
  error_code?: string | null;
  error_message?: string | null;
  created_at?: string | null;
  source?: string;
}

export interface DashboardActivities {
  scope: 'enterprise' | 'self' | string;
  recent_users: DashboardActivityUser[];
  recent_runs: DashboardActivityRun[];
  recent_calls: DashboardApiCall[];
  recent_errors: DashboardApiCall[];
  recent_run_errors?: DashboardError[];
}

export interface TokenTrend {
  date: string;
  calls: number;
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
  source?: string;
}

export interface TokenAgentRow {
  agent_id?: string | number;
  name: string;
  calls: number;
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
  source?: string;
}

export interface TokenUserRow {
  user_id?: string | number;
  username: string;
  real_name?: string;
  calls: number;
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
  ratio: number;
  source?: string;
}

export interface TokenRecord {
  id: string | number;
  created_at?: string | null;
  user_id?: string | number;
  username?: string;
  real_name?: string;
  agent_id?: string | number;
  agent_name?: string;
  model_id?: string | number;
  model_name?: string;
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
  status?: string;
  source?: string;
}

export interface TokenRecordsPage {
  items: TokenRecord[];
  total: number;
  page: number;
  size: number;
  coverage?: string;
  unavailable_sources?: string[];
}

export interface DashboardApiTrend {
  date: string;
  total_calls: number;
  success_calls: number;
  error_calls: number;
  success_rate: number;
  avg_response_time?: number;
  source?: string;
}

export interface DashboardApiHourTrend {
  hour: string;
  timestamp: string;
  total_calls: number;
  success_calls: number;
  error_calls: number;
  success_rate: number;
  avg_response_time?: number;
  source?: string;
}

export interface DashboardOnlineUser {
  user_id?: string | number | null;
  user_name?: string | null;
  real_name?: string | null;
  role?: string | null;
  department?: string | null;
  client_key?: string | null;
  device_type?: string | null;
  login_time?: string | null;
  last_active?: string | null;
}

export interface DashboardOnlineUsers {
  status: 'available' | 'unavailable';
  count: number;
  user_count: number;
  users: DashboardOnlineUser[];
  source?: string;
  as_of?: string;
  reason?: string;
}

export function fetchDashboardStats(period: DashboardPeriod, admin = false) {
  return request<DashboardStats>({
    url: `/api/portal/dashboard/${admin ? 'admin-stats' : 'user-stats'}`,
    method: 'get',
    params: { period }
  });
}

export function fetchDashboardAgentStats(period: DashboardPeriod) {
  return request<DashboardAgentStats>({
    url: '/api/portal/dashboard/agent-stats',
    method: 'get',
    params: { period }
  });
}

export function fetchDashboardActivities(limit = 10) {
  return request<DashboardActivities>({
    url: '/api/portal/dashboard/recent-activities',
    method: 'get',
    params: { limit }
  });
}

export function fetchDashboardApiTrends(days = 7) {
  return request<DashboardApiTrend[]>({ url: '/api/portal/dashboard/api-trends', method: 'get', params: { days } });
}

export function fetchDashboardApiTrends24h() {
  return request<DashboardApiHourTrend[]>({ url: '/api/portal/dashboard/api-trends-24h', method: 'get' });
}

export function fetchDashboardOnlineUsers() {
  return request<DashboardOnlineUsers>({ url: '/api/portal/dashboard/online-users', method: 'get' });
}

export function fetchTokenTrends(params: { days?: number; startDate?: string; endDate?: string } = {}) {
  return request<TokenTrend[]>({
    url: '/api/portal/dashboard/token-stats/trends',
    method: 'get',
    params: { days: params.days, start_date: params.startDate, end_date: params.endDate }
  });
}

export function fetchTokenRecords(params: { days?: number; startDate?: string; endDate?: string; page?: number; size?: number } = {}) {
  return request<TokenRecordsPage>({
    url: '/api/portal/dashboard/token-stats/records',
    method: 'get',
    params: {
      days: params.days,
      start_date: params.startDate,
      end_date: params.endDate,
      page: params.page,
      size: params.size
    }
  });
}

export function fetchTokenAgents(period: DashboardPeriod) {
  return request<TokenAgentRow[]>({ url: '/api/portal/dashboard/token-stats/agents', method: 'get', params: { period } });
}

export function fetchTokenUsers(period: DashboardPeriod) {
  return request<TokenUserRow[]>({ url: '/api/portal/dashboard/token-stats/users', method: 'get', params: { period } });
}
