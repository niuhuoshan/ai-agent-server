<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { NButton, NCard, NEmpty, NForm, NFormItem, NGrid, NGridItem, NInput, NModal, NSelect, NSpace, NSpin, NStep, NSteps, NSwitch, NTag, useDialog } from 'naive-ui';
import { useRoute, useRouter } from 'vue-router';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  getScenarioTemplate,
  getScenarioTemplateInstance,
  getScenarioTemplateInstances,
  getScenarioTemplateResourceOptions,
  getScenarioTemplates,
  installScenarioTemplate,
  precheckScenarioTemplate,
  uninstallScenarioTemplateInstance
} from '@/service/api';
import type { ScenarioPrecheck, ScenarioResourceOption, ScenarioTemplateDetail, ScenarioTemplateInstance, ScenarioTemplateSummary } from '@/service/api';

const route = useRoute();
const router = useRouter();
const dialog = useDialog();
const loading = ref(false);
const templates = ref<ScenarioTemplateSummary[]>([]);
const instances = ref<ScenarioTemplateInstance[]>([]);
const selected = ref<ScenarioTemplateDetail | null>(null);
const options = ref<Record<string, ScenarioResourceOption[]>>({});
const detailVisible = ref(false);
const instanceVisible = ref(false);
const instanceLoading = ref(false);
const uninstallLoading = ref(false);
const activeInstance = ref<ScenarioTemplateInstance | null>(null);
const installVisible = ref(false);
const installLoading = ref(false);
const prechecking = ref(false);
const step = ref(1);
const precheck = ref<ScenarioPrecheck | null>(null);
const result = ref<Record<string, unknown> | null>(null);
const form = reactive({
  instanceKey: '',
  displayName: '',
  description: '',
  publish: true,
  idempotencyKey: '',
  bindings: {} as Record<string, string[]>
});

const selectedSummary = computed(() => selected.value?.summary || null);
const bindingEntries = computed(() => Object.entries(options.value));

async function load() {
  loading.value = true;
  const [templateResult, instanceResult] = await Promise.all([getScenarioTemplates(), getScenarioTemplateInstances()]);
  if (!templateResult.error) templates.value = templateResult.data;
  if (!instanceResult.error) instances.value = instanceResult.data;
  loading.value = false;
  await openTemplateFromRoute();
}

async function openTemplate(template: ScenarioTemplateSummary) {
  const detailResult = await getScenarioTemplate(template.id);
  if (detailResult.error) return;
  selected.value = detailResult.data;
  detailVisible.value = true;
}

async function startInstall(template: ScenarioTemplateSummary) {
  const [detailResult, optionsResult] = await Promise.all([getScenarioTemplate(template.id), getScenarioTemplateResourceOptions(template.id)]);
  if (detailResult.error || optionsResult.error) return;
  selected.value = detailResult.data;
  options.value = optionsResult.data.options;
  form.instanceKey = template.id;
  form.displayName = template.name;
  form.description = template.description;
  form.publish = true;
  form.idempotencyKey = createIdempotencyKey(template.id);
  form.bindings = Object.fromEntries(Object.keys(options.value).map(key => [key, []]));
  precheck.value = null;
  result.value = null;
  step.value = 1;
  detailVisible.value = false;
  installVisible.value = true;
}

function optionValues(values: ScenarioResourceOption[]) {
  return values.map(item => ({ label: item.label, value: item.id, description: item.description }));
}

function payload() {
  return {
    instanceKey: form.instanceKey || undefined,
    displayName: form.displayName || undefined,
    description: form.description || undefined,
    publish: form.publish,
    resourceBindings: form.bindings,
    idempotencyKey: form.idempotencyKey
  };
}

async function runPrecheck() {
  if (!selectedSummary.value) return;
  if (!form.instanceKey.trim() || !form.displayName.trim()) {
    window.$message?.warning('请填写实例标识和显示名称');
    return;
  }
  prechecking.value = true;
  const response = await precheckScenarioTemplate(selectedSummary.value.id, payload());
  if (!response.error) {
    precheck.value = response.data;
    step.value = 3;
  }
  prechecking.value = false;
}

