import { describe, expect, it } from 'vitest';
import {
  buildSqlToolPolicy,
  parseSqlToolPolicy,
  toSqlDiagnosticTable,
  validateSqlToolDraft
} from './sql-tool';

describe('resource center SQL tools', () => {
  it('round-trips governed policy fields without credentials', () => {
    const policy = buildSqlToolPolicy({
      datasetId: '9223372036854775000',
      queryPurpose: ' 查询客户订单 ',
      sqlTemplate: ' SELECT o.customer_id FROM public.orders o '
    });

    expect(policy).toEqual({
      datasetId: '9223372036854775000',
      queryPurpose: '查询客户订单',
      sqlTemplate: 'SELECT o.customer_id FROM public.orders o',
      readOnly: true
    });
    expect(parseSqlToolPolicy(policy)).toEqual({
      datasetId: '9223372036854775000',
      queryPurpose: '查询客户订单',
      sqlTemplate: 'SELECT o.customer_id FROM public.orders o'
    });
    expect(Object.keys(policy)).not.toContain('password');
    expect(Object.keys(policy)).not.toContain('credentialRef');
  });

  it('requires every declared input to be a required template value', () => {
    const draft = {
      datasetId: '800',
      queryPurpose: '查询客户订单',
      sqlTemplate: 'SELECT o.customer_id FROM public.orders o WHERE o.customer_id = {{customer}}'
    };

    expect(validateSqlToolDraft(draft, [{ name: 'customer', required: true }])).toBeNull();
    expect(validateSqlToolDraft(draft, [{ name: 'customer', required: false }])).toContain('必填');
    expect(validateSqlToolDraft(draft, [{ name: 'customer', required: true }, { name: 'year', required: true }])).toContain(
      '每个输入参数'
    );
  });

  it('allows long SQL while keeping the complete persisted policy within 64KB', () => {
    const base = {
      datasetId: '800',
      queryPurpose: '查询长报表',
      sqlTemplate: `SELECT 1 AS value\n${'-- governed query\n'.repeat(600)}`
    };

    expect(new TextEncoder().encode(base.sqlTemplate).length).toBeGreaterThan(8192);
    expect(validateSqlToolDraft(base, [])).toBeNull();
    expect(validateSqlToolDraft({ ...base, sqlTemplate: `SELECT '${'x'.repeat(65500)}'` }, [])).toContain(
      '执行策略总大小'
    );
  });

  it('maps a query result into table rows rather than raw JSON', () => {
    const result = toSqlDiagnosticTable({
      queryId: '990',
      columns: ['customer_id', 'total'],
      rows: [['C-1', 3]],
      rowCount: '1',
      resultBytes: '24',
      truncated: false,
      elapsedMs: 8
    });

    expect(result?.columns).toEqual([
      { key: 'column_0', title: 'customer_id' },
      { key: 'column_1', title: 'total' }
    ]);
    expect(result?.rows).toEqual([{ __rowKey: 1, column_0: 'C-1', column_1: 3 }]);
    expect(result?.rowCount).toBe(1);
    expect(result?.resultBytes).toBe(24);
    expect(toSqlDiagnosticTable({ columns: ['id'], rows: 'not-an-array' })).toBeNull();
  });
});
