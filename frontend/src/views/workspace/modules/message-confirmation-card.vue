<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import SvgIcon from '@/components/custom/svg-icon.vue';
import type {
  BusinessConfirmationField,
  BusinessConfirmationState,
} from '@/utils/business-confirmation';

const props = withDefaults(defineProps<{
  confirmation: BusinessConfirmationState;
  disabled?: boolean;
}>(), { disabled: false });

const emit = defineEmits<{
  submit: [payload: { confirmed: boolean; fields: BusinessConfirmationField[] }];
}>();

const draftFields = reactive<BusinessConfirmationField[]>([]);
const submitted = computed(() => props.confirmation.status !== 'pending');
const locked = computed(() => props.disabled || submitted.value);
const statusLabel = computed(() => {
  if (props.confirmation.status === 'stale') return '已过期';
  if (props.confirmation.decision === 'cancelled') return '已取消';
  return submitted.value ? '已确定' : '待确认';
});

function syncFields(value: BusinessConfirmationField[]) {
  draftFields.splice(0, draftFields.length, ...value.map(field => ({ ...field })));
}

watch(() => props.confirmation, value => syncFields(value.fields), { immediate: true, deep: true });

function submit(confirmed: boolean) {
  if (locked.value) return;
  emit('submit', { confirmed, fields: draftFields.map(field => ({ ...field })) });
}

function updateBoolean(field: BusinessConfirmationField, value: boolean) {
  field.value = value;
}

function numberValue(field: BusinessConfirmationField) {
  const value = typeof field.value === 'number' ? field.value : Number(field.value);
  return Number.isFinite(value) ? value : null;
}

function updateNumber(field: BusinessConfirmationField, value: number | null) {
  field.value = value ?? '';
}

function textValue(field: BusinessConfirmationField) {
  return field.value === null || field.value === undefined ? '' : String(field.value);
}

function updateText(field: BusinessConfirmationField, value: string | [string, string] | null) {
  field.value = Array.isArray(value) ? value.join(' - ') : value ?? '';
}
</script>

<template>
  <section class="confirmation-card" :aria-label="confirmation.title || '业务数据确认'">
    <div class="confirmation-header">
      <SvgIcon icon="lucide:badge-check" />
      <div class="confirmation-heading">
        <strong>{{ confirmation.title || '请确认以下信息' }}</strong>
        <NTag
          size="tiny"
          :bordered="false"
          :type="confirmation.status === 'stale' ? 'error' : submitted ? 'success' : 'warning'"
        >
          {{ statusLabel }}
        </NTag>
      </div>
    </div>
    <p v-if="confirmation.summary" class="confirmation-summary">{{ confirmation.summary }}</p>
    <div v-if="draftFields.length" class="confirmation-fields">
      <div v-for="field in draftFields" :key="field.key || field.label" class="confirmation-field">
        <span class="confirmation-label">{{ field.label }}</span>
        <NSwitch
          v-if="field.value_type === 'boolean'"
          :value="Boolean(field.value)"
          :disabled="locked || !field.editable"
          @update:value="updateBoolean(field, $event)"
        />
        <NInputNumber
          v-else-if="field.value_type === 'number'"
          :value="numberValue(field)"
          :disabled="locked || !field.editable"
          size="small"
          class="confirmation-input"
          @update:value="updateNumber(field, $event)"
        />
        <NInput
          v-else
          :value="textValue(field)"
          :disabled="locked || !field.editable"
          :type="field.value_type === 'text' ? 'textarea' : 'text'"
          size="small"
          :autosize="field.value_type === 'text' ? { minRows: 2, maxRows: 4 } : undefined"
          class="confirmation-input"
          @update:value="updateText(field, $event)"
        />
      </div>
    </div>
    <p v-if="confirmation.risk_note" class="confirmation-risk">风险提示：{{ confirmation.risk_note }}</p>
    <NSpace v-if="!submitted" :size="8" class="confirmation-actions">
      <NButton size="small" type="primary" :disabled="locked" @click="submit(true)">
        <template #icon><SvgIcon icon="lucide:check" /></template>
        {{ confirmation.confirm_label || '确定' }}
      </NButton>
      <NButton size="small" secondary :disabled="locked" @click="submit(false)">
        <template #icon><SvgIcon icon="lucide:x" /></template>
        {{ confirmation.cancel_label || '取消' }}
      </NButton>
    </NSpace>
  </section>
</template>

<style scoped>
.confirmation-card { display: grid; gap: 10px; padding: 12px; border: 1px solid color-mix(in srgb, #0284c7 32%, var(--n-border-color)); border-radius: 8px; background: color-mix(in srgb, #0284c7 8%, var(--n-color)); color: var(--n-text-color); }
.confirmation-header, .confirmation-heading, .confirmation-actions { display: flex; align-items: center; gap: 8px; }
.confirmation-heading { min-width: 0; flex: 1; justify-content: space-between; }
.confirmation-heading strong { overflow-wrap: anywhere; font-size: 13px; }
.confirmation-summary, .confirmation-risk { margin: 0; font-size: 12px; line-height: 1.5; overflow-wrap: anywhere; }
.confirmation-risk { color: var(--n-warning-color); }
.confirmation-fields { display: grid; gap: 8px; padding: 10px; border: 1px solid var(--n-border-color); border-radius: 6px; background: var(--n-card-color, var(--n-color)); }
.confirmation-field { display: grid; grid-template-columns: minmax(80px, 30%) minmax(0, 1fr); gap: 10px; align-items: center; }
.confirmation-label { font-size: 12px; font-weight: 600; overflow-wrap: anywhere; }
.confirmation-input { min-width: 0; }
@media (max-width: 520px) { .confirmation-field { grid-template-columns: 1fr; gap: 4px; } }
</style>
