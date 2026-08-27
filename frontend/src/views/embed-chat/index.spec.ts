import { flushPromises, shallowMount } from '@vue/test-utils';
import type { VueWrapper } from '@vue/test-utils';
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  createSession: vi.fn(),
  fetchState: vi.fn(),
  resetSession: vi.fn(),
  resumeTurn: vi.fn(),
  stopTurn: vi.fn(),
  streamMessage: vi.fn(),
  uploadAttachment: vi.fn()
}));

vi.mock('@/service/api/embed-widget', () => ({
  createEmbedWidgetSession: api.createSession,
  fetchEmbedWidgetState: api.fetchState,
  resetEmbedWidgetSession: api.resetSession,
  resumeEmbedWidgetTurn: api.resumeTurn,
  stopEmbedWidgetTurn: api.stopTurn,
  streamEmbedWidgetMessage: api.streamMessage,
  uploadEmbedAttachment: api.uploadAttachment
}));

const instanceId = 'instance_12345678';
const parentOrigin = 'https://portal.example.com';
const parentPost = vi.fn();
const parentWindow = { postMessage: parentPost } as unknown as Window;
const originalParent = window.parent;
const originalScrollTo = HTMLElement.prototype.scrollTo;
let EmbedChat: typeof import('./index.vue').default;
let wrapper: VueWrapper | null = null;

beforeAll(async () => {
  window.history.replaceState({}, '', `/?instanceId=${instanceId}&parentOrigin=${encodeURIComponent(parentOrigin)}`);
  Object.defineProperty(window, 'parent', { configurable: true, value: parentWindow });
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
    }
  );
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({ matches: false }))
  );
  HTMLElement.prototype.scrollTo = vi.fn();
  EmbedChat = (await import('./index.vue')).default;
});

afterAll(() => {
  wrapper?.unmount();
  Object.defineProperty(window, 'parent', { configurable: true, value: originalParent });
  HTMLElement.prototype.scrollTo = originalScrollTo;
  vi.unstubAllGlobals();
});

beforeEach(async () => {
  wrapper?.unmount();
  parentPost.mockReset();
  Object.values(api).forEach(mock => mock.mockReset());
  api.createSession.mockResolvedValue(bootstrap('50', 'a'));
  api.fetchState.mockResolvedValue(state('50'));
  api.resetSession.mockResolvedValue(bootstrap('51', 'b'));
  api.stopTurn.mockResolvedValue({ id: '80', status: 'stopping' });
  wrapper = shallowMount(EmbedChat, {
    attachTo: document.body,
    global: {
      stubs: { SvgIcon: true, NButton: true, NInput: true }
    }
  });
  command('INIT_CONFIG', 'init_12345678', { credential: `ebt_${'z'.repeat(43)}` });
  await flushPromises();
  parentPost.mockClear();
});

