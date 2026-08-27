<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import dayjs from 'dayjs';
import type { DataTableColumns, FormInst, FormRules, SelectOption, TreeOption, UploadCustomRequestOptions } from 'naive-ui';
import { NButton, NTree, NSpace, NTag, useDialog } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  createKnowledgeBase,
  createKnowledgeDirectory,
  deleteKnowledgeBase,
  deleteKnowledgeDirectory,
  deleteKnowledgeDocument,
  downloadKnowledgeDocument,
  fetchKnowledgeBases,
  fetchKnowledgeChunks,
  fetchKnowledgeDirectoryAcls,
  fetchKnowledgeDocuments,
  fetchKnowledgeTree,
  fetchKnowledgeMetrics,
  fetchModels,
  parseKnowledgeDocument,
  retrieveKnowledge,
  putKnowledgeDirectoryAcl,
  revokeKnowledgeDirectoryAcl,
  updateKnowledgeDirectory,
  updateKnowledgeDocument,
  updateKnowledgeBase,
  uploadKnowledgeDocument
} from '@/service/api';
import type {
  KnowledgeBaseView,
  KnowledgeDirectoryView,
  KnowledgeChunkView,
  KnowledgeDirectoryAclView,
  KnowledgeDocumentView,
  KnowledgeMetricsView,
  KnowledgeRetrievalView,
  ModelView,
  SaveKnowledgeBasePayload,
  UpdateKnowledgeDocumentPayload
} from '@/service/api';

const dialog = useDialog();
const loading = ref(false);
const knowledgeBases = ref<KnowledgeBaseView[]>([]);
const embeddingModels = ref<ModelView[]>([]);
const search = ref('');
const includeInactive = ref(false);

const editVisible = ref(false);
const editSubmitting = ref(false);
const editFormRef = ref<FormInst | null>(null);
const editingBase = ref<KnowledgeBaseView | null>(null);
const editForm = reactive({
  knowledgeKey: '',
  name: '',
  description: '',
  visibility: 'enterprise_shared' as KnowledgeBaseView['visibility'],
  status: 'active' as KnowledgeBaseView['status'],
  providerType: 'postgres_pgvector',
  parserType: 'tika_auto',
  chunkSize: 1000,
  chunkOverlap: 100,
  embeddingEnabled: false,
  embeddingModelId: null as string | null,
  embeddingDimension: 1536,
  topK: 6,
  similarityThreshold: 0.2,
  vectorWeight: 0.7
});
const editRules: FormRules = {
  knowledgeKey: [
    { required: true, message: '请输入稳定标识', trigger: ['input', 'blur'] },
    { pattern: /^[a-z][a-z0-9._-]{0,127}$/, message: '仅支持小写字母开头及数字、点、下划线、短横线', trigger: ['input', 'blur'] }
  ],
  name: [{ required: true, message: '请输入知识库名称', trigger: ['input', 'blur'] }]
};

const documentVisible = ref(false);
const documentLoading = ref(false);
const activeBase = ref<KnowledgeBaseView | null>(null);
const documents = ref<KnowledgeDocumentView[]>([]);
const directories = ref<KnowledgeDirectoryView[]>([]);
const directoryError = ref('');
const documentSearch = ref('');
const allDirectoryKey = '__all__';
const rootDirectoryKey = '__root__';
const selectedDirectoryKeys = ref<string[]>([allDirectoryKey]);
const expandedDirectoryKeys = ref<string[]>([]);
const directoryVisible = ref(false);
const directorySubmitting = ref(false);
const directoryFormRef = ref<FormInst | null>(null);
const editingDirectory = ref<KnowledgeDirectoryView | null>(null);
const directoryForm = reactive({ name: '', parentKey: rootDirectoryKey });
const directoryRules: FormRules = {
  name: [{ required: true, message: '请输入目录名称', trigger: ['input', 'blur'] }]
};
const aclVisible = ref(false);
const aclLoading = ref(false);
const aclSubmitting = ref(false);
const aclRows = ref<KnowledgeDirectoryAclView[]>([]);
const aclTargetDirectoryId = ref<string | null>(null);
const aclTargetLabel = ref('');
const aclForm = reactive({
  userId: null as number | null,
  permission: 'read' as KnowledgeDirectoryAclView['permission'],
  effect: 'allow' as KnowledgeDirectoryAclView['effect'],
  inheritChildren: true
});
const documentEditVisible = ref(false);
const documentEditSubmitting = ref(false);
const documentEditFormRef = ref<FormInst | null>(null);
const editingDocument = ref<KnowledgeDocumentView | null>(null);
const documentEditForm = reactive({
  name: '',
  directoryKey: rootDirectoryKey,
  tags: [] as string[],
  remark: ''
});
const documentEditRules: FormRules = {
  name: [{ required: true, message: '请输入文档名称', trigger: ['input', 'blur'] }]
};
const documentDownloadingId = ref<string | null>(null);
const chunkVisible = ref(false);
const chunkLoading = ref(false);
const activeDocument = ref<KnowledgeDocumentView | null>(null);
const chunks = ref<KnowledgeChunkView[]>([]);
const chunkOffset = ref(0);
const chunkLimit = 20;
const chunkHasNext = ref(false);

const directoryMap = computed(() => new Map(directories.value.map(directory => [directory.id, directory])));
const selectedDirectoryId = computed<string | null>(() => {
  const key = selectedDirectoryKeys.value[0];
  return key && ![allDirectoryKey, rootDirectoryKey].includes(key) ? key : null;
});
const selectedDirectory = computed(() => {
  const id = selectedDirectoryId.value;
  return id ? directoryMap.value.get(id) || null : null;
});
const directoryTreeOptions = computed<TreeOption[]>(() => {
  const rootCount = documents.value.filter(document => !document.directoryId).length;
  return [
    { key: allDirectoryKey, label: `全部文档 (${documents.value.length})` },
    { key: rootDirectoryKey, label: `未分类 (${rootCount})` },
    ...buildDirectoryOptions(directories.value)
  ];
});
const directoryParentOptions = computed<SelectOption[]>(() => {
  const currentId = editingDirectory.value?.id;
  return [
    { label: '根目录', value: rootDirectoryKey },
    ...directories.value
      .filter(directory => !currentId || (directory.id !== currentId && !hasDirectoryAncestor(directory, currentId)))
      .map(directory => ({ label: directoryPath(directory.id), value: directory.id }))
  ];
});
const documentDirectoryOptions = computed<SelectOption[]>(() => [
  { label: '未分类（根目录）', value: rootDirectoryKey },
  ...directories.value.map(directory => ({ label: directoryPath(directory.id), value: directory.id }))
]);
const visibleDocuments = computed(() => {
  const selectedKey = selectedDirectoryKeys.value[0] || allDirectoryKey;
  const keyword = documentSearch.value.trim().toLocaleLowerCase();
  return documents.value.filter(document => {
    const inDirectory = selectedKey === allDirectoryKey
      || (selectedKey === rootDirectoryKey ? !document.directoryId : document.directoryId === selectedKey);
    if (!inDirectory) return false;
    if (!keyword) return true;
    return [document.name, document.documentKey, document.remark || '', ...(document.tags || [])]
      .some(value => value.toLocaleLowerCase().includes(keyword));
  });
});
const currentDirectoryLabel = computed(() => {
  const key = selectedDirectoryKeys.value[0] || allDirectoryKey;
  if (key === allDirectoryKey) return '全部文档';
  if (key === rootDirectoryKey) return '未分类';
  return directoryPath(key);
});

