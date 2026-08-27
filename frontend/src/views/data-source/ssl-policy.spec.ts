import { describe, expect, it } from 'vitest';
import {
  dataSourceSslOptions,
  defaultDataSourceSslMode,
  normalizeDataSourceSslMode
} from './ssl-policy';

describe('data source SSL policy', () => {
  it('uses the backend driver defaults', () => {
    expect(defaultDataSourceSslMode('postgresql')).toBe('prefer');
    expect(defaultDataSourceSslMode('mysql')).toBe('prefer');
    expect(defaultDataSourceSslMode('oracle')).toBe('disable');
    expect(defaultDataSourceSslMode('sqlserver')).toBe('verify-full');
    expect(defaultDataSourceSslMode('clickhouse')).toBe('disable');
  });

  it('only exposes modes accepted by each driver', () => {
    expect(dataSourceSslOptions('oracle').map(item => item.value)).toEqual([
      'disable', 'require', 'verify-ca', 'verify-full'
    ]);
    expect(dataSourceSslOptions('clickhouse').map(item => item.value)).toEqual([
      'disable', 'verify-full'
    ]);
    expect(dataSourceSslOptions('sqlserver').map(item => item.value)).toEqual([
      'verify-full', 'require', 'disable'
    ]);
  });

  it('replaces a mode that is invalid for the selected driver', () => {
    expect(normalizeDataSourceSslMode('clickhouse', 'prefer')).toBe('disable');
    expect(normalizeDataSourceSslMode('postgresql', 'verify-ca')).toBe('verify-ca');
  });
});
