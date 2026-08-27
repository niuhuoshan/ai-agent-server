import { describe, expect, it } from 'vitest';
import {
  EMBED_PROTOCOL,
  EMBED_PROTOCOL_VERSION,
  createEmbedEnvelope,
  exactHttpOrigin,
  isEmbedEnvelope
} from './protocol';

describe('embed postMessage protocol', () => {
  it('creates a versioned instance-scoped envelope', () => {
    const envelope = createEmbedEnvelope(
      'instance_12345678', 'SEND_MESSAGE', 'command_12345678', { input: 'hello' }
    );

    expect(envelope).toEqual({
      protocol: EMBED_PROTOCOL,
      version: EMBED_PROTOCOL_VERSION,
      instanceId: 'instance_12345678',
      type: 'SEND_MESSAGE',
      correlationId: 'command_12345678',
      payload: { input: 'hello' }
    });
    expect(isEmbedEnvelope(envelope, 'instance_12345678')).toBe(true);
    expect(isEmbedEnvelope(envelope, 'instance_other1')).toBe(false);
  });

  it('rejects unversioned and malformed cross-instance messages', () => {
    expect(isEmbedEnvelope({
      protocol: EMBED_PROTOCOL,
      version: '2.0',
      instanceId: 'instance_12345678',
      type: 'READY',
      correlationId: 'ready_12345678',
      payload: {}
    })).toBe(false);
    expect(isEmbedEnvelope({
      protocol: EMBED_PROTOCOL,
      version: EMBED_PROTOCOL_VERSION,
      instanceId: '../escape',
      type: 'READY',
      correlationId: 'ready_12345678',
      payload: {}
    })).toBe(false);
  });

  it('allows only exact path-free HTTP origins', () => {
    expect(exactHttpOrigin('HTTPS://Portal.Example.com:443')).toBe('https://portal.example.com');
    expect(() => exactHttpOrigin('https://portal.example.com/embed')).toThrow();
    expect(() => exactHttpOrigin('javascript:alert(1)')).toThrow();
  });

  it('accepts the frozen Nhs host and widget event matrix', () => {
    const commands = [
      'INIT_CONFIG', 'OPEN_SAVED_REPORT', 'SYNC_STATE', 'UPDATE_CONTEXT', 'SET_THEME',
      'STOP_GENERATION', 'CLEAR_SESSION', 'RESET_SESSION', 'SEND_COMMAND'
    ] as const;
    const events = [
      'NHS_WIDGET_READY', 'INIT_SUCCESS', 'INIT_FAILURE', 'GENERATION_STOPPED', 'CONVERSATION_CHANGED',
      'OPEN_DATA_PORTAL_FULL', 'USER_FEEDBACK', 'CONNECTION_STATUS', 'ERROR', 'RESIZE'
    ] as const;
    for (const type of [...commands, ...events]) {
      expect(isEmbedEnvelope(createEmbedEnvelope('instance_12345678', type, 'command_12345678', {}))).toBe(true);
    }
  });
});
