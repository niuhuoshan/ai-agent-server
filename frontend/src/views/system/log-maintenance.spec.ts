import { describe, expect, it } from 'vitest';
import {
  formatStorageBytes,
  logStorageModeLabel,
  maintenanceStatusLabel,
  maintenanceStatusType,
  numericSummary
} from './log-maintenance';

describe('log maintenance presentation helpers', () => {
  it('formats physical sizes and storage modes', () => {
    expect(formatStorageBytes(512)).toBe('512 B');
    expect(formatStorageBytes(1024 * 1024)).toBe('1.00 MB');
    expect(logStorageModeLabel('partitioned')).toBe('按月分区');
    expect(logStorageModeLabel('regular')).toBe('普通表');
  });

  it('maps persistent run states without exposing raw JSON', () => {
    expect(maintenanceStatusLabel('partial')).toBe('部分完成');
    expect(maintenanceStatusType('failed')).toBe('error');
    expect(numericSummary({ deletedRows: 12 }, 'deletedRows')).toBe(12);
    expect(numericSummary({ deletedRows: '12' }, 'deletedRows')).toBe(0);
  });
});
