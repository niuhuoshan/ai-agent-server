import type { DataColumnView, DataTableView } from '@/service/api';

export type MetadataTableStatusFilter = 'all' | 'active' | 'inactive';
export type MetadataColumnFilter = 'all' | 'sensitive' | 'primary' | 'inactive' | 'missing';

export interface MetadataCatalogFilters {
  keyword: string;
  tableStatus: MetadataTableStatusFilter;
  columnFilter: MetadataColumnFilter;
}

export interface MetadataColumnRow extends DataColumnView {
  rowKey: string;
  table: DataTableView;
  tableName: string;
  qualifiedTableName: string;
}

export interface MetadataCatalogStats {
  tableCount: number;
  columnCount: number;
  schemaCount: number;
  sensitiveCount: number;
  primaryCount: number;
  inactiveTableCount: number;
  inactiveColumnCount: number;
  missingMetadataCount: number;
  describedTableCount: number;
  describedColumnCount: number;
  namedTableCount: number;
  namedColumnCount: number;
  tableDescriptionCoverage: number;
  columnDescriptionCoverage: number;
  tableNameCoverage: number;
  columnNameCoverage: number;
  dataTypes: Array<{ type: string; count: number }>;
}

export interface MetadataGovernanceIssue {
  key: string;
  scope: 'table' | 'column';
  level: 'error' | 'warning' | 'info';
  title: string;
  detail: string;
  table: DataTableView;
  column?: DataColumnView;
}

function normalized(value: string | null | undefined) {
  return (value || '').trim().toLocaleLowerCase();
}

function tableMatches(table: DataTableView, needle: string) {
  return [
    table.tableKey,
    table.physicalSchema,
    table.physicalName,
    table.displayName,
    table.description,
    table.tableType,
    table.status
  ]
    .map(normalized)
    .some(value => value.includes(needle));
}

function columnMatches(column: DataColumnView, needle: string) {
  return [
    column.columnKey,
    column.physicalName,
    column.displayName,
    column.description,
    column.dataType,
    column.status,
    column.sensitive ? '敏感 sensitive' : '普通',
    column.primary ? '主键 primary' : ''
  ]
    .map(normalized)
    .some(value => value.includes(needle));
}

function columnPassesFilter(column: DataColumnView, filter: MetadataColumnFilter) {
  if (filter === 'sensitive') return column.sensitive;
  if (filter === 'primary') return column.primary;
  if (filter === 'inactive') return column.status === 'inactive';
  if (filter === 'missing') return !column.metadataPresent || !column.displayName || !column.description;
  return true;
}

export function filterMetadataCatalog(
  metadata: DataTableView[],
  filters: MetadataCatalogFilters
): DataTableView[] {
  const needle = normalized(filters.keyword);
  return metadata.flatMap(table => {
    if (filters.tableStatus !== 'all' && table.status !== filters.tableStatus) return [];
    const matchedTable = !needle || tableMatches(table, needle);
    const columns = table.columns.filter(column => {
      if (!columnPassesFilter(column, filters.columnFilter)) return false;
      return matchedTable || columnMatches(column, needle);
    });
    if (!matchedTable && columns.length === 0) return [];
    if (filters.columnFilter !== 'all' && columns.length === 0) return [];
    return [{ ...table, columns }];
  });
}

export function metadataColumnRows(metadata: DataTableView[]): MetadataColumnRow[] {
  return metadata.flatMap(table =>
    table.columns.map(column => ({
      ...column,
      rowKey: `${table.id}:${column.id}`,
      table,
      tableName: table.displayName || table.physicalName,
      qualifiedTableName: [table.physicalSchema, table.physicalName].filter(Boolean).join('.')
    }))
  );
}

function percentage(complete: number, total: number) {
  return total ? Math.round((complete / total) * 100) : 100;
}

