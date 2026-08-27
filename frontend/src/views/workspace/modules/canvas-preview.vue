<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import type { DataTableColumns, SelectOption } from "naive-ui";
import type { CanvasContentType } from "@/service/api";
import { getAuthorization } from "@/service/request/shared";
import SvgIcon from "@/components/custom/svg-icon.vue";
import MessageMermaid from "./message-mermaid.vue";
import RichMessageRenderer from "./rich-message-renderer.vue";
import {
  buildCanvasLineComparison,
  createCanvasCsvPivot,
  parseCanvasCompare,
  parseCanvasCsv,
  resolveCanvasMediaSource,
  sanitizedCanvasHtmlDocument,
  type CanvasCsvPivotRow,
} from "./canvas-state";

const props = defineProps<{
  contentType: CanvasContentType;
  content: string;
  metadata: Record<string, unknown>;
}>();

const csvMode = ref<"table" | "pivot">("table");
const pivotGroup = ref<string | null>(null);
const pivotValue = ref<string | null>(null);
const pivotAggregate = ref<"count" | "sum" | "average">("count");
const mediaUrl = ref<string | null>(null);
const mediaLoading = ref(false);
const mediaError = ref<string | null>(null);
let mediaGeneration = 0;
let mediaObjectUrl: string | null = null;
let mediaController: AbortController | null = null;

const htmlDocument = computed(() => sanitizedCanvasHtmlDocument(props.content));
const codeLanguage = computed(() =>
  typeof props.metadata.language === "string" && props.metadata.language.trim()
    ? props.metadata.language.trim()
    : "text",
);
const csvData = computed(() => parseCanvasCsv(props.content));
const csvColumns = computed<DataTableColumns<Record<string, string>>>(() =>
  csvData.value.columns.map(column => ({
    title: column,
    key: column,
    minWidth: 120,
    ellipsis: { tooltip: true },
  })),
);
const csvColumnOptions = computed<SelectOption[]>(() =>
  csvData.value.columns.map(column => ({ label: column, value: column })),
);
const pivotRows = computed(() =>
  pivotGroup.value
    ? createCanvasCsvPivot(
        csvData.value,
        pivotGroup.value,
        pivotValue.value,
        pivotAggregate.value,
      )
    : [],
);
const pivotColumns: DataTableColumns<CanvasCsvPivotRow> = [
  { title: "分组", key: "group", minWidth: 160, ellipsis: { tooltip: true } },
  { title: "记录数", key: "count", width: 100 },
  {
    title: "聚合值",
    key: "value",
    width: 140,
    render: row => Number.isInteger(row.value) ? String(row.value) : row.value.toFixed(2),
  },
];
const compareData = computed(() => parseCanvasCompare(props.content, props.metadata));
const compareLines = computed(() =>
  compareData.value.error
    ? []
    : buildCanvasLineComparison(compareData.value.left.content, compareData.value.right.content),
);
const previewEmpty = computed(() => !props.content.trim());

function resetCsvView() {
  csvMode.value = "table";
  pivotGroup.value = csvData.value.columns[0] || null;
  pivotValue.value = null;
  pivotAggregate.value = "count";
}

function releaseMedia() {
  mediaController?.abort();
  mediaController = null;
  if (mediaObjectUrl) URL.revokeObjectURL(mediaObjectUrl);
  mediaObjectUrl = null;
  mediaUrl.value = null;
}