const retrieveVisible = ref(false);
const retrieveSubmitting = ref(false);
const retrieveQuery = ref('');
const retrieveBaseIds = ref<string[]>([]);
const retrieval = ref<KnowledgeRetrievalView | null>(null);
const metricsVisible = ref(false);
const metricsLoading = ref(false);
const metricsDays = ref(7);
const metrics = ref<KnowledgeMetricsView | null>(null);
const abVisible = ref(false);
const abSubmitting = ref(false);
const abResults = ref<{ a: KnowledgeRetrievalView | null; b: KnowledgeRetrievalView | null }>({ a: null, b: null });
const abForm = reactive({
  query: '',
  baseIds: [] as string[],
  aTopK: 6,
  aThreshold: 0.2,
  aWeight: 0.7,
  bTopK: 6,
  bThreshold: 0.2,
  bWeight: 0
});
const abCards = [{ key: 'a' as const, label: 'A 组结果' }, { key: 'b' as const, label: 'B 组结果' }];

function abResult(key: 'a' | 'b') {
  return abResults.value[key];
}

const filteredBases = computed(() => {
  const keyword = search.value.trim().toLocaleLowerCase();
  return knowledgeBases.value.filter(base =>
    !keyword || [base.name, base.knowledgeKey, base.description || ''].some(value => value.toLocaleLowerCase().includes(keyword))
  );
});
const baseOptions = computed<SelectOption[]>(() =>
  knowledgeBases.value.filter(base => base.status === 'active').map(base => ({ label: base.name, value: base.id }))
);
const visibilityOptions: SelectOption[] = [
  { label: '仅本人', value: 'private' },
  { label: '企业共享', value: 'enterprise_shared' },
  { label: '受限可见', value: 'restricted' }
];
const providerOptions: SelectOption[] = [
  { label: 'PostgreSQL + pgvector', value: 'postgres_pgvector' }
];
const parserOptions: SelectOption[] = [
  { label: '自动识别文档格式（Apache Tika）', value: 'tika_auto' }
];
const embeddingModelOptions = computed<SelectOption[]>(() =>
  embeddingModels.value.map(model => ({
    label: `${model.displayName} / ${model.modelName}${model.status === 'active' ? '' : '（已停用）'}`,
    value: model.id,
    disabled: model.status !== 'active'
  }))
);

function statusType(status: string) {
  if (['active', 'processed', 'ready', 'succeeded'].includes(status)) return 'success';
  if (['failed', 'error'].includes(status)) return 'error';
  if (['pending', 'queued', 'parsing', 'processing'].includes(status)) return 'warning';
  return 'default';
}

function statusText(status: string) {
  return ({ active: '启用', disabled: '停用', uploaded: '已上传', queued: '排队中', parsing: '解析中', processing: '解析中', processed: '已完成', ready: '已完成', failed: '失败' } as Record<string, string>)[status] || status;
}

function visibilityText(value: KnowledgeBaseView['visibility']) {
  return ({ private: '仅本人', enterprise_shared: '企业共享', restricted: '受限可见' } as const)[value];
}