async function install() {
  if (!selectedSummary.value || !precheck.value?.canInstall) return;
  installLoading.value = true;
  const response = await installScenarioTemplate(selectedSummary.value.id, payload());
  if (!response.error) {
    result.value = response.data as unknown as Record<string, unknown>;
    step.value = 4;
    window.$message?.success('场景模板已完成交付');
    await load();
  }
  installLoading.value = false;
}

function closeInstall() {
  if (installLoading.value) return;
  installVisible.value = false;
}

async function openInstance(instance: ScenarioTemplateInstance) {
  instanceVisible.value = true;
  instanceLoading.value = true;
  activeInstance.value = instance;
  const response = await getScenarioTemplateInstance(instance.id);
  if (!response.error) activeInstance.value = response.data;
  instanceLoading.value = false;
}

function recordText(record: Record<string, unknown>, key: string, fallback = '-') {
  const value = record[key];
  return value === null || value === undefined || value === '' ? fallback : String(value);
}

function resourceBindingText(resource: Record<string, unknown>) {
  const value = resource.value;
  if (Array.isArray(value)) return value.length ? value.map(String).join('、') : '未绑定';
  return value === null || value === undefined || value === '' ? '未绑定' : String(value);
}

function openInstanceAgent() {
  const agentId = activeInstance.value?.agent.id;
  void router.push({ path: '/agent-center', query: agentId ? { agent_id: String(agentId) } : undefined });
}

function openAcceptanceQuestion(question: string) {
  const versionId = activeInstance.value?.agent.version_id;
  void router.push({
    path: '/workspace',
    query: {
      question,
      ...(versionId ? { agent_version_id: String(versionId) } : {})
    }
  });
}

function statusType(status: string) {
  return status === 'installed' ? 'success' : status === 'failed' ? 'error' : 'warning';
}

function statusLabel(status: string) {
  const labels: Record<string, string> = {
    installed: '已交付',
    disabled: '已卸载',
    failed: '失败',
    installing: '交付中',
    prechecking: '预检中'
  };
  return labels[status] || status;
}

function confirmUninstall() {
  const instance = activeInstance.value;
  if (!instance || instance.status === 'disabled' || uninstallLoading.value) return;
  dialog.warning({
    title: '卸载场景实例',
    content: `将停用“${instance.templateName}”实例及其关联 Agent。实例、版本和运行记录会保留用于审计。`,
    positiveText: '确认卸载',
    negativeText: '取消',
    async onPositiveClick() {
      uninstallLoading.value = true;
      const response = await uninstallScenarioTemplateInstance(instance.id, {
        confirm: true,
        reason: '用户从场景模板页面卸载',
        idempotencyKey: createIdempotencyKey(`uninstall-${instance.id}`)
      });
      uninstallLoading.value = false;
      if (response.error) return false;
      instanceVisible.value = false;
      activeInstance.value = null;
      if (response.data.warning) window.$message?.warning(response.data.warning);
      else window.$message?.success('场景实例已卸载，关联 Agent 已停用');
      await load();
      return true;
    }
  });
}

function createIdempotencyKey(templateId: string) {
  const suffix = typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `scenario-${templateId}-${suffix}`;
}

function resourceLabel(type: string) {
  const labels: Record<string, string> = {
    metadata_dataset: '数据集',
    knowledge_base: '知识库',
    tool: '工具',
    skill: 'Skill',
    connector: '连接器',
    feedback: '反馈能力'
  };
  return labels[type] || type;
}

async function openTemplateFromRoute() {
  const raw = route.query.template_id;
  const templateId = Array.isArray(raw) ? raw[0] : raw;
  if (!templateId || selectedSummary.value?.id === templateId) return;
  const template = templates.value.find(item => item.id === templateId);
  if (!template) {
    window.$message?.warning('场景模板不存在或当前账号不可用');
    await router.replace({ query: { ...route.query, template_id: undefined } });
    return;
  }
  if (route.query.action === 'install') await startInstall(template);
  else await openTemplate(template);
}

