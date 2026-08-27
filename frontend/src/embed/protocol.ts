export const EMBED_PROTOCOL = 'agent-embed' as const;
export const EMBED_PROTOCOL_VERSION = '1.0' as const;

export type EmbedHostCommand =
  | 'INIT_CONFIG'
  | 'OPEN_SAVED_REPORT'
  | 'SYNC_STATE'
  | 'UPDATE_CONTEXT'
  | 'SET_THEME'
  | 'STOP_GENERATION'
  | 'CLEAR_SESSION'
  | 'RESET_SESSION'
  | 'SEND_COMMAND'
  // Legacy aliases remain accepted by the iframe so existing integrations can upgrade in place.
  | 'SEND_MESSAGE'
  | 'STOP'
  | 'RESUME';

export type EmbedWidgetEvent =
  | 'NHS_WIDGET_READY'
  | 'INIT_SUCCESS'
  | 'INIT_FAILURE'
  | 'GENERATION_STOPPED'
  | 'CONVERSATION_CHANGED'
  | 'OPEN_DATA_PORTAL_FULL'
  | 'USER_FEEDBACK'
  | 'CONNECTION_STATUS'
  | 'MESSAGE_EVENT'
  | 'READY'
  | 'INITIALIZED'
  | 'STATE'
  | 'MESSAGE_START'
  | 'MESSAGE_COMPLETE'
  | 'ERROR'
  | 'RESIZE';

export interface EmbedEnvelope<T = unknown> {
  protocol: typeof EMBED_PROTOCOL;
  version: typeof EMBED_PROTOCOL_VERSION;
  instanceId: string;
  type: EmbedHostCommand | EmbedWidgetEvent;
  correlationId: string;
  payload: T;
}

const INSTANCE_ID = /^[A-Za-z0-9_-]{8,80}$/;
const CORRELATION_ID = /^[A-Za-z0-9_-]{8,100}$/;
const MESSAGE_TYPES = new Set<EmbedEnvelope['type']>([
  'INIT_CONFIG', 'OPEN_SAVED_REPORT', 'SYNC_STATE', 'UPDATE_CONTEXT', 'SET_THEME',
  'STOP_GENERATION', 'CLEAR_SESSION', 'RESET_SESSION', 'SEND_COMMAND',
  'SEND_MESSAGE', 'STOP', 'RESUME',
  'NHS_WIDGET_READY', 'INIT_SUCCESS', 'GENERATION_STOPPED', 'CONVERSATION_CHANGED',
  'INIT_FAILURE',
  'OPEN_DATA_PORTAL_FULL', 'USER_FEEDBACK', 'CONNECTION_STATUS', 'MESSAGE_EVENT',
  'READY', 'INITIALIZED', 'STATE', 'MESSAGE_START', 'MESSAGE_COMPLETE', 'ERROR', 'RESIZE'
]);

export function createEmbedEnvelope<T>(
  instanceId: string,
  type: EmbedEnvelope['type'],
  correlationId: string,
  payload: T
): EmbedEnvelope<T> {
  if (!INSTANCE_ID.test(instanceId) || !CORRELATION_ID.test(correlationId)) {
    throw new Error('Embed 消息标识无效');
  }
  return {
    protocol: EMBED_PROTOCOL,
    version: EMBED_PROTOCOL_VERSION,
    instanceId,
    type,
    correlationId,
    payload
  };
}

export function isEmbedEnvelope(value: unknown, expectedInstanceId?: string): value is EmbedEnvelope {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Partial<EmbedEnvelope>;
  return candidate.protocol === EMBED_PROTOCOL
    && candidate.version === EMBED_PROTOCOL_VERSION
    && typeof candidate.instanceId === 'string'
    && INSTANCE_ID.test(candidate.instanceId)
    && (!expectedInstanceId || candidate.instanceId === expectedInstanceId)
    && typeof candidate.type === 'string'
    && MESSAGE_TYPES.has(candidate.type as EmbedEnvelope['type'])
    && typeof candidate.correlationId === 'string'
    && CORRELATION_ID.test(candidate.correlationId)
    && 'payload' in candidate;
}

export function correlationId(prefix = 'embed') {
  const random = globalThis.crypto?.randomUUID?.().replaceAll('-', '')
    || `${Date.now()}${Math.random().toString(36).slice(2)}`;
  return `${prefix}_${random}`;
}

export function exactHttpOrigin(value: string) {
  const url = new URL(value);
  if (!['http:', 'https:'].includes(url.protocol)
    || url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
    throw new Error('Embed Origin 必须是无路径的 HTTP(S) 来源');
  }
  return url.origin;
}
