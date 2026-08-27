<script setup lang="ts">
import { computed, ref } from 'vue';
import { NTag } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import type { ExecutionEventView } from '@/service/api';
import {
  parseRuntimeTimeline,
  runtimeTimelineHasPending,
  type RuntimeTimelineItem,
  type RuntimeTimelineStatus,
} from '@/utils/runtime-timeline';

const props = withDefaults(defineProps<{
  events?: ExecutionEventView[];
  items?: RuntimeTimelineItem[];
  active?: boolean;
  bordered?: boolean;
}>(), {
  events: () => [],
  items: undefined,
  active: false,
  bordered: true,
});

const expanded = ref(false);
const expandedItems = ref<Record<string, boolean>>({});

const timelineItems = computed(() => {
  const source = props.items || parseRuntimeTimeline(props.events);
  return source.filter(item => item.kind !== 'question' && item.kind !== 'todo');
});

const visible = computed(() => timelineItems.value.length > 0);
const pending = computed(() => props.active || runtimeTimelineHasPending(timelineItems.value));
const current = computed(() => [...timelineItems.value].reverse().find(item => item.status === 'running' || item.status === 'warning') || timelineItems.value.at(-1));
const summary = computed(() => current.value?.title || `${timelineItems.value.length} 个执行步骤`);

const depthMap = computed(() => {
  const byId = new Map(timelineItems.value.map(item => [item.id, item]));
  const result = new Map<string, number>();
  const depth = (item: RuntimeTimelineItem, seen = new Set<string>()): number => {
    if (!item.parentId || !byId.has(item.parentId) || seen.has(item.id)) return 0;
    seen.add(item.id);
    return Math.min(3, 1 + depth(byId.get(item.parentId)!, seen));
  };
  timelineItems.value.forEach(item => result.set(item.id, depth(item)));
  return result;
});

function icon(item: RuntimeTimelineItem) {
  const icons: Record<RuntimeTimelineItem['kind'], string> = {
    run: 'lucide:play-circle', reasoning: 'lucide:brain-circuit', output: 'lucide:message-square-text',
    tool: 'lucide:wrench', approval: 'lucide:shield-check', subagent: 'lucide:git-branch',
    question: 'lucide:message-circle-question', todo: 'lucide:list-checks', error: 'lucide:circle-alert',
  };
  return icons[item.kind];
}

function statusType(status: RuntimeTimelineStatus) {
  if (status === 'success') return 'success';
  if (status === 'error') return 'error';
  if (status === 'warning' || status === 'running' || status === 'pending') return 'warning';
  if (status === 'cancelled') return 'default';
  return 'info';
}

function statusLabel(status: RuntimeTimelineStatus) {
  return {
    pending: '等待中', running: '进行中', success: '完成', error: '失败', warning: '等待处理', cancelled: '已停止',
  }[status];
}

function toggleItem(id: string) {
  expandedItems.value[id] = !expandedItems.value[id];
}

function formatTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleTimeString('zh-CN', { hour12: false });
}
</script>

