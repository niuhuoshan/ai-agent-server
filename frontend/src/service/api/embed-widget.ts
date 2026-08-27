import { getServiceBaseURL } from '@/utils/service';

export interface EmbedBrowserCredential {
  credential: string;
  expiresAt: string;
  protocolVersion: '1.0';
  embedPath: string;
}

export interface EmbedSession {
  id: string;
  agentVersionId: string;
  status: string;
  expiresAt: string;
  createdAt: string;
}

export interface EmbedApplicationTheme {
  allowedOrigins: string[];
  agentVersionIds: string[];
  displayName?: string;
  primaryColor: string;
  watermark: boolean;
  maxSessionMinutes: number;
}

export interface EmbedWelcomeCard {
  icon: string;
  title: string;
  subtitle: string;
  prompt: string;
}

export interface EmbedBootstrap {
  session: EmbedSession;
  browserCredential: EmbedBrowserCredential;
  config: EmbedApplicationTheme;
  welcomeCards: EmbedWelcomeCard[];
}

export interface EmbedTurn {
  id: string;
  status: string;
  errorSummary: string | null;
  stopRequestedAt: string | null;
  startedAt: string;
  finishedAt: string | null;
}

export interface EmbedMessage {
  id: string;
  traceId: string;
  role: 'user' | 'assistant' | string;
  content: string;
  status: string;
  createdAt: string;
  feedback?: 'up' | 'down' | null;
}

export interface EmbedState {
  session: EmbedSession;
  turns: EmbedTurn[];
  messages: EmbedMessage[];
}

export interface EmbedAttachment {
  id: string;
  conversationId: string;
  originalName: string;
  mimeType: string;
  sizeBytes: number;
  sha256: string;
  status: string;
  createdAt: string;
}

export interface EmbedSseEvent {
  event: string;
  id?: string;
  data: Record<string, unknown>;
}

interface OpenEnvelope<T> {
  requestId: string;
  data: T;
}

export async function issueEmbedBrowserCredential(
  apiKey: string,
  payload: { origin: string; agentVersionId: string; externalUserKey: string; sessionMinutes: number }
) {
  return openJson<EmbedBrowserCredential>('/open/v1/embed/browser-credentials', {
    method: 'POST',
    headers: credentialHeaders(apiKey, 'application/json'),
    body: JSON.stringify(payload)
  });
}

export function createEmbedWidgetSession(launchCredential: string, hostOrigin: string) {
  return openJson<EmbedBootstrap>('/open/v1/embed/widget/sessions', {
    method: 'POST',
    headers: widgetHeaders(launchCredential, hostOrigin)
  });
}

export function fetchEmbedWidgetState(credential: string, hostOrigin: string, sessionId: string) {
  return openJson<EmbedState>(`/open/v1/embed/widget/sessions/${encodeURIComponent(sessionId)}`, {
    headers: widgetHeaders(credential, hostOrigin)
  });
}

export function resetEmbedWidgetSession(credential: string, hostOrigin: string, sessionId: string) {
  return openJson<EmbedBootstrap>(`/open/v1/embed/widget/sessions/${encodeURIComponent(sessionId)}/reset`, {
    method: 'POST',
    headers: widgetHeaders(credential, hostOrigin)
  });
}

export function closeEmbedBrowserSession(
  credential: string,
  hostOrigin: string,
  sessionId: string,
  browserSessionId: string
) {
  return openJson<Record<string, unknown>>(`/open/v1/embed/widget/sessions/${encodeURIComponent(sessionId)}/browser/close`, {
    method: 'POST',
    headers: widgetHeaders(credential, hostOrigin),
    body: JSON.stringify({ browserSessionId })
  });
}

export function resetEmbedBrowserSession(
  credential: string,
  hostOrigin: string,
  sessionId: string,
  browserSessionId: string
) {
  return openJson<Record<string, unknown>>(`/open/v1/embed/widget/sessions/${encodeURIComponent(sessionId)}/browser/reset`, {
    method: 'POST',
    headers: widgetHeaders(credential, hostOrigin),
    body: JSON.stringify({ browserSessionId })
  });
}

export function uploadEmbedAttachment(
  credential: string,
  hostOrigin: string,
  sessionId: string,
  file: File
) {
  const body = new FormData();
  body.append('file', file, file.name);
  return openJson<EmbedAttachment>(
    `/open/v1/embed/widget/sessions/${encodeURIComponent(sessionId)}/attachments`,
    { method: 'POST', headers: widgetHeaders(credential, hostOrigin), body }
  );
}

export async function streamEmbedWidgetMessage(
  credential: string,
  hostOrigin: string,
  sessionId: string,
  payload: {
    idempotencyKey: string;
    input: string;
    attachmentIds: string[];
    context: Record<string, unknown>;
  },
  onEvent: (event: EmbedSseEvent) => void,
  signal: AbortSignal
) {
  const headers = widgetHeaders(credential, hostOrigin);
  headers.set('Content-Type', 'application/json');
  const response = await fetch(
    apiUrl(`/open/v1/embed/widget/sessions/${encodeURIComponent(sessionId)}/messages`),
    { method: 'POST', headers, body: JSON.stringify(payload), signal }
  );
  await consumeSse(response, onEvent, signal);
}