function numberValue(value: unknown, fallback: number) {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function requestErrorMessage(error: unknown) {
  if (typeof error === 'string' && error.trim()) return error;
  if (error instanceof Error && error.message) return error.message;
  if (error && typeof error === 'object' && 'message' in error) {
    const message = (error as { message?: unknown }).message;
    if (typeof message === 'string' && message.trim()) return message;
  }
  return '请求失败，请稍后重试';
}

function buildDirectoryOptions(source: KnowledgeDirectoryView[]) {
  const byId = new Map(source.map(directory => [directory.id, directory]));
  const children = new Map<string | null, KnowledgeDirectoryView[]>();
  source.forEach(directory => {
    const parentId = directory.parentId && byId.has(directory.parentId) ? directory.parentId : null;
    const siblings = children.get(parentId) || [];
    siblings.push(directory);
    children.set(parentId, siblings);
  });
  children.forEach(siblings => siblings.sort((a, b) => a.name.localeCompare(b.name, 'zh-CN')));

  const visited = new Set<string>();
  function walk(parentId: string | null, ancestors: Set<string>): TreeOption[] {
    return (children.get(parentId) || []).flatMap(directory => {
      if (ancestors.has(directory.id) || visited.has(directory.id)) return [];
      visited.add(directory.id);
      const nextAncestors = new Set(ancestors).add(directory.id);
      const nested = walk(directory.id, nextAncestors);
      const option: TreeOption = {
        key: directory.id,
        label: `${directory.name} (${directory.documentCount})`
      };
      if (nested.length) option.children = nested;
      return [option];
    });
  }

  const result = walk(null, new Set());
  source
    .filter(directory => !visited.has(directory.id))
    .sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'))
    .forEach(directory => result.push({ key: directory.id, label: `${directory.name} (${directory.documentCount})` }));
  return result;
}

function hasDirectoryAncestor(directory: KnowledgeDirectoryView, ancestorId: string) {
  const visited = new Set<string>();
  let parentId = directory.parentId;
  while (parentId && !visited.has(parentId)) {
    if (parentId === ancestorId) return true;
    visited.add(parentId);
    parentId = directoryMap.value.get(parentId)?.parentId || null;
  }
  return false;
}

function directoryPath(directoryId: string | null | undefined) {
  if (!directoryId) return '未分类';
  const names: string[] = [];
  const visited = new Set<string>();
  let currentId: string | null = directoryId;
  while (currentId && !visited.has(currentId)) {
    visited.add(currentId);
    const current = directoryMap.value.get(currentId);
    if (!current) break;
    names.unshift(current.name);
    currentId = current.parentId;
  }
  return names.length ? names.join(' / ') : '目录不可见';
}

function directoryIdFromKey(key: string) {
  return key === rootDirectoryKey ? null : key;
}

function documentCatalogRevision(document: KnowledgeDocumentView) {
  return document.catalogRevision || document.revision;
}

function normalizedTags(tags: string[]) {
  const seen = new Set<string>();
  return tags
    .map(tag => tag.trim())
    .filter(tag => {
      if (!tag) return false;
      const key = tag.toLocaleLowerCase();
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    })
    .slice(0, 32);
}

async function loadBases() {
  loading.value = true;
  const { data, error } = await fetchKnowledgeBases(undefined, includeInactive.value);
  if (!error) knowledgeBases.value = data;
  loading.value = false;
}

async function loadEmbeddingModels() {
  const { data, error } = await fetchModels('embedding', true);
  if (!error) embeddingModels.value = data;
}

function openEdit(base?: KnowledgeBaseView) {
  const config = base?.config || {};
  editingBase.value = base || null;
  editForm.knowledgeKey = base?.knowledgeKey || '';
  editForm.name = base?.name || '';
  editForm.description = base?.description || '';
  editForm.visibility = base?.visibility || 'enterprise_shared';
  editForm.status = base?.status || 'active';
  editForm.providerType = base?.providerType || 'postgres_pgvector';
  editForm.parserType = 'tika_auto';
  editForm.chunkSize = numberValue(config.chunkSize, 1000);
  editForm.chunkOverlap = numberValue(config.chunkOverlap, 100);
  editForm.embeddingEnabled = config.embeddingModelId !== null && config.embeddingModelId !== undefined;
  editForm.embeddingModelId = config.embeddingModelId === null || config.embeddingModelId === undefined
    ? null
    : String(config.embeddingModelId);
  editForm.embeddingDimension = numberValue(config.embeddingDimension, 1536);
  editForm.topK = numberValue(config.topK, 6);
  editForm.similarityThreshold = numberValue(config.similarityThreshold, 0.2);
  editForm.vectorWeight = numberValue(config.vectorWeight, 0.7);
  editVisible.value = true;
}

function updateEmbeddingModel(modelId: string | null) {
  editForm.embeddingModelId = modelId;
  const model = embeddingModels.value.find(item => item.id === modelId);
  const dimension = model?.capabilities?.embeddingDimension ?? model?.capabilities?.dimensions;
  if (typeof dimension === 'number' && Number.isInteger(dimension) && dimension > 0) {
    editForm.embeddingDimension = dimension;
  }
}

async function submitEdit() {
  await editFormRef.value?.validate();
  const chunkSize = numberValue(editForm.chunkSize, 1000);
  const chunkOverlap = numberValue(editForm.chunkOverlap, 100);
  const topK = numberValue(editForm.topK, 6);
  const similarityThreshold = numberValue(editForm.similarityThreshold, 0.2);
  const vectorWeight = numberValue(editForm.vectorWeight, 0.7);
  if (chunkSize < 200 || chunkSize > 4000 || chunkOverlap < 0 || chunkOverlap > 1000) {
    window.$message?.warning('请检查切片长度，单片范围为 200 到 4000，重叠范围为 0 到 1000');
    return;
  }
  if (chunkOverlap >= chunkSize) {
    window.$message?.warning('切片重叠长度必须小于单个切片长度');
    return;
  }
  if (topK < 1 || topK > 20 || similarityThreshold < 0 || similarityThreshold > 1 || vectorWeight < 0 || vectorWeight > 1) {
    window.$message?.warning('请检查检索策略的数值范围');
    return;
  }
  const config: Record<string, unknown> = {
    chunkSize,
    chunkOverlap,
    topK,
    similarityThreshold,
    vectorWeight
  };
  if (editForm.embeddingEnabled) {
    if (!editForm.embeddingModelId || !Number.isInteger(editForm.embeddingDimension) || editForm.embeddingDimension < 1) {
      window.$message?.warning('请选择 Embedding 模型并填写正确的向量维度');
      return;
    }
    config.embeddingModelId = editForm.embeddingModelId;
    config.embeddingDimension = editForm.embeddingDimension;
  }
  const payload: SaveKnowledgeBasePayload = {
    name: editForm.name.trim(),
    description: editForm.description.trim() || undefined,
    visibility: editForm.visibility,
    status: editForm.status,
    config
  };
  editSubmitting.value = true;
  const result = editingBase.value
    ? await updateKnowledgeBase(editingBase.value.id, { ...payload, expectedRevision: editingBase.value.revision })
    : await createKnowledgeBase({ ...payload, knowledgeKey: editForm.knowledgeKey.trim() });
  if (!result.error) {
    editVisible.value = false;
    window.$message?.success(editingBase.value ? '知识库已更新' : '知识库已创建');
    await loadBases();
  }
  editSubmitting.value = false;
}

function confirmDeleteBase(base: KnowledgeBaseView) {
  dialog.warning({
    title: '删除知识库',
    content: `确认删除“${base.name}”？仍被智能体版本引用时后端会拒绝。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const { error } = await deleteKnowledgeBase(base.id, base.revision);
      if (!error) {
        window.$message?.success('知识库已删除');
        await loadBases();
      }
    }
  });
}

async function openDocuments(base: KnowledgeBaseView) {
  activeBase.value = base;
  documents.value = [];
  directories.value = [];
  directoryError.value = '';
  documentSearch.value = '';
  selectedDirectoryKeys.value = [allDirectoryKey];
  expandedDirectoryKeys.value = [];
  documentVisible.value = true;
  await loadDocuments();
}

async function loadDocuments() {
  if (!activeBase.value) return;
  documentLoading.value = true;
  directoryError.value = '';
  const baseId = activeBase.value.id;
  const treeResult = await fetchKnowledgeTree(baseId);
  if (!treeResult.error) {
    directories.value = treeResult.data.directories;
    documents.value = treeResult.data.documents;
    const selectedKey = selectedDirectoryKeys.value[0];
    if (selectedKey && ![allDirectoryKey, rootDirectoryKey].includes(selectedKey)
      && !treeResult.data.directories.some(directory => directory.id === selectedKey)) {
      selectedDirectoryKeys.value = [allDirectoryKey];
    }
    if (!expandedDirectoryKeys.value.length) {
      expandedDirectoryKeys.value = treeResult.data.directories
        .filter(directory => directory.parentId === null)
        .map(directory => directory.id);
    }
  } else {
    const legacyResult = await fetchKnowledgeDocuments(baseId);
    if (!legacyResult.error) {
      directories.value = [];
      documents.value = legacyResult.data;
      selectedDirectoryKeys.value = [allDirectoryKey];
      directoryError.value = '目录服务暂不可用，当前以兼容模式展示全部文档。';
    } else {
      directoryError.value = '目录和文档加载失败，请稍后重试。';
    }
  }
  documentLoading.value = false;
}

function selectDirectory(keys: Array<string | number>) {
  selectedDirectoryKeys.value = [keys.length ? String(keys[0]) : allDirectoryKey];
}

function updateExpandedDirectories(keys: Array<string | number>) {
  expandedDirectoryKeys.value = keys.map(key => String(key));
}

function expandDirectoryAncestors(directory: KnowledgeDirectoryView) {
  const keys = new Set(expandedDirectoryKeys.value);
  let parentId = directory.parentId;
  while (parentId && !keys.has(parentId)) {
    keys.add(parentId);
    parentId = directoryMap.value.get(parentId)?.parentId || null;
  }
  expandedDirectoryKeys.value = [...keys];
}

function openCreateDirectory() {
  editingDirectory.value = null;
  directoryForm.name = '';
  directoryForm.parentKey = selectedDirectoryId.value || rootDirectoryKey;
  directoryVisible.value = true;
}

function openEditDirectory() {
  if (!selectedDirectory.value) return;
  editingDirectory.value = selectedDirectory.value;
  directoryForm.name = selectedDirectory.value.name;
  directoryForm.parentKey = selectedDirectory.value.parentId || rootDirectoryKey;
  directoryVisible.value = true;
}

async function openDirectoryAcl() {
  const selectedKey = selectedDirectoryKeys.value[0] || allDirectoryKey;
  if (selectedKey === allDirectoryKey) {
    window.$message?.warning('请选择具体目录或根目录后再设置授权');
    return;
  }
  if (!activeBase.value) return;
  aclTargetDirectoryId.value = selectedKey === rootDirectoryKey ? null : selectedKey;
  aclTargetLabel.value = selectedKey === rootDirectoryKey ? '知识库根目录' : directoryPath(selectedKey);
  aclForm.userId = null;
  aclForm.permission = 'read';
  aclForm.effect = 'allow';
  aclForm.inheritChildren = true;
  aclRows.value = [];
  aclVisible.value = true;
  aclLoading.value = true;
  const { data, error } = await fetchKnowledgeDirectoryAcls(activeBase.value.id, aclTargetDirectoryId.value);
  if (!error) aclRows.value = data;
  else window.$message?.error(requestErrorMessage(error));
  aclLoading.value = false;
}

async function submitDirectoryAcl() {
  if (!activeBase.value || !aclForm.userId || aclForm.userId <= 0) {
    window.$message?.warning('请输入有效的用户 ID');
    return;
  }
  const current = aclRows.value.find(row => row.userId === String(aclForm.userId) && row.permission === aclForm.permission);
  aclSubmitting.value = true;
  const { error } = await putKnowledgeDirectoryAcl(activeBase.value.id, {
    directoryId: aclTargetDirectoryId.value,
    userId: String(aclForm.userId),
    permission: aclForm.permission,
    effect: aclForm.effect,
    inheritChildren: aclForm.inheritChildren,
    expectedRevision: current?.revision
  });
  if (!error) {
    window.$message?.success('目录授权已保存');
    const { data } = await fetchKnowledgeDirectoryAcls(activeBase.value.id, aclTargetDirectoryId.value);
    aclRows.value = data || [];
    aclForm.userId = null;
  }
  aclSubmitting.value = false;
}

function removeDirectoryAcl(row: KnowledgeDirectoryAclView) {
  if (!activeBase.value) return;
  const baseId = activeBase.value.id;
  dialog.warning({
    title: '撤销目录授权',
    content: `确认撤销用户 ${row.userId} 的目录授权？`,
    positiveText: '撤销',
    negativeText: '取消',
    onPositiveClick: async () => {
      const { error } = await revokeKnowledgeDirectoryAcl(baseId, row.id, row.revision);
      if (!error) {
        window.$message?.success('目录授权已撤销');
        aclRows.value = aclRows.value.filter(item => item.id !== row.id);
      } else {
        window.$message?.error(requestErrorMessage(error));
      }
    }
  });
}

async function submitDirectory() {
  if (!activeBase.value) return;
  await directoryFormRef.value?.validate();
  const name = directoryForm.name.trim();
  if (!name) return;
  directorySubmitting.value = true;
  const parentId = directoryIdFromKey(directoryForm.parentKey);
  const result = editingDirectory.value
    ? await updateKnowledgeDirectory(activeBase.value.id, editingDirectory.value.id, {
      name,
      parentId,
      expectedRevision: editingDirectory.value.revision
    })
    : await createKnowledgeDirectory(activeBase.value.id, {
      name,
      parentId
    });
  if (!result.error) {
    selectedDirectoryKeys.value = [result.data.id];
    expandDirectoryAncestors(result.data);
    directoryVisible.value = false;
    window.$message?.success(editingDirectory.value ? '目录已更新' : '目录已创建');
    await loadDocuments();
  }
  directorySubmitting.value = false;
}

function confirmDeleteDirectory() {
  if (!activeBase.value || !selectedDirectory.value) return;
  const directory = selectedDirectory.value;
  if (directory.documentCount > 0 || directory.childDirectoryCount > 0) {
    window.$message?.warning('仅空目录可以删除，请先移动其中的文档和子目录');
    return;
  }
  const baseId = activeBase.value.id;
  dialog.warning({
    title: '删除目录',
    content: `确认删除空目录“${directoryPath(directory.id)}”？`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const { error } = await deleteKnowledgeDirectory(baseId, directory.id, directory.revision);
      if (!error) {
        selectedDirectoryKeys.value = [allDirectoryKey];
        window.$message?.success('目录已删除');
        await loadDocuments();
      }
    }
  });
}

function openDocumentEdit(row: KnowledgeDocumentView) {
  editingDocument.value = row;
  documentEditForm.name = row.name;
  documentEditForm.directoryKey = row.directoryId || rootDirectoryKey;
  documentEditForm.tags = [...(row.tags || [])];
  documentEditForm.remark = row.remark || '';
  documentEditVisible.value = true;
}

async function submitDocumentEdit() {
  if (!activeBase.value || !editingDocument.value) return;
  await documentEditFormRef.value?.validate();
  if (documentEditForm.tags.some(tag => tag.trim().length > 64)) {
    window.$message?.warning('每个标签最多 64 个字符');
    return;
  }
  const payload: UpdateKnowledgeDocumentPayload = {
    name: documentEditForm.name.trim(),
    directoryId: directoryIdFromKey(documentEditForm.directoryKey),
    tags: normalizedTags(documentEditForm.tags),
    remark: documentEditForm.remark.trim() || null,
    expectedRevision: documentCatalogRevision(editingDocument.value)
  };
  documentEditSubmitting.value = true;
  const { error } = await updateKnowledgeDocument(activeBase.value.id, editingDocument.value.id, payload);
  if (!error) {
    documentEditVisible.value = false;
    window.$message?.success('文档信息已更新');
    await loadDocuments();
  }
  documentEditSubmitting.value = false;
}

async function openChunks(row: KnowledgeDocumentView) {
  if (!activeBase.value) return;
  activeDocument.value = row;
  chunkOffset.value = 0;
  chunks.value = [];
  chunkVisible.value = true;
  await loadChunks();
}

async function loadChunks() {
  if (!activeBase.value || !activeDocument.value) return;
  chunkLoading.value = true;
  const { data, error } = await fetchKnowledgeChunks(
    activeBase.value.id,
    activeDocument.value.id,
    chunkOffset.value,
    chunkLimit + 1
  );
  if (!error) {
    chunks.value = data.slice(0, chunkLimit);
    chunkHasNext.value = data.length > chunkLimit;
  }
  chunkLoading.value = false;
}

async function changeChunkPage(delta: -1 | 1) {
  const nextOffset = chunkOffset.value + delta * chunkLimit;
  if (nextOffset < 0 || (delta > 0 && !chunkHasNext.value)) return;
  chunkOffset.value = nextOffset;
  await loadChunks();
}

async function downloadDocument(row: KnowledgeDocumentView) {
  if (!activeBase.value || documentDownloadingId.value) return;
  documentDownloadingId.value = row.id;
  try {
    const result = await downloadKnowledgeDocument(activeBase.value.id, row.id);
    const url = URL.createObjectURL(result.blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = result.fileName || row.name;
    anchor.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 0);
  } catch (error) {
    window.$message?.error(error instanceof Error ? error.message : '文档下载失败');
  } finally {
    documentDownloadingId.value = null;
  }
}

async function uploadDocument(options: UploadCustomRequestOptions) {
  const file = options.file.file;
  if (!(file instanceof File) || !activeBase.value) {
    options.onError();
    return;
  }
  if (/\.rar$/i.test(file.name) || /(?:x-)?rar/i.test(file.type)) {
    window.$message?.error('不支持 RAR 文件，请解压后上传受支持的文档格式');
    options.onError();
    return;
  }
  const targetDirectoryId = selectedDirectoryId.value;
  const result = await uploadKnowledgeDocument(activeBase.value.id, file, targetDirectoryId);
  if (result.error) {
    options.onError();
    return;
  }
  let moved = !targetDirectoryId || result.data.directoryId === targetDirectoryId;
  if (targetDirectoryId && !moved) {
    const moveResult = await updateKnowledgeDocument(activeBase.value.id, result.data.id, {
      directoryId: targetDirectoryId,
      expectedRevision: documentCatalogRevision(result.data)
    });
    if (moveResult.error) {
      // The upload already succeeded; do not ask the user to retry and create a duplicate.
      window.$message?.warning('文档已上传到未分类目录，移动到当前目录失败，请稍后重试');
    } else {
      moved = true;
    }
  }
  options.onFinish();
  if (moved) window.$message?.success(targetDirectoryId ? '文档已上传到当前目录，请继续触发解析' : '文档已上传，请继续触发解析');
  await loadDocuments();
}

async function parseDocument(row: KnowledgeDocumentView) {
  if (!activeBase.value) return;
  const { data, error } = await parseKnowledgeDocument(activeBase.value.id, row.id);
  if (!error) {
    window.$message?.success(`解析任务 #${data.jobId} 已进入队列`);
    await loadDocuments();
  }
}

function confirmDeleteDocument(row: KnowledgeDocumentView) {
  if (!activeBase.value) return;
  const baseId = activeBase.value.id;
  dialog.warning({
    title: '删除文档',
    content: `确认删除“${row.name}”及其检索切片？`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const { error } = await deleteKnowledgeDocument(baseId, row.id);
      if (!error) {
        window.$message?.success('文档已删除');
        await loadDocuments();
      }
    }
  });
}

function openRetrieve(base?: KnowledgeBaseView) {
  retrieveBaseIds.value = base ? [base.id] : [];
  retrieveQuery.value = '';
  retrieval.value = null;
  retrieveVisible.value = true;
}

function openMetrics() {
  metricsVisible.value = true;
  void loadMetrics();
}

async function loadMetrics() {
  metricsLoading.value = true;
  const result = await fetchKnowledgeMetrics(metricsDays.value);
  if (!result.error) metrics.value = result.data;
  metricsLoading.value = false;
}

function openAbTest() {
  abForm.query = '';
  abForm.baseIds = [];
  abResults.value = { a: null, b: null };
  abVisible.value = true;
}

async function runAbTest() {
  if (!abForm.baseIds.length || !abForm.query.trim()) {
    window.$message?.warning('请选择知识库并输入检索问题');
    return;
  }
  abSubmitting.value = true;
  const [a, b] = await Promise.all([
    retrieveKnowledge({ knowledgeBaseIds: abForm.baseIds, query: abForm.query.trim(), topK: abForm.aTopK, similarityThreshold: abForm.aThreshold, vectorWeight: abForm.aWeight }),
    retrieveKnowledge({ knowledgeBaseIds: abForm.baseIds, query: abForm.query.trim(), topK: abForm.bTopK, similarityThreshold: abForm.bThreshold, vectorWeight: abForm.bWeight })
  ]);
  abResults.value = { a: a.error ? null : a.data, b: b.error ? null : b.data };
  if (a.error && b.error) window.$message?.error('A/B 两组检索均失败');
  else if (a.error || b.error) window.$message?.warning('一组检索失败，已保留另一组结果');
  abSubmitting.value = false;
}

function metricNumber(value: unknown) {
  return typeof value === 'number' ? value : Number(value || 0);
}

const metricBaseColumns: DataTableColumns<Record<string, unknown>> = [
  { title: '知识库', key: 'name', minWidth: 180 },
  { title: '文档', key: 'document_count', width: 80, render: row => metricNumber(row.document_count) },
  { title: '切片', key: 'chunk_count', width: 80, render: row => metricNumber(row.chunk_count) },
  { title: '检索次数', key: 'retrieval_count', width: 100, render: row => metricNumber(row.retrieval_count) },
  { title: '引用数', key: 'citation_count', width: 90, render: row => metricNumber(row.citation_count) }
];
const metricTrendColumns: DataTableColumns<Record<string, unknown>> = [
  { title: '日期', key: 'day', width: 130 },
  { title: '检索', key: 'retrieval_count', width: 80, render: row => metricNumber(row.retrieval_count) },
  { title: '空结果', key: 'empty_count', width: 80, render: row => metricNumber(row.empty_count) },
  { title: '失败', key: 'failed_count', width: 80, render: row => metricNumber(row.failed_count) },
  { title: '引用', key: 'citation_count', width: 80, render: row => metricNumber(row.citation_count) }
];

async function submitRetrieve() {
  if (!retrieveBaseIds.value.length || !retrieveQuery.value.trim()) {
    window.$message?.warning('请选择知识库并输入检索问题');
    return;
  }
  retrieveSubmitting.value = true;
  const { data, error } = await retrieveKnowledge({
    knowledgeBaseIds: retrieveBaseIds.value,
    query: retrieveQuery.value.trim(),
    topK: 8,
    similarityThreshold: 0,
    vectorWeight: 0.7
  });
  if (!error) retrieval.value = data;
  retrieveSubmitting.value = false;
}

const columns: DataTableColumns<KnowledgeBaseView> = [
  {
    title: '知识库', key: 'name', minWidth: 270,
    render: row => h('div', { class: 'primary-cell' }, [h('strong', row.name), h('span', `${row.knowledgeKey} · ${row.description || '暂无描述'}`)])
  },
  { title: '提供方', key: 'providerType', width: 110 },
  { title: '可见范围', key: 'visibility', width: 115, render: row => visibilityText(row.visibility) },
  { title: '修订号', key: 'revision', width: 100, render: row => `#${row.revision}` },
  { title: '状态', key: 'status', width: 90, render: row => h(NTag, { size: 'small', type: statusType(row.status) }, () => statusText(row.status)) },
  { title: '更新时间', key: 'updatedAt', width: 165, render: row => dayjs(row.updatedAt || row.createdAt).format('YYYY-MM-DD HH:mm') },
  {
    title: '操作', key: 'actions', width: 270, fixed: 'right',
    render: row => h(NSpace, { size: 6 }, () => [
      h(NButton, { size: 'small', type: 'primary', secondary: true, onClick: () => openDocuments(row) }, () => '文档'),
      h(NButton, { size: 'small', quaternary: true, disabled: row.status !== 'active', onClick: () => openRetrieve(row) }, () => '检索'),
      h(NButton, { size: 'small', quaternary: true, onClick: () => openEdit(row) }, () => '编辑'),
      h(NButton, { size: 'small', type: 'error', quaternary: true, onClick: () => confirmDeleteBase(row) }, () => '删除')
    ])
  }
];

const documentColumns: DataTableColumns<KnowledgeDocumentView> = [
  {
    title: '文档', key: 'name', minWidth: 220,
    render: row => h('div', { class: 'primary-cell' }, [h('strong', row.name), h('span', row.documentKey)])
  },
  { title: '目录', key: 'directoryId', minWidth: 150, render: row => directoryPath(row.directoryId) },
  { title: '格式', key: 'mimeType', width: 150, ellipsis: { tooltip: true }, render: row => row.mimeType || '-' },
  { title: '大小', key: 'sizeBytes', width: 100, render: row => row.sizeBytes === null ? '-' : `${(Number(row.sizeBytes) / 1024).toFixed(1)} KB` },
  { title: '切片', key: 'chunkCount', width: 75, render: row => row.chunkCount ?? '-' },
  {
    title: '标签', key: 'tags', minWidth: 170,
    render: row => {
      const tags = row.tags ?? [];
      return tags.length
        ? h(NSpace, { size: 4, wrap: true }, () => tags.map(tag => h(NTag, { size: 'small', bordered: false }, () => tag)))
        : '-';
    }
  },
  { title: '备注', key: 'remark', minWidth: 160, ellipsis: { tooltip: true }, render: row => row.remark || '-' },
  { title: '状态', key: 'status', width: 100, render: row => h(NTag, { size: 'small', type: statusType(row.status) }, () => statusText(row.status)) },
  { title: '错误', key: 'errorSummary', minWidth: 170, ellipsis: { tooltip: true }, render: row => row.errorSummary || '-' },
  {
    title: '操作', key: 'actions', width: 330,
    render: row => h(NSpace, { size: 6 }, () => [
      h(NButton, { size: 'small', type: 'primary', secondary: true, disabled: ['queued', 'parsing', 'processing'].includes(row.status), onClick: () => parseDocument(row) }, () => '解析'),
      h(NButton, { size: 'small', quaternary: true, disabled: !row.chunkCount, onClick: () => openChunks(row) }, () => '切片'),
      h(NButton, { size: 'small', quaternary: true, loading: documentDownloadingId.value === row.id, onClick: () => downloadDocument(row) }, () => '下载'),
      h(NButton, { size: 'small', quaternary: true, onClick: () => openDocumentEdit(row) }, () => '编辑'),
      h(NButton, { size: 'small', type: 'error', quaternary: true, onClick: () => confirmDeleteDocument(row) }, () => '删除')
    ])
  }
];

const chunkColumns: DataTableColumns<KnowledgeChunkView> = [
  { title: '序号', key: 'chunkNo', width: 76, render: row => row.chunkNo },
  { title: 'Token', key: 'tokenCount', width: 90, render: row => row.tokenCount ?? '-' },
  { title: '内容', key: 'content', minWidth: 520, render: row => h('div', { class: 'chunk-content' }, row.content) },
  { title: '状态', key: 'status', width: 90, render: row => h(NTag, { size: 'small', type: statusType(row.status) }, () => statusText(row.status)) }
];

onMounted(async () => Promise.all([loadBases(), loadEmbeddingModels()]));
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div><h2>{{ $t('page.knowledge.title') }}</h2><p>{{ $t('page.knowledge.desc') }}</p></div>
      <NSpace>
        <NButton secondary @click="openRetrieve()"><template #icon><SvgIcon icon="lucide:search" /></template>跨库检索</NButton>
        <NButton secondary @click="openAbTest"><template #icon><SvgIcon icon="lucide:split" /></template>A/B 检索</NButton>
        <NButton secondary @click="openMetrics"><template #icon><SvgIcon icon="lucide:chart-no-axes-combined" /></template>运营指标</NButton>
        <NButton type="primary" @click="openEdit()"><template #icon><SvgIcon icon="lucide:plus" /></template>创建知识库</NButton>
      </NSpace>
    </div>
    <div class="toolbar">
      <NInput v-model:value="search" clearable placeholder="搜索名称、标识或描述" class="search-input"><template #prefix><SvgIcon icon="lucide:search" /></template></NInput>
      <NSpace align="center"><NCheckbox v-model:checked="includeInactive" @update:checked="loadBases">包含停用</NCheckbox><NButton secondary :loading="loading" @click="loadBases"><template #icon><SvgIcon icon="lucide:refresh-cw" /></template>刷新</NButton></NSpace>
    </div>
    <NDataTable :columns="columns" :data="filteredBases" :loading="loading" :scroll-x="1250" :row-key="row => row.id" />

  <NModal
    v-model:show="editVisible"
    preset="card"
    :title="editingBase ? '编辑知识库' : '创建知识库'"
    class="form-modal"
    style="width: min(860px, calc(100vw - 32px))"
  >
    <NForm ref="editFormRef" :model="editForm" :rules="editRules" label-placement="top">
      <div class="form-grid">
        <NFormItem label="稳定标识" path="knowledgeKey"><NInput v-model:value="editForm.knowledgeKey" :disabled="Boolean(editingBase)" placeholder="product_docs" /></NFormItem>
        <NFormItem label="名称" path="name"><NInput v-model:value="editForm.name" maxlength="255" /></NFormItem>
        <NFormItem label="可见范围"><NSelect v-model:value="editForm.visibility" :options="visibilityOptions" /></NFormItem>
        <NFormItem label="状态"><NSwitch v-model:value="editForm.status" checked-value="active" unchecked-value="disabled"><template #checked>启用</template><template #unchecked>停用</template></NSwitch></NFormItem>
        <NFormItem class="full-field" label="描述"><NInput v-model:value="editForm.description" type="textarea" maxlength="12000" :autosize="{ minRows: 3, maxRows: 6 }" /></NFormItem>
      </div>

      <section class="config-section">
        <div class="config-heading"><SvgIcon icon="lucide:scan-text" /><div><h3>存储与解析</h3><p>一期使用 PostgreSQL 向量存储，并自动识别上传文档格式。</p></div></div>
        <div class="form-grid">
          <NFormItem label="存储提供方"><NSelect v-model:value="editForm.providerType" :options="providerOptions" disabled /></NFormItem>
          <NFormItem label="文档解析器"><NSelect v-model:value="editForm.parserType" :options="parserOptions" disabled /></NFormItem>
        </div>
      </section>

      <section class="config-section">
        <div class="config-heading"><SvgIcon icon="lucide:split" /><div><h3>文本切片</h3><p>控制文档拆分粒度和相邻切片之间保留的上下文。</p></div></div>
        <div class="form-grid">
          <NFormItem label="单个切片长度"><NInputNumber v-model:value="editForm.chunkSize" :min="200" :max="4000" :step="100" /></NFormItem>
          <NFormItem label="切片重叠长度"><NInputNumber v-model:value="editForm.chunkOverlap" :min="0" :max="1000" :step="20" /></NFormItem>
        </div>
      </section>

      <section class="config-section">
        <div class="config-heading with-switch">
          <div class="config-heading-main"><SvgIcon icon="lucide:binary" /><div><h3>Embedding</h3><p>启用后为切片生成向量，用于语义检索。</p></div></div>
          <NSwitch v-model:value="editForm.embeddingEnabled"><template #checked>已启用</template><template #unchecked>未启用</template></NSwitch>
        </div>
        <div v-if="editForm.embeddingEnabled" class="form-grid">
          <NFormItem label="Embedding 模型">
            <NSelect
              :value="editForm.embeddingModelId"
              :options="embeddingModelOptions"
              filterable
              placeholder="选择模型中心已登记的 Embedding 模型"
              @update:value="updateEmbeddingModel"
            />
          </NFormItem>
          <NFormItem label="向量维度"><NInputNumber v-model:value="editForm.embeddingDimension" :min="1" :max="8192" /></NFormItem>
        </div>
        <NAlert v-else type="info" :bordered="false">未配置向量模型时保留关键词检索能力。</NAlert>
      </section>

      <section class="config-section">
        <div class="config-heading"><SvgIcon icon="lucide:list-filter" /><div><h3>检索策略</h3><p>设置默认召回数量、最低相似度和语义检索权重。</p></div></div>
        <div class="form-grid retrieval-grid">
          <NFormItem label="默认召回数量"><NInputNumber v-model:value="editForm.topK" :min="1" :max="20" /></NFormItem>
          <NFormItem label="最低相似度"><NInputNumber v-model:value="editForm.similarityThreshold" :min="0" :max="1" :step="0.05" /></NFormItem>
          <NFormItem label="向量检索权重" class="full-field"><NSlider v-model:value="editForm.vectorWeight" :min="0" :max="1" :step="0.05" /></NFormItem>
        </div>
      </section>
    </NForm>
    <template #footer><NSpace justify="end"><NButton @click="editVisible = false">取消</NButton><NButton type="primary" :loading="editSubmitting" @click="submitEdit">保存</NButton></NSpace></template>
  </NModal>

  <NModal v-model:show="abVisible" preset="card" title="知识检索 A/B 实验" style="width: min(1000px, calc(100vw - 32px))">
    <NForm label-placement="top">
      <NFormItem label="知识库"><NSelect v-model:value="abForm.baseIds" :options="baseOptions" multiple filterable :max-tag-count="3" /></NFormItem>
      <NFormItem label="问题"><NInput v-model:value="abForm.query" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }" maxlength="4000" /></NFormItem>
      <NGrid :cols="2" :x-gap="16" responsive="screen" item-responsive>
        <NGi span="2 m:1"><NCard size="small" title="A 组：向量+关键词"><NGrid :cols="3" :x-gap="10"><NFormItemGi label="Top K"><NInputNumber v-model:value="abForm.aTopK" :min="1" :max="20" /></NFormItemGi><NFormItemGi label="阈值"><NInputNumber v-model:value="abForm.aThreshold" :min="0" :max="1" :step="0.05" /></NFormItemGi><NFormItemGi label="向量权重"><NInputNumber v-model:value="abForm.aWeight" :min="0" :max="1" :step="0.05" /></NFormItemGi></NGrid></NCard></NGi>
        <NGi span="2 m:1"><NCard size="small" title="B 组：关键词基线"><NGrid :cols="3" :x-gap="10"><NFormItemGi label="Top K"><NInputNumber v-model:value="abForm.bTopK" :min="1" :max="20" /></NFormItemGi><NFormItemGi label="阈值"><NInputNumber v-model:value="abForm.bThreshold" :min="0" :max="1" :step="0.05" /></NFormItemGi><NFormItemGi label="向量权重"><NInputNumber v-model:value="abForm.bWeight" :min="0" :max="1" :step="0.05" /></NFormItemGi></NGrid></NCard></NGi>
      </NGrid>
    </NForm>
    <NSpace justify="end" class="mt-12px"><NButton type="primary" :loading="abSubmitting" @click="runAbTest"><template #icon><SvgIcon icon="lucide:play" /></template>并行检索</NButton></NSpace>
    <NGrid v-if="abResults.a || abResults.b" :cols="2" :x-gap="16" responsive="screen" item-responsive class="mt-12px">
      <NGi span="2 m:1" v-for="entry in abCards" :key="entry.key"><NCard size="small" :title="entry.label"><NAlert v-if="!abResult(entry.key)" type="error" :bordered="false">该组请求失败或无响应</NAlert><template v-else><NTag size="small" :type="abResult(entry.key)?.status === 'ok' ? 'success' : 'warning'">{{ abResult(entry.key)?.status }}</NTag><div class="mt-8px max-h-220px overflow-auto whitespace-pre-wrap text-12px">{{ abResult(entry.key)?.content }}</div><div class="mt-8px text-12px op-60">引用 {{ abResult(entry.key)?.citations.length || 0 }} 条</div></template></NCard></NGi>
    </NGrid>
  </NModal>

  <NModal v-model:show="metricsVisible" preset="card" title="知识运营指标" style="width: min(1100px, calc(100vw - 32px))">
    <NSpace align="center" class="mb-12px"><NSelect v-model:value="metricsDays" :options="[{ label: '最近 7 天', value: 7 }, { label: '最近 30 天', value: 30 }, { label: '最近 90 天', value: 90 }]" style="width: 150px" @update:value="loadMetrics" /><NButton secondary :loading="metricsLoading" @click="loadMetrics"><template #icon><SvgIcon icon="lucide:refresh-cw" /></template>刷新</NButton></NSpace>
    <NSpin :show="metricsLoading"><template v-if="metrics"><NDescriptions :column="4" bordered responsive="screen"><NDescriptionsItem label="检索次数">{{ metricNumber(metrics.summary.retrieval_count) }}</NDescriptionsItem><NDescriptionsItem label="引用次数">{{ metricNumber(metrics.summary.citation_count) }}</NDescriptionsItem><NDescriptionsItem label="引用率">{{ metricNumber(metrics.summary.citation_rate) }}%</NDescriptionsItem><NDescriptionsItem label="平均延迟">{{ metricNumber(metrics.summary.average_latency_ms) }} ms</NDescriptionsItem></NDescriptions><NGrid :cols="2" :x-gap="16" responsive="screen" item-responsive class="mt-12px"><NGi span="2 m:1"><NCard size="small" title="知识库覆盖"><NDataTable :columns="metricBaseColumns" :data="metrics.knowledgeBases" :single-line="false" /></NCard></NGi><NGi span="2 m:1"><NCard size="small" title="每日趋势"><NDataTable :columns="metricTrendColumns" :data="metrics.dailyTrend" :single-line="false" /></NCard></NGi></NGrid></template><NEmpty v-else description="暂无指标数据" /></NSpin>
  </NModal>

  <NDrawer v-model:show="documentVisible" width="min(1180px, calc(100vw - 16px))" placement="right">
    <NDrawerContent closable>
      <template #header>
        <div class="drawer-header">
          <div class="drawer-title"><strong>{{ activeBase?.name || '' }} · 知识目录</strong><span>当前：{{ currentDirectoryLabel }}</span></div>
          <NSpace wrap>
            <NButton secondary size="small" :loading="documentLoading" @click="loadDocuments"><template #icon><SvgIcon icon="lucide:refresh-cw" /></template>刷新</NButton>
            <NButton secondary size="small" @click="openCreateDirectory"><template #icon><SvgIcon icon="lucide:folder-plus" /></template>新建目录</NButton>
            <NUpload :show-file-list="false" :custom-request="uploadDocument" accept=".txt,.md,.pdf,.doc,.docx,.html,.csv,.json">
              <NButton type="primary" size="small"><template #icon><SvgIcon icon="lucide:upload" /></template>上传文档</NButton>
            </NUpload>
          </NSpace>
        </div>
      </template>
      <NAlert v-if="directoryError" type="warning" :bordered="false" class="drawer-alert" closable @close="directoryError = ''">
        <NSpace align="center" justify="space-between">
          <span>{{ directoryError }}</span>
          <NButton text size="small" @click="loadDocuments">重试</NButton>
        </NSpace>
      </NAlert>
      <NAlert type="info" :bordered="false" class="drawer-alert">上传后需触发解析。RAR 扩展名、MIME 或文件魔数会被拒绝。</NAlert>
      <div class="catalog-layout">
        <section class="directory-panel" aria-label="知识目录树">
          <div class="panel-heading">
            <strong>目录</strong>
            <NSpace size="small">
              <NButton quaternary circle size="small" :disabled="selectedDirectoryKeys[0] === allDirectoryKey" title="设置目录授权" @click="openDirectoryAcl"><template #icon><SvgIcon icon="lucide:users" /></template></NButton>
              <NButton quaternary circle size="small" :disabled="!selectedDirectory" title="编辑目录" @click="openEditDirectory"><template #icon><SvgIcon icon="lucide:pencil" /></template></NButton>
              <NButton quaternary circle size="small" type="error" :disabled="!selectedDirectory" title="删除目录" @click="confirmDeleteDirectory"><template #icon><SvgIcon icon="lucide:trash-2" /></template></NButton>
            </NSpace>
          </div>
          <NSpin :show="documentLoading">
            <NTree
              v-if="directoryTreeOptions.length"
              block-line
              :data="directoryTreeOptions"
              :selected-keys="selectedDirectoryKeys"
              :expanded-keys="expandedDirectoryKeys"
              @update:selected-keys="selectDirectory"
              @update:expanded-keys="updateExpandedDirectories"
            />
            <NEmpty v-else description="暂无目录" />
          </NSpin>
        </section>
        <section class="document-panel" aria-label="知识文档列表">
          <div class="document-toolbar">
            <div><strong>{{ currentDirectoryLabel }}</strong><span class="document-count">{{ visibleDocuments.length }} 个文档</span></div>
            <NInput v-model:value="documentSearch" clearable placeholder="搜索文档、标签或备注" class="document-search"><template #prefix><SvgIcon icon="lucide:search" /></template></NInput>
          </div>
          <NSpin :show="documentLoading">
            <NDataTable v-if="visibleDocuments.length || documentLoading" :columns="documentColumns" :data="visibleDocuments" :loading="documentLoading" :scroll-x="1450" :row-key="row => row.id" :single-line="false" />
            <NEmpty v-else description="当前目录暂无文档" />
          </NSpin>
        </section>
      </div>
    </NDrawerContent>
  </NDrawer>

  <NModal v-model:show="directoryVisible" preset="card" :title="editingDirectory ? '编辑目录' : '新建目录'" style="width: min(520px, calc(100vw - 32px))">
    <NForm ref="directoryFormRef" :model="directoryForm" :rules="directoryRules" label-placement="top">
      <NFormItem label="目录名称" path="name"><NInput v-model:value="directoryForm.name" maxlength="255" placeholder="例如：产品手册" /></NFormItem>
      <NFormItem label="父目录"><NSelect v-model:value="directoryForm.parentKey" :options="directoryParentOptions" filterable /></NFormItem>
    </NForm>
    <template #footer><NSpace justify="end"><NButton @click="directoryVisible = false">取消</NButton><NButton type="primary" :loading="directorySubmitting" @click="submitDirectory">保存</NButton></NSpace></template>
  </NModal>

  <NModal v-model:show="aclVisible" preset="card" :title="`目录授权 · ${aclTargetLabel}`" style="width: min(720px, calc(100vw - 32px))">
    <NSpin :show="aclLoading">
      <NForm label-placement="top">
        <NGrid :cols="4" :x-gap="12" responsive="screen" item-responsive>
          <NGi span="4 s:1"><NFormItem label="用户 ID"><NInputNumber v-model:value="aclForm.userId" :min="1" :precision="0" placeholder="输入用户 ID" /></NFormItem></NGi>
          <NGi span="4 s:1"><NFormItem label="权限"><NSelect v-model:value="aclForm.permission" :options="[{ label: '读取', value: 'read' }, { label: '写入', value: 'write' }]" /></NFormItem></NGi>
          <NGi span="4 s:1"><NFormItem label="效果"><NSelect v-model:value="aclForm.effect" :options="[{ label: '允许', value: 'allow' }, { label: '拒绝', value: 'deny' }]" /></NFormItem></NGi>
          <NGi span="4 s:1"><NFormItem label="子目录"><NSwitch v-model:value="aclForm.inheritChildren"><template #checked>继承</template><template #unchecked>仅当前目录</template></NSwitch></NFormItem></NGi>
        </NGrid>
        <NSpace justify="end"><NButton type="primary" :loading="aclSubmitting" @click="submitDirectoryAcl"><template #icon><SvgIcon icon="lucide:save" /></template>保存授权</NButton></NSpace>
      </NForm>
      <NDivider />
      <NEmpty v-if="!aclRows.length" description="当前目录暂无授权规则" />
      <NList v-else bordered>
        <NListItem v-for="row in aclRows" :key="row.id">
          <div class="acl-row">
            <div class="acl-row-main">
              <strong>用户 {{ row.userId }}</strong>
              <NTag size="small" :type="row.effect === 'allow' ? 'success' : 'error'">{{ row.effect === 'allow' ? '允许' : '拒绝' }}</NTag>
              <NTag size="small">{{ row.permission === 'read' ? '读取' : '写入' }}</NTag>
              <span class="text-12px op-60">{{ row.inheritChildren ? '包含子目录' : '仅当前目录' }} · 版本 {{ row.revision }}</span>
            </div>
            <NButton text type="error" @click="removeDirectoryAcl(row)">撤销</NButton>
          </div>
        </NListItem>
      </NList>
    </NSpin>
    <template #footer><NSpace justify="end"><NButton @click="aclVisible = false">关闭</NButton></NSpace></template>
  </NModal>

  <NModal v-model:show="documentEditVisible" preset="card" title="编辑文档信息" style="width: min(620px, calc(100vw - 32px))">
    <NForm ref="documentEditFormRef" :model="documentEditForm" :rules="documentEditRules" label-placement="top">
      <NFormItem label="文档名称" path="name"><NInput v-model:value="documentEditForm.name" maxlength="255" /></NFormItem>
      <NFormItem label="所属目录"><NSelect v-model:value="documentEditForm.directoryKey" :options="documentDirectoryOptions" filterable /></NFormItem>
      <NFormItem label="标签"><NDynamicTags v-model:value="documentEditForm.tags" :max="32" /></NFormItem>
      <NFormItem label="备注"><NInput v-model:value="documentEditForm.remark" type="textarea" maxlength="4000" :autosize="{ minRows: 3, maxRows: 6 }" /></NFormItem>
    </NForm>
    <template #footer><NSpace justify="end"><NButton @click="documentEditVisible = false">取消</NButton><NButton type="primary" :loading="documentEditSubmitting" @click="submitDocumentEdit">保存</NButton></NSpace></template>
  </NModal>

  <NModal
    v-model:show="chunkVisible"
    preset="card"
    :title="`${activeDocument?.name || ''} · 切片详情`"
    style="width: min(980px, calc(100vw - 32px))"
  >
    <NSpin :show="chunkLoading">
      <NDataTable
        v-if="chunks.length || chunkLoading"
        :columns="chunkColumns"
        :data="chunks"
        :single-line="false"
        :scroll-x="820"
        :row-key="row => row.id"
      />
      <NEmpty v-if="!chunkLoading && !chunks.length" description="该文档暂无可用切片" />
      <NSpace v-if="chunks.length || chunkOffset > 0" justify="end" class="chunk-pagination">
        <NButton secondary :disabled="chunkOffset === 0 || chunkLoading" @click="changeChunkPage(-1)">
          <template #icon><SvgIcon icon="lucide:chevron-left" /></template>
          上一页
        </NButton>
        <span class="chunk-page-label">第 {{ Math.floor(chunkOffset / chunkLimit) + 1 }} 页</span>
        <NButton secondary :disabled="!chunkHasNext || chunkLoading" @click="changeChunkPage(1)">
          下一页
          <template #icon><SvgIcon icon="lucide:chevron-right" /></template>
        </NButton>
      </NSpace>
    </NSpin>
  </NModal>

  <NModal
    v-model:show="retrieveVisible"
    preset="card"
    title="知识检索与引用"
    class="retrieve-modal"
    style="width: min(820px, calc(100vw - 32px))"
  >
    <NForm label-placement="top">
      <NFormItem label="知识库"><NSelect v-model:value="retrieveBaseIds" :options="baseOptions" multiple filterable :max-tag-count="3" /></NFormItem>
      <NFormItem label="问题"><NInput v-model:value="retrieveQuery" type="textarea" :autosize="{ minRows: 3, maxRows: 6 }" maxlength="4000" /></NFormItem>
    </NForm>
    <NButton type="primary" :loading="retrieveSubmitting" @click="submitRetrieve"><template #icon><SvgIcon icon="lucide:search" /></template>检索</NButton>
    <div v-if="retrieval" class="retrieval-result">
      <NAlert :type="retrieval.status === 'success' ? 'success' : 'info'" :bordered="false">{{ retrieval.content || '检索已完成' }}</NAlert>
      <NList bordered>
        <NListItem v-for="citation in retrieval.citations" :key="citation.id">
          <NThing :title="`${citation.documentName} · 切片 ${citation.chunkNo}`" :description="citation.content">
            <template #header-extra><NTag size="small" :bordered="false">{{ citation.similarity.toFixed(4) }}</NTag></template>
          </NThing>
        </NListItem>
      </NList>
    </div>
  </NModal>
  </div>
