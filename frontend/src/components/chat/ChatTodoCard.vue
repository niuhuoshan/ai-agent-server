<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { NTag } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import type { ExecutionEventView } from '@/service/api';
import {
  latestRuntimeTodo,
  latestRuntimeTodoFromTimeline,
  type RuntimeTimelineItem,
  type RuntimeTodoSnapshot,
} from '@/utils/runtime-timeline';

const props = withDefaults(defineProps<{
  events?: ExecutionEventView[];
  timeline?: RuntimeTimelineItem[];
}>(), {
  events: () => [],
  timeline: undefined,
});

const expanded = ref(true);
const closed = ref(false);
const todo = computed<RuntimeTodoSnapshot | undefined>(() => props.timeline
  ? latestRuntimeTodoFromTimeline(props.timeline)
  : latestRuntimeTodo(props.events));

watch(todo, (next, previous) => {
  if (!next) return;
  if (!previous || next.id !== previous.id) {
    closed.value = false;
    expanded.value = true;
  }
  if (next.todos.length && next.counts.completed === next.todos.length) expanded.value = false;
}, { deep: true });

function statusType(status: string) {
  if (status === 'completed') return 'success';
  if (status === 'in_progress') return 'warning';
  return 'default';
}

function statusLabel(status: string) {
  return status === 'completed' ? '已完成' : status === 'in_progress' ? '进行中' : '待处理';
}
</script>

<template>
  <section v-if="todo && !closed" class="runtime-todo-card" aria-label="执行任务清单">
    <div class="runtime-todo-header">
      <button
        type="button"
        class="runtime-todo-toggle"
        :aria-expanded="expanded"
        @click="expanded = !expanded"
      >
        <SvgIcon icon="lucide:list-checks" />
        <strong>{{ todo.title }}</strong>
        <NTag size="tiny" :bordered="false" :type="todo.counts.completed === todo.todos.length ? 'success' : 'info'">
          {{ todo.counts.completed }}/{{ todo.todos.length }} 已完成
        </NTag>
        <SvgIcon :icon="expanded ? 'lucide:chevron-up' : 'lucide:chevron-down'" />
      </button>
      <button type="button" class="runtime-todo-close" aria-label="隐藏任务清单" title="隐藏任务清单" @click="closed = true">
        <SvgIcon icon="lucide:x" />
      </button>
    </div>
    <div v-show="expanded" class="runtime-todo-list">
      <div v-for="item in todo.todos" :key="item.content" class="runtime-todo-row" :class="{ 'runtime-todo-completed': item.status === 'completed' }">
        <SvgIcon :icon="item.status === 'completed' ? 'lucide:circle-check' : item.status === 'in_progress' ? 'lucide:loader-circle' : 'lucide:circle'" :class="{ 'runtime-spin': item.status === 'in_progress' }" />
        <span>{{ item.content }}</span>
        <NTag v-if="item.status !== 'pending'" size="tiny" :bordered="false" :type="statusType(item.status)">{{ statusLabel(item.status) }}</NTag>
      </div>
    </div>
  </section>
</template>

<style scoped>
.runtime-todo-card { display: grid; gap: 5px; margin: 6px 0 10px; padding: 8px 10px; border: 1px solid var(--n-border-color); border-radius: 8px; background: color-mix(in srgb, var(--n-color) 94%, var(--n-primary-color)); color: var(--n-text-color-2); font-size: 12px; }
.runtime-todo-header { display: flex; min-width: 0; align-items: center; gap: 7px; }
.runtime-todo-toggle { display: flex; min-width: 0; padding: 0; border: 0; background: transparent; color: inherit; cursor: pointer; align-items: center; gap: 7px; flex: 1; text-align: left; }
.runtime-todo-toggle strong { min-width: 0; overflow: hidden; color: var(--n-text-color); text-overflow: ellipsis; white-space: nowrap; }
.runtime-todo-toggle > svg:last-child { flex: 0 0 auto; color: var(--n-text-color-3); }
.runtime-todo-close { display: inline-flex; width: 22px; height: 22px; padding: 0; border: 0; border-radius: 4px; background: transparent; color: var(--n-text-color-3); cursor: pointer; align-items: center; justify-content: center; }
.runtime-todo-close:hover { background: var(--n-action-color); color: var(--n-text-color); }
.runtime-todo-list { display: grid; gap: 3px; padding: 6px 0 0 24px; border-top: 1px solid var(--n-border-color); }
.runtime-todo-row { display: flex; min-width: 0; min-height: 23px; align-items: center; gap: 7px; }
.runtime-todo-row > svg { flex: 0 0 auto; color: var(--n-text-color-3); }
.runtime-todo-row > span { min-width: 0; overflow-wrap: anywhere; flex: 1; }
.runtime-todo-completed { color: var(--n-text-color-3); text-decoration: line-through; }
.runtime-todo-completed > svg { color: var(--n-success-color); }
.runtime-spin { animation: runtime-spin 1.1s linear infinite; color: var(--n-warning-color) !important; }
@keyframes runtime-spin { to { transform: rotate(360deg); } }
</style>
