<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue';
import type { DataTableColumns } from 'naive-ui';
import { NAlert, NButton, NCard, NDataTable, NEmpty, NInput, NModal, NSpace, NSpin, NTag } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  fetchPortalPromptDetail,
  fetchPortalPromptHistory,
  fetchPortalPrompts,
  optimizePortalPrompt,
  restorePortalPrompt,
  savePortalPrompt,
  testPortalPrompt,
  type PortalPromptHistory,
  type PortalPromptMetadata,
  type PortalPromptOptimizeResult,
  type PortalPromptTestResult
} from '@/service/api';
import {
  defaultPromptVariables,
  mergePromptVariables,
  missingPromptVariables,
  parsePromptVariables,
  promptRequestError
} from './prompt-studio-state';

const loading = ref(false);
const saving = ref(false);
const prompts = ref<PortalPromptMetadata[]>([]);
const selected = ref<PortalPromptMetadata | null>(null);
const content = ref('');
const originalContent = ref('');
const variables = ref<string[]>([]);
const history = ref<PortalPromptHistory[]>([]);
const historyVisible = ref(false);
const restoreLoading = ref(false);
const testVisible = ref(false);
const testInput = ref('');
const testVariables = ref('{}');
const testLoading = ref(false);
const testResult = ref<PortalPromptTestResult | null>(null);
const testError = ref('');
const optimizeLoading = ref(false);
const optimizeVisible = ref(false);
const optimizeOriginal = ref('');
const optimizeResult = ref<PortalPromptOptimizeResult | null>(null);
const optimizeError = ref('');
const search = ref('');
const testVariableMissing = computed(() => missingPromptVariables(variables.value, testVariables.value));
const testVariableJsonError = computed(() => {
  try {
    parsePromptVariables(testVariables.value);
    return '';
  } catch (error) {
    return error instanceof Error ? error.message : '变量必须是有效的 JSON 对象';
  }
});

const filteredPrompts = computed(() => {
  const keyword = search.value.trim().toLowerCase();
  if (!keyword) return prompts.value;
  return prompts.value.filter(item => `${item.display_name} ${item.name} ${item.description}`.toLowerCase().includes(keyword));
});
const dirty = computed(() => content.value !== originalContent.value);

function variableLabel(variable: string) {
  return `{${variable}}`;
}

const columns: DataTableColumns<PortalPromptMetadata> = [
  {
    title: '提示词', key: 'display_name', minWidth: 220,
    render: row => h('div', { class: 'min-w-0' }, [
      h('div', { class: 'font-500 truncate' }, row.display_name || row.name),
      h('div', { class: 'text-12px op-60 truncate' }, row.name)
    ])
  },
  { title: '来源', key: 'category', width: 110 },
  {
    title: '当前版本', key: 'versions', width: 120,
    render: row => row.versions?.[0] ? h(NTag, { size: 'small', bordered: false }, () => `v${row.versions[0].version_number}`) : '-'
  },
  {
    title: '操作', key: 'actions', width: 100,
    render: row => h(NButton, { size: 'small', type: selected.value?.id === row.id ? 'primary' : 'default', secondary: selected.value?.id !== row.id, onClick: () => selectPrompt(row) }, () => '编辑')
  }
];

async function load() {
  loading.value = true;
  const result = await fetchPortalPrompts();
  if (!result.error) {
    prompts.value = result.data;
    if (!selected.value || !prompts.value.some(item => item.id === selected.value?.id)) {
      selected.value = prompts.value[0] || null;
    }
    if (selected.value) await loadDetail(selected.value);
  }
  loading.value = false;
}

async function loadDetail(prompt: PortalPromptMetadata, version?: number) {
  selected.value = prompt;
  const [detailResult, historyResult] = await Promise.all([
    fetchPortalPromptDetail(prompt.source, prompt.id, version),
    fetchPortalPromptHistory(prompt.source, prompt.id)
  ]);
  if (!detailResult.error) {
    content.value = detailResult.data.content || '';
    originalContent.value = content.value;
    variables.value = detailResult.data.variables || [];
    testVariables.value = mergePromptVariables(variables.value, testVariables.value);
  }
  if (!historyResult.error) history.value = historyResult.data;
}

function selectPrompt(prompt: PortalPromptMetadata) {
  if (selected.value?.id === prompt.id) return;
  if (dirty.value && !window.confirm('当前提示词有未保存修改，确认切换吗？')) return;
  void loadDetail(prompt);
}

