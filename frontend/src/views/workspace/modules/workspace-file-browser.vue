<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  createWorkspaceEntry,
  deleteWorkspaceEntry,
  emptyWorkspaceTrash,
  fetchRecentWorkspaceFiles,
  fetchWorkspaceBrowserPreferences,
  fetchWorkspaceFiles,
  fetchWorkspaceTrash,
  previewWorkspaceFile,
  purgeWorkspaceEntry,
  renameWorkspaceEntry,
  restoreWorkspaceEntry,
  searchWorkspaceFiles,
  updateRecentWorkspaceFiles,
  updateWorkspaceBrowserPreferences,
  uploadWorkspaceFile,
  writeWorkspaceFile
} from '@/service/api';
import type {
  WorkspaceBrowserPreferences,
  WorkspaceFileEntry,
  WorkspaceFilePreview,
  WorkspaceTrashEntry
} from '@/service/api';

const props = withDefaults(defineProps<{ visible?: boolean }>(), { visible: true });

type BrowserMode = 'browse' | 'search' | 'recent' | 'trash';
type CreateKind = 'file' | 'directory';

const loading = ref(false);
const initialized = ref(false);
const currentPath = ref('');
const mode = ref<BrowserMode>('browse');
const entries = ref<WorkspaceFileEntry[]>([]);
const recentEntries = ref<WorkspaceFileEntry[]>([]);
const trashEntries = ref<WorkspaceTrashEntry[]>([]);
const searchQuery = ref('');
const uploadInput = ref<HTMLInputElement | null>(null);
const uploading = ref(false);

const selectedEntry = ref<WorkspaceFileEntry | null>(null);
const preview = ref<WorkspaceFilePreview | null>(null);
const previewLoading = ref(false);
const editorContent = ref('');
const saving = ref(false);

const createVisible = ref(false);
const createSubmitting = ref(false);
const createForm = reactive<{ name: string; kind: CreateKind }>({ name: '', kind: 'file' });
const renameVisible = ref(false);
const renameSubmitting = ref(false);
const renameForm = reactive({ path: '', name: '' });

const preferences = reactive<Required<Pick<WorkspaceBrowserPreferences, 'view' | 'sort' | 'order'>> & WorkspaceBrowserPreferences>({
  view: 'list',
  sort: 'name',
  order: 'asc',
  include_subdirs: true,
  type_filter: 'all'
});

const sortOptions = [
  { label: '名称', value: 'name' },
  { label: '修改时间', value: 'mtime' },
  { label: '大小', value: 'size' }
];
const typeOptions = [
  { label: '全部类型', value: 'all' },
  { label: '文件夹', value: 'folder' },
  { label: '文档', value: 'document' },
  { label: '图片', value: 'image' },
  { label: '代码', value: 'code' },
  { label: '数据', value: 'data' }
];

const pathSegments = computed(() => currentPath.value.split('/').filter(Boolean));
const parentPath = computed(() => pathSegments.value.slice(0, -1).join('/'));
const editorDirty = computed(() => Boolean(preview.value) && editorContent.value !== preview.value?.content);
const textPreviewable = computed(() => isTextEntry(selectedEntry.value));
const activeEntries = computed(() => mode.value === 'recent' ? recentEntries.value : entries.value);
const visibleEntries = computed(() => {
  const filtered = activeEntries.value.filter(matchesTypeFilter);
  const direction = preferences.order === 'asc' ? 1 : -1;
  return [...filtered].sort((left, right) => {
    if (left.is_dir !== right.is_dir) return left.is_dir ? -1 : 1;
    if (preferences.sort === 'mtime') return (Number(left.mtime) - Number(right.mtime)) * direction;
    if (preferences.sort === 'size') return (Number(left.size) - Number(right.size)) * direction;
    return left.name.localeCompare(right.name, 'zh-CN', { numeric: true }) * direction;
  });
});

function errorMessage(error: unknown, fallback: string) {
  if (error instanceof Error && error.message) return error.message;
  return fallback;
}

