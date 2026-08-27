export interface SqlToolDraft {
  datasetId: string | null;
  queryPurpose: string;
  sqlTemplate: string;
}

export interface SqlToolParameterDraft {
  name: string;
  required: boolean;
}

export interface SqlDiagnosticTable {
  columns: Array<{ key: string; title: string }>;
  rows: Array<Record<string, unknown>>;
  queryId: string;
  rowCount: number;
  resultBytes: number;
  truncated: boolean;
  elapsedMs: number;
}

export function parseSqlToolPolicy(policy: Record<string, unknown>): SqlToolDraft {
  const rawDatasetId = policy.datasetId;
  return {
    datasetId:
      typeof rawDatasetId === 'string' || typeof rawDatasetId === 'number' ? String(rawDatasetId) : null,
    queryPurpose: typeof policy.queryPurpose === 'string' ? policy.queryPurpose : '',
    sqlTemplate: typeof policy.sqlTemplate === 'string' ? policy.sqlTemplate : ''
  };
}

export function buildSqlToolPolicy(draft: SqlToolDraft): Record<string, unknown> {
  return {
    datasetId: draft.datasetId,
    queryPurpose: draft.queryPurpose.trim(),
    sqlTemplate: draft.sqlTemplate.trim(),
    readOnly: true
  };
}

export function validateSqlToolDraft(draft: SqlToolDraft, parameters: SqlToolParameterDraft[]): string | null {
  if (!draft.datasetId || !/^[1-9]\d*$/.test(draft.datasetId)) return '请选择当前账号可查询的数据集';
  if (!draft.queryPurpose.trim() || draft.queryPurpose.trim().length > 1000) return '请填写 1000 字以内的查询用途';
  const sql = draft.sqlTemplate.trim();
  if (!sql || new TextEncoder().encode(sql).length > 65536) return '请填写 64KB 以内的 SQL 模板';
  const policyBytes = new TextEncoder().encode(JSON.stringify(buildSqlToolPolicy(draft))).length;
  if (policyBytes > 65536) return 'SQL 工具执行策略总大小不能超过 64KB';

  const declared = new Set(parameters.map(item => item.name.trim()).filter(Boolean));
  if (parameters.some(item => !item.required)) return 'SQL 模板参数必须全部设为必填';
  const referenced = new Set<string>();
  const marker = /{{\s*([A-Za-z_][A-Za-z0-9_]{0,63})\s*}}/g;
  for (const match of sql.matchAll(marker)) referenced.add(match[1]);
  const stripped = sql.replace(marker, '');
  if (stripped.includes('{{') || stripped.includes('}}')) return 'SQL 参数占位符格式不正确';
  if ([...referenced].some(name => !declared.has(name))) return 'SQL 模板引用了未声明的参数';
  if ([...declared].some(name => !referenced.has(name))) return '每个输入参数都必须在 SQL 模板中使用';
  return null;
}

export function toSqlDiagnosticTable(data: unknown): SqlDiagnosticTable | null {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return null;
  const source = data as Record<string, unknown>;
  if (!Array.isArray(source.columns) || !source.columns.every(item => typeof item === 'string')) return null;
  if (!Array.isArray(source.rows) || !source.rows.every(item => Array.isArray(item))) return null;
  const columns = source.columns.map((title, index) => ({ key: `column_${index}`, title }));
  const rows = source.rows.map((raw, rowIndex) => {
    const values = raw as unknown[];
    const row: Record<string, unknown> = { __rowKey: rowIndex + 1 };
    columns.forEach((column, index) => {
      row[column.key] = values[index] ?? null;
    });
    return row;
  });
  return {
    columns,
    rows,
    queryId: String(source.queryId ?? ''),
    rowCount: finiteNumber(source.rowCount),
    resultBytes: finiteNumber(source.resultBytes),
    truncated: source.truncated === true,
    elapsedMs: finiteNumber(source.elapsedMs)
  };
}

function finiteNumber(value: unknown) {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && /^\d+$/.test(value)) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }
  return 0;
}