async function save() {
  if (!selected.value || !content.value.trim() || saving.value) return;
  saving.value = true;
  const result = await savePortalPrompt({ source: selected.value.source, targetId: selected.value.id, content: content.value });
  if (!result.error) {
    originalContent.value = content.value;
    window.$message?.success(result.data.status === 'unchanged' ? '提示词没有变化' : `提示词已保存${result.data.version_number ? `，版本 v${result.data.version_number}` : ''}`);
    await load();
  }
  saving.value = false;
}

async function optimize() {
  if (!content.value.trim() || optimizeLoading.value) return;
  optimizeOriginal.value = content.value;
  optimizeResult.value = null;
  optimizeError.value = '';
  optimizeVisible.value = true;
  optimizeLoading.value = true;
  try {
    const result = await optimizePortalPrompt(optimizeOriginal.value);
    if (result.error) {
      optimizeError.value = promptRequestError(result.error, '提示词优化失败');
      return;
    }
    optimizeResult.value = result.data;
  } catch (error) {
    optimizeError.value = promptRequestError(error, '提示词优化失败');
  } finally {
    optimizeLoading.value = false;
  }
}

async function test() {
  testResult.value = null;
  testError.value = '';
  let variableValues: Record<string, unknown>;
  try {
    variableValues = parsePromptVariables(testVariables.value);
  } catch (error) {
    testError.value = error instanceof Error ? error.message : '变量必须是 JSON 对象';
    return;
  }
  testLoading.value = true;
  try {
    const result = await testPortalPrompt({
      content: content.value,
      variables: variableValues,
      userInput: testInput.value
    });
    if (result.error) {
      testError.value = promptRequestError(result.error, '提示词测试失败');
      return;
    }
    testResult.value = result.data;
  } catch (error) {
    testError.value = promptRequestError(error, '提示词测试失败');
  } finally {
    testLoading.value = false;
  }
}

function openTest() {
  testResult.value = null;
  testError.value = '';
  if (variables.value.length > 0) {
    testVariables.value = testVariables.value.trim() === '{}'
      ? defaultPromptVariables(variables.value)
      : mergePromptVariables(variables.value, testVariables.value);
  }
  testVisible.value = true;
}

function applyOptimization() {
  const suggestion = optimizeResult.value?.optimized_content;
  if (!suggestion) return;
  if (
    content.value !== optimizeOriginal.value &&
    !window.confirm('编辑器内容已在优化期间发生变化，确认用优化建议覆盖吗？')
  ) {
    return;
  }
  content.value = suggestion;
  optimizeVisible.value = false;
  window.$message?.success('优化建议已应用，保存后生效');
}

function openHistory() {
  historyVisible.value = true;
}

function chooseHistory(row: PortalPromptHistory) {
  if (dirty.value && !window.confirm('当前提示词有未保存修改，确认切换版本吗？')) return;
  if (selected.value) void loadDetail(selected.value, row.version_number);
  historyVisible.value = false;
}

