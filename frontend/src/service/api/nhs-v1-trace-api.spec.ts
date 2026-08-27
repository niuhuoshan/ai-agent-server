import { afterEach, describe, expect, it, vi } from 'vitest';
import { downloadNhsV1TraceData, fetchNhsV1TraceLogs } from './platform';
import { request } from '../request';

vi.mock('../request', () => ({ request: vi.fn() }));

afterEach(() => {
  vi.mocked(request).mockReset();
  vi.unstubAllGlobals();
});

describe('Nhs V1 trace API', () => {
  it('requests the owner-authorized trace log contract', () => {
    fetchNhsV1TraceLogs('trace/a b');

    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/chat/logs/trace%2Fa%20b',
      method: 'get'
    });
  });

  it('downloads CSV with the response filename and explicit media type', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response('a,b\n1,2', {
        status: 200,
        headers: {
          'Content-Type': 'text/csv;charset=UTF-8',
          'Content-Disposition': "attachment; filename*=UTF-8''report%20data.csv"
        }
      })
    );
    vi.stubGlobal('fetch', fetchMock);

    const result = await downloadNhsV1TraceData('trace-1', 'csv');

    expect(result.fileName).toBe('report data.csv');
    expect(await result.blob.text()).toBe('a,b\n1,2');
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/chat/export/data/trace-1?format=csv');
    expect(new Headers(init.headers).get('Accept')).toBe('text/csv');
  });

  it('downloads XLSX with the spreadsheet media type and a stable fallback name', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(new Uint8Array([80, 75, 3, 4]), {
        status: 200,
        headers: {
          'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
        }
      })
    );
    vi.stubGlobal('fetch', fetchMock);

    const result = await downloadNhsV1TraceData('trace-2', 'xlsx');

    expect(result.fileName).toBe('export_trace-2.xlsx');
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/chat/export/data/trace-2?format=xlsx');
    expect(new Headers(init.headers).get('Accept')).toBe(
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    );
  });

  it('surfaces a backend export error instead of downloading JSON', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            code: 404,
            msg: '当前回复没有可导出的数据'
          }),
          {
            status: 404,
            headers: { 'Content-Type': 'application/json' }
          }
        )
      )
    );

    await expect(downloadNhsV1TraceData('trace-1', 'xlsx')).rejects.toThrow('当前回复没有可导出的数据');
  });
});
