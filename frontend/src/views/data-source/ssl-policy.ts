import type { DataSourceDatabaseType } from '@/service/api';

export interface DataSourceSslOption {
  label: string;
  value: string;
}

const sslModeLabels: Record<string, string> = {
  prefer: '优先使用加密连接',
  require: '必须使用加密连接',
  'verify-ca': '校验证书机构',
  'verify-full': '完整校验证书和主机',
  allow: '允许非加密连接',
  disable: '关闭加密连接'
};

const sslModes: Record<DataSourceDatabaseType, readonly string[]> = {
  postgresql: ['prefer', 'require', 'verify-ca', 'verify-full', 'allow', 'disable'],
  mysql: ['prefer', 'require', 'verify-ca', 'verify-full', 'disable'],
  oracle: ['disable', 'require', 'verify-ca', 'verify-full'],
  sqlserver: ['verify-full', 'require', 'disable'],
  clickhouse: ['disable', 'verify-full']
};

const defaultSslModes: Record<DataSourceDatabaseType, string> = {
  postgresql: 'prefer',
  mysql: 'prefer',
  oracle: 'disable',
  sqlserver: 'verify-full',
  clickhouse: 'disable'
};

export function dataSourceSslOptions(dbType: DataSourceDatabaseType): DataSourceSslOption[] {
  return sslModes[dbType].map(value => ({ label: sslModeLabels[value], value }));
}

export function defaultDataSourceSslMode(dbType: DataSourceDatabaseType) {
  return defaultSslModes[dbType];
}

export function normalizeDataSourceSslMode(dbType: DataSourceDatabaseType, value: unknown) {
  return typeof value === 'string' && sslModes[dbType].includes(value)
    ? value
    : defaultDataSourceSslMode(dbType);
}
