<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ApiReference, type ApiReferenceConfiguration } from '@scalar/api-reference';
import '@scalar/api-reference/style.css';
import { NAlert, NButton, NEmpty, NSpin } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { getAuthorization } from '@/service/request/shared';
import { getServiceBaseURL } from '@/utils/service';
import { apiDocsUrl, filterNhsV1Spec, type OpenApiDocument } from './api-playground';

const loading = ref(true);
const error = ref('');
const document = ref<OpenApiDocument | null>(null);
const token = computed(() => (getAuthorization() || '').replace(/^Bearer\s+/i, ''));
const serviceBaseUrl = getServiceBaseURL(
  import.meta.env,
  import.meta.env.DEV && import.meta.env.VITE_HTTP_PROXY === 'Y'
).baseURL;

const configuration = computed(() => ({
  spec: { content: document.value },
  authentication: {
    preferredSecurityScheme: 'SessionBearer',
    securitySchemes: { SessionBearer: { token: token.value } }
  },
  theme: 'default',
  hideDownloadButton: true,
  hideModels: true
}) as unknown as ApiReferenceConfiguration);

async function loadSpec() {
  loading.value = true;
  error.value = '';
  document.value = null;
  try {
    const response = await fetch(apiDocsUrl(serviceBaseUrl), {
      headers: { Accept: 'application/json', ...(getAuthorization() ? { Authorization: getAuthorization() as string } : {}) }
    });
    if (!response.ok) throw new Error(`接口定义加载失败（${response.status}）`);
    const source = await response.json() as OpenApiDocument;
    const filtered = filterNhsV1Spec(source, serviceBaseUrl);
    if (!Object.keys(filtered.paths || {}).length) throw new Error('当前服务未发布 /api/v1 接口');
    document.value = filtered;
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '接口定义加载失败';
  } finally {
    loading.value = false;
  }
}

onMounted(() => void loadSpec());
</script>

<template>
  <section class="playground-pane">
    <NAlert v-if="error" type="error" :bordered="false" class="playground-alert">
      {{ error }}
    </NAlert>
    <div v-if="error" class="playground-retry"><NButton size="small" secondary @click="loadSpec"><template #icon><SvgIcon icon="lucide:refresh-cw" /></template>重试</NButton></div>
    <div v-if="loading" class="playground-state"><NSpin size="large" /><span>正在加载可执行接口定义...</span></div>
    <div v-else-if="!document" class="playground-state"><NEmpty description="暂无可执行接口" /></div>
    <ApiReference v-else :configuration="configuration" />
  </section>
</template>

<style scoped>
.playground-pane { min-height: 680px; overflow: hidden; border: 1px solid var(--n-border-color); background: var(--n-color); }
.playground-alert { margin: 16px; }
.playground-retry { display: flex; justify-content: flex-end; margin: -8px 16px 16px; }
.playground-state { min-height: 620px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; color: var(--n-text-color-3); }
</style>