describe('embed chat operation races', () => {
  it('settles a queued pre-meta stop when attachment upload fails', async () => {
    let rejectUpload!: (reason: unknown) => void;
    api.uploadAttachment.mockReturnValue(
      new Promise((_, reject) => {
        rejectUpload = reject;
      })
    );

    command('SEND_MESSAGE', 'send_12345678', {
      input: 'hello',
      attachments: [new File(['hello'], 'note.txt', { type: 'text/plain' })]
    });
    await waitForCall(api.uploadAttachment);
    command('STOP', 'stop_12345678', {});
    rejectUpload(new Error('upload failed'));
    await flushPromises();

    expect(errorCorrelations()).toEqual(expect.arrayContaining(['send_12345678', 'stop_12345678']));
    expect(api.stopTurn).not.toHaveBeenCalled();
  });

  it('cancels a stale send without overwriting a successful reset', async () => {
    let rejectAbortedStream!: () => void;
    api.streamMessage.mockImplementation(
      (...args: unknown[]) =>
        new Promise((_resolve, reject) => {
          const signal = args.at(-1) as AbortSignal;
          signal.addEventListener('abort', () => {
            rejectAbortedStream = () => reject(new DOMException('aborted', 'AbortError'));
          });
        })
    );

    command('SEND_MESSAGE', 'send_12345678', { input: 'hello', attachments: [] });
    await waitForCall(api.streamMessage);
    command('RESET_SESSION', 'reset_12345678', {});
    await flushPromises();
    rejectAbortedStream();
    await flushPromises();

    const messages = postedEnvelopes();
    expect(messages).toContainEqual(
      expect.objectContaining({
        type: 'STATE',
        correlationId: 'reset_12345678',
        payload: expect.objectContaining({
          status: 'ready',
          session: expect.objectContaining({ id: '51' })
        })
      })
    );
    expect(messages).toContainEqual(
      expect.objectContaining({
        type: 'ERROR',
        correlationId: 'send_12345678',
        payload: expect.objectContaining({
          cancelled: true,
          state: expect.objectContaining({ status: 'ready' })
        })
      })
    );
    expect(messages).not.toContainEqual(
      expect.objectContaining({
        type: 'MESSAGE_COMPLETE',
        correlationId: 'send_12345678'
      })
    );
    expect(wrapper?.text()).toContain('在线');
  });

  it('streams resumed deltas into a fresh assistant draft', async () => {
    const running = state(
      '50',
      [{ id: '80', status: 'running' }],
      [
        {
          id: 'old-assistant',
          traceId: 'old-trace',
          role: 'assistant',
          content: 'old answer',
          status: 'completed',
          createdAt: '2026-08-17T00:00:00Z'
        }
      ]
    );
    api.fetchState.mockResolvedValue(running);
    let finishResume!: () => void;
    api.resumeTurn.mockImplementation((...args: unknown[]) => {
      const onEvent = args.at(-2) as (event: unknown) => void;
      onEvent({
        event: 'execution',
        id: '1',
        data: { eventType: 'text_delta', summary: 'new answer' }
      });
      return new Promise<void>(resolve => {
        finishResume = resolve;
      });
    });

    command('RESUME', 'resume_12345678', {});
    await waitForCall(api.resumeTurn);
    await flushPromises();

    const contents = wrapper?.findAll('.message-content').map(node => node.text());
    expect(contents).toEqual(['old answer', 'new answer']);

    finishResume();
    await flushPromises();
  });

  it('rejects resume takeover without aborting the active send', async () => {
    let finishSend!: () => void;
    let sendSignal!: AbortSignal;
    api.streamMessage.mockImplementation((...args: unknown[]) => {
      sendSignal = args.at(-1) as AbortSignal;
      return new Promise<void>(resolve => {
        finishSend = resolve;
      });
    });

    command('SEND_MESSAGE', 'send_12345678', { input: 'hello', attachments: [] });
    await waitForCall(api.streamMessage);
    command('RESUME', 'resume_12345678', {});
    await flushPromises();

    expect(api.resumeTurn).not.toHaveBeenCalled();
    expect(sendSignal.aborted).toBe(false);
    expect(errorCorrelations()).toContain('resume_12345678');
    expect(wrapper?.text()).toContain('streaming');

    finishSend();
    await flushPromises();
  });

  it('keeps a reset failure authoritative over stale send and stop failures', async () => {
    let rejectAbortedStream!: () => void;
    let rejectStop!: () => void;
    api.streamMessage.mockImplementation(
      (...args: unknown[]) =>
        new Promise((_resolve, reject) => {
          const onEvent = args.at(-2) as (event: unknown) => void;
          const signal = args.at(-1) as AbortSignal;
          onEvent({ event: 'meta', data: { turnId: '80' } });
          signal.addEventListener('abort', () => {
            rejectAbortedStream = () => reject(new DOMException('aborted', 'AbortError'));
          });
        })
    );
    api.stopTurn.mockReturnValue(
      new Promise((_resolve, reject) => {
        rejectStop = () => reject(new Error('stale stop failed'));
      })
    );
    api.resetSession.mockRejectedValue(new Error('reset failed'));

    command('SEND_MESSAGE', 'send_12345678', { input: 'hello', attachments: [] });
    await waitForCall(api.streamMessage);
    command('STOP', 'stop_12345678', {});
    await waitForCall(api.stopTurn);
    command('RESET_SESSION', 'reset_12345678', {});
    await flushPromises();
    rejectStop();
    rejectAbortedStream();
    await flushPromises();

    const messages = postedEnvelopes();
    expect(messages).toContainEqual(
      expect.objectContaining({
        type: 'ERROR',
        correlationId: 'reset_12345678',
        payload: expect.objectContaining({
          message: 'reset failed',
          state: expect.objectContaining({ status: 'error' })
        })
      })
    );
    expect(messages).toContainEqual(
      expect.objectContaining({
        type: 'ERROR',
        correlationId: 'stop_12345678',
        payload: expect.objectContaining({
          cancelled: true,
          state: expect.objectContaining({ status: 'error' })
        })
      })
    );
    expect(messages).not.toContainEqual(expect.objectContaining({ type: 'STATE', correlationId: 'stop_12345678' }));
    expect(wrapper?.text()).toContain('reset failed');
  });
});

