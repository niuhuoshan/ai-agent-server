<script setup lang="ts">
import { onMounted, ref } from 'vue';
import dayjs from 'dayjs';
import { useRouter } from 'vue-router';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { fetchTasks } from '@/service/api';
import type { TaskView } from '@/service/api';

const router = useRouter();
const loading = ref(false);
const recentTasks = ref<TaskView[]>([]);

function statusType(status: string) {
  if (status === 'completed') return 'success';
  if (status === 'failed') return 'error';
  if (['running', 'ready'].includes(status)) return 'info';
  if (['waiting', 'paused'].includes(status)) return 'warning';
  return 'default';
}

function statusText(status: string) {
  return (
    {
      draft: '草稿',
      ready: '就绪',
      running: '运行中',
      waiting: '等待中',
      paused: '已暂停',
      completed: '已完成',
      failed: '失败',
      cancelled: '已取消'
    } as Record<string, string>
  )[status] || status;
}

async function loadTasks() {
  loading.value = true;
  const { data, error } = await fetchTasks(8);
  if (!error) recentTasks.value = data.slice(0, 8);
  loading.value = false;
}

function viewAllTasks() {
  router.push('/task-center');
}

onMounted(loadTasks);
</script>

<template>
  <NCard :bordered="false" class="recent-tasks-card">
    <template #header>
      <div class="card-header">
        <div class="header-left">
          <SvgIcon icon="lucide:list-checks" class="text-18px" />
          <span>{{ $t('page.workspace.recentTasks') }}</span>
        </div>
        <NButton text size="small" @click="viewAllTasks">查看全部</NButton>
      </div>
    </template>

    <NSpin :show="loading">
      <div v-if="recentTasks.length" class="tasks-list">
        <button v-for="task in recentTasks" :key="task.id" type="button" class="task-item" @click="viewAllTasks">
          <div class="task-title">{{ task.title }}</div>
          <div class="task-meta">
            <NTag size="small" :type="statusType(task.status)" :bordered="false">{{ statusText(task.status) }}</NTag>
            <span>{{ dayjs(task.createdAt).format('MM-DD HH:mm') }}</span>
          </div>
        </button>
      </div>
      <NEmpty v-else description="暂无最近任务" />
    </NSpin>
  </NCard>
</template>

<style scoped lang="scss">
.card-header,
.header-left,
.task-meta {
  display: flex;
  align-items: center;
}

.card-header {
  justify-content: space-between;
}

.header-left {
  gap: 8px;
  font-weight: 500;
}

.tasks-list {
  max-height: 430px;
  overflow-y: auto;
}

.task-item {
  width: 100%;
  padding: 14px 0;
  border: 0;
  border-bottom: 1px solid var(--n-border-color);
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-align: left;
}

.task-item:last-child {
  border-bottom: 0;
}

.task-title {
  overflow: hidden;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-meta {
  gap: 10px;
  margin-top: 8px;
  color: var(--n-text-color-3);
  font-size: 12px;
}
</style>
