import type { EmbedHostCommand, EmbedWidgetEvent } from '@/embed/protocol';

export const NHS_HOST_COMMANDS: EmbedHostCommand[] = [
  'INIT_CONFIG',
  'OPEN_SAVED_REPORT',
  'SYNC_STATE',
  'UPDATE_CONTEXT',
  'SET_THEME',
  'STOP_GENERATION',
  'CLEAR_SESSION',
  'RESET_SESSION',
  'SEND_COMMAND'
];

export const NHS_WIDGET_EVENTS: EmbedWidgetEvent[] = [
  'NHS_WIDGET_READY',
  'INIT_SUCCESS',
  'INIT_FAILURE',
  'GENERATION_STOPPED',
  'CONVERSATION_CHANGED',
  'OPEN_DATA_PORTAL_FULL',
  'USER_FEEDBACK',
  'CONNECTION_STATUS',
  'ERROR',
  'RESIZE'
];

const SECRET_KEY = /(credential|token|secret|password|authorization|api[-_]?key)/i;

export interface ProtocolLogEntry {
  id: string;
  direction: 'TX' | 'RX';
  type: string;
  correlationId: string;
  timestamp: string;
  payload: unknown;
}

export function redactProtocolPayload(value: unknown, depth = 0): unknown {
  if (depth > 8) return '[truncated]';
  if (Array.isArray(value)) return value.slice(0, 100).map(item => redactProtocolPayload(item, depth + 1));
  if (!value || typeof value !== 'object') return value;
  return Object.fromEntries(Object.entries(value).slice(0, 100).map(([key, child]) => [
    key,
    SECRET_KEY.test(key) ? '[redacted]' : redactProtocolPayload(child, depth + 1)
  ]));
}

export function parseCommandPayload(value: string): Record<string, unknown> {
  const text = value.trim();
  if (!text) return {};
  const parsed: unknown = JSON.parse(text);
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('命令载荷必须是 JSON 对象');
  }
  return parsed as Record<string, unknown>;
}

export function prettyProtocolPayload(value: unknown) {
  return JSON.stringify(redactProtocolPayload(value), null, 2);
}

export function buildWidgetIntegrationCode(options: {
  origin: string;
  embedPath?: string;
  agentVersionId?: string;
  targetOrigin?: string;
}) {
  const origin = options.targetOrigin || options.origin;
  const embedPath = options.embedPath || '/embed/chat';
  const agentVersionId = options.agentVersionId || 'replace-with-agent-version-id';
  return `<script src="${options.origin}/embed-widget.js"></script>
<div id="agent-widget"></div>
<script>
  // Exchange the application credential on your server. Never put it in this page.
  const launch = await fetch('/your-server/embed-token', { credentials: 'include' })
    .then(response => response.json());
  const widget = AgentEmbed.create({
    container: document.getElementById('agent-widget'),
    embedUrl: '${options.origin}${embedPath}',
    credential: launch.credential,
    protocolMode: 'nhs-v1',
    context: { page: location.pathname },
    theme: 'auto'
  });
  widget.on('NHS_WIDGET_READY', (_payload, envelope) => {
    console.debug('widget ready', envelope.instanceId);
  });
  widget.on('CONNECTION_STATUS', payload => console.debug('connection', payload));
  widget.on('CONVERSATION_CHANGED', payload => console.debug('conversation', payload));
  widget.on('ERROR', payload => console.error('widget error', payload));
  // Host commands are instance-scoped and sent only to the exact origin.
  const frame = document.querySelector('#agent-widget iframe');
  frame.contentWindow.postMessage({
    protocol: 'agent-embed', version: '1.0', instanceId: widget.instanceId,
    type: 'SYNC_STATE', correlationId: 'sync_' + crypto.randomUUID().replaceAll('-', ''),
    payload: { state: { agentVersionId: '${agentVersionId}' } }
  }, '${origin}');
</script>
<!-- Expected targetOrigin: ${origin} -->`;
}

