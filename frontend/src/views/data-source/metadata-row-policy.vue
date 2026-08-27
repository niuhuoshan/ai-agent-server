<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import {
  fetchDatasetRowPolicy,
  updateDatasetRowPolicy,
  type DataTableView,
  type DatasetRowPolicyOperator,
  type DatasetRowPolicyRule,
  type DatasetRowPolicyValueSource,
  type DatasetRowPolicyView
} from '@/service/api';

interface EditableRule extends DatasetRowPolicyRule {
  clientId: string;
}

const props = defineProps<{ datasetId: string; metadata: DataTableView[] }>();

const loading = ref(false);
const saving = ref(false);
const error = ref('');
const savedSnapshot = ref('');
let sequence = 0;
const policy = reactive<{ revisionNo: number; enabled: boolean; rules: EditableRule[] }>({
  revisionNo: 0,
  enabled: false,
  rules: []
});

const operatorOptions: Array<{ label: string; value: DatasetRowPolicyOperator }> = [
  { label: '等于', value: 'eq' },
  { label: '不等于', value: 'ne' }
];
const numericTypes = new Set([
  'smallint', 'integer', 'int', 'int2', 'int4', 'int8', 'bigint',
  'numeric', 'decimal', 'number', 'tinyint', 'mediumint'
]);
const textTypes = new Set([
  'char', 'character', 'varchar', 'character varying', 'text', 'nvarchar', 'nvarchar2', 'string'
]);
const tableOptions = computed(() =>
  props.metadata.map(table => ({
    label: `${table.displayName || table.physicalName} / ${table.physicalSchema}.${table.physicalName}`,
    value: table.id,
    disabled: table.status === 'inactive' || !table.metadataPresent
  }))
);
const preview = computed(() => policy.rules.map(rule => ({
  id: rule.clientId,
  field: `${tableName(rule.tableId)}.${columnName(rule.tableId, rule.columnId)}`,
  operator: operatorOptions.find(item => item.value === rule.operator)?.label || rule.operator,
  source: rule.valueSource === 'principal_id' ? '当前用户 ID' : '当前用户名'
})));
const dirty = computed(() => savedSnapshot.value !== serializePolicy());

function requestError(result: unknown, fallback: string) {
  const value = result as {
    error?: { response?: { status?: number; data?: { msg?: string; message?: string } }; message?: string };
  };
  if (!value.error) return '';
  const status = value.error.response?.status;
  if (status === 403) return '没有维护此数据集行策略的权限';
  if (status === 409) return '行策略已被其他人修改，已加载最新版本';
  return value.error.response?.data?.msg || value.error.response?.data?.message || value.error.message || fallback;
}

function editable(rule: DatasetRowPolicyRule): EditableRule {
  return {
    ...rule,
    clientId: `rule-${++sequence}`
  };
}

function applyPolicy(value: DatasetRowPolicyView) {
  policy.revisionNo = value.revisionNo;
  policy.enabled = value.enabled;
  policy.rules = (value.rules || []).map(editable);
  savedSnapshot.value = serializePolicy();
}

async function load() {
  if (!props.datasetId) return;
  loading.value = true;
  error.value = '';
  try {
    const result = await fetchDatasetRowPolicy(props.datasetId);
    const failure = requestError(result, '行策略加载失败');
    if (failure) error.value = failure;
    else if (result.data) applyPolicy(result.data);
    else error.value = '行策略返回为空';
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '行策略加载失败';
  } finally {
    loading.value = false;
  }
}

function table(id: string) {
  return props.metadata.find(item => String(item.id) === String(id));
}

function tableName(id: string) {
  const item = table(id);
  return item?.displayName || item?.physicalName || `表 #${id}`;
}

function columnName(tableId: string, columnId: string) {
  const column = table(tableId)?.columns.find(item => String(item.id) === String(columnId));
  return column?.displayName || column?.physicalName || `字段 #${columnId}`;
}

function columnOptions(rule: EditableRule) {
  return (table(rule.tableId)?.columns || []).map(column => ({
    label: `${column.displayName || column.physicalName} / ${column.physicalName}`,
    value: column.id,
    disabled:
      column.status === 'inactive' ||
      !column.metadataPresent ||
      (!numericTypes.has(baseType(column.dataType)) && !textTypes.has(baseType(column.dataType)))
  }));
}

function baseType(dataType: string) {
  return dataType.trim().toLocaleLowerCase().split('(', 1)[0].trim();
}

function supportedColumn(tableId: string) {
  return table(tableId)?.columns.find(item => {
    const type = baseType(item.dataType);
    return item.status === 'active' && item.metadataPresent && (numericTypes.has(type) || textTypes.has(type));
  });
}