function joinPath(parent: string, child: string) {
  return parent ? `${parent}/${child}` : child;
}

function breadcrumbPath(index: number) {
  return pathSegments.value.slice(0, index + 1).join('/');
}

function extension(entry: WorkspaceFileEntry | null) {
  if (!entry || entry.is_dir) return '';
  const index = entry.name.lastIndexOf('.');
  return index < 0 ? '' : entry.name.slice(index + 1).toLowerCase();
}

function isTextEntry(entry: WorkspaceFileEntry | null) {
  if (!entry || entry.is_dir) return false;
  if (entry.mime_type?.startsWith('text/')) return true;
  return new Set([
    'txt', 'md', 'csv', 'json', 'sql', 'xml', 'html', 'css', 'yaml', 'yml', 'ini', 'conf', 'log',
    'java', 'kt', 'py', 'js', 'jsx', 'ts', 'tsx', 'vue', 'sh', 'ps1', 'go', 'rs', 'c', 'h', 'cpp'
  ]).has(extension(entry));
}

function fileIcon(entry: WorkspaceFileEntry) {
  if (entry.is_dir) return 'lucide:folder';
  const ext = extension(entry);
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg'].includes(ext)) return 'lucide:image';
  if (['csv', 'xlsx', 'xls'].includes(ext)) return 'lucide:table-2';
  if (['java', 'kt', 'py', 'js', 'jsx', 'ts', 'tsx', 'vue', 'sh', 'go', 'rs', 'c', 'cpp'].includes(ext)) {
    return 'lucide:file-code-2';
  }
  if (['zip', 'gz', 'tar', '7z', 'rar'].includes(ext)) return 'lucide:file-archive';
  return 'lucide:file-text';
}

function matchesTypeFilter(entry: WorkspaceFileEntry) {
  const filter = preferences.type_filter || 'all';
  if (filter === 'all') return true;
  if (filter === 'folder') return entry.is_dir;
  if (entry.is_dir) return false;
  const ext = extension(entry);
  if (filter === 'image') return ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg'].includes(ext);
  if (filter === 'code') return ['java', 'kt', 'py', 'js', 'jsx', 'ts', 'tsx', 'vue', 'sh', 'go', 'rs', 'c', 'cpp'].includes(ext);
  if (filter === 'data') return ['csv', 'json', 'sql', 'xlsx', 'xls', 'parquet'].includes(ext);
  return ['txt', 'md', 'pdf', 'doc', 'docx', 'html', 'xml', 'yaml', 'yml'].includes(ext);
}

