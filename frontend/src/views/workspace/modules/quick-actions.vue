<script setup lang="ts">
import { useRouter } from 'vue-router';
import SvgIcon from '@/components/custom/svg-icon.vue';

const router = useRouter();
const quickActions = [
  { title: '任务中心', icon: 'lucide:list-checks', route: '/task-center' },
  { title: '智能体中心', icon: 'lucide:bot', route: '/agent-center' },
  { title: '知识库', icon: 'lucide:library', route: '/knowledge' },
  { title: '审批与审计', icon: 'lucide:shield-check', route: '/risk-control' }
];
</script>

<template>
  <NCard :bordered="false">
    <template #header><div class="card-header"><SvgIcon icon="lucide:layout-grid" class="text-18px" /><span>{{ $t('page.workspace.quickActions') }}</span></div></template>
    <div class="actions-grid">
      <button v-for="action in quickActions" :key="action.route" type="button" class="action-item" @click="router.push(action.route)">
        <SvgIcon :icon="action.icon" class="text-22px" />
        <span>{{ action.title }}</span>
      </button>
    </div>
  </NCard>
</template>

<style scoped lang="scss">
.card-header { display: flex; align-items: center; gap: 8px; font-weight: 500; }
.actions-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.action-item { display: flex; min-height: 76px; align-items: center; justify-content: center; gap: 8px; padding: 10px; border: 1px solid var(--n-border-color); border-radius: 6px; background: transparent; color: inherit; cursor: pointer; font: inherit; }
.action-item:hover { border-color: #18a058; color: #18a058; }
.action-item span { overflow-wrap: anywhere; }
@media (max-width: 380px) { .actions-grid { grid-template-columns: 1fr; } }
</style>
