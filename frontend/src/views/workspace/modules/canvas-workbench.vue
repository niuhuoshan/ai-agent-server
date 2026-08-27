<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import dayjs from "dayjs";
import type { SelectOption } from "naive-ui";
import SvgIcon from "@/components/custom/svg-icon.vue";
import {
  createConversationCanvas,
  deleteConversationCanvas,
  fetchConversationCanvas,
  fetchConversationCanvases,
  fetchConversationCanvasVersions,
  restoreConversationCanvasVersion,
  saveConversationCanvasToWorkspace,
  updateConversationCanvas,
} from "@/service/api";
import type {
  CanvasContentType,
  CanvasVersionView,
  CanvasView,
  ConversationMessageView,
} from "@/service/api";
import CanvasPreview from "./canvas-preview.vue";
import {
  canvasDraftFromMessage,
  canvasDraftFromView,
  canvasDraftFromVersion,
  canvasDraftSignature,
  canvasRequestError,
  createCanvasDraft,
  isCanvasDraftDirty,
  suggestedCanvasWorkspacePath,
  validateCanvasWorkspacePath,
  type CanvasDraft,
} from "./canvas-state";

const props = defineProps<{
  conversationId: string | null;
}>();

const drawerVisible = ref(false);
const canvasConversationId = ref<string | null>(null);
const canvases = ref<CanvasView[]>([]);
const listLoading = ref(false);
const detailLoading = ref(false);
const versionsLoading = ref(false);
const saving = ref(false);
const deleting = ref(false);
const restoring = ref(false);
const workspaceSaving = ref(false);
const activeCanvas = ref<CanvasView | null>(null);
const draft = ref<CanvasDraft | null>(null);
const baselineSignature = ref("");
const versions = ref<CanvasVersionView[]>([]);
const versionPreview = ref<CanvasVersionView | null>(null);
const workbenchMode = ref<"edit" | "preview" | "split">("split");
const workspacePath = ref("");
const listError = ref<ReturnType<typeof canvasRequestError> | null>(null);
const detailError = ref<ReturnType<typeof canvasRequestError> | null>(null);
const detailTargetCanvasId = ref<string | null>(null);
const mutationError = ref<ReturnType<typeof canvasRequestError> | null>(null);

const dirtyGuardVisible = ref(false);
const dirtyGuardReason = ref("");
const dirtyGuardSaving = ref(false);
let dirtyGuardResolver: ((allowed: boolean) => void) | null = null;
let dirtyGuardPromise: Promise<boolean> | null = null;

const overwriteVisible = ref(false);
const overwritePath = ref("");
const deleteVisible = ref(false);
const restoreVisible = ref(false);
const restoreTarget = ref<CanvasVersionView | null>(null);

const contentTypeOptions: SelectOption[] = [
  { label: "Markdown", value: "markdown" },
  { label: "HTML", value: "html" },
  { label: "代码", value: "code" },
  { label: "Mermaid", value: "mermaid" },
  { label: "CSV", value: "csv" },
  { label: "图片", value: "image" },
  { label: "PDF", value: "pdf" },
  { label: "文件比较", value: "compare" },
];
const mediaEncodingOptions: SelectOption[] = [
  { label: "Data URL", value: "data-url" },
  { label: "Base64", value: "base64" },
  { label: "平台授权 URL", value: "url" },
];
const modeOptions = [
  { label: "编辑", value: "edit" },
  { label: "分栏", value: "split" },
  { label: "预览", value: "preview" },
];

const isDirty = computed(() => isCanvasDraftDirty(draft.value, baselineSignature.value));
const canSave = computed(() => Boolean(
  draft.value
  && draft.value.title.trim()
  && !saving.value
  && isDirty.value,
));
const draftLanguage = computed({
  get: () => typeof draft.value?.metadata.language === "string" ? draft.value.metadata.language : "text",
  set: value => setMetadata("language", value),
});
const draftEncoding = computed({
  get: () => typeof draft.value?.metadata.encoding === "string" ? draft.value.metadata.encoding : "data-url",
  set: value => setMetadata("encoding", value),
});
const draftMimeType = computed({
  get: () => {
    if (typeof draft.value?.metadata.mimeType === "string") return draft.value.metadata.mimeType;
    return draft.value?.contentType === "pdf" ? "application/pdf" : "image/png";
  },
  set: value => setMetadata("mimeType", value),
});
const leftLabel = computed({
  get: () => typeof draft.value?.metadata.leftLabel === "string" ? draft.value.metadata.leftLabel : "左侧",
  set: value => setMetadata("leftLabel", value),
});
const rightLabel = computed({
  get: () => typeof draft.value?.metadata.rightLabel === "string" ? draft.value.metadata.rightLabel : "右侧",
  set: value => setMetadata("rightLabel", value),
});
const activeVersion = computed(() => activeCanvas.value?.currentVersion || draft.value?.currentVersion || null);
const editorPlaceholder = computed(() => {
  if (!draft.value) return "";
  if (draft.value.contentType === "compare") {
    return '{\n  "left": "左侧文件内容",\n  "right": "右侧文件内容"\n}';
  }
  if (draft.value.contentType === "mermaid") return "graph TD\n  A[开始] --> B[完成]";
  if (draft.value.contentType === "csv") return "名称,数量\n示例,1";
  if (draft.value.contentType === "html") return "<main><h1>HTML Canvas</h1></main>";
  if (draft.value.contentType === "image" || draft.value.contentType === "pdf") {
    return draftEncoding.value === "url" ? "/platform/..." : "粘贴受支持的媒体内容";
  }
  return "输入 Canvas 内容";
});