function formatBytes(value: number) {
  const bytes = Number(value) || 0;
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function formatTimestamp(value: number | string) {
  const numeric = Number(value);
  const date = Number.isFinite(numeric)
    ? new Date(numeric < 10_000_000_000 ? numeric * 1000 : numeric)
    : new Date(value);
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN', { hour12: false });
}

function resetPreview() {
  selectedEntry.value = null;
  preview.value = null;
  editorContent.value = '';
}

async function initialize() {
  if (initialized.value) {
    await refreshCurrent();
    return;
  }
  loading.value = true;
  try {
    const [prefsResult, recentResult] = await Promise.all([
      fetchWorkspaceBrowserPreferences(),
      fetchRecentWorkspaceFiles(undefined, 20)
    ]);
    if (!prefsResult.error) Object.assign(preferences, prefsResult.data || {});
    if (!recentResult.error) recentEntries.value = recentResult.data?.items || [];
    initialized.value = true;
    await loadDirectory('');
  } finally {
    loading.value = false;
  }
}

async function loadDirectory(path: string) {
  loading.value = true;
  try {
    const result = await fetchWorkspaceFiles(path);
    if (result.error) return;
    currentPath.value = path;
    entries.value = Array.isArray(result.data) ? result.data : [];
    mode.value = 'browse';
    resetPreview();
  } finally {
    loading.value = false;
  }
}

async function refreshCurrent() {
  if (mode.value === 'trash') return openTrash();
  if (mode.value === 'recent') return openRecent();
  if (mode.value === 'search' && searchQuery.value.trim()) return runSearch();
  return loadDirectory(currentPath.value);
}

async function runSearch() {
  const query = searchQuery.value.trim();
  if (!query) {
    await loadDirectory(currentPath.value);
    return;
  }
  loading.value = true;
  try {
    const result = await searchWorkspaceFiles(query, currentPath.value);
    if (result.error) return;
    entries.value = Array.isArray(result.data) ? result.data : [];
    mode.value = 'search';
    resetPreview();
  } finally {
    loading.value = false;
  }
}

async function openRecent() {
  loading.value = true;
  try {
    const result = await fetchRecentWorkspaceFiles(undefined, 20);
    if (result.error) return;
    recentEntries.value = result.data?.items || [];
    mode.value = 'recent';
    resetPreview();
  } finally {
    loading.value = false;
  }
}

async function openTrash() {
  loading.value = true;
  try {
    const result = await fetchWorkspaceTrash();
    if (result.error) return;
    trashEntries.value = result.data;
    mode.value = 'trash';
    resetPreview();
  } finally {
    loading.value = false;
  }
}

async function rememberEntry(entry: WorkspaceFileEntry) {
  const next = [entry, ...recentEntries.value.filter(item => item.path !== entry.path)].slice(0, 20);
  recentEntries.value = next;
  const result = await updateRecentWorkspaceFiles(next);
  if (!result.error) recentEntries.value = result.data.items;
}

async function openEntry(entry: WorkspaceFileEntry) {
  if (entry.is_dir) {
    await loadDirectory(entry.path);
    return;
  }
  selectedEntry.value = entry;
  preview.value = null;
  editorContent.value = '';
  if (!isTextEntry(entry)) {
    await rememberEntry(entry);
    return;
  }
  previewLoading.value = true;
  try {
    const result = await previewWorkspaceFile(entry.path);
    if (result.error) return;
    preview.value = result.data;
    editorContent.value = result.data.content;
    await rememberEntry(entry);
  } finally {
    previewLoading.value = false;
  }
}

async function saveFile() {
  if (!selectedEntry.value || !editorDirty.value) return;
  saving.value = true;
  try {
    const result = await writeWorkspaceFile(selectedEntry.value.path, editorContent.value);
    if (result.error) return;
    selectedEntry.value = result.data;
    preview.value = {
      path: result.data.path,
      content: editorContent.value,
      size: new Blob([editorContent.value]).size,
      mime_type: result.data.mime_type || 'text/plain'
    };
    window.$message?.success('文件已保存');
    await refreshEntriesWithoutReset();
  } finally {
    saving.value = false;
  }
}

async function refreshEntriesWithoutReset() {
  const result = await fetchWorkspaceFiles(currentPath.value);
  if (!result.error && Array.isArray(result.data)) entries.value = result.data;
}

function openCreate(kind: CreateKind) {
  createForm.name = '';
  createForm.kind = kind;
  createVisible.value = true;
}

async function submitCreate() {
  const name = createForm.name.trim();
  if (!name) {
    window.$message?.warning('请输入名称');
    return;
  }
  createSubmitting.value = true;
  try {
    const result = await createWorkspaceEntry(currentPath.value, name, createForm.kind);
    if (result.error) return;
    createVisible.value = false;
    window.$message?.success(createForm.kind === 'directory' ? '文件夹已创建' : '文件已创建');
    await refreshEntriesWithoutReset();
    if (createForm.kind === 'file') await openEntry(result.data);
  } finally {
    createSubmitting.value = false;
  }
}

function openRename(entry: WorkspaceFileEntry) {
  renameForm.path = entry.path;
  renameForm.name = entry.name;
  renameVisible.value = true;
}

async function submitRename() {
  const name = renameForm.name.trim();
  if (!name) {
    window.$message?.warning('请输入新名称');
    return;
  }
  renameSubmitting.value = true;
  try {
    const result = await renameWorkspaceEntry(renameForm.path, name);
    if (result.error) return;
    renameVisible.value = false;
    if (selectedEntry.value?.path === renameForm.path) {
      selectedEntry.value = result.data;
      if (preview.value) preview.value = { ...preview.value, path: result.data.path };
    }
    window.$message?.success('名称已更新');
    await refreshEntriesWithoutReset();
  } finally {
    renameSubmitting.value = false;
  }
}

function confirmDelete(entry: WorkspaceFileEntry) {
  window.$dialog?.warning({
    title: '移至回收站',
    content: `确认将“${entry.name}”移至回收站？`,
    positiveText: '移至回收站',
    negativeText: '取消',
    onPositiveClick: async () => {
      const result = await deleteWorkspaceEntry(entry.path);
      if (result.error) return false;
      if (selectedEntry.value?.path === entry.path) resetPreview();
      window.$message?.success('已移至回收站');
      await refreshEntriesWithoutReset();
      return true;
    }
  });
}

async function restoreTrash(entry: WorkspaceTrashEntry) {
  const result = await restoreWorkspaceEntry(entry.id);
  if (result.error) return;
  window.$message?.success('文件已恢复');
  await openTrash();
}

function confirmPurge(entry: WorkspaceTrashEntry) {
  window.$dialog?.error({
    title: '永久删除',
    content: `“${entry.original_path}”删除后无法恢复。`,
    positiveText: '永久删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const result = await purgeWorkspaceEntry(entry.id);
      if (result.error) return false;
      window.$message?.success('已永久删除');
      await openTrash();
      return true;
    }
  });
}

