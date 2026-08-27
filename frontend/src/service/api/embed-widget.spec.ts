import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  createEmbedWidgetSession,
  embedWidgetApiInternals,
  issueEmbedBrowserCredential,
  stopEmbedWidgetTurn,
  uploadEmbedAttachment
} from './embed-widget';

afterEach(() => vi.unstubAllGlobals());

describe('embed widget API', () => {
  it('uses the application secret only for the browser-credential exchange', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      data: {
        credential: `ebt_${'a'.repeat(43)}`,
        expiresAt: '2026-08-17T00:05:00',
        protocolVersion: '1.0',
        embedPath: '/embed/chat'
      }
    }));
    vi.stubGlobal('fetch', fetchMock);

    await issueEmbedBrowserCredential('agk_application.secret', {
      origin: 'https://portal.example.com',
      agentVersionId: '40',
      externalUserKey: 'customer-1',
      sessionMinutes: 30
    });

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/open/v1/embed/browser-credentials');
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer agk_application.secret');
    expect(JSON.parse(String(init.body))).toMatchObject({ origin: 'https://portal.example.com' });
  });

  it('bootstraps the iframe with only an origin-bound short credential', async () => {
    const launch = `ebt_${'b'.repeat(43)}`;
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      data: { session: { id: '50' }, browserCredential: { credential: `ebt_${'c'.repeat(43)}` }, config: {} }
    }));
    vi.stubGlobal('fetch', fetchMock);

    await createEmbedWidgetSession(launch, 'https://portal.example.com');

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const headers = new Headers(init.headers);
    expect(url).toContain('/open/v1/embed/widget/sessions');
    expect(headers.get('Authorization')).toBe(`Bearer ${launch}`);
    expect(headers.get('X-Embed-Host-Origin')).toBe('https://portal.example.com');
    expect(JSON.stringify(init)).not.toContain('agk_');
  });

  it('uploads multipart data and sends durable stop to the exact session turn', async () => {
    const token = `ebt_${'d'.repeat(43)}`;
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ data: { id: '70' } }))
      .mockResolvedValueOnce(jsonResponse({ data: { id: '80', status: 'stopping' } }));
    vi.stubGlobal('fetch', fetchMock);

    await uploadEmbedAttachment(
      token, 'https://portal.example.com', '50', new File(['hello'], 'note.txt', { type: 'text/plain' })
    );
    await stopEmbedWidgetTurn(token, 'https://portal.example.com', '50', '80');

    const [uploadUrl, uploadInit] = fetchMock.mock.calls[0] as [string, RequestInit];
    const [stopUrl, stopInit] = fetchMock.mock.calls[1] as [string, RequestInit];
    expect(uploadUrl).toContain('/sessions/50/attachments');
    expect(uploadInit.body).toBeInstanceOf(FormData);
    expect(stopUrl).toContain('/sessions/50/turns/80/stop');
    expect(stopInit.method).toBe('POST');
  });

  it('parses SSE frames without confusing ids and payloads', () => {
    expect(embedWidgetApiInternals.parseFrame(
      'id: 12\nevent: execution\ndata: {"eventType":"text_delta","summary":"hi"}'
    )).toEqual({
      id: '12',
      event: 'execution',
      data: { eventType: 'text_delta', summary: 'hi' }
    });
  });
});

function jsonResponse(value: unknown) {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { 'Content-Type': 'application/json' }
  });
}
