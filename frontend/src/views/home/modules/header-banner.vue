<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useAppStore } from '@/store/modules/app';
import { useAuthStore } from '@/store/modules/auth';
import { $t } from '@/locales';
import { fetchNotificationUnreadCount, fetchProjects, fetchTasks } from '@/service/api';

defineOptions({
  name: 'HeaderBanner'
});

const appStore = useAppStore();
const authStore = useAuthStore();

const gap = computed(() => (appStore.isMobile ? 0 : 16));
const projectCount = ref(0);
const todoCount = ref(0);
const taskCount = ref(0);

interface StatisticData {
  id: number;
  label: string;
  value: string;
}

const statisticData = computed<StatisticData[]>(() => [
  {
    id: 0,
    label: $t('page.home.projectCount'),
    value: String(projectCount.value)
  },
  {
    id: 1,
    label: $t('page.home.todo'),
    value: `${todoCount.value}/${taskCount.value}`
  },
  {
    id: 2,
    label: $t('page.home.message'),
    value: String(unreadCount.value)
  }
]);

const unreadCount = ref(0);

onMounted(async () => {
  const [projects, tasks, unread] = await Promise.all([
    fetchProjects('active'),
    fetchTasks(200),
    fetchNotificationUnreadCount()
  ]);
  if (!projects.error) projectCount.value = projects.data.length;
  if (!tasks.error) {
    taskCount.value = tasks.data.length;
    todoCount.value = tasks.data.filter(task => ['ready', 'scheduled', 'running', 'waiting', 'blocked', 'rework'].includes(task.status)).length;
  }
  if (!unread.error) unreadCount.value = unread.data;
});
</script>

<template>
  <NCard :bordered="false" class="card-wrapper">
    <NGrid :x-gap="gap" :y-gap="16" responsive="screen" item-responsive>
      <NGi span="24 s:24 m:18">
        <div class="flex-y-center">
          <div class="platform-avatar size-72px shrink-0 overflow-hidden rd-1/2" aria-label="牛火山企业智能体平台">牛</div>
          <div class="pl-12px">
            <h3 class="text-18px font-semibold">
              {{ $t('page.home.greeting', { userName: authStore.userInfo.userName }) }}
            </h3>
            <p class="text-#999 leading-30px">{{ $t('page.home.weatherDesc') }}</p>
          </div>
        </div>
      </NGi>
      <NGi span="24 s:24 m:6">
        <NSpace :size="24" justify="end">
          <NStatistic v-for="item in statisticData" :key="item.id" class="whitespace-nowrap" v-bind="item" />
        </NSpace>
      </NGi>
    </NGrid>
  </NCard>
</template>

<style scoped>
.platform-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  background: #18a058;
  color: #fff;
  font-size: 24px;
  font-weight: 600;
}
</style>