function confirmEmptyTrash() {
  if (!trashEntries.value.length) return;
  window.$dialog?.error({
    title: '清空回收站',
    content: `将永久删除 ${trashEntries.value.length} 个条目。`,
    positiveText: '清空',
    negativeText: '取消',
    onPositiveClick: async () => {
      const result = await emptyWorkspaceTrash();
      if (result.error) return false;
      window.$message?.success(`已清理 ${result.data.purged_count} 个条目`);
      await openTrash();
      return true;
    }
  });
}

function chooseUpload() {
  uploadInput.value?.click();
}

async function handleUpload(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  if (file.size > 10 * 1024 * 1024) {
    window.$message?.warning('单个文件不能超过 10MB');
    return;
  }
  uploading.value = true;
  try {
    const result = await uploadWorkspaceFile(currentPath.value, file);
    if (result.error) return;
    window.$message?.success('文件已上传');
    await refreshEntriesWithoutReset();
  } finally {
    uploading.value = false;
  }
}

async function savePreferences() {
  const result = await updateWorkspaceBrowserPreferences({ ...preferences });
  if (result.error) {
    window.$message?.error(errorMessage(result.error, '保存浏览偏好失败'));
  }
}

async function setView(view: 'list' | 'grid') {
  preferences.view = view;
  await savePreferences();
}

async function toggleSortOrder() {
  preferences.order = preferences.order === 'asc' ? 'desc' : 'asc';
  await savePreferences();
}

watch(
  () => props.visible,
  visible => {
    if (visible) void initialize();
  },
  { immediate: true }
);
</script>