export async function resumeEmbedWidgetTurn(
  credential: string,
  hostOrigin: string,
  sessionId: string,
  turnId: string,
  cursor: number,
  onEvent: (event: EmbedSseEvent) => void,
  signal: AbortSignal
) {
  const path = `/open/v1/embed/widget/sessions/${encodeURIComponent(sessionId)}`
    + `/turns/${encodeURIComponent(turnId)}/events?cursor=${encodeURIComponent(cursor)}`;
  const response = await fetch(apiUrl(path), {
    headers: widgetHeaders(credential, hostOrigin),
    signal
  });
  await consumeSse(response, onEvent, signal);
}

export function stopEmbedWidgetTurn(
  credential: string,
  hostOrigin: string,
  sessionId: string,
  turnId: string
) {
  const path = `/open/v1/embed/widget/sessions/${encodeURIComponent(sessionId)}`
    + `/turns/${encodeURIComponent(turnId)}/stop`;
  return openJson<EmbedTurn>(path, {
    method: 'POST',
    headers: widgetHeaders(credential, hostOrigin)
  });
}

export function submitEmbedFeedback(
  credential: string,
  hostOrigin: string,
  sessionId: string,
  payload: { messageId: number; turnId?: number; rating: 'up' | 'down'; reason?: string; comment?: string; traceId?: string }
) {
  return openJson<Record<string, unknown>>(
    `/open/v1/embed/widget/sessions/${encodeURIComponent(sessionId)}/feedback`,
    {
      method: 'POST',
      headers: widgetJsonHeaders(credential, hostOrigin),
      body: JSON.stringify({
        messageId: payload.messageId,
        turnId: payload.turnId,
        rating: payload.rating,
        reason: payload.reason,
        comment: payload.comment,
        traceId: payload.traceId
      })
    }
  );
}

function credentialHeaders(credential: string, contentType?: string) {
  const headers = new Headers({ Accept: 'application/json' });
  headers.set('Authorization', bearer(credential));
  if (contentType) headers.set('Content-Type', contentType);
  return headers;
}

function widgetHeaders(credential: string, hostOrigin: string) {
  const headers = credentialHeaders(credential);
  headers.set('X-Embed-Host-Origin', hostOrigin);
  return headers;
}

function widgetJsonHeaders(credential: string, hostOrigin: string) {
  const headers = widgetHeaders(credential, hostOrigin);
  headers.set('Content-Type', 'application/json');
  return headers;
}

function bearer(value: string) {
  const credential = value.trim();
  return /^Bearer\s/i.test(credential) ? credential : `Bearer ${credential}`;
}

async function openJson<T>(path: string, init: RequestInit) {
  const response = await fetch(apiUrl(path), init);
  if (!response.ok) throw await responseError(response);
  const body = await response.json() as OpenEnvelope<T> | T;
  return isEnvelope<T>(body) ? body.data : body;
}

async function consumeSse(
  response: Response,
  onEvent: (event: EmbedSseEvent) => void,
  signal: AbortSignal
) {
  if (!response.ok) throw await responseError(response);
  if (!response.body) throw new Error('当前浏览器不支持流式响应');
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  while (!signal.aborted) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value, { stream: !done });
    const frames = buffer.split(/\r?\n\r?\n/);
    buffer = frames.pop() || '';
    for (const frame of frames) {
      const event = parseFrame(frame);
      if (event) onEvent(event);
    }
    if (done) break;
  }
  // Some proxies close immediately after the final SSE line without writing
  // the optional blank separator. Preserve that final durable event.
  if (buffer.trim() && !signal.aborted) {
    const event = parseFrame(buffer);
    if (event) onEvent(event);
  }
}

function parseFrame(frame: string): EmbedSseEvent | null {
  let event = 'message';
  let id: string | undefined;
  const data: string[] = [];
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('id:')) id = line.slice(3).trim();
    else if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
  }
  if (!data.length) return null;
  return { event, id, data: JSON.parse(data.join('\n')) as Record<string, unknown> };
}

async function responseError(response: Response) {
  let message = `请求失败 (${response.status})`;
  try {
    const payload = await response.json() as { msg?: string; message?: string };
    message = payload.msg || payload.message || message;
  } catch {
    // Preserve the status-only fallback.
  }
  const error = new Error(message) as Error & { status?: number };
  error.status = response.status;
  return error;
}

function isEnvelope<T>(value: OpenEnvelope<T> | T): value is OpenEnvelope<T> {
  return Boolean(value && typeof value === 'object' && 'data' in value);
}

function apiUrl(path: string) {
  const isHttpProxy = import.meta.env.DEV && import.meta.env.VITE_HTTP_PROXY === 'Y';
  const { baseURL } = getServiceBaseURL(import.meta.env, isHttpProxy);
  return `${baseURL.replace(/\/$/, '')}${path}`;
}

export const embedWidgetApiInternals = { parseFrame, bearer };