async function openLibrary() {
  if (!props.conversationId) {
    window.$message?.warning("请先选择会话");
    return;
  }
  if (drawerVisible.value && canvasConversationId.value === props.conversationId) return;
  if (isDirty.value && !(await guardDirtyTransition("打开 Canvas 列表"))) return;
  canvasConversationId.value = props.conversationId;
  drawerVisible.value = true;
  resetActiveState();
  await loadCanvases(true);
}

async function openFromMessage(message: ConversationMessageView) {
  if (!props.conversationId) {
    window.$message?.warning("请先选择会话");
    return;
  }
  if (isDirty.value && !(await guardDirtyTransition("从消息创建 Canvas"))) return;
  canvasConversationId.value = props.conversationId;
  drawerVisible.value = true;
  activeCanvas.value = null;
  draft.value = canvasDraftFromMessage(message);
  baselineSignature.value = "";
  versions.value = [];
  versionPreview.value = null;
  detailError.value = null;
  mutationError.value = null;
  workspacePath.value = suggestedCanvasWorkspacePath(draft.value);
  void loadCanvases(false);
}

async function createBlankCanvas() {
  if (isDirty.value && !(await guardDirtyTransition("新建 Canvas"))) return;
  activeCanvas.value = null;
  draft.value = createCanvasDraft();
  baselineSignature.value = "";
  versions.value = [];
  versionPreview.value = null;
  detailError.value = null;
  mutationError.value = null;
  workspacePath.value = suggestedCanvasWorkspacePath(draft.value);
}

async function loadCanvases(selectFirst: boolean) {
  const conversationId = canvasConversationId.value;
  if (!conversationId) return;
  listLoading.value = true;
  listError.value = null;
  const result = await fetchConversationCanvases(conversationId);
  if (result.error) {
    listError.value = canvasRequestError(result.error, "Canvas 列表加载失败");
  } else {
    canvases.value = result.data.map(normalizeCanvas);
    if (selectFirst && !draft.value && canvases.value[0]) await selectCanvas(canvases.value[0].id, true);
  }
  listLoading.value = false;
}

async function requestSelectCanvas(canvasId: string) {
  if (activeCanvas.value?.id === canvasId && draft.value) return;
  if (isDirty.value && !(await guardDirtyTransition("切换 Canvas"))) return;
  await selectCanvas(canvasId, true);
}

async function selectCanvas(canvasId: string, loadVersionHistory: boolean) {
  const conversationId = canvasConversationId.value;
  if (!conversationId) return;
  detailLoading.value = true;
  detailTargetCanvasId.value = canvasId;
  detailError.value = null;
  mutationError.value = null;
  versionPreview.value = null;
  const result = await fetchConversationCanvas(conversationId, canvasId);
  if (result.error) {
    detailError.value = canvasRequestError(result.error, "Canvas 加载失败");
    if (result.response?.status === 404) void loadCanvases(false);
  } else {
    applyActiveCanvas(normalizeCanvas(result.data));
    if (loadVersionHistory) void loadVersions();
  }
  detailLoading.value = false;
}

async function loadVersions() {
  const conversationId = canvasConversationId.value;
  const canvasId = activeCanvas.value?.id;
  if (!conversationId || !canvasId) return;
  versionsLoading.value = true;
  const result = await fetchConversationCanvasVersions(conversationId, canvasId);
  if (result.error) {
    mutationError.value = canvasRequestError(result.error, "版本历史加载失败");
  } else {
    versions.value = result.data.map(normalizeVersion).sort((a, b) => b.versionNo - a.versionNo);
  }
  versionsLoading.value = false;
}

function applyActiveCanvas(canvas: CanvasView) {
  activeCanvas.value = canvas;
  draft.value = canvasDraftFromView(canvas);
  baselineSignature.value = canvasDraftSignature(draft.value);
  workspacePath.value = canvas.workspacePath || suggestedCanvasWorkspacePath(draft.value);
  canvases.value = [canvas, ...canvases.value.filter(item => item.id !== canvas.id)];
  versionPreview.value = null;
}