</template>

<style scoped lang="scss">
.page-container { padding: 16px; }
.page-header, .toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.page-header { margin-bottom: 20px; }
.page-header h2 { margin: 0 0 4px; font-size: 20px; }
.page-header p { margin: 0; color: var(--n-text-color-3); }
.toolbar { margin-bottom: 16px; }
.search-input { width: min(420px, 100%); }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 16px; }
.full-field { grid-column: 1 / -1; }
.config-section { padding: 16px 0; border-top: 1px solid var(--n-border-color); }
.config-heading, .config-heading-main { display: flex; align-items: flex-start; gap: 10px; }
.config-heading { margin-bottom: 14px; }
.config-heading.with-switch { justify-content: space-between; }
.config-heading h3 { margin: 0 0 3px; font-size: 15px; }
.config-heading p { margin: 0; color: var(--n-text-color-3); font-size: 12px; }
.config-heading :deep(.svg-icon) { margin-top: 1px; font-size: 19px; color: var(--n-primary-color); }
.drawer-alert { margin-bottom: 16px; }
.drawer-header { display: flex; width: 100%; align-items: center; justify-content: space-between; gap: 16px; }
.drawer-title { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
.drawer-title strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.drawer-title span, .document-count { color: var(--n-text-color-3); font-size: 12px; }
.catalog-layout { display: grid; min-height: 440px; grid-template-columns: minmax(190px, 240px) minmax(0, 1fr); gap: 16px; }
.directory-panel, .document-panel { min-width: 0; border: 1px solid var(--n-border-color); border-radius: 6px; padding: 12px; }
.directory-panel { overflow: auto; }
.acl-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; width: 100%; }
.acl-row-main { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; min-width: 0; }
.panel-heading, .document-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 10px; }
.document-toolbar > div { display: flex; min-width: 0; align-items: baseline; gap: 8px; }
.document-search { width: min(260px, 100%); }
.document-panel :deep(.n-data-table) { min-width: 0; }
.chunk-content { max-height: 140px; overflow: auto; white-space: pre-wrap; word-break: break-word; }
.chunk-pagination { align-items: center; margin-top: 12px; }
.chunk-page-label { color: var(--n-text-color-3); font-size: 12px; }
.retrieval-result { display: grid; gap: 12px; margin-top: 16px; }
:deep(.primary-cell) { display: flex; min-width: 0; flex-direction: column; gap: 4px; }
:deep(.primary-cell span) { overflow: hidden; color: var(--n-text-color-3); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 640px) {
  .page-header, .toolbar { align-items: stretch; flex-direction: column; }
  .form-grid { grid-template-columns: 1fr; }
  .full-field { grid-column: auto; }
}
@media (max-width: 760px) {
  .drawer-header, .document-toolbar { align-items: stretch; flex-direction: column; }
  .catalog-layout { grid-template-columns: 1fr; }
  .directory-panel { max-height: 220px; }
  .document-search { width: 100%; }
}
</style>
