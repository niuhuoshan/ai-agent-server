import {
  EMBED_PROTOCOL_VERSION,
  correlationId,
  createEmbedEnvelope,
  exactHttpOrigin,
  isEmbedEnvelope
} from './protocol';
import type { EmbedEnvelope, EmbedHostCommand, EmbedWidgetEvent } from './protocol';

export interface AgentEmbedOptions {
  container: HTMLElement;
  embedUrl: string;
  credential: string;
  context?: Record<string, unknown>;
  theme?: 'light' | 'dark' | 'auto';
  /** Selects the frozen Nhs host contract for new integrations. */
  protocolMode?: 'nhs-v1' | 'legacy';
  minHeight?: number;
  maxHeight?: number;
}

type Listener = (payload: unknown, envelope: EmbedEnvelope) => void;

/** Strict host-side controller for one isolated Embed iframe instance. */
export class AgentEmbedWidget {
  readonly instanceId = correlationId('instance');
  readonly protocolVersion = EMBED_PROTOCOL_VERSION;

  private readonly iframe: HTMLIFrameElement;
  private readonly targetOrigin: string;
  private readonly parentOrigin: string;
  private readonly credential: string;
  private readonly context: Record<string, unknown>;
  private readonly theme: 'light' | 'dark' | 'auto';
  private readonly minHeight: number;
  private readonly maxHeight: number;
  private readonly protocolMode: 'nhs-v1' | 'legacy';
  private readonly pending = new Map<string, {
    resolve: (payload: unknown) => void;
    reject: (reason: unknown) => void;
    timer: number;
  }>();
  private readonly listeners = new Map<string, Set<Listener>>();
  private initializing = false;
  private initialized = false;
  private destroyed = false;

  constructor(options: AgentEmbedOptions) {
    if (!(options.container instanceof HTMLElement)) throw new Error('Embed 容器无效');
    if (!/^ebt_[A-Za-z0-9_-]{43}$/.test(options.credential.trim())) {
      throw new Error('Embed 启动凭证无效');
    }
    const embedUrl = new URL(options.embedUrl, window.location.href);
    this.targetOrigin = exactHttpOrigin(embedUrl.origin);
    this.parentOrigin = exactHttpOrigin(window.location.origin);
    this.credential = options.credential.trim();
    this.context = structuredClone(options.context || {});
    this.theme = options.theme || 'auto';
    this.minHeight = Math.max(240, options.minHeight || 420);
    this.maxHeight = Math.max(this.minHeight, options.maxHeight || 760);
    this.protocolMode = options.protocolMode || 'nhs-v1';
    embedUrl.searchParams.set('instanceId', this.instanceId);
    embedUrl.searchParams.set('parentOrigin', this.parentOrigin);

    this.iframe = document.createElement('iframe');
    this.iframe.src = embedUrl.toString();
    this.iframe.title = '智能助手';
    this.iframe.setAttribute('allow', 'clipboard-write');
    this.iframe.setAttribute('referrerpolicy', 'strict-origin');
    this.iframe.style.cssText = `display:block;width:100%;height:${this.minHeight}px;border:0;background:transparent`;
    window.addEventListener('message', this.handleMessage);
    options.container.replaceChildren(this.iframe);
  }

  on(type: EmbedWidgetEvent | '*', listener: Listener) {
    const group = this.listeners.get(type) || new Set<Listener>();
    group.add(listener);
    this.listeners.set(type, group);
    return () => group.delete(listener);
  }

  updateContext(context: Record<string, unknown>) {
    return this.command('UPDATE_CONTEXT', { context: structuredClone(context) });
  }

  syncState(state: Record<string, unknown>) {
    return this.command('SYNC_STATE', { state: structuredClone(state) });
  }

  openSavedReport(report: Record<string, unknown>) {
    return this.command('OPEN_SAVED_REPORT', { report: structuredClone(report) });
  }

  resetSession() {
    return this.command('RESET_SESSION', {});
  }

  clearSession() {
    return this.command('CLEAR_SESSION', {});
  }

  sendMessage(input: string, attachments: File[] = []) {
    return this.protocolMode === 'legacy'
      ? this.command('SEND_MESSAGE', { input, attachments })
      : this.sendCommand('SEND_MESSAGE', { input, attachments });
  }

  sendCommand(command: string, payload: Record<string, unknown> = {}) {
    return this.command('SEND_COMMAND', { command, ...structuredClone(payload) });
  }

  stop() {
    return this.command(this.protocolMode === 'legacy' ? 'STOP' : 'STOP_GENERATION', {});
  }

  stopGeneration() {
    return this.stop();
  }