async function saveDraft() {
  const conversationId = canvasConversationId.value;
  const current = draft.value;
  if (!conversationId || !current || saving.value) return false;
  const title = current.title.trim();
  if (!title) {
    window.$message?.warning("请输入 Canvas 标题");
    return false;
  }
  if (title.length > 255) {
    window.$message?.warning("Canvas 标题不能超过 255 个字符");
    return false;
  }
  if (!current.content) {
    window.$message?.warning("Canvas 内容不能为空");
    return false;
  }
  if (new TextEncoder().encode(current.content).length > 10 * 1024 * 1024) {
    window.$message?.warning("Canvas 内容不能超过 10MB");
    return false;
  }
  saving.value = true;
  mutationError.value = null;
  const metadata = {
    ...current.metadata,
    ...(current.sourceMessageId ? { sourceMessageId: current.sourceMessageId } : {}),
  };
  const payload = {
    title,
    contentType: current.contentType,
    content: current.content,
    metadata,
  };
  const result = current.id && current.currentVersion
    ? await updateConversationCanvas(conversationId, current.id, {
        ...payload,
        expectedVersion: current.currentVersion,
      })
    : await createConversationCanvas(conversationId, payload);
  if (result.error) {
    mutationError.value = canvasRequestError(result.error, "Canvas 保存失败");
    saving.value = false;
    return false;
  }
  applyActiveCanvas(normalizeCanvas(result.data));
  await loadVersions();
  saving.value = false;
  window.$message?.success("Canvas 已保存");
  return true;
}

async function reloadAfterConflict() {
  const canvasId = activeCanvas.value?.id;
  if (!canvasId) return;
  await selectCanvas(canvasId, true);
}

async function saveConflictAsNew() {
  if (!draft.value) return;
  draft.value = createCanvasDraft({
    ...draft.value,
    id: null,
    currentVersion: null,
    title: `${draft.value.title} · 副本`,
  });
  activeCanvas.value = null;
  baselineSignature.value = "";
  mutationError.value = null;
  await saveDraft();
}

async function requestWorkspaceSave() {
  if (!draft.value) return;
  const path = workspacePath.value.trim();
  const pathError = validateCanvasWorkspacePath(path);
  if (pathError) {
    window.$message?.warning(pathError);
    return;
  }
  if (path && draft.value.metadata.workspacePath !== path) {
    setMetadata("workspacePath", path);
  }
  if (isDirty.value && !(await saveDraft())) return;
  await saveToWorkspace(path, false);
}

async function saveToWorkspace(path: string, overwrite: boolean) {
  const conversationId = canvasConversationId.value;
  const canvas = activeCanvas.value;
  if (!conversationId || !canvas) return;
  workspaceSaving.value = true;
  mutationError.value = null;
  const result = await saveConversationCanvasToWorkspace(conversationId, canvas.id, {
    path: path || undefined,
    overwrite,
    expectedVersion: canvas.currentVersion,
  });
  if (result.error) {
    const error = canvasRequestError(result.error, "保存到工作区失败");
    if (result.response?.status === 409 && !overwrite) {
      overwritePath.value = path || suggestedCanvasWorkspacePath(draft.value!);
      overwriteVisible.value = true;
    } else {
      if (overwrite) overwriteVisible.value = false;
      mutationError.value = error;
    }
    workspaceSaving.value = false;
    return;
  }
  overwriteVisible.value = false;
  workspacePath.value = result.data.path;
  activeCanvas.value = { ...canvas, workspacePath: result.data.path };
  canvases.value = canvases.value.map(item =>
    item.id === canvas.id ? { ...item, workspacePath: result.data.path } : item,
  );
  window.$message?.success(result.data.overwritten ? "工作区文件已覆盖" : "已保存到工作区");
  workspaceSaving.value = false;
}

async function confirmOverwrite() {
  await saveToWorkspace(overwritePath.value, true);
}

async function requestRestore(version: CanvasVersionView) {
  if (isDirty.value && !(await guardDirtyTransition("恢复历史版本"))) return;
  restoreTarget.value = version;
  restoreVisible.value = true;
}

async function confirmRestore() {
  const conversationId = canvasConversationId.value;
  const canvas = activeCanvas.value;
  const target = restoreTarget.value;
  if (!conversationId || !canvas || !target) return;
  restoring.value = true;
  mutationError.value = null;
  const result = await restoreConversationCanvasVersion(
    conversationId,
    canvas.id,
    target.versionNo,
    canvas.currentVersion,
  );
  if (result.error) {
    mutationError.value = canvasRequestError(result.error, "Canvas 版本恢复失败");
  } else {
    applyActiveCanvas(normalizeCanvas(result.data));
    restoreVisible.value = false;
    await loadVersions();
    window.$message?.success(`已恢复版本 ${target.versionNo}`);
  }
  restoring.value = false;
}

function previewVersion(version: CanvasVersionView) {
  versionPreview.value = version;
  workbenchMode.value = "preview";
}

function useVersionAsDraft() {
  if (!activeCanvas.value || !versionPreview.value) return;
  draft.value = canvasDraftFromVersion(activeCanvas.value, versionPreview.value);
  versionPreview.value = null;
  workbenchMode.value = "split";
}