<template>
  <section v-if="visible" class="runtime-timeline" :class="{ 'runtime-timeline-bordered': bordered }">
    <button
      type="button"
      class="runtime-timeline-header"
      :aria-expanded="expanded"
      aria-label="展开或折叠执行时间线"
      @click="expanded = !expanded"
    >
      <span class="runtime-timeline-heading">
        <SvgIcon :icon="pending ? 'lucide:loader-circle' : 'lucide:activity'" :class="{ 'runtime-spin': pending }" />
        <strong>执行过程</strong>
        <NTag size="tiny" :bordered="false" :type="pending ? 'warning' : 'default'">{{ summary }}</NTag>
      </span>
      <span class="runtime-timeline-header-meta">
        <span>{{ timelineItems.length }} 步</span>
        <SvgIcon :icon="expanded ? 'lucide:chevron-up' : 'lucide:chevron-down'" />
      </span>
    </button>

    <div v-show="expanded" class="runtime-timeline-list">
      <div
        v-for="item in timelineItems"
        :key="item.id"
        class="runtime-timeline-row"
        :class="[`runtime-timeline-${item.status}`, { 'runtime-timeline-row-expandable': item.details }]"
        :style="{ '--timeline-depth': depthMap.get(item.id) || 0 }"
      >
        <span class="runtime-timeline-marker"><SvgIcon :icon="icon(item)" /></span>
        <div class="runtime-timeline-copy">
          <button
            v-if="item.details"
            type="button"
            class="runtime-timeline-title runtime-timeline-title-button"
            :aria-expanded="Boolean(expandedItems[item.id])"
            @click="toggleItem(item.id)"
          >
            <span>{{ item.title }}</span>
            <SvgIcon :icon="expandedItems[item.id] ? 'lucide:chevron-up' : 'lucide:chevron-down'" />
          </button>
          <strong v-else class="runtime-timeline-title">{{ item.title }}</strong>
          <span v-if="item.summary" class="runtime-timeline-summary">{{ item.summary }}</span>
          <pre v-if="item.details && expandedItems[item.id]" class="runtime-timeline-details">{{ item.details }}</pre>
        </div>
        <div class="runtime-timeline-meta">
          <NTag size="tiny" :bordered="false" :type="statusType(item.status)">{{ statusLabel(item.status) }}</NTag>
          <time>{{ formatTime(item.occurredAt) }}</time>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.runtime-timeline { display: grid; gap: 4px; margin: 6px 0 10px; color: var(--n-text-color-2); font-size: 12px; }
.runtime-timeline-bordered { padding: 8px 10px; border: 1px solid var(--n-border-color); border-radius: 8px; background: color-mix(in srgb, var(--n-color) 94%, var(--n-primary-color)); }
.runtime-timeline-header { display: flex; width: 100%; min-height: 28px; padding: 0; border: 0; background: transparent; color: inherit; cursor: pointer; align-items: center; justify-content: space-between; gap: 10px; text-align: left; }
.runtime-timeline-heading, .runtime-timeline-header-meta { display: inline-flex; min-width: 0; align-items: center; gap: 7px; }
.runtime-timeline-heading strong { color: var(--n-text-color); font-size: 12px; }
.runtime-timeline-header-meta { color: var(--n-text-color-3); font-size: 11px; }
.runtime-timeline-list { display: grid; gap: 2px; padding-top: 4px; }
.runtime-timeline-row { display: grid; min-width: 0; padding: 5px 4px 5px calc(4px + var(--timeline-depth) * 15px); border-top: 1px solid color-mix(in srgb, var(--n-border-color) 65%, transparent); grid-template-columns: 18px minmax(0, 1fr) auto; align-items: start; gap: 7px; }
.runtime-timeline-marker { display: inline-flex; width: 18px; height: 20px; align-items: center; justify-content: center; color: var(--n-text-color-3); }
.runtime-timeline-running .runtime-timeline-marker, .runtime-timeline-warning .runtime-timeline-marker { color: var(--n-warning-color); }
.runtime-timeline-error .runtime-timeline-marker { color: var(--n-error-color); }
.runtime-timeline-success .runtime-timeline-marker { color: var(--n-success-color); }
.runtime-timeline-copy { display: grid; min-width: 0; gap: 2px; }
.runtime-timeline-title { min-width: 0; overflow: hidden; color: var(--n-text-color); font-size: 12px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.runtime-timeline-title-button { display: flex; width: 100%; padding: 0; border: 0; background: transparent; cursor: pointer; align-items: center; justify-content: space-between; gap: 6px; text-align: left; }
.runtime-timeline-title-button svg { flex: 0 0 auto; color: var(--n-text-color-3); }
.runtime-timeline-summary { overflow: hidden; color: var(--n-text-color-3); text-overflow: ellipsis; white-space: nowrap; }
.runtime-timeline-details { max-height: 180px; margin: 4px 0 0; padding: 5px 7px; overflow: auto; border-radius: 4px; background: color-mix(in srgb, var(--n-text-color) 5%, transparent); color: var(--n-text-color-2); font: inherit; line-height: 1.5; white-space: pre-wrap; overflow-wrap: anywhere; }
.runtime-timeline-meta { display: inline-flex; align-items: center; gap: 7px; color: var(--n-text-color-3); font-size: 10px; white-space: nowrap; }
.runtime-spin { animation: runtime-spin 1.1s linear infinite; }
@keyframes runtime-spin { to { transform: rotate(360deg); } }
</style>