<template>
  <div class="workspace-browser">
    <div class="browser-toolbar">
      <div class="path-tools">
        <NTooltip>
          <template #trigger>
            <NButton circle quaternary :disabled="!currentPath || mode !== 'browse'" aria-label="上级目录" @click="loadDirectory(parentPath)">
              <template #icon><SvgIcon icon="lucide:arrow-up" /></template>
            </NButton>
          </template>
          上级目录
        </NTooltip>
        <NTooltip>
          <template #trigger>
            <NButton circle quaternary :loading="loading" aria-label="刷新" @click="refreshCurrent">
              <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
            </NButton>
          </template>
          刷新
        </NTooltip>
        <div class="breadcrumbs" aria-label="当前目录">
          <button type="button" :class="{ active: !currentPath }" @click="loadDirectory('')">工作区</button>
          <template v-for="(segment, index) in pathSegments" :key="breadcrumbPath(index)">
            <SvgIcon icon="lucide:chevron-right" />
            <button type="button" :class="{ active: index === pathSegments.length - 1 }" @click="loadDirectory(breadcrumbPath(index))">
              {{ segment }}
            </button>
          </template>
          <NTag v-if="mode !== 'browse'" size="small" :bordered="false">
            {{ mode === 'search' ? '搜索结果' : mode === 'recent' ? '最近文件' : '回收站' }}
          </NTag>
        </div>
      </div>

      <div class="browser-search">
        <NInput v-model:value="searchQuery" clearable placeholder="搜索文件" @keyup.enter="runSearch">
          <template #prefix><SvgIcon icon="lucide:search" /></template>
        </NInput>
      </div>

      <NSpace :wrap="false" size="small" class="toolbar-actions">
        <NTooltip>
          <template #trigger>
            <NButton circle quaternary :type="mode === 'recent' ? 'primary' : 'default'" aria-label="最近文件" @click="openRecent">
              <template #icon><SvgIcon icon="lucide:clock-3" /></template>
            </NButton>
          </template>
          最近文件
        </NTooltip>
        <NTooltip>
          <template #trigger>
            <NButton circle quaternary :type="mode === 'trash' ? 'primary' : 'default'" aria-label="回收站" @click="openTrash">
              <template #icon><SvgIcon icon="lucide:trash-2" /></template>
            </NButton>
          </template>
          回收站
        </NTooltip>
        <template v-if="mode !== 'trash'">
          <NTooltip>
            <template #trigger>
              <NButton circle quaternary aria-label="新建文件" @click="openCreate('file')">
                <template #icon><SvgIcon icon="lucide:file-plus-2" /></template>
              </NButton>
            </template>
            新建文件
          </NTooltip>
          <NTooltip>
            <template #trigger>
              <NButton circle quaternary aria-label="新建文件夹" @click="openCreate('directory')">
                <template #icon><SvgIcon icon="lucide:folder-plus" /></template>
              </NButton>
            </template>
            新建文件夹
          </NTooltip>
          <NTooltip>
            <template #trigger>
              <NButton circle quaternary :loading="uploading" aria-label="上传文件" @click="chooseUpload">
                <template #icon><SvgIcon icon="lucide:upload" /></template>
              </NButton>
            </template>
            上传文件
          </NTooltip>
        </template>
      </NSpace>
    </div>

    <div class="browser-options">
      <NSelect v-model:value="preferences.type_filter" :options="typeOptions" size="small" class="type-select" @update:value="savePreferences" />
      <NSelect v-model:value="preferences.sort" :options="sortOptions" size="small" class="sort-select" @update:value="savePreferences" />
      <NTooltip>
        <template #trigger>
          <NButton circle quaternary size="small" :aria-label="preferences.order === 'asc' ? '升序' : '降序'" @click="toggleSortOrder">
            <template #icon><SvgIcon :icon="preferences.order === 'asc' ? 'lucide:arrow-up-narrow-wide' : 'lucide:arrow-down-wide-narrow'" /></template>
          </NButton>
        </template>
        {{ preferences.order === 'asc' ? '升序' : '降序' }}
      </NTooltip>
      <div class="view-switch">
        <NTooltip>
          <template #trigger>
            <NButton circle quaternary size="small" :type="preferences.view === 'list' ? 'primary' : 'default'" aria-label="列表视图" @click="setView('list')">
              <template #icon><SvgIcon icon="lucide:list" /></template>
            </NButton>
          </template>
          列表视图
        </NTooltip>
        <NTooltip>
          <template #trigger>
            <NButton circle quaternary size="small" :type="preferences.view === 'grid' ? 'primary' : 'default'" aria-label="网格视图" @click="setView('grid')">
              <template #icon><SvgIcon icon="lucide:grid-2x2" /></template>
            </NButton>
          </template>
          网格视图
        </NTooltip>
      </div>
      <span class="entry-count">{{ mode === 'trash' ? trashEntries.length : visibleEntries.length }} 项</span>
      <NButton v-if="mode === 'trash'" size="small" tertiary type="error" :disabled="!trashEntries.length" @click="confirmEmptyTrash">
        <template #icon><SvgIcon icon="lucide:trash" /></template>
        清空回收站
      </NButton>
    </div>

    <div class="browser-body">
      <NSpin :show="loading" class="entries-pane">
        <template v-if="mode === 'trash'">
          <div v-if="trashEntries.length" class="trash-list">
            <div v-for="entry in trashEntries" :key="entry.id" class="trash-row">
              <SvgIcon :icon="entry.is_dir ? 'lucide:folder' : 'lucide:file-text'" class="entry-icon" />
              <div class="entry-copy">
                <strong>{{ entry.original_path }}</strong>
                <span>{{ formatTimestamp(entry.deleted_at) }}</span>
              </div>
              <NTooltip>
                <template #trigger>
                  <NButton circle quaternary size="small" aria-label="恢复" @click="restoreTrash(entry)">
                    <template #icon><SvgIcon icon="lucide:rotate-ccw" /></template>
                  </NButton>
                </template>
                恢复
              </NTooltip>
              <NTooltip>
                <template #trigger>
                  <NButton circle quaternary size="small" type="error" aria-label="永久删除" @click="confirmPurge(entry)">
                    <template #icon><SvgIcon icon="lucide:trash-2" /></template>
                  </NButton>
                </template>
                永久删除
              </NTooltip>
            </div>
          </div>
          <NEmpty v-else description="回收站为空" />
        </template>

        <template v-else-if="preferences.view === 'list'">
          <div v-if="visibleEntries.length" class="entry-list" role="list">
            <div v-for="entry in visibleEntries" :key="entry.path" class="entry-row" :class="{ selected: selectedEntry?.path === entry.path }" role="listitem">
              <button type="button" class="entry-open" @click="openEntry(entry)">
                <SvgIcon :icon="fileIcon(entry)" class="entry-icon" />
                <span class="entry-name">{{ entry.name }}</span>
                <span class="entry-size">{{ entry.is_dir ? '-' : formatBytes(entry.size) }}</span>
                <span class="entry-time">{{ formatTimestamp(entry.mtime) }}</span>
              </button>
              <NTooltip>
                <template #trigger>
                  <NButton circle quaternary size="small" aria-label="重命名" @click="openRename(entry)">
                    <template #icon><SvgIcon icon="lucide:pencil" /></template>
                  </NButton>
                </template>
                重命名
              </NTooltip>
              <NTooltip>
                <template #trigger>
                  <NButton circle quaternary size="small" type="error" aria-label="删除" @click="confirmDelete(entry)">
                    <template #icon><SvgIcon icon="lucide:trash-2" /></template>
                  </NButton>
                </template>
                移至回收站
              </NTooltip>
            </div>
          </div>
          <NEmpty v-else :description="mode === 'search' ? '没有匹配的文件' : '当前目录为空'" />
        </template>

        <template v-else>
          <div v-if="visibleEntries.length" class="entry-grid" role="list">
            <div v-for="entry in visibleEntries" :key="entry.path" class="entry-tile" :class="{ selected: selectedEntry?.path === entry.path }" role="listitem">
              <button type="button" class="tile-open" @click="openEntry(entry)">
                <SvgIcon :icon="fileIcon(entry)" class="tile-icon" />
                <strong>{{ entry.name }}</strong>
                <span>{{ entry.is_dir ? '文件夹' : formatBytes(entry.size) }}</span>
              </button>
              <div class="tile-actions">
                <NButton circle quaternary size="tiny" aria-label="重命名" @click="openRename(entry)">
                  <template #icon><SvgIcon icon="lucide:pencil" /></template>
                </NButton>
                <NButton circle quaternary size="tiny" type="error" aria-label="删除" @click="confirmDelete(entry)">
                  <template #icon><SvgIcon icon="lucide:trash-2" /></template>
                </NButton>
              </div>
            </div>
          </div>
          <NEmpty v-else :description="mode === 'search' ? '没有匹配的文件' : '当前目录为空'" />
        </template>
      </NSpin>

      <section class="preview-pane" aria-label="文件预览">
        <template v-if="selectedEntry">
          <header class="preview-header">
            <div class="preview-title">
              <SvgIcon :icon="fileIcon(selectedEntry)" />
              <div>
                <strong>{{ selectedEntry.name }}</strong>
                <span>{{ selectedEntry.path }}</span>
              </div>
            </div>
            <NTooltip v-if="textPreviewable">
              <template #trigger>
                <NButton circle type="primary" :loading="saving" :disabled="!editorDirty" aria-label="保存文件" @click="saveFile">
                  <template #icon><SvgIcon icon="lucide:save" /></template>
                </NButton>
              </template>
              保存文件
            </NTooltip>
          </header>
          <NSpin :show="previewLoading" class="preview-content">
            <NInput
              v-if="textPreviewable && preview"
              v-model:value="editorContent"
              type="textarea"
              :autosize="false"
              class="file-editor"
              placeholder="文件内容"
            />
            <NEmpty v-else-if="!textPreviewable" description="该文件不支持文本预览" />
          </NSpin>
        </template>
        <NEmpty v-else description="选择文件后查看内容" />
      </section>
    </div>

    <input ref="uploadInput" class="hidden-file-input" type="file" @change="handleUpload" />

    <NModal
      v-model:show="createVisible"
      preset="card"
      :title="createForm.kind === 'directory' ? '新建文件夹' : '新建文件'"
      class="entry-modal"
      style="width: min(440px, calc(100vw - 32px))"
    >
      <NForm label-placement="top" @submit.prevent="submitCreate">
        <NFormItem label="类型">
          <NRadioGroup v-model:value="createForm.kind">
            <NRadioButton value="file">文件</NRadioButton>
            <NRadioButton value="directory">文件夹</NRadioButton>
          </NRadioGroup>
        </NFormItem>
        <NFormItem label="名称">
          <NInput v-model:value="createForm.name" maxlength="200" autofocus @keyup.enter="submitCreate" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton :disabled="createSubmitting" @click="createVisible = false">取消</NButton>
          <NButton type="primary" :loading="createSubmitting" @click="submitCreate">创建</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal
      v-model:show="renameVisible"
      preset="card"
      title="重命名"
      class="entry-modal"
      style="width: min(440px, calc(100vw - 32px))"
    >
      <NForm label-placement="top" @submit.prevent="submitRename">
        <NFormItem label="新名称">
          <NInput v-model:value="renameForm.name" maxlength="200" autofocus @keyup.enter="submitRename" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton :disabled="renameSubmitting" @click="renameVisible = false">取消</NButton>
          <NButton type="primary" :loading="renameSubmitting" @click="submitRename">保存</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped lang="scss">