export function buildIframeSnippet(options: { origin: string; embedPath?: string }) {
  const origin = options.origin;
  const path = options.embedPath || '/embed/chat';
  return `<iframe
  title="智能助手"
  src="${origin}${path}?instanceId=instance_replace_me&parentOrigin=${encodeURIComponent(origin)}"
  referrerpolicy="strict-origin"
  allow="clipboard-write"
  style="width:100%;min-height:420px;border:0"
></iframe>`;
}

export function buildFloatingSnippet(options: { origin: string; embedPath?: string }) {
  const path = options.embedPath || '/embed/chat';
  return `<button id="agent-widget-toggle" type="button" aria-controls="agent-widget-panel">打开助手</button>
<aside id="agent-widget-panel" hidden style="position:fixed;right:24px;bottom:72px;width:min(390px,calc(100vw - 32px));height:620px">
  <div id="agent-widget-floating"></div>
</aside>
<script src="${options.origin}/embed-widget.js"></script>
<script>
  const launch = await fetch('/your-server/embed-token').then(response => response.json());
  const widget = AgentEmbed.create({
    container: document.getElementById('agent-widget-floating'),
    embedUrl: '${options.origin}${path}', credential: launch.credential, protocolMode: 'nhs-v1'
  });
  const panel = document.getElementById('agent-widget-panel');
  document.getElementById('agent-widget-toggle').addEventListener('click', () => {
    panel.hidden = !panel.hidden;
  });
</script>`;
}

export function buildPostMessageSnippet(options: { origin: string; embedPath?: string }) {
  const path = options.embedPath || '/embed/chat';
  return `<iframe id="agent-widget" title="智能助手" referrerpolicy="strict-origin"></iframe>
<script>
  const targetOrigin = '${options.origin}';
  const instanceId = 'instance_' + crypto.randomUUID().replaceAll('-', '');
  const frame = document.getElementById('agent-widget');
  frame.src = targetOrigin + '${path}?instanceId=' + encodeURIComponent(instanceId)
    + '&parentOrigin=' + encodeURIComponent(location.origin);
  window.addEventListener('message', event => {
    const message = event.data;
    if (event.origin !== targetOrigin || event.source !== frame.contentWindow
      || message?.instanceId !== instanceId || message?.protocol !== 'agent-embed') return;
    if (message.type === 'NHS_WIDGET_READY' || message.type === 'READY') {
      fetch('/your-server/embed-token').then(response => response.json()).then(launch => {
        frame.contentWindow.postMessage({
          protocol: 'agent-embed', version: '1.0', instanceId,
          type: 'INIT_CONFIG', correlationId: 'init_' + crypto.randomUUID().replaceAll('-', ''),
          payload: { credential: launch.credential, contract: 'nhs-v1', context: {} }
        }, targetOrigin);
      });
    }
  });
</script>`;
}

export function buildMultiInstanceSnippet(options: { origin: string; embedPath?: string }) {
  const path = options.embedPath || '/embed/chat';
  return `<script src="${options.origin}/embed-widget.js"></script>
<div id="assistant-sales"></div>
<div id="assistant-support"></div>
<script>
  const [salesLaunch, supportLaunch] = await Promise.all([
    fetch('/your-server/embed-token?agent=sales').then(response => response.json()),
    fetch('/your-server/embed-token?agent=support').then(response => response.json())
  ]);
  const sales = AgentEmbed.create({
    container: document.getElementById('assistant-sales'), embedUrl: '${options.origin}${path}',
    credential: salesLaunch.credential, protocolMode: 'nhs-v1'
  });
  const support = AgentEmbed.create({
    container: document.getElementById('assistant-support'), embedUrl: '${options.origin}${path}',
    credential: supportLaunch.credential, protocolMode: 'nhs-v1'
  });
  sales.on('*', (_payload, envelope) => console.debug('sales', envelope.instanceId));
  support.on('*', (_payload, envelope) => console.debug('support', envelope.instanceId));
  window.addEventListener('beforeunload', () => { sales.destroy(); support.destroy(); });
</script>`;
}
