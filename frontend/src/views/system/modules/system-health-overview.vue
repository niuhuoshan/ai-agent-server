<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { NAlert, NButton, NCard, NDescriptions, NDescriptionsItem, NEmpty, NGrid, NGi, NResult, NSpace, NSpin, NTag } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { fetchSystemDiagnostics, fetchSystemHealthOverview } from '@/service/api';
import type { SystemDiagnostics, SystemHealthOverview } from '@/service/api';
import {
  componentDetailEntries,
  diagnosticMetricEntries,
  runtimeMetricEntries,
  systemHealthComponentName,
  systemHealthStatusText,
  systemHealthStatusType
} from '../system-health';

const overview = ref<SystemHealthOverview | null>(null);
const loading = ref(false);
const loadError = ref('');
const diagnostics = ref<SystemDiagnostics | null>(null);
const diagnosticLoading = ref(false);
const diagnosticError = ref('');

const checkedAtText = computed(() => {
  if (!overview.value?.checkedAt) return '-';
  const date = new Date(overview.value.checkedAt);
  return Number.isNaN(date.getTime()) ? overview.value.checkedAt : date.toLocaleString('zh-CN');
});

async function load() {
  loading.value = true;
  loadError.value = '';
  const result = await fetchSystemHealthOverview();
  if (result.error) {
    loadError.value = result.error instanceof Error ? result.error.message : '运行健康数据加载失败';
    overview.value = null;
  } else {
    overview.value = result.data;
  }
  loading.value = false;
}

async function runDiagnostics() {
  diagnosticLoading.value = true;
  diagnosticError.value = '';
  const result = await fetchSystemDiagnostics();
  if (result.error) {
    diagnosticError.value = result.error instanceof Error ? result.error.message : '深度诊断执行失败';
    diagnostics.value = null;
  } else {
    diagnostics.value = result.data;
  }
  diagnosticLoading.value = false;
}

onMounted(load);
</script>

<template>
  <section class="health-pane">
    <div class="health-toolbar">
      <div>
        <h3>系统运行健康</h3>
        <p>采集当前私有化实例的应用、数据库、Redis 与 Agent 运行时状态。</p>
      </div>
      <NSpace>
        <NButton secondary :loading="diagnosticLoading" @click="runDiagnostics">
          <template #icon><SvgIcon icon="lucide:stethoscope" /></template>
          深度诊断
        </NButton>
        <NButton secondary :loading="loading" @click="load">
          <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
          刷新检查
        </NButton>
      </NSpace>
    </div>

    <NResult v-if="loadError" status="error" title="运行健康检查失败" :description="loadError">
      <template #footer><NButton type="primary" @click="load">重新检查</NButton></template>
    </NResult>
    <NSpin v-else :show="loading">
      <NEmpty v-if="!overview && !loading" description="暂无运行健康数据" />
      <template v-else-if="overview">
        <NAlert :type="systemHealthStatusType(overview.status)" :bordered="false" class="health-summary">
          <template #header>
            <NSpace align="center"><span>当前系统状态</span><NTag :type="systemHealthStatusType(overview.status)" size="small">{{ systemHealthStatusText(overview.status) }}</NTag></NSpace>
          </template>
          <span>{{ overview.applicationName }}<template v-if="overview.applicationVersion"> · {{ overview.applicationVersion }}</template> · 最近检查 {{ checkedAtText }}</span>
        </NAlert>

        <NGrid :cols="4" :x-gap="12" :y-gap="12" responsive="screen" item-responsive class="health-components">
          <NGi v-for="component in overview.components" :key="component.key" span="24 s:24 m:12 l:6">
            <NCard size="small" :bordered="true" class="health-component">
              <template #header><span>{{ systemHealthComponentName(component) }}</span></template>
              <template #header-extra><NTag size="small" :type="systemHealthStatusType(component.status)">{{ systemHealthStatusText(component.status) }}</NTag></template>
              <p class="component-message">{{ component.message }}</p>
              <NDescriptions :column="1" size="small" label-placement="left">
                <NDescriptionsItem label="响应时间">{{ component.responseTimeMs }} ms</NDescriptionsItem>
                <NDescriptionsItem v-for="entry in componentDetailEntries(component)" :key="entry.key" :label="entry.label">{{ entry.value }}</NDescriptionsItem>
              </NDescriptions>
            </NCard>
          </NGi>
        </NGrid>

        <NEmpty v-if="overview.components.length === 0" description="当前没有可展示的组件检查结果" />
        <NCard size="small" :bordered="true" class="runtime-card" title="JVM 运行指标">
          <NDescriptions :column="3" label-placement="top" bordered>
            <NDescriptionsItem v-for="entry in runtimeMetricEntries(overview.runtime)" :key="entry.key" :label="entry.label">{{ entry.value }}</NDescriptionsItem>
          </NDescriptions>
        </NCard>

        <NResult v-if="diagnosticError" status="error" title="深度诊断失败" :description="diagnosticError">
          <template #footer><NButton type="primary" @click="runDiagnostics">重新诊断</NButton></template>
        </NResult>
        <NCard v-else-if="diagnostics" size="small" :bordered="true" class="diagnostic-card">
          <template #header>
            <NSpace align="center">
              <span>深度诊断</span>
              <NTag size="small" :type="systemHealthStatusType(diagnostics.status)">{{ systemHealthStatusText(diagnostics.status) }}</NTag>
            </NSpace>
          </template>
          <NGrid :cols="2" :x-gap="12" :y-gap="12" responsive="screen" item-responsive>
            <NGi v-for="check in diagnostics.checks" :key="check.key" span="24 s:24 m:12">
              <div class="diagnostic-check">
                <div class="diagnostic-heading">
                  <NSpace align="center">
                    <strong>{{ check.name }}</strong>
                    <NTag v-if="check.required" size="small" type="error">就绪必选</NTag>
                  </NSpace>
                  <NTag size="small" :type="systemHealthStatusType(check.status)">{{ systemHealthStatusText(check.status) }}</NTag>
                </div>
                <p>{{ check.message }}</p>
                <NDescriptions v-if="diagnosticMetricEntries(check).length" :column="2" size="small" label-placement="top">
                  <NDescriptionsItem v-for="metric in diagnosticMetricEntries(check)" :key="metric.key" :label="metric.label">{{ metric.value }}</NDescriptionsItem>
                </NDescriptions>
                <NAlert v-if="check.remediation && check.status !== 'healthy'" type="warning" :bordered="false">
                  {{ check.remediation }}
                </NAlert>
              </div>
            </NGi>
          </NGrid>
        </NCard>
      </template>
    </NSpin>
  </section>
</template>

<style scoped lang="scss">
.health-pane { padding-top: 8px; }
.health-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.health-toolbar h3 { margin: 0 0 4px; font-size: 16px; }
.health-toolbar p { margin: 0; color: var(--n-text-color-3); font-size: 13px; }
.health-summary { margin-bottom: 16px; }
.health-components { margin-bottom: 16px; }
.health-component { min-height: 174px; }
.component-message { min-height: 36px; margin: 0 0 12px; color: var(--n-text-color-2); font-size: 13px; }
.runtime-card { margin-top: 16px; }
.diagnostic-card { margin-top: 16px; }
.diagnostic-check { height: 100%; padding: 12px; border: 1px solid var(--n-border-color); border-radius: 6px; }
.diagnostic-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.diagnostic-check p { margin: 8px 0 12px; color: var(--n-text-color-2); font-size: 13px; }
@media (max-width: 640px) { .health-toolbar { align-items: stretch; flex-direction: column; } }
</style>
