import { describe, expect, it } from 'vitest';
import {
  NHS_HOST_COMMANDS,
  buildFloatingSnippet,
  buildIframeSnippet,
  buildMultiInstanceSnippet,
  buildPostMessageSnippet,
  buildWidgetIntegrationCode,
  parseCommandPayload,
  prettyProtocolPayload,
  redactProtocolPayload
} from './protocol-debugger';

describe('widget debugger protocol helpers', () => {
  it('keeps the frozen nine host commands in order', () => {
    expect(NHS_HOST_COMMANDS).toEqual([
      'INIT_CONFIG', 'OPEN_SAVED_REPORT', 'SYNC_STATE', 'UPDATE_CONTEXT', 'SET_THEME',
      'STOP_GENERATION', 'CLEAR_SESSION', 'RESET_SESSION', 'SEND_COMMAND'
    ]);
  });

  it('redacts credentials recursively before putting payloads in the log', () => {
    expect(redactProtocolPayload({ credential: 'ebt_secret', nested: { apiKey: 'key' } })).toEqual({
      credential: '[redacted]', nested: { apiKey: '[redacted]' }
    });
    expect(prettyProtocolPayload({ token: 'secret', value: 'kept' })).toContain('[redacted]');
  });

  it('accepts only JSON objects as manual command payloads', () => {
    expect(parseCommandPayload('{"state":{"page":"home"}}')).toEqual({ state: { page: 'home' } });
    expect(parseCommandPayload('')).toEqual({});
    expect(() => parseCommandPayload('[]')).toThrow('JSON 对象');
    expect(() => parseCommandPayload('oops')).toThrow();
  });

  it('generates exact-origin integration snippets without embedding secrets', () => {
    const code = buildWidgetIntegrationCode({ origin: 'https://portal.example.com', agentVersionId: '42' });
    expect(code).toContain("protocolMode: 'nhs-v1'");
    expect(code).toContain("}, 'https://portal.example.com'");
    expect(code).not.toContain('apiKey');
    expect(buildIframeSnippet({ origin: 'https://portal.example.com' })).toContain('parentOrigin=https%3A%2F%2Fportal.example.com');
    expect(buildFloatingSnippet({ origin: 'https://portal.example.com' })).toContain('agent-widget-panel');
    expect(buildPostMessageSnippet({ origin: 'https://portal.example.com' })).toContain("}, targetOrigin)");
    const multi = buildMultiInstanceSnippet({ origin: 'https://portal.example.com' });
    expect(multi).toContain('assistant-sales');
    expect(multi).toContain('assistant-support');
    expect(multi).toContain('sales.destroy(); support.destroy()');
  });
});
