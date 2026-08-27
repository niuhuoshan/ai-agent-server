<script setup lang="ts">
import { h, onMounted, reactive, ref } from 'vue';
import type { DataTableColumns, FormInst, FormRules } from 'naive-ui';
import { NButton, NCard, NDataTable, NForm, NFormItem, NInput, NInputNumber, NModal, NSpace, useDialog } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  createPortalSlashCommand,
  deletePortalSlashCommand,
  fetchPortalSlashCommands,
  reorderPortalSlashCommands,
  updatePortalSlashCommand,
  type PortalSlashCommand
} from '@/service/api';

const dialog = useDialog();
const loading = ref(false);
const submitting = ref(false);
const commands = ref<PortalSlashCommand[]>([]);
const visible = ref(false);
const editing = ref<PortalSlashCommand | null>(null);
const formRef = ref<FormInst | null>(null);
const form = reactive({ label: '', command: '', sortOrder: 0 });
const rules: FormRules = {
  label: [{ required: true, message: '请输入命令名称', trigger: ['input', 'blur'] }],
  command: [{ required: true, message: '请输入命令内容', trigger: ['input', 'blur'] }]
};

const columns: DataTableColumns<PortalSlashCommand> = [
  { title: '名称', key: 'label', width: 180 },
  { title: '命令', key: 'command', minWidth: 320, ellipsis: { tooltip: true } },
  { title: '排序', key: 'sort_order', width: 90 },
  { title: '更新时间', key: 'updated_at', width: 190, render: row => row.updated_at || row.created_at || '-' },
  {
    title: '操作', key: 'actions', width: 170,
    render: row => h(NSpace, { size: 6 }, () => [
      h(NButton, { size: 'small', onClick: () => openEdit(row) }, () => '编辑'),
      h(NButton, { size: 'small', type: 'error', tertiary: true, onClick: () => remove(row) }, () => '删除')
    ])
  }
];

async function load() {
  loading.value = true;
  const result = await fetchPortalSlashCommands();
  if (!result.error) commands.value = result.data;
  loading.value = false;
}

function openCreate() {
  editing.value = null;
  Object.assign(form, { label: '', command: '', sortOrder: commands.value.length });
  visible.value = true;
}

function openEdit(row: PortalSlashCommand) {
  editing.value = row;
  Object.assign(form, { label: row.label, command: row.command, sortOrder: row.sort_order });
  visible.value = true;
}

async function save() {
  try { await formRef.value?.validate(); } catch { return; }
  submitting.value = true;
  const result = editing.value
    ? await updatePortalSlashCommand(editing.value.id, form)
    : await createPortalSlashCommand(form);
  if (!result.error) {
    visible.value = false;
    window.$message?.success(editing.value ? 'Slash 命令已更新' : 'Slash 命令已创建');
    await load();
  }
  submitting.value = false;
}

function remove(row: PortalSlashCommand) {
  dialog.warning({
    title: '删除 Slash 命令', content: `确认删除“${row.label}”？`, positiveText: '删除', negativeText: '取消',
    onPositiveClick: async () => {
      const result = await deletePortalSlashCommand(row.id);
      if (!result.error) { window.$message?.success('命令已删除'); await load(); }
    }
  });
}

async function persistOrder() {
  const result = await reorderPortalSlashCommands(commands.value.map((item, index) => ({ id: item.id, sortOrder: index })));
  if (!result.error) window.$message?.success('排序已保存');
}

onMounted(load);
</script>

<template>
  <div class="h-full flex-col-stretch gap-12px overflow-hidden p-16px">
    <NCard :bordered="false" size="small" class="shrink-0">
      <template #header><div class="flex items-center gap-8px"><SvgIcon icon="lucide:command" /><span>Slash 命令</span></div></template>
      <template #header-extra><NSpace><NButton secondary :loading="loading" @click="load"><template #icon><SvgIcon icon="lucide:refresh-cw" /></template>刷新</NButton><NButton secondary @click="persistOrder"><template #icon><SvgIcon icon="lucide:list-ordered" /></template>保存排序</NButton><NButton type="primary" @click="openCreate"><template #icon><SvgIcon icon="lucide:plus" /></template>新建命令</NButton></NSpace></template>
    </NCard>
    <NCard :bordered="false" class="min-h-0 flex-1" content-class="h-full">
      <NDataTable :columns="columns" :data="commands" :loading="loading" :single-line="false" flex-height class="h-full" />
    </NCard>
    <NModal v-model:show="visible" preset="card" :title="editing ? '编辑 Slash 命令' : '新建 Slash 命令'" style="width: min(620px, 94vw)">
      <NForm ref="formRef" :model="form" :rules="rules" label-placement="top">
        <NFormItem label="命令名称" path="label"><NInput v-model:value="form.label" maxlength="128" /></NFormItem>
        <NFormItem label="命令内容" path="command"><NInput v-model:value="form.command" type="textarea" maxlength="2048" :autosize="{ minRows: 5, maxRows: 12 }" placeholder="例如：查询本月销售额并按区域汇总" /></NFormItem>
        <NFormItem label="排序"><NInputNumber v-model:value="form.sortOrder" :min="0" :max="9999" /></NFormItem>
      </NForm>
      <template #footer><NSpace justify="end"><NButton @click="visible = false">取消</NButton><NButton type="primary" :loading="submitting" @click="save">保存</NButton></NSpace></template>
    </NModal>
  </div>
</template>
