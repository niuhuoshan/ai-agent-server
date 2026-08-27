<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { NButton, NInput, NSpace, NTag } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import type { RuntimeUserQuestionState } from '@/utils/runtime-user-question';

const props = withDefaults(defineProps<{
  payload: RuntimeUserQuestionState;
  disabled?: boolean;
}>(), { disabled: false });

const emit = defineEmits<{
  submit: [payload: { selectedOptionIds: string[]; customInput: string; cancelled: boolean }];
}>();

const selectedOptionIds = ref<string[]>([]);
const customInput = ref('');

watch(() => props.payload, value => {
  selectedOptionIds.value = [...(value.selected_option_ids || [])];
  customInput.value = value.custom_input || '';
}, { immediate: true, deep: true });

const locked = computed(() => props.disabled || !props.payload.question_id
  || ['submitted', 'cancelled', 'expired', 'stale'].includes(props.payload.status));
const canSubmit = computed(() => !locked.value && (selectedOptionIds.value.length > 0 || Boolean(customInput.value.trim())));
const statusLabel = computed(() => ({
  pending: '等待回答', submitted: '已提交', cancelled: '已取消', expired: '已过期', stale: '已失效',
}[props.payload.status]));
const statusType = computed(() => ({
  pending: 'warning', submitted: 'success', cancelled: 'default', expired: 'error', stale: 'default',
}[props.payload.status] as 'default' | 'success' | 'warning' | 'error'));

function toggleOption(id: string) {
  if (locked.value) return;
  if (props.payload.is_multi_select) {
    selectedOptionIds.value = selectedOptionIds.value.includes(id)
      ? selectedOptionIds.value.filter(item => item !== id)
      : [...selectedOptionIds.value, id];
  } else {
    selectedOptionIds.value = [id];
  }
}

function submit() {
  if (!canSubmit.value) return;
  emit('submit', { selectedOptionIds: [...selectedOptionIds.value], customInput: customInput.value.trim(), cancelled: false });
}

function cancel() {
  if (locked.value) return;
  emit('submit', { selectedOptionIds: [], customInput: '', cancelled: true });
}
</script>

<template>
  <section class="runtime-question-card" :aria-label="payload.question || 'Agent 提问'">
    <div class="runtime-question-header">
      <span class="runtime-question-icon"><SvgIcon icon="lucide:message-circle-question" /></span>
      <div class="runtime-question-heading">
        <strong>需要你的补充</strong>
        <NTag size="tiny" :bordered="false" :type="statusType">{{ statusLabel }}</NTag>
      </div>
    </div>
    <p class="runtime-question-title">{{ payload.question }}</p>
    <p v-if="payload.context" class="runtime-question-context">{{ payload.context }}</p>
    <div class="runtime-question-options">
      <button
        v-for="option in payload.options"
        :key="option.id"
        type="button"
        class="runtime-question-option"
        :class="{ selected: selectedOptionIds.includes(option.id) }"
        :disabled="locked"
        @click="toggleOption(option.id)"
      >
        <SvgIcon :icon="selectedOptionIds.includes(option.id) ? 'lucide:circle-check' : payload.is_multi_select ? 'lucide:square' : 'lucide:circle'" />
        <span><strong>{{ option.label }}</strong><small v-if="option.description">{{ option.description }}</small></span>
      </button>
    </div>
    <NInput
      v-if="payload.allow_custom_input"
      v-model:value="customInput"
      type="textarea"
      :disabled="locked"
      :autosize="{ minRows: 2, maxRows: 4 }"
      maxlength="4000"
      show-count
      placeholder="补充说明（可选）"
    />
    <NSpace v-if="payload.status === 'pending'" :size="8" class="runtime-question-actions">
      <NButton size="small" type="primary" :disabled="!canSubmit" @click="submit">
        <template #icon><SvgIcon icon="lucide:send" /></template>
        提交回答
      </NButton>
      <NButton size="small" secondary :disabled="locked" @click="cancel">
        <template #icon><SvgIcon icon="lucide:x" /></template>
        取消
      </NButton>
    </NSpace>
    <p v-if="!payload.question_id" class="runtime-question-note">当前事件缺少问题标识，暂时只能查看。</p>
  </section>
</template>

<style scoped>
.runtime-question-card { display: grid; gap: 9px; margin: 6px 0 10px; padding: 12px; border: 1px solid color-mix(in srgb, var(--n-primary-color) 35%, var(--n-border-color)); border-radius: 8px; background: color-mix(in srgb, var(--n-primary-color) 8%, var(--n-color)); color: var(--n-text-color); }
.runtime-question-header, .runtime-question-heading, .runtime-question-actions { display: flex; align-items: center; gap: 8px; }
.runtime-question-icon { display: inline-flex; width: 26px; height: 26px; border-radius: 6px; background: color-mix(in srgb, var(--n-primary-color) 15%, transparent); color: var(--n-primary-color); align-items: center; justify-content: center; }
.runtime-question-heading { min-width: 0; flex: 1; justify-content: space-between; }
.runtime-question-heading strong { font-size: 13px; }
.runtime-question-title, .runtime-question-context, .runtime-question-note { margin: 0; line-height: 1.5; overflow-wrap: anywhere; }
.runtime-question-title { font-size: 14px; font-weight: 600; }
.runtime-question-context, .runtime-question-note { color: var(--n-text-color-3); font-size: 12px; }
.runtime-question-options { display: grid; gap: 6px; }
.runtime-question-option { display: flex; width: 100%; padding: 8px 10px; border: 1px solid var(--n-border-color); border-radius: 6px; background: var(--n-color); color: var(--n-text-color-2); cursor: pointer; align-items: flex-start; gap: 8px; text-align: left; transition: border-color .15s ease, background-color .15s ease; }
.runtime-question-option:hover:not(:disabled), .runtime-question-option.selected { border-color: var(--n-primary-color); background: color-mix(in srgb, var(--n-primary-color) 7%, var(--n-color)); color: var(--n-text-color); }
.runtime-question-option:disabled { cursor: default; opacity: .72; }
.runtime-question-option > svg { margin-top: 1px; flex: 0 0 auto; color: var(--n-primary-color); }
.runtime-question-option > span { display: grid; min-width: 0; gap: 2px; }
.runtime-question-option small { color: var(--n-text-color-3); font-size: 11px; line-height: 1.4; overflow-wrap: anywhere; }
</style>