function requestDelete() {
  if (!activeCanvas.value) return;
  deleteVisible.value = true;
}

async function confirmDelete() {
  const conversationId = canvasConversationId.value;
  const canvas = activeCanvas.value;
  if (!conversationId || !canvas) return;
  deleting.value = true;
  mutationError.value = null;
  const result = await deleteConversationCanvas(conversationId, canvas.id, canvas.currentVersion);
  if (result.error) {
    mutationError.value = canvasRequestError(result.error, "Canvas 删除失败");
  } else {
    canvases.value = canvases.value.filter(item => item.id !== canvas.id);
    deleteVisible.value = false;
    resetActiveState();
    if (canvases.value[0]) await selectCanvas(canvases.value[0].id, true);
    window.$message?.success("Canvas 已删除");
  }
  deleting.value = false;
}

async function requestClose() {
  if (!(await guardDirtyTransition("关闭 Canvas"))) return;
  drawerVisible.value = false;
  resetActiveState();
}

async function guardTransition(reason = "离开当前页面") {
  const allowed = await guardDirtyTransition(reason);
  if (allowed) {
    drawerVisible.value = false;
    resetActiveState();
  }
  return allowed;
}

function conversationChanged() {
  drawerVisible.value = false;
  canvasConversationId.value = null;
  canvases.value = [];
  resetActiveState();
}

function guardDirtyTransition(reason: string) {
  if (!isDirty.value) return Promise.resolve(true);
  if (dirtyGuardPromise) return dirtyGuardPromise;
  dirtyGuardReason.value = reason;
  dirtyGuardVisible.value = true;
  dirtyGuardPromise = new Promise<boolean>(resolve => {
    dirtyGuardResolver = resolve;
  });
  return dirtyGuardPromise;
}

async function resolveDirtyGuard(action: "save" | "discard" | "cancel") {
  if (action === "save") {
    dirtyGuardSaving.value = true;
    const saved = await saveDraft();
    dirtyGuardSaving.value = false;
    if (!saved) return;
    finishDirtyGuard(true);
    return;
  }
  finishDirtyGuard(action === "discard");
}

function finishDirtyGuard(allowed: boolean) {
  dirtyGuardVisible.value = false;
  dirtyGuardResolver?.(allowed);
  dirtyGuardResolver = null;
  dirtyGuardPromise = null;
}

function resetActiveState() {
  activeCanvas.value = null;
  draft.value = null;
  baselineSignature.value = "";
  versions.value = [];
  versionPreview.value = null;
  workspacePath.value = "";
  detailError.value = null;
  detailTargetCanvasId.value = null;
  mutationError.value = null;
}

function setMetadata(key: string, value: unknown) {
  if (!draft.value) return;
  draft.value.metadata = { ...draft.value.metadata, [key]: value };
}

function handleDrawerVisibility(value: boolean) {
  if (!value) void requestClose();
}

function handleBeforeUnload(event: BeforeUnloadEvent) {
  if (!isDirty.value) return;
  event.preventDefault();
  event.returnValue = "";
}

function normalizeCanvas(value: CanvasView): CanvasView {
  const metadata = value.metadata && typeof value.metadata === "object" ? value.metadata : {};
  const candidate = value as CanvasView & { version?: number };
  return {
    ...value,
    currentVersion: Number(value.currentVersion || candidate.version || 1),
    revision: Number(value.revision || 1),
    workspacePath:
      value.workspacePath
      ?? (typeof metadata.workspacePath === "string" ? metadata.workspacePath : null),
    sourceMessageId:
      value.sourceMessageId
      ?? (typeof metadata.sourceMessageId === "string" ? metadata.sourceMessageId : null),
    contentSize: Number(value.contentSize || new TextEncoder().encode(value.content || "").length),
    contentSha256: value.contentSha256 || "",
    metadata,
  };
}

function normalizeVersion(value: CanvasVersionView): CanvasVersionView {
  const candidate = value as CanvasVersionView & { version?: number };
  return {
    ...value,
    versionNo: Number(value.versionNo || candidate.version || 1),
    workspacePath:
      value.workspacePath
      ?? (typeof value.metadata?.workspacePath === "string" ? value.metadata.workspacePath : null),
  };
}

function contentTypeLabel(type: CanvasContentType) {
  return contentTypeOptions.find(option => option.value === type)?.label || type;
}

