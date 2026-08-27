<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { createReusableTemplate } from '@vueuse/core';
import { useThemeStore } from '@/store/modules/theme';
import { fetchApprovals, fetchNotificationUnreadCount, fetchTasks } from '@/service/api';

defineOptions({
  name: 'CardData'
});

interface CardData {
  key: string;
  title: string;
  value: number;
  unit: string;
  color: {
    start: string;
    end: string;
  };
  icon: string;
}

const taskCount = ref(0);
const runningCount = ref(0);
const pendingApprovalCount = ref(0);
const unreadCount = ref(0);

const cardData = computed<CardData[]>(() => [
  {
    key: 'visitCount',
    title: '任务总数',
    value: taskCount.value,
    unit: '',
    color: {
      start: '#ec4786',
      end: '#b955a4'
    },
    icon: 'ant-design:bar-chart-outlined'
  },
  {
    key: 'turnover',
    title: '运行中任务',
    value: runningCount.value,
    unit: '',
    color: {
      start: '#865ec0',
      end: '#5144b4'
    },
    icon: 'ant-design:money-collect-outlined'
  },
  {
    key: 'downloadCount',
    title: '待审批请求',
    value: pendingApprovalCount.value,
    unit: '',
    color: {
      start: '#56cdf3',
      end: '#719de3'
    },
    icon: 'carbon:document-download'
  },
  {
    key: 'dealCount',
    title: '未读通知',
    value: unreadCount.value,
    unit: '',
    color: {
      start: '#fcbc25',
      end: '#f68057'
    },
    icon: 'ant-design:trademark-circle-outlined'
  }
]);

onMounted(async () => {
  const [tasks, approvals, notifications] = await Promise.all([
    fetchTasks(200),
    fetchApprovals('pending', 200),
    fetchNotificationUnreadCount()
  ]);
  if (!tasks.error) {
    taskCount.value = tasks.data.length;
    runningCount.value = tasks.data.filter(task => ['running', 'waiting', 'blocked', 'rework'].includes(task.status)).length;
  }
  if (!approvals.error) pendingApprovalCount.value = approvals.data.length;
  if (!notifications.error) unreadCount.value = notifications.data;
});

interface GradientBgProps {
  gradientColor: string;
}

const [DefineGradientBg, GradientBg] = createReusableTemplate<GradientBgProps>();

const themeStore = useThemeStore();

function getGradientColor(color: CardData['color']) {
  return `linear-gradient(to bottom right, ${color.start}, ${color.end})`;
}
</script>

<template>
  <NCard :bordered="false" size="small" class="card-wrapper">
    <!-- define component start: GradientBg -->
    <DefineGradientBg v-slot="{ $slots, gradientColor }">
      <div
        class="px-16px pb-4px pt-8px text-white"
        :style="{ backgroundImage: gradientColor, borderRadius: themeStore.themeRadius + 'px' }"
      >
        <component :is="$slots.default" />
      </div>
    </DefineGradientBg>
    <!-- define component end: GradientBg -->

    <NGrid cols="s:1 m:2 l:4" responsive="screen" :x-gap="16" :y-gap="16">
      <NGi v-for="item in cardData" :key="item.key">
        <GradientBg :gradient-color="getGradientColor(item.color)" class="flex-1">
          <h3 class="text-16px">{{ item.title }}</h3>
          <div class="flex justify-between pt-12px">
            <SvgIcon :icon="item.icon" class="text-32px" />
            <CountTo
              :prefix="item.unit"
              :start-value="1"
              :end-value="item.value"
              class="text-30px text-white dark:text-dark"
            />
          </div>
        </GradientBg>
      </NGi>
    </NGrid>
  </NCard>
</template>

<style scoped></style>