.workspace-browser {
  display: flex;
  min-width: 0;
  height: min(760px, calc(100dvh - 110px));
  flex-direction: column;
}

.browser-toolbar,
.browser-options,
.path-tools,
.breadcrumbs,
.view-switch,
.entry-row,
.trash-row,
.preview-header,
.preview-title {
  display: flex;
  align-items: center;
}

.browser-toolbar {
  min-height: 48px;
  gap: 12px;
  border-bottom: 1px solid var(--n-border-color);
}

.path-tools {
  min-width: 0;
  flex: 1;
}

.breadcrumbs {
  min-width: 0;
  gap: 3px;
  overflow: hidden;
}

.breadcrumbs button {
  min-width: 0;
  overflow: hidden;
  padding: 3px 5px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--n-text-color-3);
  cursor: pointer;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.breadcrumbs button.active {
  color: var(--n-text-color-1);
  font-weight: 600;
}

.browser-search {
  width: min(280px, 30vw);
}

.browser-options {
  min-height: 42px;
  gap: 8px;
  border-bottom: 1px solid var(--n-border-color);
}

.type-select {
  width: 120px;
}

.sort-select {
  width: 110px;
}

.view-switch {
  gap: 2px;
}

.entry-count {
  margin-left: auto;
  color: var(--n-text-color-3);
  font-size: 12px;
}