onMounted(load);
watch(() => [route.query.template_id, route.query.action], () => void openTemplateFromRoute());
</script>

<template>
  <div class="page-container">
    <header class="scenario-hero">
      <div class="scenario-hero-copy">
        <div class="scenario-title-mark"><SvgIcon icon="lucide:package-check" /></div>
        <div>
          <div class="scenario-kicker">DELIVERY CATALOG</div>
          <h1>场景模板</h1>
          <p>把经过验证的业务能力，交付为可运行、可审计的企业 Agent。</p>
        </div>
      </div>
      <div class="scenario-hero-actions">
        <div class="scenario-counts" aria-label="场景模板统计">
          <div><strong>{{ templates.length }}</strong><span>可交付模板</span></div>
          <div><strong>{{ instances.length }}</strong><span>已交付实例</span></div>
        </div>
        <NButton secondary :loading="loading" @click="load">
          <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
          刷新目录
        </NButton>
      </div>
    </header>

    <div class="catalog-heading">
      <div>
        <h2>可交付场景</h2>
        <p>选择一个模板查看交付物、资源要求和验收问题。</p>
      </div>
    </div>

    <NSpin :show="loading">
      <NEmpty v-if="!templates.length" description="暂无可交付场景模板" />
      <NGrid v-else cols="1 s:2 l:3" responsive="screen" :x-gap="16" :y-gap="16">
        <NGridItem v-for="template in templates" :key="template.id">
          <NCard size="small" class="template-card">
            <template #header>
              <NSpace align="center"><strong>{{ template.name }}</strong><NTag v-if="template.recommended" size="small" type="success">推荐</NTag></NSpace>
            </template>
            <template #header-extra><NTag size="small">{{ template.category }}</NTag></template>
            <p class="muted">{{ template.description }}</p>
            <div class="tag-line"><NTag v-for="tag in template.tags" :key="tag" size="small" :bordered="false">{{ tag }}</NTag></div>
            <div class="template-meta">{{ template.requiredResources.filter(item => item.required).length }} 项必选资源 · {{ template.deliveryTime || '按预检结果' }}</div>
            <template #action><NSpace justify="end"><NButton size="small" secondary @click="openTemplate(template)">查看详情</NButton><NButton size="small" type="primary" @click="startInstall(template)">开始交付</NButton></NSpace></template>
          </NCard>
        </NGridItem>
      </NGrid>
    </NSpin>

    <section class="instance-section">
      <div class="section-heading"><div><h2>已交付实例</h2><p>实例由真实 Agent、版本、资源绑定和安装运行记录组成。</p></div></div>
      <NEmpty v-if="!instances.length" description="还没有交付实例" />
      <NGrid v-else cols="1 s:2 l:4" responsive="screen" :x-gap="12" :y-gap="12">
        <NGridItem v-for="instance in instances" :key="instance.id"><NCard size="small" hoverable @click="openInstance(instance)"><template #header>{{ instance.templateName }}</template><p class="muted">{{ instance.agent.name || instance.agent.id }}</p><NSpace><NTag size="small" :type="statusType(instance.status)">{{ statusLabel(instance.status) }}</NTag><span class="muted">{{ instance.sampleQuestions.length }} 条样例</span></NSpace></NCard></NGridItem>
      </NGrid>
    </section>

    <NModal v-model:show="detailVisible" preset="card" :title="selectedSummary?.name || '场景模板详情'" style="width: min(820px, calc(100vw - 32px))">
      <template v-if="selectedSummary">
        <p>{{ selectedSummary.description }}</p>
        <NSpace class="mb-12px"><NTag v-for="tag in selectedSummary.tags" :key="tag">{{ tag }}</NTag></NSpace>
        <NGrid :cols="2" :x-gap="18" responsive="screen">
          <NGridItem><h4>交付物</h4><ul><li v-for="item in selectedSummary.deliverables" :key="item">{{ item }}</li></ul></NGridItem>
          <NGridItem><h4>验收标准</h4><ul><li v-for="item in selectedSummary.acceptanceCriteria" :key="item">{{ item }}</li></ul></NGridItem>
        </NGrid>
        <h4>推荐问题</h4>
        <ul><li v-for="item in selectedSummary.sampleQuestions" :key="item">{{ item }}</li></ul>
      </template>
      <template #footer><NSpace justify="end"><NButton @click="detailVisible = false">关闭</NButton><NButton v-if="selectedSummary" type="primary" @click="startInstall(selectedSummary)">开始交付</NButton></NSpace></template>
    </NModal>

    <NModal v-model:show="instanceVisible" preset="card" :title="activeInstance?.templateName || '已交付实例'" style="width: min(860px, calc(100vw - 32px))">
      <NSpin :show="instanceLoading">
        <template v-if="activeInstance">
          <div class="instance-overview">
            <div><span>Agent</span><strong>{{ recordText(activeInstance.agent, 'name') }}</strong></div>
            <div><span>实例状态</span><NTag size="small" :type="statusType(activeInstance.status)">{{ statusLabel(activeInstance.status) }}</NTag></div>
            <div><span>最近运行</span><strong>{{ recordText(activeInstance.latestRun, 'status', '暂无运行') }}</strong></div>
            <div><span>交付人</span><strong>{{ activeInstance.owner || '-' }}</strong></div>
          </div>

          <section class="instance-detail-section">
            <h4>资源绑定</h4>
            <NEmpty v-if="!activeInstance.resourceSummary.length" description="未记录资源绑定" />
            <div v-else class="resource-list">
              <div v-for="(resource, index) in activeInstance.resourceSummary" :key="`${recordText(resource, 'type')}-${index}`" class="resource-row">
                <NTag size="small">{{ resourceLabel(recordText(resource, 'type')) }}</NTag>
                <span>{{ resourceBindingText(resource) }}</span>
              </div>
            </div>
          </section>

          <NGrid :cols="2" responsive="screen" :x-gap="24" class="instance-detail-section">
            <NGridItem>
              <h4>验收标准</h4>
              <NEmpty v-if="!activeInstance.acceptanceCriteria.length" description="暂无验收标准" />
              <ul v-else><li v-for="item in activeInstance.acceptanceCriteria" :key="item">{{ item }}</li></ul>
            </NGridItem>
            <NGridItem>
              <h4>下一步</h4>
              <NEmpty v-if="!activeInstance.nextSteps.length" description="暂无后续步骤" />
              <ul v-else><li v-for="item in activeInstance.nextSteps" :key="item">{{ item }}</li></ul>
            </NGridItem>
          </NGrid>

          <section class="instance-detail-section">
            <h4>样例问题</h4>
            <NEmpty v-if="!activeInstance.sampleQuestions.length" description="暂无样例问题" />
            <div v-else class="question-list">
              <button v-for="question in activeInstance.sampleQuestions" :key="question" type="button" @click="openAcceptanceQuestion(question)">
                <span>{{ question }}</span><SvgIcon icon="lucide:arrow-up-right" />
              </button>
            </div>
          </section>
        </template>
      </NSpin>
      <template #footer>
        <NSpace justify="space-between">
          <NButton v-if="activeInstance && activeInstance.status !== 'disabled'" type="error" secondary :loading="uninstallLoading" @click="confirmUninstall">
            <template #icon><SvgIcon icon="lucide:archive-x" /></template>卸载实例
          </NButton>
          <span v-else />
          <NSpace>
            <NButton @click="instanceVisible = false">关闭</NButton>
            <NButton type="primary" @click="openInstanceAgent"><template #icon><SvgIcon icon="lucide:bot" /></template>进入智能体中心</NButton>
          </NSpace>
        </NSpace>
      </template>
    </NModal>

    <NModal v-model:show="installVisible" preset="card" :title="`交付：${selectedSummary?.name || ''}`" :mask-closable="false" style="width: min(900px, calc(100vw - 32px))" @close="closeInstall">
      <NSteps :current="step" size="small"><NStep title="基础信息" /><NStep title="绑定资源" /><NStep title="预检" /><NStep title="完成" /></NSteps>
      <div v-if="step === 1" class="step-content"><NForm label-placement="top"><NFormItem label="实例标识"><NInput v-model:value="form.instanceKey" placeholder="只允许小写字母、数字、点、短横线和下划线" /></NFormItem><NFormItem label="显示名称"><NInput v-model:value="form.displayName" /></NFormItem><NFormItem label="交付说明"><NInput v-model:value="form.description" type="textarea" :autosize="{ minRows: 3, maxRows: 5 }" /></NFormItem></NForm><NSpace justify="end"><NButton @click="closeInstall">取消</NButton><NButton type="primary" @click="step = 2">下一步</NButton></NSpace></div>
      <div v-else-if="step === 2" class="step-content"><NForm label-placement="top"><NFormItem v-for="[type, values] in bindingEntries" :key="type" :label="resourceLabel(type)"><NSelect v-model:value="form.bindings[type]" multiple clearable filterable :options="optionValues(values)" :placeholder="values.length ? '选择已授权资源' : '暂无可用资源'" /></NFormItem><NFormItem label="交付后立即发布"><NSwitch v-model:value="form.publish"><template #checked>发布</template><template #unchecked>仅草稿</template></NSwitch></NFormItem></NForm><NSpace justify="end"><NButton @click="step = 1">上一步</NButton><NButton type="primary" :loading="prechecking" @click="runPrecheck">运行预检</NButton></NSpace></div>
      <div v-else-if="step === 3" class="step-content"><NTag v-if="precheck?.canInstall" type="success">预检通过</NTag><NTag v-else type="error">预检未通过</NTag><div class="check-list"><div v-for="check in precheck?.checks" :key="check.key" class="check-row"><NTag size="small" :type="check.status === 'error' ? 'error' : check.status === 'warning' ? 'warning' : 'success'">{{ check.status }}</NTag><div><strong>{{ check.label }}</strong><p>{{ check.message }}</p></div></div></div><NSpace justify="end"><NButton @click="step = 2">返回修改</NButton><NButton type="primary" :disabled="!precheck?.canInstall" :loading="installLoading" @click="install">确认交付</NButton></NSpace></div>
      <div v-else class="step-content"><NTag type="success">安装已持久化</NTag><p>已创建实例、Agent 版本和安装运行记录。可以进入智能体中心继续调整，也可以直接在工作区使用已发布 Agent。</p><NCard v-if="result" size="small" class="result-card"><NGrid cols="1 s:3" responsive="screen" :x-gap="12"><NGridItem><div class="muted">实例</div><strong>{{ (result.instance as Record<string, unknown>)?.id || '-' }}</strong></NGridItem><NGridItem><div class="muted">Agent</div><strong>{{ (result.agent as Record<string, unknown>)?.name || (result.agent as Record<string, unknown>)?.id || '-' }}</strong></NGridItem><NGridItem><div class="muted">状态</div><strong>{{ (result.run as Record<string, unknown>)?.status || 'installed' }}</strong></NGridItem></NGrid></NCard><NSpace justify="end"><NButton @click="router.push('/agent-center')">智能体中心</NButton><NButton type="primary" @click="closeInstall">完成</NButton></NSpace></div>
    </NModal>
  </div>
