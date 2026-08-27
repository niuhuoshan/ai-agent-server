import type { ToolView } from '@/service/api';

export type SqlToolResource = Pick<ToolView, 'id' | 'toolType' | 'executionPolicy'>;

export function includeSqlToolDatasets(resourceKeys: string[], tools: SqlToolResource[]) {
  const result = new Set(resourceKeys);
  for (const key of resourceKeys) {
    if (!key.startsWith('tool:')) continue;
    const tool = tools.find(item => item.id === key.slice('tool:'.length));
    const datasetId = tool?.toolType === 'sql' ? tool.executionPolicy.datasetId : null;
    if (typeof datasetId === 'string' || typeof datasetId === 'number') result.add(`dataset:${datasetId}`);
  }
  return [...result];
}

export function taskResourcePermission(resourceType: string) {
  if (resourceType === 'dataset') return 'query' as const;
  if (resourceType === 'knowledge_base' || resourceType === 'data_source') return 'read' as const;
  return 'use' as const;
}