  resume() {
    return this.command('RESUME', {});
  }

  setTheme(theme: 'light' | 'dark' | 'auto', styleVars: Record<string, string> = {}) {
    return this.command('SET_THEME', { theme, styleVars: structuredClone(styleVars) });
  }

  /** Exposes a typed escape hatch for the protocol debugger and SDK integrations. */
  sendRawCommand(type: EmbedHostCommand, payload: unknown = {}) {
    return this.command(type, structuredClone(payload));
  }

  destroy() {
    if (this.destroyed) return;
    this.destroyed = true;
    window.removeEventListener('message', this.handleMessage);
    for (const item of this.pending.values()) {
      window.clearTimeout(item.timer);
      item.reject(new Error('Embed 实例已销毁'));
    }
    this.pending.clear();
    this.listeners.clear();
    this.iframe.remove();
  }

  private command(type: EmbedHostCommand, payload: unknown) {
    if (!this.initialized && type !== 'INIT_CONFIG') {
      return Promise.reject(new Error('Embed 尚未初始化'));
    }
    const id = correlationId('command');
    const envelope = createEmbedEnvelope(this.instanceId, type, id, payload);
    return new Promise<unknown>((resolve, reject) => {
      const timeoutMs = type === 'SEND_MESSAGE' || type === 'SEND_COMMAND' || type === 'RESUME' ? 300_000 : 30_000;
      const timer = window.setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`Embed ${type} 操作超时`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      this.post(envelope);
    });
  }

  private post(envelope: EmbedEnvelope) {
    const target = this.iframe.contentWindow;
    if (!target) throw new Error('Embed iframe 尚未就绪');
    target.postMessage(envelope, this.targetOrigin);
  }

  private handleMessage = (event: MessageEvent) => {
    if (this.destroyed || event.origin !== this.targetOrigin
      || event.source !== this.iframe.contentWindow
      || !isEmbedEnvelope(event.data, this.instanceId)) return;
    const envelope = event.data;
    if ((envelope.type === 'READY' || envelope.type === 'NHS_WIDGET_READY') && !this.initialized && !this.initializing) {
      this.initializing = true;
      void this.command('INIT_CONFIG', {
        credential: this.credential,
        context: this.context,
        theme: this.theme,
        contract: this.protocolMode
      }).then(() => {
        this.initialized = true;
        this.initializing = false;
      }).catch(() => {
        this.initializing = false;
      });
    }
    if (envelope.type === 'RESIZE') this.resize(envelope.payload);
    const pending = this.pending.get(envelope.correlationId);
    if (pending && (envelope.type === 'MESSAGE_START' || envelope.type === 'MESSAGE_EVENT'
      || envelope.type === 'CONNECTION_STATUS')) {
      window.clearTimeout(pending.timer);
      pending.timer = window.setTimeout(() => {
        this.pending.delete(envelope.correlationId);
        pending.reject(new Error('Embed 流式操作超时'));
      }, 300_000);
    }
    if (pending && (envelope.type === 'INITIALIZED' || envelope.type === 'INIT_SUCCESS'
      || envelope.type === 'INIT_FAILURE'
      || envelope.type === 'STATE' || envelope.type === 'CONVERSATION_CHANGED'
      || envelope.type === 'MESSAGE_COMPLETE' || envelope.type === 'GENERATION_STOPPED'
      || envelope.type === 'OPEN_DATA_PORTAL_FULL' || envelope.type === 'USER_FEEDBACK'
      || envelope.type === 'ERROR')) {
      window.clearTimeout(pending.timer);
      this.pending.delete(envelope.correlationId);
      if (envelope.type === 'ERROR' || envelope.type === 'INIT_FAILURE') pending.reject(this.error(envelope.payload));
      else pending.resolve(envelope.payload);
    }
    this.emit(envelope);
  };

  private resize(payload: unknown) {
    if (!payload || typeof payload !== 'object') return;
    const requested = Number((payload as { height?: unknown }).height);
    if (!Number.isFinite(requested)) return;
    const height = Math.max(this.minHeight, Math.min(this.maxHeight, Math.ceil(requested)));
    this.iframe.style.height = `${height}px`;
  }

  private emit(envelope: EmbedEnvelope) {
    for (const listener of this.listeners.get(envelope.type) || []) listener(envelope.payload, envelope);
    for (const listener of this.listeners.get('*') || []) listener(envelope.payload, envelope);
  }

  private error(payload: unknown) {
    const message = payload && typeof payload === 'object'
      && typeof (payload as { message?: unknown }).message === 'string'
      ? (payload as { message: string }).message
      : 'Embed 操作失败';
    return new Error(message);
  }
}