.browser-body {
  display: grid;
  min-height: 0;
  flex: 1;
  grid-template-columns: minmax(380px, 1.15fr) minmax(320px, 0.85fr);
}

.entries-pane,
.preview-pane {
  min-width: 0;
  min-height: 0;
}

.entries-pane {
  overflow: auto;
  padding: 8px 8px 8px 0;
  border-right: 1px solid var(--n-border-color);
}

.entry-list,
.trash-list {
  display: flex;
  gap: 2px;
  flex-direction: column;
}

.entry-row,
.trash-row {
  min-height: 42px;
  gap: 4px;
  padding: 2px 4px;
  border-radius: 6px;
}

.entry-row:hover,
.entry-row.selected,
.trash-row:hover {
  background: rgba(127, 127, 127, 0.09);
}

.entry-open {
  display: grid;
  min-width: 0;
  padding: 7px 4px;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-align: left;
  flex: 1;
  grid-template-columns: 22px minmax(100px, 1fr) 76px 138px;
  align-items: center;
  gap: 8px;
}

.entry-icon {
  width: 18px;
  height: 18px;
  color: #3f7d62;
}

.entry-name,
.entry-copy strong,
.preview-title strong,
.tile-open strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.entry-size,
.entry-time,
.entry-copy span,
.preview-title span,
.tile-open span {
  color: var(--n-text-color-3);
  font-size: 12px;
}