async function restoreHistory(row: PortalPromptHistory) {
  if (!selected.value || restoreLoading.value) return;
  if (dirty.value && !window.confirm('当前提示词有未保存修改，恢复版本会创建新的草稿，确认继续吗？')) return;
  if (!window.confirm(`确认将 v${row.version_number} 恢复为新的草稿吗？`)) return;
  restoreLoading.value = true;
  try {
    const result = await restorePortalPrompt({
      source: selected.value.source,
      targetId: selected.value.id,
      versionNumber: row.version_number
    });
    if (result.error) {
      window.$message?.error(promptRequestError(result.error, '提示词版本恢复失败'));
      return;
    }
    historyVisible.value = false;
    await load();
    window.$message?.success(`已从 v${row.version_number} 恢复为草稿 v${result.data.version_number}`);
  } catch (error) {
    window.$message?.error(promptRequestError(error, '提示词版本恢复失败'));
  } finally {
    restoreLoading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="h-full flex-col-stretch gap-12px overflow-hidden p-16px">
    <NCard :bordered="false" size="small" class="shrink-0">
      <template #header>
        <div class="flex items-center gap-8px"><SvgIcon icon="lucide:pen-line" /><span>提示词工坊</span></div>
      </template>
      <template #header-extra>
        <NSpace>
          <NInput v-model:value="search" clearable placeholder="搜索 Agent 提示词" style="width: 220px" />
          <NButton secondary :loading="loading" @click="load"><template #icon><SvgIcon icon="lucide:refresh-cw" /></template>刷新</NButton>
          <NButton secondary @click="$router.push('/slash-commands')"><template #icon><SvgIcon icon="lucide:command" /></template>Slash 命令</NButton>
        </NSpace>
      </template>
    </NCard>

    <NGrid :x-gap="16" :y-gap="16" responsive="screen" item-responsive class="min-h-0 flex-1">
      <NGi span="24 m:9" class="min-h-0">
        <NCard :bordered="false" title="可编辑提示词" class="h-full" content-class="h-full overflow-hidden">
          <NDataTable :columns="columns" :data="filteredPrompts" :loading="loading" :single-line="false" flex-height class="h-full" />
        </NCard>
      </NGi>
      <NGi span="24 m:15" class="min-h-0">
        <NCard v-if="selected" :bordered="false" class="h-full" content-class="h-full flex-col-stretch">
          <template #header>
            <div class="min-w-0"><div class="font-600 truncate">{{ selected.display_name }}</div><div class="text-12px op-60 truncate">{{ selected.description || selected.name }}</div></div>
          </template>
          <template #header-extra><NTag size="small" :bordered="false">{{ selected.source }}</NTag></template>
          <div class="min-h-0 flex-1 flex-col-stretch gap-10px">
            <div class="flex flex-wrap items-center justify-between gap-8px">
              <NSpace size="small">
                <NTag v-for="variable in variables" :key="variable" size="small" :bordered="false">{{ variableLabel(variable) }}</NTag>
                <span class="text-12px op-60">{{ dirty ? '有未保存修改' : '已同步到当前草稿' }}</span>
              </NSpace>
              <NSpace size="small">
                <NButton size="small" secondary @click="openHistory"><template #icon><SvgIcon icon="lucide:history" /></template>版本历史</NButton>
                <NButton size="small" secondary :loading="optimizeLoading" @click="optimize"><template #icon><SvgIcon icon="lucide:sparkles" /></template>AI 优化</NButton>
                <NButton size="small" secondary @click="openTest"><template #icon><SvgIcon icon="lucide:flask-conical" /></template>测试</NButton>
                <NButton size="small" type="primary" :loading="saving" :disabled="!dirty" @click="save"><template #icon><SvgIcon icon="lucide:save" /></template>保存</NButton>
              </NSpace>
            </div>
            <NInput v-model:value="content" type="textarea" class="min-h-0 flex-1" :autosize="false" placeholder="输入系统提示词，使用 {variable} 声明变量" />
            <div class="text-12px op-60">已声明变量会在会话运行前由 Agent 上下文填充；测试和 AI 优化需要配置可用模型提供方。</div>
          </div>
        </NCard>
        <NCard v-else :bordered="false" class="h-full"><NEmpty description="暂无可编辑的 Agent 提示词" /></NCard>
      </NGi>
    </NGrid>

    <NModal v-model:show="historyVisible" preset="card" title="提示词版本历史" style="width: min(760px, 94vw)">
      <NDataTable
        :data="history"
        :columns="[
          { title: '版本', key: 'version_number', width: 90, render: (row: PortalPromptHistory) => `v${row.version_number}` },
          { title: '状态', key: 'status', width: 100 },
          { title: '时间', key: 'created_at', width: 180 },
          {
            title: '操作', key: 'action', width: 170,
            render: (row: PortalPromptHistory) => h(NSpace, { size: 'small' }, () => [
              h(NButton, { size: 'small', onClick: () => chooseHistory(row) }, () => '查看'),
              h(NButton, { size: 'small', type: 'primary', secondary: true, loading: restoreLoading, onClick: () => restoreHistory(row) }, () => '恢复为草稿')
            ])
          }
        ]"
        :single-line="false"
      />
    </NModal>

    <NModal v-model:show="testVisible" preset="card" title="提示词测试" style="width: min(900px, 94vw)">
      <NSpace vertical size="large">
        <NInput v-model:value="testInput" type="textarea" placeholder="用户输入（可选）" :autosize="{ minRows: 3, maxRows: 8 }" />
        <NInput v-model:value="testVariables" type="textarea" placeholder="变量 JSON，例如 {&quot;department&quot;:&quot;财务&quot;}" :autosize="{ minRows: 2, maxRows: 5 }" />
        <NAlert v-if="testVariableJsonError" type="error" title="变量格式错误" :show-icon="true">
          {{ testVariableJsonError }}
        </NAlert>
        <NAlert v-else-if="testVariableMissing.length" type="warning" title="仍有变量未填写" :show-icon="true">
          {{ testVariableMissing.map(variableLabel).join('、') }}
        </NAlert>
        <div v-if="variables.length" class="test-variable-summary">
          <NTag v-for="variable in variables" :key="variable" size="small" :type="testVariableMissing.includes(variable) ? 'warning' : 'success'" :bordered="false">
            {{ variableLabel(variable) }}
          </NTag>
        </div>
        <NAlert v-if="testError" type="error" title="测试失败" :show-icon="true">
          {{ testError }}
        </NAlert>
        <div v-if="testResult" class="flex-col-stretch gap-12px" aria-live="polite">
          <div class="flex flex-wrap items-center gap-x-12px gap-y-6px text-13px">
            <NTag type="success" size="small" :bordered="false">已完成</NTag>
            <span class="font-500">{{ testResult.model_name || testResult.provider_model }}</span>
            <span class="op-65">{{ testResult.provider }} / {{ testResult.provider_model }}</span>
            <span class="op-65">{{ testResult.elapsed_ms }} ms</span>
          </div>
          <section class="prompt-result-panel">
            <div class="prompt-result-label">模型输出</div>
            <pre>{{ testResult.output }}</pre>
          </section>
          <section class="prompt-result-panel">
            <div class="prompt-result-label">实际执行提示词</div>
            <pre>{{ testResult.rendered_prompt }}</pre>
          </section>
        </div>
        <NSpace justify="end">
          <NButton :disabled="testLoading" @click="testVisible = false">关闭</NButton>
          <NButton type="primary" :loading="testLoading" :disabled="Boolean(testVariableJsonError) || testVariableMissing.length > 0" @click="test">执行测试</NButton>
        </NSpace>
      </NSpace>
    </NModal>

    <NModal v-model:show="optimizeVisible" preset="card" title="AI 优化建议" style="width: min(1080px, 96vw)">
      <NAlert v-if="optimizeError" type="error" title="优化失败" :show-icon="true" class="mb-12px">
        {{ optimizeError }}
      </NAlert>
      <NGrid :x-gap="12" :y-gap="12" responsive="screen" item-responsive>
        <NGi span="24 m:12">
          <section class="prompt-result-panel h-full">
            <div class="prompt-result-label">原始提示词</div>
            <pre>{{ optimizeOriginal }}</pre>
          </section>
        </NGi>
        <NGi span="24 m:12">
          <section class="prompt-result-panel h-full">
            <div class="prompt-result-label flex items-center justify-between gap-8px">
              <span>优化建议</span>
              <span v-if="optimizeResult" class="font-400 op-65">
                {{ optimizeResult.model_name || optimizeResult.provider_model }} · {{ optimizeResult.elapsed_ms }} ms
              </span>
            </div>
            <div v-if="optimizeLoading" class="min-h-120px flex items-center justify-center gap-8px op-70">
              <NSpin size="small" />
              <span>正在生成优化建议</span>
            </div>
            <pre v-else-if="optimizeResult">{{ optimizeResult.optimized_content }}</pre>
            <div v-else-if="!optimizeError" class="min-h-120px" />
          </section>
        </NGi>
      </NGrid>
      <NSpace justify="end" class="mt-16px">
        <NButton :disabled="optimizeLoading" @click="optimizeVisible = false">关闭</NButton>
        <NButton secondary :loading="optimizeLoading" @click="optimize">重新生成</NButton>
        <NButton type="primary" :disabled="!optimizeResult || optimizeLoading" @click="applyOptimization">
          应用到编辑器
        </NButton>
      </NSpace>
    </NModal>
  </div>
</template>

<style scoped>
.prompt-result-panel {
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--n-border-color, rgba(128, 128, 128, 0.24));
  border-radius: 6px;
}

.prompt-result-label {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
}

.prompt-result-panel pre {
  max-height: 320px;
  margin: 0;
  overflow: auto;
  font-family: var(--n-font-family-mono, ui-monospace, SFMono-Regular, Consolas, monospace);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.test-variable-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