describe('nhs host protocol', () => {
  it('uses canonical handshake and state events when requested by the host', async () => {
    wrapper?.unmount();
    parentPost.mockReset();
    api.createSession.mockResolvedValue(bootstrap('60', 'c'));
    api.fetchState.mockResolvedValue(state('60'));
    wrapper = shallowMount(EmbedChat, {
      attachTo: document.body,
      global: { stubs: { SvgIcon: true, NButton: true, NInput: true } }
    });
    command('INIT_CONFIG', 'init_nhs1', {
      credential: `ebt_${'c'.repeat(43)}`,
      contract: 'nhs-v1'
    });
    await flushPromises();
    expect(postedEnvelopes()).toContainEqual(expect.objectContaining({ type: 'INIT_SUCCESS', correlationId: 'init_nhs1' }));

    command('SYNC_STATE', 'sync_nhs1', { state: { page: 'portal' } });
    await flushPromises();
    expect(postedEnvelopes()).toContainEqual(expect.objectContaining({
      type: 'CONVERSATION_CHANGED',
      correlationId: 'sync_nhs1'
    }));
  });

  it('dispatches SEND_COMMAND and emits feedback without exposing credentials', async () => {
    parentPost.mockReset();
    command('SEND_COMMAND', 'feedback_nhs1', {
      command: 'USER_FEEDBACK',
      payload: { rating: 'up', credential: 'should-not-be-returned' }
    });
    await flushPromises();
    expect(postedEnvelopes()).toContainEqual(expect.objectContaining({
      type: 'USER_FEEDBACK',
      correlationId: 'feedback_nhs1',
      payload: expect.objectContaining({ rating: 'up', accepted: true })
    }));
    expect(postedEnvelopes().find(message => message.type === 'USER_FEEDBACK')?.payload).not.toHaveProperty('credential');
  });
});

function command(type: string, correlationId: string, payload: unknown) {
  const event = new MessageEvent('message', {
    data: {
      protocol: 'agent-embed',
      version: '1.0',
      instanceId,
      type,
      correlationId,
      payload
    },
    origin: parentOrigin
  });
  Object.defineProperty(event, 'source', { value: parentWindow });
  window.dispatchEvent(event);
}

function postedEnvelopes() {
  return parentPost.mock.calls.map(([message]) => message);
}

function errorCorrelations() {
  return postedEnvelopes()
    .filter(message => message.type === 'ERROR')
    .map(message => message.correlationId);
}

async function waitForCall(mock: ReturnType<typeof vi.fn>) {
  await vi.waitFor(() => expect(mock).toHaveBeenCalled());
}

function bootstrap(sessionId: string, tokenCharacter: string) {
  return {
    session: session(sessionId),
    browserCredential: {
      credential: `ebt_${tokenCharacter.repeat(43)}`,
      expiresAt: '2026-08-17T01:00:00Z',
      protocolVersion: '1.0',
      embedPath: '/embed/chat'
    },
    config: {
      allowedOrigins: [parentOrigin],
      agentVersionIds: ['40'],
      displayName: 'Assistant',
      primaryColor: '#18a058',
      watermark: false,
      maxSessionMinutes: 30
    }
  };
}

function state(sessionId: string, turns: unknown[] = [], messages: unknown[] = []) {
  return { session: session(sessionId), turns, messages };
}

function session(id: string) {
  return {
    id,
    agentVersionId: '40',
    status: 'active',
    expiresAt: '2026-08-17T01:00:00Z',
    createdAt: '2026-08-17T00:00:00Z'
  };
}
