import { describe, expect, it } from 'vitest';
import {
  includeSqlToolDatasets,
  taskResourcePermission,
  type SqlToolResource
} from './sql-tool-resources';

describe('task SQL tool resources', () => {
  it('freezes the SQL tool dataset with query permission', () => {
    const tool: SqlToolResource = {
      id: '73',
      toolType: 'sql',
      executionPolicy: { datasetId: '800' }
    };

    expect(includeSqlToolDatasets(['tool:73'], [tool])).toEqual(['tool:73', 'dataset:800']);
    expect(taskResourcePermission('dataset')).toBe('query');
  });

  it('does not infer datasets for non-SQL tools or duplicate explicit grants', () => {
    const sql: SqlToolResource = { id: '73', toolType: 'sql', executionPolicy: { datasetId: '800' } };
    const api: SqlToolResource = { id: '74', toolType: 'api', executionPolicy: { datasetId: '999' } };

    expect(includeSqlToolDatasets(['tool:73', 'dataset:800', 'tool:74'], [sql, api])).toEqual([
      'tool:73',
      'dataset:800',
      'tool:74'
    ]);
    expect(taskResourcePermission('knowledge_base')).toBe('read');
    expect(taskResourcePermission('tool')).toBe('use');
  });
});
