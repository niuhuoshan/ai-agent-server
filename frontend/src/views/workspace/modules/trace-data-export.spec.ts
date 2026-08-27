import { afterEach, describe, expect, it, vi } from 'vitest';
import { traceDataExportErrorMessage, traceDataExportKey, triggerTraceDataDownload } from './trace-data-export';

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('trace data export UI helpers', () => {
  it('keeps loading keys stable across traces and formats', () => {
    expect(traceDataExportKey('trace-1', 'csv')).toBe(traceDataExportKey('trace-1', 'csv'));
    expect(traceDataExportKey(' trace-1 ', 'csv')).toBe(traceDataExportKey('trace-1', 'csv'));
    expect(traceDataExportKey('trace-1', 'csv')).not.toBe(traceDataExportKey('trace-1', 'xlsx'));
    expect(traceDataExportKey('trace-1', 'csv')).not.toBe(traceDataExportKey('trace-2', 'csv'));
  });

  it('downloads through an object URL and revokes it after the click', () => {
    vi.useFakeTimers();
    const createObjectURL = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:trace-export');
    const revokeObjectURL = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);

    triggerTraceDataDownload(new Blob(['a,b\n1,2']), 'result.csv');

    expect(createObjectURL).toHaveBeenCalledOnce();
    expect(click).toHaveBeenCalledOnce();
    expect(document.querySelector('a[download="result.csv"]')).toBeNull();
    expect(revokeObjectURL).not.toHaveBeenCalled();
    vi.runAllTimers();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:trace-export');
  });

  it('keeps the requested format and backend detail in the error', () => {
    expect(traceDataExportErrorMessage(new Error('查询结果不存在'), 'xlsx')).toBe('XLSX 数据导出失败：查询结果不存在');
    expect(traceDataExportErrorMessage(null, 'csv')).toContain('CSV 数据导出失败');
  });
});
