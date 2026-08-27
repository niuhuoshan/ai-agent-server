import { request } from '../request';
import { getAuthorization } from '../request/shared';
import type { ExecutionEventView, RunStepView, TaskRunView } from './platform';
import { getServiceBaseURL } from '@/utils/service';

export interface AgentDebugVersionOption {
  id: string;
  versionNo: number;
  status: 'published' | 'archived';
  modelId: string;
  contentHash: string;
  publishedAt: string;
}

export interface AgentDebugOption {
  id: string;
  agentKey: string;
  name: string;
  description: string | null;
  avatarUrl: string | null;
  defaultAgent: boolean;
  publishedVersionId: string | null;
  versions: AgentDebugVersionOption[];
}

export interface AgentDebugRunSummary {
  id: string;
  parentDebugRunId: string | null;
  agentId: string;
  agentKey: string;
  agentName: string;
  agentVersionId: string;
  versionNo: number;
  versionStatus: string;
  taskId: string;
  input: string;
  inputSha256: string;
  run: TaskRunView;
  createdAt: string;
}

export interface AgentDebugMetrics {
  promptTokens: number;
  completionTokens: number;
  cachedTokens: number;
  totalTokens: number;
  elapsedMs: number;
  modelDurationMs: number;
  modelCalls: number;
  toolCalls: number;
  eventCount: number;
  truncated: boolean;
}

export interface AgentDebugRunDetail {
  summary: AgentDebugRunSummary;
  steps: RunStepView[];
  metrics: AgentDebugMetrics;
  finalOutput: string;
}

export interface AgentDebugExecutionEvent extends ExecutionEventView {
  projection: Record<string, unknown>;
}

export interface CreateAgentDebugRunPayload {
  idempotencyKey: string;
  agentId: string;
  agentVersionId: string;
  input: string;
}

export function fetchAgentDebugOptions() {
  return request<AgentDebugOption[]>({ url: '/platform/agent-debug/options', method: 'get' });
}

export function fetchAgentDebugRuns(limit = 50) {
  return request<AgentDebugRunSummary[]>({
    url: '/platform/agent-debug/runs',
    method: 'get',
    params: { limit }
  });
}

export function createAgentDebugRun(payload: CreateAgentDebugRunPayload) {
  return request<AgentDebugRunDetail>({
    url: '/platform/agent-debug/runs',
    method: 'post',
    data: payload
  });
}

export function fetchAgentDebugRun(debugRunId: string) {
  return request<AgentDebugRunDetail>({
    url: `/platform/agent-debug/runs/${debugRunId}`,
    method: 'get'
  });
}

export function fetchAgentDebugEvents(debugRunId: string, cursor = 0, limit = 500) {
  return request<AgentDebugExecutionEvent[]>({
    url: `/platform/agent-debug/runs/${debugRunId}/events`,
    method: 'get',
    params: { cursor, limit }
  });
}

export function stopAgentDebugRun(debugRunId: string, reason?: string) {
  return request<AgentDebugRunDetail>({
    url: `/platform/agent-debug/runs/${debugRunId}/stop`,
    method: 'post',
    data: reason ? { reason } : {}
  });
}

export function resumeAgentDebugRun(debugRunId: string) {
  return request<AgentDebugRunDetail>({
    url: `/platform/agent-debug/runs/${debugRunId}/resume`,
    method: 'post'
  });
}

export function retryAgentDebugRun(debugRunId: string, idempotencyKey: string) {
  return request<AgentDebugRunDetail>({
    url: `/platform/agent-debug/runs/${debugRunId}/retry`,
    method: 'post',
    data: { idempotencyKey }
  });
}

export async function streamAgentDebugEvents(
  debugRunId: string,
  cursor: number,
  onEvent: (event: AgentDebugExecutionEvent) => void,
  onDone: () => void,
  signal: AbortSignal
) {
  const response = await fetch(rawUrl(
    `/platform/agent-debug/runs/${debugRunId}/events/stream?cursor=${encodeURIComponent(cursor)}`
  ), {
    headers: rawHeaders(),
    signal
  });
  if (!response.ok) throw new Error(await responseMessage(response));
  if (!response.body) throw new Error('浏览器未提供事件流响应体');

  const reader = response.body.getReader();
  const textDecoder = new TextDecoder();
  const decoder = new AgentDebugSseDecoder();
  while (!signal.aborted) {
    const { value, done } = await reader.read();
    const frames = decoder.feed(textDecoder.decode(value, { stream: !done }), done);
    for (const frame of frames) {
      if (frame.done) {
        onDone();
        return;
      }
      if (frame.data) onEvent(frame.data);
    }
    if (done) return;
  }
}

interface DecodedFrame {
  id: string | null;
  event: string | null;
  data: AgentDebugExecutionEvent | null;
  done: boolean;
}

export class AgentDebugSseDecoder {
  private buffer = '';

  feed(chunk: string, flush = false): DecodedFrame[] {
    this.buffer += chunk;
    const normalized = this.buffer.replace(/\r\n/g, '\n');
    const blocks = normalized.split('\n\n');
    this.buffer = flush ? '' : blocks.pop() || '';
    return blocks.filter(Boolean).map(block => this.parse(block)).filter(Boolean) as DecodedFrame[];
  }

  private parse(block: string): DecodedFrame | null {
    if (!block || block.startsWith(':')) return null;
    let id: string | null = null;
    let event: string | null = null;
    const data: string[] = [];
    for (const line of block.split('\n')) {
      if (line.startsWith('id:')) id = line.slice(3).trim();
      else if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
    }
    const payload = data.join('\n');
    if (!payload) return null;
    if (payload === '[DONE]') return { id, event, data: null, done: true };
    return {
      id,
      event,
      data: JSON.parse(payload) as AgentDebugExecutionEvent,
      done: false
    };
  }
}

function rawUrl(path: string) {
  const isHttpProxy = import.meta.env.DEV && import.meta.env.VITE_HTTP_PROXY === 'Y';
  const { baseURL } = getServiceBaseURL(import.meta.env, isHttpProxy);
  return `${baseURL.replace(/\/$/, '')}${path}`;
}

function rawHeaders() {
  const headers = new Headers({
    Accept: 'text/event-stream',
    clientid: import.meta.env.VITE_APP_CLIENT_ID
  });
  const authorization = getAuthorization();
  if (authorization) headers.set('Authorization', authorization);
  return headers;
}

async function responseMessage(response: Response) {
  try {
    const payload = await response.json() as { msg?: string; message?: string };
    return payload.msg || payload.message || `请求失败 (${response.status})`;
  } catch {
    return `请求失败 (${response.status})`;
  }
}
