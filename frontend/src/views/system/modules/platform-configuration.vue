<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import dayjs from 'dayjs';
import type { DataTableColumns, FormInst, FormRules, SelectOption } from 'naive-ui';
import { NTag } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  fetchPlatformConfiguration,
  fetchPlatformConfigurationHistory,
  updatePlatformConfiguration
} from '@/service/api';
import type {
  PlatformConfiguration,
  PlatformConfigurationHistory,
  PublicPlatformConfiguration
} from '@/service/api';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import { applyPublicPlatformConfiguration } from '@/utils/platform-configuration';

const appStore = useAppStore();
const themeStore = useThemeStore();
const formRef = ref<FormInst | null>(null);
const loading = ref(false);
const submitting = ref(false);
const loadError = ref('');
const configuration = ref<PlatformConfiguration | null>(null);
const history = ref<PlatformConfigurationHistory[]>([]);
const baseline = ref('');
const logoFailed = ref(false);

const form = reactive({
  productName: '',
  productShortName: '',
  logoUrl: null as string | null,
  faviconUrl: null as string | null,
  primaryColor: '#18A058',
  platformTimezone: 'Asia/Shanghai',
  defaultLocale: 'zh-CN' as 'zh-CN' | 'en-US',
  watermarkEnabled: false,
  changeReason: ''
});

