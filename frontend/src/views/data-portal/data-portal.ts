import type {
  NhsDatasetColumn,
  NhsDatasetGroup,
  NhsDatasetMenu,
  NhsDatasetQuestion,
  PortalDataPortalReport
} from '@/service/api';

export type DataPortalSection = 'overview' | 'reports' | 'scenarios' | 'catalog';

export interface DataPortalCatalogTable {
  key: string;
  name: string;
  physicalName: string;
  description: string;
  columns: NhsDatasetColumn[];
}

export interface DataPortalCatalogDataset {
  key: string;
  id?: string;
  groupId?: string;
  name: string;
  tables: DataPortalCatalogTable[];
  columnCount: number;
}

const sectionAliases: Record<string, DataPortalSection> = {
  home: 'overview',
  overview: 'overview',
  reports: 'reports',
  scenes: 'scenarios',
  scenarios: 'scenarios',
  catalog: 'catalog'
};

export function resolveDataPortalSection(value: unknown): DataPortalSection {
  return typeof value === 'string' && sectionAliases[value] ? sectionAliases[value] : 'overview';
}

export function datasetIdFromGroup(group: NhsDatasetGroup): string | undefined {
  const explicit = group.related_data?.find(item => item.dataset_id !== undefined)?.dataset_id;
  if (explicit !== undefined && explicit !== null && String(explicit).trim()) return String(explicit);
  const id = String(group.id || '').trim();
  const match = /^dataset_(\d+)$/.exec(id);
  return match?.[1];
}

export function datasetGroupKey(group: NhsDatasetGroup): string {
  return String(group.id || group.title);
}

export function datasetGroupTables(group: NhsDatasetGroup): string[] {
  return Array.from(new Set((group.related_data || []).flatMap(item => item.tables || []).filter(Boolean)));
}

export function buildDataPortalCatalog(menu: NhsDatasetMenu | null): DataPortalCatalogDataset[] {
  const datasets = new Map<
    string,
    { id?: string; groupId?: string; name: string; tables: Map<string, DataPortalCatalogTable> }
  >();

  for (const group of menu?.groups || []) {
    for (const related of group.related_data || []) {
      const key = String(related.dataset || related.dataset_id || related.display_name || group.id || group.title);
      const existing = datasets.get(key) || {
        id: related.dataset_id === undefined ? datasetIdFromGroup(group) : String(related.dataset_id),
        groupId: group.id,
        name: related.display_name || related.dataset || group.title || '未命名数据集',
        tables: new Map<string, DataPortalCatalogTable>()
      };

      for (const name of related.tables || []) {
        const previous = existing.tables.get(name);
        const description = related.table_descriptions?.find(item => item.name === name)?.description || '';
        existing.tables.set(name, {
          key: `${key}::${name}`,
          name,
          physicalName: related.table_physical_names?.[name] || previous?.physicalName || '',
          description: description || previous?.description || '',
          columns: related.table_columns?.[name] || previous?.columns || []
        });
      }
      datasets.set(key, existing);
    }
  }

  return Array.from(datasets.entries()).map(([key, dataset]) => {
    const tables = Array.from(dataset.tables.values());
    return {
      key,
      id: dataset.id,
      groupId: dataset.groupId,
      name: dataset.name,
      tables,
      columnCount: tables.reduce((total, table) => total + table.columns.length, 0)
    };
  });
}

export function filterDataPortalCatalog(
  datasets: DataPortalCatalogDataset[],
  keyword: string
): DataPortalCatalogDataset[] {
  const needle = keyword.trim().toLocaleLowerCase();
  if (!needle) return datasets;

  return datasets
    .map(dataset => {
      if (dataset.name.toLocaleLowerCase().includes(needle)) return dataset;
      const tables = dataset.tables.filter(table =>
        [
          table.name,
          table.physicalName,
          table.description,
          ...table.columns.flatMap(column => [
            column.name,
            column.term || '',
            column.type || '',
            column.description || ''
          ])
        ]
          .join(' ')
          .toLocaleLowerCase()
          .includes(needle)
      );
      return { ...dataset, tables, columnCount: tables.reduce((total, table) => total + table.columns.length, 0) };
    })
    .filter(dataset => dataset.tables.length > 0);
}

export function portalQuestionRoute(
  question: NhsDatasetQuestion,
  datasetId?: string
): { path: string; query: Record<string, string> } {
  return {
    path: '/chatbi',
    query: {
      question: question.query,
      source: 'data_portal',
      ...(datasetId ? { dataset_id: datasetId } : {})
    }
  };
}

export type DataPortalReportFilter = 'all' | 'subscribed' | 'shared' | 'recent' | 'failed';

export function filterDataPortalReports(
  reports: PortalDataPortalReport[],
  filter: DataPortalReportFilter,
  keyword: string
): PortalDataPortalReport[] {
  const needle = keyword.trim().toLocaleLowerCase();
  return reports.filter(report => {
    const matchesFilter =
      filter === 'all' ||
      (filter === 'subscribed' && Boolean(report.subscription_status)) ||
      (filter === 'shared' && !report.is_owner) ||
      (filter === 'recent' && Boolean(report.last_run_at)) ||
      (filter === 'failed' && Boolean(report.last_error));
    if (!matchesFilter) return false;
    if (!needle) return true;
    return [report.title, report.description || '', report.owner_name || '', ...(report.tags || [])]
      .join(' ')
      .toLocaleLowerCase()
      .includes(needle);
  });
}

export function questionClickCount(question: NhsDatasetQuestion): number {
  const value = Number(question.click_count || 0);
  return Number.isFinite(value) && value > 0 ? Math.floor(value) : 0;
}
