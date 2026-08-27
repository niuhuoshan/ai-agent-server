<script setup lang="ts">
import { onMounted } from 'vue';
import { useEcharts } from '@/hooks/common/echarts';
import { fetchTasks } from '@/service/api';

defineOptions({
  name: 'PieChart'
});

const { domRef, updateOptions } = useEcharts(() => ({
  tooltip: {
    trigger: 'item'
  },
  legend: {
    bottom: '1%',
    left: 'center',
    itemStyle: {
      borderWidth: 0
    }
  },
  series: [
    {
      color: ['#5da8ff', '#8e9dff', '#fedc69', '#26deca'],
      name: '任务状态',
      type: 'pie',
      radius: ['45%', '75%'],
      avoidLabelOverlap: false,
      itemStyle: {
        borderRadius: 10,
        borderColor: '#fff',
        borderWidth: 1
      },
      label: {
        show: false,
        position: 'center'
      },
      emphasis: {
        label: {
          show: true,
          fontSize: '12'
        }
      },
      labelLine: {
        show: false
      },
      data: [] as { name: string; value: number }[]
    }
  ]
}));

onMounted(async () => {
  const { data, error } = await fetchTasks(500);
  if (error) return;
  const labels: Record<string, string> = {
    draft: '草稿', ready: '就绪', scheduled: '已排期', running: '运行中', waiting: '等待中',
    blocked: '已阻塞', rework: '返工中', completed: '已完成', failed: '失败', cancelled: '已取消'
  };
  const counts = new Map<string, number>();
  data.forEach(task => counts.set(task.status, (counts.get(task.status) || 0) + 1));
  updateOptions(opts => {
    opts.series[0].data = [...counts.entries()].map(([name, value]) => ({ name: labels[name] || name, value }));
    return opts;
  });
});
</script>

<template>
  <NCard :bordered="false" class="card-wrapper">
    <div ref="domRef" class="h-360px overflow-hidden"></div>
  </NCard>
</template>

<style scoped></style>