async function loadMedia() {
  const generation = ++mediaGeneration;
  releaseMedia();
  mediaError.value = null;
  if (props.contentType !== "image" && props.contentType !== "pdf") return;
  if (!props.content.trim()) return;
  const source = resolveCanvasMediaSource(props.content, props.contentType, props.metadata);
  if (source.error || !source.url) {
    mediaError.value = source.error || "媒体内容无效";
    return;
  }
  if (!source.requiresAuthorization) {
    mediaUrl.value = source.url;
    return;
  }
  mediaLoading.value = true;
  const controller = new AbortController();
  mediaController = controller;
  try {
    const headers = new Headers({
      Accept: source.mimeType,
      clientid: import.meta.env.VITE_APP_CLIENT_ID,
    });
    const authorization = getAuthorization();
    if (authorization) headers.set("Authorization", authorization);
    const response = await fetch(source.url, {
      method: "GET",
      credentials: "same-origin",
      headers,
      signal: controller.signal,
    });
    if (!response.ok) {
      throw new Error(
        response.status === 403
          ? "没有媒体读取权限"
          : response.status === 404
            ? "媒体文件不存在"
            : `媒体加载失败（${response.status}）`,
      );
    }
    const length = Number(response.headers.get("content-length") || 0);
    if (length > 20 * 1024 * 1024) throw new Error("媒体内容超过 20MB 预览限制");
    const blob = await response.blob();
    if (blob.size > 20 * 1024 * 1024) throw new Error("媒体内容超过 20MB 预览限制");
    if (generation !== mediaGeneration) return;
    mediaObjectUrl = URL.createObjectURL(blob);
    mediaUrl.value = mediaObjectUrl;
  } catch (error) {
    if (controller.signal.aborted || generation !== mediaGeneration) return;
    mediaError.value = error instanceof Error ? error.message : "媒体加载失败";
  } finally {
    if (generation === mediaGeneration) mediaLoading.value = false;
  }
}

watch(
  () => [props.contentType, props.content, props.metadata.encoding, props.metadata.mimeType],
  () => void loadMedia(),
  { immediate: true },
);
watch(
  () => props.content,
  () => resetCsvView(),
);
watch(
  () => csvData.value.columns,
  columns => {
    if (!pivotGroup.value || !columns.includes(pivotGroup.value)) pivotGroup.value = columns[0] || null;
    if (pivotValue.value && !columns.includes(pivotValue.value)) pivotValue.value = null;
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  mediaGeneration += 1;
  releaseMedia();
});
</script>

<template>
  <div class="canvas-preview" :class="`preview-${contentType}`">
    <NEmpty v-if="previewEmpty" description="输入内容后将在这里预览" />

    <RichMessageRenderer
      v-else-if="contentType === 'markdown'"
      :content="content"
      :code-actions="false"
    />

    <iframe
      v-else-if="contentType === 'html'"
      class="html-preview"
      :srcdoc="htmlDocument"
      sandbox=""
      referrerpolicy="no-referrer"
      title="HTML Canvas 安全预览"
    />

    <div v-else-if="contentType === 'code'" class="code-preview">
      <div class="preview-caption">
        <SvgIcon icon="lucide:code-2" />
        <span>{{ codeLanguage }}</span>
      </div>
      <NCode :code="content" :language="codeLanguage" word-wrap show-line-numbers />
    </div>

    <MessageMermaid
      v-else-if="contentType === 'mermaid'"
      :content="content"
      :complete="true"
    />

    <div v-else-if="contentType === 'csv'" class="csv-preview">
      <NAlert v-if="csvData.error" type="error" :bordered="false">{{ csvData.error }}</NAlert>
      <template v-else>
        <div class="csv-toolbar">
          <NRadioGroup v-model:value="csvMode" size="small">
            <NRadioButton value="table">表格</NRadioButton>
            <NRadioButton value="pivot">透视</NRadioButton>
          </NRadioGroup>
          <template v-if="csvMode === 'pivot'">
            <NSelect
              v-model:value="pivotGroup"
              size="small"
              :options="csvColumnOptions"
              placeholder="分组列"
            />
            <NSelect
              v-model:value="pivotValue"
              size="small"
              :options="csvColumnOptions"
              clearable
              placeholder="数值列"
              :disabled="pivotAggregate === 'count'"
            />
            <NSelect
              v-model:value="pivotAggregate"
              size="small"
              :options="[
                { label: '计数', value: 'count' },
                { label: '求和', value: 'sum' },
                { label: '平均值', value: 'average' },
              ]"
            />
          </template>
          <NTooltip>
            <template #trigger>
              <NButton circle quaternary size="small" aria-label="重置 CSV 视图" @click="resetCsvView">
                <template #icon><SvgIcon icon="lucide:rotate-ccw" /></template>
              </NButton>
            </template>
            重置表格与透视设置
          </NTooltip>
        </div>
        <NDataTable
          v-if="csvMode === 'table'"
          :columns="csvColumns"
          :data="csvData.rows"
          :row-key="row => csvData.rows.indexOf(row)"
          :max-height="520"
          :scroll-x="Math.max(720, csvData.columns.length * 140)"
          size="small"
          striped
        />
        <NAlert
          v-if="csvData.truncated"
          class="csv-note"
          type="info"
          :bordered="false"
        >
          当前表格与透视基于前 {{ csvData.rows.length }} / {{ csvData.totalRows }} 行
        </NAlert>
        <NDataTable
          v-else-if="pivotRows.length"
          :columns="pivotColumns"
          :data="pivotRows"
          :row-key="row => row.group"
          :max-height="520"
          size="small"
          striped
        />
        <NEmpty v-else description="选择分组列后生成透视结果" />
      </template>
    </div>

    <div v-else-if="contentType === 'image' || contentType === 'pdf'" class="media-preview">
      <NSpin v-if="mediaLoading" size="large" description="正在授权加载媒体" />
      <NAlert v-else-if="mediaError" type="error" :bordered="false">{{ mediaError }}</NAlert>
      <img
        v-else-if="contentType === 'image' && mediaUrl"
        :src="mediaUrl"
        alt="Canvas 图片预览"
        referrerpolicy="no-referrer"
      >
      <iframe
        v-else-if="contentType === 'pdf' && mediaUrl"
        :src="mediaUrl"
        sandbox=""
        referrerpolicy="no-referrer"
        title="Canvas PDF 预览"
      />
    </div>

    <div v-else-if="contentType === 'compare'" class="compare-preview">
      <NAlert v-if="compareData.error" type="error" :bordered="false">{{ compareData.error }}</NAlert>
      <template v-else>
        <div class="compare-header">
          <strong>{{ compareData.left.label }}</strong>
          <strong>{{ compareData.right.label }}</strong>
        </div>
        <div class="compare-lines">
          <div v-for="line in compareLines" :key="line.key" class="compare-line" :class="`is-${line.state}`">
            <pre>{{ line.left ?? '' }}</pre>
            <pre>{{ line.right ?? '' }}</pre>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped lang="scss">