function sourceForColumn(tableId: string, columnId: string): DatasetRowPolicyValueSource {
  const column = table(tableId)?.columns.find(item => String(item.id) === String(columnId));
  return column && numericTypes.has(baseType(column.dataType)) ? 'principal_id' : 'principal_username';
}

function valueSourceOptions(rule: EditableRule) {
  const column = table(rule.tableId)?.columns.find(item => String(item.id) === String(rule.columnId));
  const type = column ? baseType(column.dataType) : '';
  return [
    { label: '当前用户 ID', value: 'principal_id', disabled: !numericTypes.has(type) },
    { label: '当前用户名', value: 'principal_username', disabled: !textTypes.has(type) }
  ];
}

function addRule() {
  const firstTable = props.metadata.find(
    item => item.status === 'active' && item.metadataPresent && Boolean(supportedColumn(item.id))
  );
  const firstColumn = firstTable ? supportedColumn(firstTable.id) : undefined;
  if (!firstTable || !firstColumn) {
    error.value = '当前数据集没有可用于行策略的启用字段';
    return;
  }
  policy.rules.push({
    clientId: `rule-${++sequence}`,
    tableId: firstTable.id,
    columnId: firstColumn.id,
    operator: 'eq',
    valueSource: sourceForColumn(firstTable.id, firstColumn.id)
  });
}

function removeRule(clientId: string) {
  policy.rules = policy.rules.filter(rule => rule.clientId !== clientId);
}

function changeTable(rule: EditableRule) {
  rule.columnId = supportedColumn(rule.tableId)?.id || '';
  if (rule.columnId) rule.valueSource = sourceForColumn(rule.tableId, rule.columnId);
}

function changeColumn(rule: EditableRule) {
  rule.valueSource = sourceForColumn(rule.tableId, rule.columnId);
}

function payloadRules(): DatasetRowPolicyRule[] {
  return policy.rules.map(rule => ({
    tableId: rule.tableId,
    columnId: rule.columnId,
    operator: rule.operator,
    valueSource: rule.valueSource
  }));
}

function serializePolicy() {
  return JSON.stringify({ revisionNo: policy.revisionNo, enabled: policy.enabled, rules: payloadRules() });
}

function validate() {
  if (policy.enabled && policy.rules.length === 0) {
    error.value = '启用行策略前至少添加一条规则';
    return false;
  }
  const seen = new Set<string>();
  for (const [index, rule] of policy.rules.entries()) {
    const sourceTable = table(rule.tableId);
    const column = sourceTable?.columns.find(item => String(item.id) === String(rule.columnId));
    if (!sourceTable || !column) {
      error.value = `第 ${index + 1} 条规则引用的表或字段已失效`;
      return false;
    }
    const type = baseType(column.dataType);
    if (rule.valueSource === 'principal_id' && !numericTypes.has(type)) {
      error.value = `第 ${index + 1} 条规则的当前用户 ID 只能绑定数值字段`;
      return false;
    }
    if (rule.valueSource === 'principal_username' && !textTypes.has(type)) {
      error.value = `第 ${index + 1} 条规则的当前用户名只能绑定文本字段`;
      return false;
    }
    const key = `${rule.tableId}:${rule.columnId}`;
    if (seen.has(key)) {
      error.value = `第 ${index + 1} 条规则与已有规则重复`;
      return false;
    }
    seen.add(key);
  }
  return true;
}

async function save() {
  error.value = '';
  if (!validate()) return;
  saving.value = true;
  try {
    const result = await updateDatasetRowPolicy(props.datasetId, {
      revisionNo: policy.revisionNo,
      enabled: policy.enabled,
      rules: payloadRules()
    });
    const failure = requestError(result, '行策略保存失败');
    if (failure) {
      error.value = failure;
      if ((result as { error?: { response?: { status?: number } } }).error?.response?.status === 409) await load();
      return;
    }
    if (!result.data) {
      error.value = '行策略保存成功但服务端未返回最新版本';
      return;
    }
    applyPolicy(result.data);
    window.$message?.success('行策略已保存');
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '行策略保存失败';
  } finally {
    saving.value = false;
  }
}

watch(() => props.datasetId, () => void load(), { immediate: true });
</script>