</template>

<style scoped>
.page-container { min-height: 100%; padding: 20px 24px 32px; }
.scenario-hero { display: flex; align-items: flex-end; justify-content: space-between; gap: 28px; padding: 22px 0 20px; border-bottom: 1px solid var(--n-border-color); }
.scenario-hero-copy { display: flex; align-items: flex-start; gap: 14px; min-width: 0; }
.scenario-title-mark { display: grid; place-items: center; flex: 0 0 auto; width: 42px; height: 42px; color: var(--n-primary-color); background: color-mix(in srgb, var(--n-primary-color) 11%, transparent); border: 1px solid color-mix(in srgb, var(--n-primary-color) 25%, var(--n-border-color)); border-radius: 8px; }
.scenario-title-mark svg { width: 21px; height: 21px; }
.scenario-kicker { color: var(--n-primary-color); font-size: 10px; font-weight: 700; letter-spacing: .14em; line-height: 1.2; }
.scenario-hero h1 { margin: 5px 0 5px; color: var(--n-text-color); font-size: 28px; font-weight: 700; letter-spacing: 0; line-height: 1.2; }
.scenario-hero p, .catalog-heading p, .section-heading p { margin: 0; color: var(--n-text-color-3); font-size: 13px; line-height: 1.6; }
.scenario-hero-actions { display: flex; align-items: center; gap: 20px; flex: 0 0 auto; }
.scenario-counts { display: flex; align-items: stretch; gap: 20px; }
.scenario-counts > div { display: grid; gap: 2px; min-width: 76px; padding-left: 16px; border-left: 1px solid var(--n-border-color); }
.scenario-counts strong { color: var(--n-text-color); font-size: 20px; font-weight: 700; line-height: 1.2; }
.scenario-counts span { color: var(--n-text-color-3); font-size: 11px; white-space: nowrap; }
.catalog-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin: 26px 0 14px; }
.catalog-heading h2, .section-heading h2 { margin: 0 0 4px; color: var(--n-text-color); font-size: 18px; font-weight: 650; letter-spacing: 0; }
.tag-line { display: flex; gap: 6px; flex-wrap: wrap; margin: 12px 0; }
.template-meta { color: var(--n-text-color-3); font-size: 12px; }
.instance-section { margin-top: 28px; }
.section-heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.template-card { height: 100%; transition: border-color .18s ease, transform .18s ease; }
.template-card:hover { border-color: color-mix(in srgb, var(--n-primary-color) 42%, var(--n-border-color)); transform: translateY(-1px); }
.template-card :deep(.n-card__header) { min-height: 42px; }
.template-card :deep(.n-card__content) { min-height: 150px; }
.template-card :deep(.n-card__action) { border-top: 1px solid var(--n-border-color); }
.step-content { padding-top: 22px; }
.check-list { margin: 18px 0; display: grid; gap: 10px; }
.check-row { display: flex; gap: 10px; align-items: flex-start; padding: 10px 12px; border: 1px solid var(--n-border-color); border-radius: 6px; }
.check-row p { margin: 4px 0 0; color: var(--n-text-color-3); }
.result-card { margin: 16px 0; }
.instance-overview { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; padding: 4px 0 18px; border-bottom: 1px solid var(--n-border-color); }
.instance-overview > div { display: grid; gap: 6px; min-width: 0; }
.instance-overview span { color: var(--n-text-color-3); font-size: 12px; }
.instance-overview strong { overflow-wrap: anywhere; }
.instance-detail-section { margin-top: 20px; }
.instance-detail-section h4 { margin: 0 0 10px; }
.instance-detail-section ul { margin: 0; padding-left: 20px; line-height: 1.8; }
.resource-list { display: grid; gap: 8px; }
.resource-row { display: grid; grid-template-columns: 120px minmax(0, 1fr); gap: 12px; align-items: start; padding: 9px 0; border-bottom: 1px solid var(--n-border-color); }
.resource-row span { overflow-wrap: anywhere; }
.question-list { display: grid; gap: 8px; }
.question-list button { display: flex; width: 100%; min-height: 42px; align-items: center; justify-content: space-between; gap: 12px; padding: 9px 12px; color: var(--n-text-color); text-align: left; background: transparent; border: 1px solid var(--n-border-color); border-radius: 6px; cursor: pointer; }
.question-list button:hover { border-color: var(--n-primary-color); background: var(--n-action-color); }
.question-list button svg { flex: 0 0 auto; }
@media (max-width: 700px) {
  .page-container { padding: 14px 14px 24px; }
  .scenario-hero, .scenario-hero-actions { align-items: stretch; flex-direction: column; }
  .scenario-hero { gap: 18px; padding-top: 12px; }
  .scenario-hero-actions { gap: 14px; }
  .scenario-counts { gap: 14px; }
  .scenario-counts > div { flex: 1; }
  .scenario-hero-actions :deep(.n-button) { align-self: flex-start; }
  .instance-overview { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .resource-row { grid-template-columns: 96px minmax(0, 1fr); }
}
</style>