.canvas-preview {
  width: 100%;
  min-width: 0;
  min-height: 360px;
  padding: 16px;
  overflow: auto;
  background: var(--n-color);
}

.html-preview,
.media-preview iframe {
  width: 100%;
  height: min(64vh, 720px);
  min-height: 420px;
  border: 1px solid var(--n-border-color);
  background: #fff;
}

.code-preview {
  min-height: 360px;
  overflow: auto;
}

.preview-caption {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
  gap: 8px;
  color: var(--n-text-color-3);
  font-size: 12px;
  text-transform: uppercase;
}

.csv-toolbar {
  display: grid;
  grid-template-columns: auto minmax(130px, 1fr) minmax(130px, 1fr) minmax(110px, 140px) auto;
  align-items: center;
  margin-bottom: 12px;
  gap: 8px;
}

.csv-note {
  margin-top: 8px;
}

.media-preview {
  display: grid;
  min-height: 420px;
  place-items: center;
}

.media-preview img {
  display: block;
  max-width: 100%;
  max-height: min(68vh, 760px);
  object-fit: contain;
}

.compare-preview {
  min-width: 720px;
  border: 1px solid var(--n-border-color);
}

.compare-header,
.compare-line {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
}

.compare-header {
  position: sticky;
  z-index: 1;
  top: -16px;
  background: var(--n-color);
  border-bottom: 1px solid var(--n-border-color);
}

.compare-header strong,
.compare-line pre {
  min-width: 0;
  margin: 0;
  padding: 8px 12px;
  border-right: 1px solid var(--n-border-color);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.compare-line pre {
  font: 12px/1.55 ui-monospace, SFMono-Regular, Consolas, monospace;
}

.compare-line.is-added pre:last-child,
.compare-line.is-changed pre:last-child {
  background: rgba(24, 160, 88, 0.1);
}

.compare-line.is-removed pre:first-child,
.compare-line.is-changed pre:first-child {
  background: rgba(208, 48, 80, 0.1);
}

@media (max-width: 760px) {
  .canvas-preview {
    min-height: 300px;
    padding: 10px;
  }

  .csv-toolbar {
    grid-template-columns: 1fr 1fr;
  }

  .compare-preview {
    min-width: 620px;
  }
}
</style>
