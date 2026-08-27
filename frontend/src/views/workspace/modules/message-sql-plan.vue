<script setup lang="ts">
import { computed } from "vue";

const props = defineProps<{
  plan: Record<string, unknown>;
}>();

const LABELS: Record<string, string> = {
  goal: "查询目标",
  tables: "数据表",
  fields: "字段",
  columns: "字段",
  metrics: "指标",
  filters: "筛选条件",
  time_range: "时间范围",
  group_by: "分组",
  order_by: "排序",
  risk_notes: "风险提示",
};

const entries = computed(() => Object.entries(props.plan).map(([key, value]) => ({
  key,
  label: LABELS[key] || key,
  value: formatValue(value),
})));

function formatValue(value: unknown) {
  if (value === null || value === undefined || value === "") return "-";
  if (typeof value === "string") return value;
  return JSON.stringify(value, null, 2);
}
</script>

<template>
  <NCollapse class="sql-plan-card">
    <NCollapseItem name="sql-plan" title="SQL 执行计划">
      <dl>
        <div v-for="entry in entries" :key="entry.key">
          <dt>{{ entry.label }}</dt>
          <dd>{{ entry.value }}</dd>
        </div>
      </dl>
    </NCollapseItem>
  </NCollapse>
</template>

<style scoped>
.sql-plan-card {
  padding: 0 12px;
  border: 1px solid var(--n-border-color);
  border-radius: 6px;
  background: rgba(32, 128, 240, 0.04);
}

dl {
  display: grid;
  margin: 0;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 18px;
}

dl > div {
  min-width: 0;
}

dt {
  margin-bottom: 3px;
  color: var(--n-text-color-3);
  font-size: 12px;
}

dd {
  margin: 0;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

@media (max-width: 640px) {
  dl {
    grid-template-columns: 1fr;
  }
}
</style>
