import { afterEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import { fetchAuditTrace, fetchAuditTraceSpans } from './audit';

vi.mock('../request', () => ({ request: vi.fn() }));

afterEach(() => {
  vi.mocked(request).mockReset();
});

describe('Audit trace API', () => {
  it('encodes trace IDs before requesting the permission-replayed trace', () => {
    fetchAuditTrace('private/trace a');

    expect(request).toHaveBeenCalledWith({
      url: '/platform/audit-events/traces/private%2Ftrace%20a',
      method: 'get'
    });
  });

  it('keeps the flat span endpoint available for runtime field inspection', () => {
    fetchAuditTraceSpans('run:401');

    expect(request).toHaveBeenCalledWith({
      url: '/platform/audit-events/traces/run%3A401/spans',
      method: 'get'
    });
  });
});