const rules: FormRules = {
  productName: [
    { required: true, message: '请输入产品名称', trigger: ['input', 'blur'] },
    { max: 128, message: '产品名称不能超过 128 个字符', trigger: ['input', 'blur'] }
  ],
  productShortName: [
    { required: true, message: '请输入产品短名称', trigger: ['input', 'blur'] },
    { max: 32, message: '产品短名称不能超过 32 个字符', trigger: ['input', 'blur'] }
  ],
  primaryColor: [
    { required: true, pattern: /^#[0-9A-Fa-f]{6}$/, message: '请选择有效的品牌色', trigger: ['change', 'blur'] }
  ],
  platformTimezone: [{ required: true, message: '请选择平台时区', trigger: ['change', 'blur'] }],
  changeReason: [
    { required: true, message: '请填写变更原因', trigger: ['input', 'blur'] },
    { min: 2, max: 500, message: '变更原因需为 2-500 个字符', trigger: ['input', 'blur'] }
  ]
};

const timezoneOptions: SelectOption[] = [
  { label: '中国标准时间 · Asia/Shanghai', value: 'Asia/Shanghai' },
  { label: '香港时间 · Asia/Hong_Kong', value: 'Asia/Hong_Kong' },
  { label: '日本标准时间 · Asia/Tokyo', value: 'Asia/Tokyo' },
  { label: '协调世界时 · UTC', value: 'UTC' },
  { label: '伦敦时间 · Europe/London', value: 'Europe/London' },
  { label: '纽约时间 · America/New_York', value: 'America/New_York' }
];

const dirty = computed(() => baseline.value !== snapshot());
const previewStyle = computed(() => ({ '--preview-primary': form.primaryColor }));

const historyColumns: DataTableColumns<PlatformConfigurationHistory> = [
  {
    title: '版本', key: 'revisionNo', width: 90,
    render: row => h(NTag, { size: 'small', type: row.revisionNo === configuration.value?.revisionNo ? 'success' : 'default' }, () => `v${row.revisionNo}`)
  },
  { title: '产品名称', key: 'productName', minWidth: 210, ellipsis: { tooltip: true } },
  {
    title: '外观', key: 'primaryColor', width: 125,
    render: row => h('span', { class: 'color-cell' }, [
      h('span', { class: 'color-swatch', style: { background: row.primaryColor } }),
      row.primaryColor
    ])
  },
  { title: '时区', key: 'platformTimezone', minWidth: 150 },
  { title: '变更原因', key: 'changeReason', minWidth: 220, ellipsis: { tooltip: true } },
  { title: '操作人', key: 'changedBy', width: 105, render: row => `#${row.changedBy}` },
  { title: '时间', key: 'createdAt', width: 165, render: row => dayjs(row.createdAt).format('YYYY-MM-DD HH:mm') }
];

function snapshot() {
  return JSON.stringify({
    productName: form.productName.trim(),
    productShortName: form.productShortName.trim(),
    logoUrl: form.logoUrl?.trim() || null,
    faviconUrl: form.faviconUrl?.trim() || null,
    primaryColor: form.primaryColor.toUpperCase(),
    platformTimezone: form.platformTimezone,
    defaultLocale: form.defaultLocale,
    watermarkEnabled: form.watermarkEnabled
  });
}

function applyCurrent(value: PlatformConfiguration) {
  configuration.value = value;
  Object.assign(form, {
    productName: value.productName,
    productShortName: value.productShortName,
    logoUrl: value.logoUrl,
    faviconUrl: value.faviconUrl,
    primaryColor: value.primaryColor,
    platformTimezone: value.platformTimezone,
    defaultLocale: value.defaultLocale,
    watermarkEnabled: value.watermarkEnabled,
    changeReason: ''
  });
  logoFailed.value = false;
  baseline.value = snapshot();
}

async function load() {
  loading.value = true;
  loadError.value = '';
  const [currentResult, historyResult] = await Promise.all([
    fetchPlatformConfiguration(),
    fetchPlatformConfigurationHistory(30)
  ]);
  if (currentResult.error) {
    loadError.value = currentResult.error instanceof Error
      ? currentResult.error.message
      : '平台配置加载失败';
  } else {
    applyCurrent(currentResult.data);
  }
  if (!historyResult.error) history.value = historyResult.data;
  loading.value = false;
}

async function save() {
  if (!configuration.value) return;
  await formRef.value?.validate();
  submitting.value = true;
  const result = await updatePlatformConfiguration({
    productName: form.productName.trim(),
    productShortName: form.productShortName.trim(),
    logoUrl: form.logoUrl?.trim() || null,
    faviconUrl: form.faviconUrl?.trim() || null,
    primaryColor: form.primaryColor.toUpperCase(),
    platformTimezone: form.platformTimezone,
    defaultLocale: form.defaultLocale,
    watermarkEnabled: form.watermarkEnabled,
    expectedRevision: configuration.value.revisionNo,
    changeReason: form.changeReason.trim()
  });
  if (result.error) {
    const message = result.error instanceof Error ? result.error.message : '平台配置保存失败';
    window.$message?.error(message);
    if (message.includes('刷新')) await load();
  } else {
    applyCurrent(result.data);
    applyRuntime(result.data);
    window.$message?.success('平台配置已保存');
    const historyResult = await fetchPlatformConfigurationHistory(30);
    if (!historyResult.error) history.value = historyResult.data;
  }
  submitting.value = false;
}

function applyRuntime(value: PublicPlatformConfiguration) {
  applyPublicPlatformConfiguration(value);
  themeStore.updateThemeColors('primary', value.primaryColor);
  themeStore.watermark.visible = value.watermarkEnabled;
  appStore.updateDocumentTitleByLocale();
}

onMounted(load);
</script>

<template>
  <section class="configuration-pane">
    <div class="configuration-toolbar">
      <div>
        <h3>平台配置</h3>
        <NSpace v-if="configuration" size="small" align="center">
          <NTag size="small" type="info">v{{ configuration.revisionNo }}</NTag>
          <span>更新于 {{ dayjs(configuration.updatedAt).format('YYYY-MM-DD HH:mm') }}</span>
        </NSpace>
      </div>
      <NButton secondary :loading="loading" @click="load">
        <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
        刷新
      </NButton>
    </div>

    <NAlert v-if="loadError" type="error" :bordered="false" class="load-alert">{{ loadError }}</NAlert>
    <NSpin :show="loading">
      <NForm ref="formRef" :model="form" :rules="rules" label-placement="top" :disabled="!configuration">
        <NGrid :cols="12" :x-gap="20" :y-gap="4" responsive="screen" item-responsive>
          <NFormItemGi span="12 m:8" label="产品名称" path="productName">
            <NInput v-model:value="form.productName" maxlength="128" show-count />
          </NFormItemGi>
          <NFormItemGi span="12 m:4" label="导航短名称" path="productShortName">
            <NInput v-model:value="form.productShortName" maxlength="32" show-count />
          </NFormItemGi>
          <NFormItemGi span="12 m:4" label="品牌色" path="primaryColor">
            <NColorPicker v-model:value="form.primaryColor" :modes="['hex']" :show-alpha="false" />
          </NFormItemGi>
          <NFormItemGi span="12 m:4" label="平台时区" path="platformTimezone">
            <NSelect v-model:value="form.platformTimezone" :options="timezoneOptions" filterable tag />
          </NFormItemGi>
          <NFormItemGi span="12 m:4" label="默认语言">
            <NRadioGroup v-model:value="form.defaultLocale" name="default-locale">
              <NRadioButton value="zh-CN">中文</NRadioButton>
              <NRadioButton value="en-US">English</NRadioButton>
            </NRadioGroup>
          </NFormItemGi>
          <NFormItemGi span="12 m:6" label="Logo 地址">
            <NInput v-model:value="form.logoUrl" clearable placeholder="/assets/logo.svg 或 HTTPS 地址" @update:value="logoFailed = false" />
          </NFormItemGi>
          <NFormItemGi span="12 m:6" label="浏览器图标地址">
            <NInput v-model:value="form.faviconUrl" clearable placeholder="/favicon.svg 或 HTTPS 地址" />
          </NFormItemGi>
          <NFormItemGi span="12 m:4" label="登录用户水印">
            <NSwitch v-model:value="form.watermarkEnabled">
              <template #checked>启用</template>
              <template #unchecked>关闭</template>
            </NSwitch>
          </NFormItemGi>
          <NFormItemGi span="12 m:8" label="变更原因" path="changeReason">
            <NInput v-model:value="form.changeReason" maxlength="500" show-count placeholder="记录本次配置变更的业务原因" />
          </NFormItemGi>
        </NGrid>

        <div class="brand-preview" :style="previewStyle">
          <div class="preview-mark">
            <img v-if="form.logoUrl && !logoFailed" :src="form.logoUrl" alt="平台 Logo 预览" @error="logoFailed = true" />
            <SvgIcon v-else icon="lucide:sparkles" />
          </div>
          <div><strong>{{ form.productShortName || '智能体平台' }}</strong><span>{{ form.productName || '企业级智能体工作平台' }}</span></div>
          <NTag size="small" :color="{ color: form.primaryColor, textColor: '#fff', borderColor: form.primaryColor }">品牌预览</NTag>
        </div>

        <NSpace justify="end" class="form-actions">
          <NButton :disabled="!dirty || submitting" @click="configuration && applyCurrent(configuration)">撤销修改</NButton>
          <NButton type="primary" :disabled="!dirty || !form.changeReason.trim()" :loading="submitting" @click="save">
            <template #icon><SvgIcon icon="lucide:save" /></template>
            保存配置
          </NButton>
        </NSpace>
      </NForm>
    </NSpin>

    <section class="history-section">
      <h3>版本历史</h3>
      <NDataTable :columns="historyColumns" :data="history" :loading="loading" :scroll-x="1120" :row-key="row => row.id" />
      <NEmpty v-if="!loading && history.length === 0" description="暂无配置历史" />
    </section>
  </section>
</template>

<style scoped lang="scss">
.configuration-pane { padding-top: 8px; }
.configuration-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.configuration-toolbar h3, .history-section h3 { margin: 0 0 6px; font-size: 16px; }
.configuration-toolbar span { color: var(--n-text-color-3); font-size: 12px; }
.load-alert { margin-bottom: 16px; }
.brand-preview { display: grid; grid-template-columns: 44px minmax(0, 1fr) auto; align-items: center; gap: 12px; min-height: 68px; padding: 10px 12px; border: 1px solid var(--n-border-color); border-left: 3px solid var(--preview-primary); border-radius: 6px; }
.preview-mark { display: flex; width: 40px; height: 40px; align-items: center; justify-content: center; overflow: hidden; border-radius: 6px; background: color-mix(in srgb, var(--preview-primary) 12%, transparent); color: var(--preview-primary); font-size: 22px; }
.preview-mark img { width: 100%; height: 100%; object-fit: contain; }
.brand-preview > div:nth-child(2) { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
.brand-preview strong, .brand-preview span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.brand-preview span { color: var(--n-text-color-3); font-size: 12px; }
.form-actions { margin-top: 16px; }
.history-section { margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--n-border-color); }
:deep(.color-cell) { display: inline-flex; align-items: center; gap: 7px; }
:deep(.color-swatch) { width: 14px; height: 14px; flex: 0 0 14px; border: 1px solid var(--n-border-color); border-radius: 3px; }
@media (max-width: 640px) {
  .configuration-toolbar { align-items: stretch; flex-direction: column; }
  .brand-preview { grid-template-columns: 40px minmax(0, 1fr); }
  .brand-preview > .n-tag { display: none; }
}
</style>