.entry-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(130px, 1fr));
  gap: 8px;
}

.entry-tile {
  position: relative;
  min-width: 0;
  aspect-ratio: 1.25;
  border: 1px solid var(--n-border-color);
  border-radius: 6px;
  background: var(--n-color);
}

.entry-tile.selected,
.entry-tile:hover {
  border-color: #3f7d62;
}

.tile-open {
  display: flex;
  width: 100%;
  height: 100%;
  min-width: 0;
  padding: 18px 12px 10px;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.tile-icon {
  width: 34px;
  height: 34px;
  color: #3f7d62;
}

.tile-actions {
  position: absolute;
  top: 3px;
  right: 3px;
  display: flex;
}

.trash-row {
  padding: 6px 8px;
}

.entry-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
}

.preview-pane {
  display: flex;
  padding: 8px 0 0 12px;
  overflow: hidden;
  flex-direction: column;
}

.preview-header {
  min-height: 44px;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid var(--n-border-color);
}

.preview-title {
  min-width: 0;
  gap: 8px;
}

.preview-title > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.preview-content {
  min-height: 0;
  padding-top: 8px;
  flex: 1;
}

.file-editor,
.file-editor :deep(.n-input-wrapper),
.file-editor :deep(.n-input__textarea),
.file-editor :deep(.n-input__textarea-el) {
  height: 100%;
  min-height: 0;
}

.file-editor :deep(.n-input__textarea-el) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  line-height: 1.55;
}

.hidden-file-input {
  display: none;
}

@media (max-width: 900px) {
  .workspace-browser {
    height: calc(100dvh - 86px);
  }

  .browser-toolbar {
    padding: 4px 0;
    flex-wrap: wrap;
  }

  .path-tools {
    width: 100%;
    order: 1;
  }

  .browser-search {
    width: auto;
    min-width: 0;
    flex: 1;
    order: 2;
  }

  .toolbar-actions {
    order: 3;
  }

  .browser-body {
    display: flex;
    overflow: auto;
    flex-direction: column;
  }

  .entries-pane {
    min-height: 300px;
    border-right: 0;
    border-bottom: 1px solid var(--n-border-color);
  }

  .preview-pane {
    min-height: 320px;
    padding-left: 0;
  }

  .entry-open {
    grid-template-columns: 22px minmax(100px, 1fr) 68px;
  }

  .entry-time {
    display: none;
  }
}

@media (max-width: 560px) {
  .type-select,
  .sort-select {
    width: 105px;
  }

  .browser-options {
    overflow-x: auto;
  }

  .entry-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