<template>
  <section class="row-policy-section">
    <div class="policy-header">
      <div><h3>数据集行策略</h3><p>规则在服务端查询边界执行；当前页面只提交结构化策略，不拼接 SQL。</p></div>
      <div class="policy-actions">
        <NTag size="small" :type="dirty ? 'warning' : 'success'" :bordered="false">{{ dirty ? '有未保存修改' : `版本 ${policy.revisionNo}` }}</NTag>
        <NSwitch v-model:value="policy.enabled" :disabled="loading || saving"><template #checked>已启用</template><template #unchecked>已停用</template></NSwitch>
      </div>
    </div>

    <NAlert v-if="error" type="error" :bordered="false">{{ error }}</NAlert>

    <div class="rule-toolbar"><span>{{ policy.rules.length }} 条规则</span><NButton type="primary" secondary :disabled="loading" @click="addRule"><template #icon><SvgIcon icon="lucide:plus" /></template>添加规则</NButton></div>
    <div v-if="policy.rules.length" class="rule-list">
      <article v-for="(rule, index) in policy.rules" :key="rule.clientId">
        <div class="rule-index">{{ index + 1 }}</div>
        <div class="rule-grid">
          <NFormItem label="数据表"><NSelect v-model:value="rule.tableId" :options="tableOptions" filterable @update:value="changeTable(rule)" /></NFormItem>
          <NFormItem label="字段"><NSelect v-model:value="rule.columnId" :options="columnOptions(rule)" filterable @update:value="changeColumn(rule)" /></NFormItem>
          <NFormItem label="运算符"><NSelect v-model:value="rule.operator" :options="operatorOptions" /></NFormItem>
          <NFormItem label="取值来源"><NSelect v-model:value="rule.valueSource" :options="valueSourceOptions(rule)" /></NFormItem>
        </div>
        <NButton quaternary type="error" title="删除规则" @click="removeRule(rule.clientId)"><template #icon><SvgIcon icon="lucide:trash-2" /></template></NButton>
      </article>
    </div>
    <NEmpty v-else description="尚未配置行策略规则" />

    <div class="policy-preview">
      <div class="preview-heading"><div><h3>规则预览</h3><p>预览仅描述策略含义，最终执行与类型校验由服务端完成。</p></div><NTag size="small" :type="policy.enabled ? 'success' : 'default'" :bordered="false">{{ policy.enabled ? '启用后生效' : '当前不生效' }}</NTag></div>
      <ol v-if="preview.length"><li v-for="item in preview" :key="item.id"><strong>{{ item.field }}</strong><span>{{ item.operator }}</span><code>{{ item.source }}</code></li></ol><NEmpty v-else description="添加规则后可查看策略预览" />
    </div>

    <div class="policy-footer"><NButton secondary :loading="loading" @click="load"><template #icon><SvgIcon icon="lucide:refresh-cw" /></template>重新加载</NButton><NButton type="primary" :loading="saving" :disabled="!dirty" data-testid="row-policy-save" @click="save"><template #icon><SvgIcon icon="lucide:save" /></template>保存行策略</NButton></div>
  </section>
</template>

<style scoped lang="scss">
.row-policy-section { display: grid; gap: 13px; }
.policy-header, .policy-actions, .rule-toolbar, .preview-heading, .policy-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.policy-header h3, .preview-heading h3 { margin: 0 0 4px; font-size: 14px; }
.policy-header p, .preview-heading p { margin: 0; color: var(--n-text-color-3); font-size: 11px; }
.policy-actions { justify-content: flex-end; }
.rule-toolbar { padding-top: 3px; color: var(--n-text-color-3); font-size: 12px; }
.rule-list { display: grid; gap: 8px; }
.rule-list article { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: start; gap: 10px; padding: 12px; border: 1px solid var(--n-border-color); border-radius: 7px; background: var(--n-color); }
.rule-index { display: grid; width: 24px; height: 24px; place-items: center; color: var(--n-primary-color); border-radius: 5px; background: var(--n-action-color); font-size: 11px; font-weight: 700; }
.rule-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 0 8px; }
.value-field { grid-column: 1 / -1; }
.policy-preview { padding: 13px; border: 1px solid var(--n-border-color); border-radius: 7px; background: var(--n-action-color); }
.policy-preview ol { display: grid; gap: 6px; margin: 10px 0 0; padding: 0; list-style: none; }
.policy-preview li { display: grid; grid-template-columns: minmax(150px, 1fr) auto minmax(100px, .7fr); align-items: center; gap: 8px; padding: 8px; border-radius: 5px; background: var(--n-color); font-size: 11px; }
.policy-preview li span { color: var(--n-text-color-3); }
.policy-preview li code { overflow: hidden; color: var(--n-primary-color); text-overflow: ellipsis; white-space: nowrap; }
.policy-footer { justify-content: flex-end; padding-top: 2px; }
@media (max-width: 900px) { .rule-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 640px) {
  .policy-header, .preview-heading { align-items: flex-start; flex-direction: column; }
  .rule-list article, .rule-grid { grid-template-columns: 1fr; }
  .value-field { grid-column: auto; }
  .policy-preview li { grid-template-columns: 1fr; }
}
</style>