function formatTime(value: string | null | undefined) {
  return value ? dayjs(value).format("YYYY-MM-DD HH:mm") : "-";
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

onMounted(() => window.addEventListener("beforeunload", handleBeforeUnload));
onBeforeUnmount(() => {
  window.removeEventListener("beforeunload", handleBeforeUnload);
  if (dirtyGuardResolver) finishDirtyGuard(false);
});

defineExpose({
  openLibrary,
  openFromMessage,
  guardTransition,
  conversationChanged,
  isDirty,
});
</script>

<template>
  <NDrawer
    :show="drawerVisible"
    width="min(1440px, calc(100vw - 12px))"
    placement="right"
    :mask-closable="false"
    :close-on-esc="false"
    @update:show="handleDrawerVisibility"
  >
    <NDrawerContent :native-scrollbar="false" class="canvas-drawer">
      <template #header>
        <div class="canvas-header">
          <div class="canvas-heading">
            <SvgIcon icon="lucide:panel-top" />
            <div>
              <strong>Canvas 工作台</strong>
              <span>{{ activeCanvas ? `版本 ${activeVersion}` : draft ? "新建草稿" : "会话内容资产" }}</span>
            </div>
          </div>
          <NSpace :wrap="false" size="small">
            <NTag v-if="isDirty" type="warning" size="small" :bordered="false">未保存</NTag>
            <NRadioGroup v-if="draft" v-model:value="workbenchMode" size="small" class="mode-switch">
              <NRadioButton v-for="option in modeOptions" :key="option.value" :value="option.value">
                {{ option.label }}
              </NRadioButton>
            </NRadioGroup>
            <NTooltip>
              <template #trigger>
                <NButton circle quaternary aria-label="关闭 Canvas" @click="requestClose">
                  <template #icon><SvgIcon icon="lucide:x" /></template>
                </NButton>
              </template>
              关闭 Canvas
            </NTooltip>
          </NSpace>
        </div>
      </template>

      <div class="canvas-layout">
        <aside class="canvas-library" aria-label="Canvas 列表">
          <div class="library-header">
            <div>
              <strong>当前会话</strong>
              <span>{{ canvases.length }} 个 Canvas</span>
            </div>
            <NTooltip>
              <template #trigger>
                <NButton circle size="small" type="primary" aria-label="新建 Canvas" @click="createBlankCanvas">
                  <template #icon><SvgIcon icon="lucide:plus" /></template>
                </NButton>
              </template>
              新建 Canvas
            </NTooltip>
          </div>
          <NSpin :show="listLoading">
            <NAlert v-if="listError" :type="listError.status === 403 ? 'warning' : 'error'" :bordered="false">
              <strong>{{ listError.title }}</strong>
              <div>{{ listError.message }}</div>
              <div class="alert-actions"><NButton size="tiny" @click="loadCanvases(false)">重试</NButton></div>
            </NAlert>
            <div v-else-if="canvases.length" class="canvas-list">
              <button
                v-for="canvas in canvases"
                :key="canvas.id"
                type="button"
                class="canvas-list-item"
                :class="{ active: activeCanvas?.id === canvas.id }"
                @click="requestSelectCanvas(canvas.id)"
              >
                <span class="canvas-list-title">{{ canvas.title }}</span>
                <span class="canvas-list-meta">
                  <NTag size="tiny" :bordered="false">{{ contentTypeLabel(canvas.contentType) }}</NTag>
                  <small>v{{ canvas.currentVersion }}</small>
                </span>
                <small>{{ formatTime(canvas.updatedAt) }}</small>
              </button>
            </div>
            <NEmpty v-else description="当前会话还没有 Canvas">
              <template #extra><NButton size="small" @click="createBlankCanvas">新建 Canvas</NButton></template>
            </NEmpty>
          </NSpin>
        </aside>

        <main class="canvas-main">
          <NSpin :show="detailLoading" class="canvas-detail-spin">
            <NAlert
              v-if="detailError"
              :type="detailError.status === 403 ? 'warning' : 'error'"
              :bordered="false"
              class="canvas-detail-alert"
            >
              <strong>{{ detailError.title }}</strong>
              <div>{{ detailError.message }}</div>
              <div v-if="detailTargetCanvasId" class="alert-actions">
                <NButton size="small" @click="selectCanvas(detailTargetCanvasId, true)">重试</NButton>
              </div>
            </NAlert>

            <template v-else-if="draft">
              <div class="canvas-editor-header">
                <NInput
                  v-model:value="draft.title"
                  maxlength="255"
                  placeholder="Canvas 标题"
                  class="canvas-title-input"
                />
                <NSelect
                  v-model:value="draft.contentType"
                  :options="contentTypeOptions"
                  class="canvas-type-select"
                />
                <NTooltip>
                  <template #trigger>
                    <NButton
                      circle
                      secondary
                      type="primary"
                      :loading="saving"
                      :disabled="!canSave"
                      aria-label="保存 Canvas"
                      @click="saveDraft"
                    >
                      <template #icon><SvgIcon icon="lucide:save" /></template>
                    </NButton>
                  </template>
                  保存 Canvas
                </NTooltip>
                <NTooltip v-if="activeCanvas">
                  <template #trigger>
                    <NButton circle quaternary type="error" aria-label="删除 Canvas" @click="requestDelete">
                      <template #icon><SvgIcon icon="lucide:trash-2" /></template>
                    </NButton>
                  </template>
                  删除 Canvas
                </NTooltip>
              </div>

              <div v-if="draft.contentType === 'code'" class="canvas-format-settings">
                <NInput v-model:value="draftLanguage" size="small" placeholder="代码语言，例如 typescript" />
              </div>
              <div v-else-if="draft.contentType === 'image' || draft.contentType === 'pdf'" class="canvas-format-settings media-settings">
                <NSelect v-model:value="draftEncoding" size="small" :options="mediaEncodingOptions" />
                <NInput v-model:value="draftMimeType" size="small" placeholder="MIME 类型" />
              </div>
              <div v-else-if="draft.contentType === 'compare'" class="canvas-format-settings compare-settings">
                <NInput v-model:value="leftLabel" size="small" placeholder="左侧标签" />
                <NInput v-model:value="rightLabel" size="small" placeholder="右侧标签" />
              </div>

              <NAlert
                v-if="mutationError"
                :type="mutationError.status === 409 ? 'warning' : mutationError.status === 403 ? 'warning' : 'error'"
                :bordered="false"
                class="mutation-alert"
              >
                <strong>{{ mutationError.title }}</strong>
                <div>{{ mutationError.message }}</div>
                <div v-if="mutationError.status === 409 && activeCanvas" class="alert-actions">
                  <NSpace>
                    <NButton size="small" @click="reloadAfterConflict">加载服务端版本</NButton>
                    <NButton size="small" type="primary" @click="saveConflictAsNew">另存为副本</NButton>
                  </NSpace>
                </div>
              </NAlert>

              <div
                class="editor-preview-grid"
                :class="{
                  'only-editor': workbenchMode === 'edit',
                  'only-preview': workbenchMode === 'preview',
                }"
              >
                <section v-show="workbenchMode !== 'preview'" class="canvas-editor-pane">
                  <div class="pane-label"><SvgIcon icon="lucide:square-pen" /><span>内容</span></div>
                  <NInput
                    v-model:value="draft.content"
                    type="textarea"
                    :autosize="false"
                    :placeholder="editorPlaceholder"
                    class="canvas-content-input"
                  />
                </section>
                <section v-show="workbenchMode !== 'edit'" class="canvas-preview-pane">
                  <div class="pane-label">
                    <SvgIcon icon="lucide:eye" />
                    <span>{{ versionPreview ? `历史版本 ${versionPreview.versionNo}` : "预览" }}</span>
                    <NButton v-if="versionPreview" text size="tiny" @click="useVersionAsDraft">以此版本编辑</NButton>
                  </div>
                  <CanvasPreview
                    :content-type="versionPreview?.contentType || draft.contentType"
                    :content="versionPreview?.content || draft.content"
                    :metadata="versionPreview?.metadata || draft.metadata"
                  />
                </section>
              </div>

              <section class="workspace-save-band">
                <div class="workspace-save-copy">
                  <SvgIcon icon="lucide:folder-input" />
                  <div><strong>保存到文件工作区</strong><span>路径必须位于当前用户工作区内</span></div>
                </div>
                <NInput v-model:value="workspacePath" placeholder="canvas/result.md" :disabled="workspaceSaving" />
                <NButton
                  secondary
                  type="primary"
                  :loading="workspaceSaving"
                  :disabled="!activeCanvas && !isDirty"
                  @click="requestWorkspaceSave"
                >
                  <template #icon><SvgIcon icon="lucide:folder-down" /></template>
                  保存文件
                </NButton>
              </section>

              <section v-if="activeCanvas" class="version-history">
                <div class="version-heading">
                  <div><strong>版本历史</strong><span>{{ versions.length }} 个版本</span></div>
                  <NButton circle quaternary size="small" :loading="versionsLoading" aria-label="刷新版本" @click="loadVersions">
                    <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
                  </NButton>
                </div>
                <NSpin :show="versionsLoading">
                  <div v-if="versions.length" class="version-list">
                    <div v-for="version in versions" :key="version.id" class="version-row">
                      <div class="version-number">v{{ version.versionNo }}</div>
                      <div class="version-copy">
                        <strong>{{ version.title }}</strong>
                        <span>{{ contentTypeLabel(version.contentType) }} · {{ formatBytes(version.contentSize) }} · {{ formatTime(version.createdAt) }}</span>
                      </div>
                      <NTag v-if="version.versionNo === activeVersion" type="success" size="tiny" :bordered="false">
                        当前
                      </NTag>
                      <NSpace :wrap="false" size="small">
                        <NButton size="tiny" quaternary @click="previewVersion(version)">
                          预览
                        </NButton>
                        <NButton
                          size="tiny"
                          secondary
                          :disabled="version.versionNo === activeVersion"
                          @click="requestRestore(version)"
                        >
                          恢复
                        </NButton>
                      </NSpace>
                    </div>
                  </div>
                  <NEmpty v-else description="暂无版本记录" />
                </NSpin>
              </section>
            </template>

            <NEmpty v-else description="选择一个 Canvas，或新建内容">
              <template #extra><NButton type="primary" @click="createBlankCanvas">新建 Canvas</NButton></template>
            </NEmpty>
          </NSpin>
        </main>
      </div>
    </NDrawerContent>
  </NDrawer>

  <NModal
    :show="dirtyGuardVisible"
    preset="card"
    title="Canvas 尚未保存"
    :mask-closable="false"
    :close-on-esc="false"
    :closable="false"
    style="width: min(520px, calc(100vw - 32px))"
  >
    <p class="guard-copy">{{ dirtyGuardReason }}前，请处理当前修改。</p>
    <template #footer>
      <NSpace justify="end">
        <NButton :disabled="dirtyGuardSaving" @click="resolveDirtyGuard('cancel')">取消</NButton>
        <NButton :disabled="dirtyGuardSaving" @click="resolveDirtyGuard('discard')">放弃修改</NButton>
        <NButton type="primary" :loading="dirtyGuardSaving" @click="resolveDirtyGuard('save')">保存并继续</NButton>
      </NSpace>
    </template>
  </NModal>

  <NModal
    v-model:show="overwriteVisible"
    preset="card"
    title="工作区文件已存在"
    :mask-closable="!workspaceSaving"
    style="width: min(520px, calc(100vw - 32px))"
  >
    <NAlert type="warning" :bordered="false">
      将使用当前 Canvas 覆盖 <strong>{{ overwritePath }}</strong>。此操作只在你明确确认后执行。
    </NAlert>
    <template #footer>
      <NSpace justify="end">
        <NButton :disabled="workspaceSaving" @click="overwriteVisible = false">取消</NButton>
        <NButton type="warning" :loading="workspaceSaving" @click="confirmOverwrite">确认覆盖</NButton>
      </NSpace>
    </template>
  </NModal>

  <NModal
    v-model:show="restoreVisible"
    preset="card"
    title="恢复 Canvas 版本"
    :mask-closable="!restoring"
    style="width: min(500px, calc(100vw - 32px))"
  >
    <p class="guard-copy">恢复版本 {{ restoreTarget?.versionNo }} 会生成一个新的当前版本，既有历史不会删除。</p>
    <template #footer>
      <NSpace justify="end">
        <NButton :disabled="restoring" @click="restoreVisible = false">取消</NButton>
        <NButton type="primary" :loading="restoring" @click="confirmRestore">确认恢复</NButton>
      </NSpace>
    </template>
  </NModal>

  <NModal
    v-model:show="deleteVisible"
    preset="card"
    title="删除 Canvas"
    :mask-closable="!deleting"
    style="width: min(500px, calc(100vw - 32px))"
  >
    <NAlert type="error" :bordered="false">确认删除“{{ activeCanvas?.title }}”？该 Canvas 将不再出现在会话中。</NAlert>
    <template #footer>
      <NSpace justify="end">
        <NButton :disabled="deleting" @click="deleteVisible = false">取消</NButton>
        <NButton type="error" :loading="deleting" @click="confirmDelete">删除</NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped lang="scss">