export function metadataCatalogStats(metadata: DataTableView[]): MetadataCatalogStats {
  const columns = metadata.flatMap(table => table.columns);
  const describedTableCount = metadata.filter(table => Boolean(table.description?.trim())).length;
  const describedColumnCount = columns.filter(column => Boolean(column.description?.trim())).length;
  const namedTableCount = metadata.filter(table => Boolean(table.displayName?.trim())).length;
  const namedColumnCount = columns.filter(column => Boolean(column.displayName?.trim())).length;
  const typeCounts = new Map<string, number>();
  for (const column of columns) {
    const type = column.dataType?.trim() || 'unknown';
    typeCounts.set(type, (typeCounts.get(type) || 0) + 1);
  }

  return {
    tableCount: metadata.length,
    columnCount: columns.length,
    schemaCount: new Set(metadata.map(table => table.physicalSchema).filter(Boolean)).size,
    sensitiveCount: columns.filter(column => column.sensitive).length,
    primaryCount: columns.filter(column => column.primary).length,
    inactiveTableCount: metadata.filter(table => table.status === 'inactive').length,
    inactiveColumnCount: columns.filter(column => column.status === 'inactive').length,
    missingMetadataCount:
      metadata.filter(table => !table.metadataPresent).length + columns.filter(column => !column.metadataPresent).length,
    describedTableCount,
    describedColumnCount,
    namedTableCount,
    namedColumnCount,
    tableDescriptionCoverage: percentage(describedTableCount, metadata.length),
    columnDescriptionCoverage: percentage(describedColumnCount, columns.length),
    tableNameCoverage: percentage(namedTableCount, metadata.length),
    columnNameCoverage: percentage(namedColumnCount, columns.length),
    dataTypes: Array.from(typeCounts.entries())
      .map(([type, count]) => ({ type, count }))
      .sort((left, right) => right.count - left.count || left.type.localeCompare(right.type))
  };
}

export function metadataGovernanceIssues(metadata: DataTableView[]): MetadataGovernanceIssue[] {
  const issues: MetadataGovernanceIssue[] = [];
  for (const table of metadata) {
    const tableName = table.displayName || table.physicalName;
    if (!table.metadataPresent) {
      issues.push({
        key: `table:${table.id}:metadata`,
        scope: 'table',
        level: 'error',
        title: `${tableName} 元数据缺失`,
        detail: '物理表已不在最近一次同步结果中，请重新同步后确认。',
        table
      });
    }
    if (!table.displayName?.trim()) {
      issues.push({
        key: `table:${table.id}:name`,
        scope: 'table',
        level: 'warning',
        title: `${table.physicalName} 缺少业务名称`,
        detail: '补充显示名称后，目录和 ChatBI 更容易识别这张表。',
        table
      });
    }
    if (!table.description?.trim()) {
      issues.push({
        key: `table:${table.id}:description`,
        scope: 'table',
        level: 'info',
        title: `${tableName} 缺少业务描述`,
        detail: '补充用途、统计口径和数据更新时间说明。',
        table
      });
    }
    for (const column of table.columns) {
      const columnName = column.displayName || column.physicalName;
      if (!column.metadataPresent) {
        issues.push({
          key: `column:${column.id}:metadata`,
          scope: 'column',
          level: 'error',
          title: `${tableName}.${columnName} 元数据缺失`,
          detail: '字段已不在最近一次同步结果中，请重新同步后确认。',
          table,
          column
        });
      }
      if (!column.displayName?.trim()) {
        issues.push({
          key: `column:${column.id}:name`,
          scope: 'column',
          level: 'warning',
          title: `${tableName}.${column.physicalName} 缺少业务名称`,
          detail: '补充字段别名，避免物理字段名直接暴露给业务用户。',
          table,
          column
        });
      }
      if (!column.description?.trim()) {
        issues.push({
          key: `column:${column.id}:description`,
          scope: 'column',
          level: column.sensitive ? 'warning' : 'info',
          title: `${tableName}.${columnName} 缺少字段说明`,
          detail: column.sensitive ? '敏感字段应说明使用边界和脱敏口径。' : '补充字段含义、枚举值或统计口径。',
          table,
          column
        });
      }
    }
  }
  const rank = { error: 0, warning: 1, info: 2 };
  return issues.sort((left, right) => rank[left.level] - rank[right.level] || left.title.localeCompare(right.title));
}