.canvas-header,
.canvas-heading,
.library-header,
.canvas-editor-header,
.pane-label,
.workspace-save-band,
.workspace-save-copy,
.version-heading,
.version-row {
  display: flex;
  align-items: center;
}

.canvas-header,
.library-header,
.version-heading {
  justify-content: space-between;
}

.canvas-header {
  width: 100%;
  min-width: 0;
}

.canvas-heading {
  min-width: 0;
  gap: 10px;
}

.canvas-heading > svg {
  width: 22px;
  height: 22px;
  color: var(--n-primary-color);
}

.canvas-heading div,
.library-header div,
.version-heading > div,
.workspace-save-copy div {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.canvas-heading span,
.library-header span,
.version-heading span,
.workspace-save-copy span {
  color: var(--n-text-color-3);
  font-size: 12px;
}

.canvas-layout {
  display: grid;
  height: calc(100dvh - 88px);
  min-height: 560px;
  grid-template-columns: 250px minmax(0, 1fr);
}

.canvas-library {
  min-width: 0;
  padding: 14px 10px;
  overflow-y: auto;
  border-right: 1px solid var(--n-border-color);
}

.library-header {
  padding: 0 4px 12px;
}

.canvas-list {
  display: grid;
  gap: 4px;
}

.canvas-list-item {
  display: grid;
  width: 100%;
  padding: 10px;
  color: inherit;
  text-align: left;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 6px;
  cursor: pointer;
  gap: 5px;
}

.canvas-list-item:hover,
.canvas-list-item.active {
  background: var(--n-color-hover);
}

.canvas-list-item.active {
  border-color: var(--n-primary-color-suppl);
}

.canvas-list-title {
  overflow: hidden;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.canvas-list-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.canvas-list-item > small,
.canvas-list-meta small {
  color: var(--n-text-color-3);
}

.canvas-main,
.canvas-detail-spin {
  min-width: 0;
  min-height: 0;
}

.canvas-main {
  height: 100%;
  overflow-y: auto;
}

.canvas-detail-spin :deep(.n-spin-content) {
  min-height: 100%;
}

.canvas-detail-alert,
.mutation-alert {
  margin: 12px;
}

.alert-actions {
  margin-top: 8px;
}

.canvas-editor-header {
  position: sticky;
  z-index: 3;
  top: 0;
  padding: 10px 14px;
  background: var(--n-color);
  border-bottom: 1px solid var(--n-border-color);
  gap: 8px;
}

.canvas-title-input {
  min-width: 180px;
  flex: 1;
}

.canvas-type-select {
  width: 150px;
}

.canvas-format-settings {
  display: grid;
  padding: 8px 14px;
  background: var(--n-color-modal);
  border-bottom: 1px solid var(--n-border-color);
  grid-template-columns: minmax(180px, 360px);
  gap: 8px;
}

.media-settings,
.compare-settings {
  grid-template-columns: repeat(2, minmax(180px, 280px));
}

.editor-preview-grid {
  display: grid;
  min-height: 520px;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
}

.editor-preview-grid.only-editor,
.editor-preview-grid.only-preview {
  grid-template-columns: minmax(0, 1fr);
}

.canvas-editor-pane,
.canvas-preview-pane {
  min-width: 0;
  min-height: 520px;
}

.canvas-editor-pane {
  display: grid;
  border-right: 1px solid var(--n-border-color);
  grid-template-rows: 38px minmax(0, 1fr);
}

.canvas-preview-pane {
  overflow: auto;
}

.pane-label {
  min-height: 38px;
  padding: 7px 12px;
  color: var(--n-text-color-3);
  background: var(--n-color-modal);
  border-bottom: 1px solid var(--n-border-color);
  gap: 7px;
  font-size: 12px;
  font-weight: 600;
}

.pane-label .n-button {
  margin-left: auto;
}

.canvas-content-input,
.canvas-content-input :deep(.n-input-wrapper),
.canvas-content-input :deep(.n-input__textarea),
.canvas-content-input :deep(.n-input__textarea-el) {
  height: 100%;
  min-height: 480px;
}

.canvas-content-input :deep(.n-input__textarea-el) {
  padding: 14px;
  font: 13px/1.65 ui-monospace, SFMono-Regular, Consolas, monospace;
}

.workspace-save-band {
  display: grid;
  padding: 14px;
  border-top: 1px solid var(--n-border-color);
  border-bottom: 1px solid var(--n-border-color);
  grid-template-columns: minmax(180px, 260px) minmax(220px, 1fr) auto;
  gap: 12px;
}

.workspace-save-copy {
  gap: 9px;
}

.workspace-save-copy > svg {
  flex: 0 0 auto;
  color: var(--n-primary-color);
}

.version-history {
  padding: 16px 14px 28px;
}

.version-list {
  margin-top: 10px;
  border-top: 1px solid var(--n-border-color);
}

.version-row {
  min-width: 0;
  min-height: 54px;
  padding: 8px 4px;
  border-bottom: 1px solid var(--n-border-color);
  gap: 12px;
}

.version-number {
  width: 44px;
  color: var(--n-primary-color);
  font-weight: 700;
}

.version-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
}

.version-copy strong,
.version-copy span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.version-copy span {
  color: var(--n-text-color-3);
  font-size: 12px;
}

.guard-copy {
  margin: 0;
  color: var(--n-text-color-2);
  line-height: 1.7;
}

@media (max-width: 900px) {
  .mode-switch {
    display: none;
  }

  .canvas-layout {
    grid-template-columns: 188px minmax(0, 1fr);
  }

  .editor-preview-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .canvas-editor-pane {
    border-right: 0;
    border-bottom: 1px solid var(--n-border-color);
  }

  .workspace-save-band {
    grid-template-columns: 1fr auto;
  }

  .workspace-save-copy {
    grid-column: 1 / -1;
  }
}

@media (max-width: 620px) {
  .canvas-layout {
    display: block;
    height: calc(100dvh - 74px);
  }

  .canvas-library {
    max-height: 180px;
    border-right: 0;
    border-bottom: 1px solid var(--n-border-color);
  }

  .canvas-list {
    display: flex;
    overflow-x: auto;
  }

  .canvas-list-item {
    min-width: 170px;
  }

  .canvas-editor-header {
    flex-wrap: wrap;
  }

  .canvas-title-input {
    width: 100%;
    flex-basis: 100%;
  }

  .canvas-type-select {
    flex: 1;
  }

  .media-settings,
  .compare-settings,
  .workspace-save-band {
    grid-template-columns: 1fr;
  }

  .workspace-save-copy {
    grid-column: auto;
  }

  .version-row {
    align-items: flex-start;
    flex-wrap: wrap;
  }
}
</style>
