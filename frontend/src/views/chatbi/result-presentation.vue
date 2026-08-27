<script setup lang="ts">
import * as echarts from 'echarts';
import { computed, h, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import type { DataTableColumns } from 'naive-ui';
import { NButton } from 'naive-ui';
import type { PortalChatBIPresentation } from '@/service/api';
import { useThemeStore } from '@/store/modules/theme';

type PresentationMode = 'chart' | 'pivot';
type PivotRow = { index: number; cells: unknown[] };

const props = defineProps<{
  mode: PresentationMode;
  presentation: PortalChatBIPresentation;
}>();

const emit = defineEmits<{
  drilldown: [payload: { dimension: string; value: unknown }];
}>();

const themeStore = useThemeStore();
const chartContainer = ref<HTMLElement | null>(null);
const chartError = ref('');
let chart: echarts.ECharts | null = null;
let observer: ResizeObserver | null = null;

const pivotRows = computed<PivotRow[]>(() =>
  (props.presentation.pivot_data?.rows || []).map((cells, index) => ({ index: index + 1, cells }))
);
const pivotColumns = computed<DataTableColumns<PivotRow>>(() => {
  const columns: DataTableColumns<PivotRow> = [
    { title: '#', key: 'index', width: 56, fixed: 'left', render: row => row.index }
  ];
  for (const [index, title] of (props.presentation.pivot_data?.columns || []).entries()) {
    columns.push({
      title,
      key: `pivot-${index}`,
      minWidth: 140,
      ellipsis: { tooltip: true },
      render: row => formatCell(row.cells[index])
    });
  }
  const dimension = props.presentation.pivot.row_dimensions[0];
  if (dimension) {
    columns.push({
      title: '操作',
      key: 'actions',
      width: 84,
      fixed: 'right',
      render: row =>
        h(
          NButton,
          {
            size: 'tiny',
            quaternary: true,
            type: 'primary',
            onClick: () => emit('drilldown', { dimension, value: row.cells[0] })
          },
          { default: () => '下钻' }
        )
    });
  }
  return columns;
});
const pivotScrollX = computed(() => Math.max(720, pivotColumns.value.length * 150));

function chartOption(): echarts.EChartsOption {
  const { chart: config, chart_data: data } = props.presentation;
  const categories = data?.categories || [];
  const series = data?.series || [];
  if (config.type === 'pie') {
    const first = series[0];
    return {
      tooltip: { trigger: 'item' },
      legend: { type: 'scroll', bottom: 0 },
      series: [
        {
          type: 'pie',
          radius: ['30%', '68%'],
          center: ['50%', '45%'],
          data: categories.map((name, index) => ({ name, value: Number(first?.values[index] ?? 0) }))
        }
      ]
    };
  }
  return {
    tooltip: { trigger: 'axis' },
    legend: { type: 'scroll', top: 0 },
    grid: { left: 16, right: 20, top: 48, bottom: 24, containLabel: true },
    xAxis: { type: 'category', data: categories, axisLabel: { hideOverlap: true } },
    yAxis: { type: 'value', scale: true },
    series: series.map(item => ({
      name: item.name,
      type: config.type === 'line' ? 'line' : 'bar',
      smooth: config.type === 'line',
      data: item.values
    }))
  };
}

async function renderChart() {
  if (props.mode !== 'chart' || props.presentation.chart.type === 'none') {
    chart?.dispose();
    chart = null;
    chartError.value = '';
    return;
  }
  await nextTick();
  if (!chartContainer.value) return;
  try {
    chart ||= echarts.init(chartContainer.value, themeStore.darkMode ? 'dark' : 'light');
    chart.setOption(chartOption(), { notMerge: true });
    chart.off('click');
    chart.on('click', params => {
      const dimension = props.presentation.chart.dimension;
      if (dimension && params.name !== undefined && params.name !== null) {
        const dataIndex = typeof params.dataIndex === 'number' ? params.dataIndex : -1;
        const value = dataIndex >= 0 ? props.presentation.chart_data.category_values?.[dataIndex] : undefined;
        emit('drilldown', { dimension, value: value === undefined ? params.name : value });
      }
    });
    chartError.value = '';
  } catch (error) {
    chart?.clear();
    chartError.value = error instanceof Error ? error.message : '图表渲染失败';
  }
}

function formatCell(value: unknown) {
  if (value === null || value === undefined || value === '') return '-';
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value);
    } catch {
      return String(value);
    }
  }
  return String(value);
}

watch(
  () => [props.mode, props.presentation, themeStore.darkMode],
  async (_current, previous) => {
    if (previous && previous[2] !== themeStore.darkMode) {
      chart?.dispose();
      chart = null;
    }
    await renderChart();
  },
  { deep: true }
);

onMounted(() => {
  if (chartContainer.value) {
    observer = new ResizeObserver(() => chart?.resize());
    observer.observe(chartContainer.value);
  }
  void renderChart();
});

onBeforeUnmount(() => {
  observer?.disconnect();
  chart?.dispose();
});
</script>

<template>
  <div class="presentation-shell">
    <template v-if="mode === 'chart'">
      <NAlert v-if="chartError" type="error" :show-icon="true" class="mb-12px">{{ chartError }}</NAlert>
      <NAlert
        v-else-if="presentation.chart.type === 'none'"
        type="info"
        :show-icon="true"
        title="当前结果尚未配置图表"
      />
      <template v-else>
        <NAlert v-if="presentation.chart_data.truncated" type="warning" :show-icon="true" class="mb-8px">
          图表仅展示前 100 个维度值
        </NAlert>
        <div ref="chartContainer" class="chart-container" />
      </template>
    </template>
    <template v-else>
      <NAlert v-if="presentation.pivot_data.truncated" type="warning" :show-icon="true" class="mb-8px">
        透视表仅展示前 200 个行分组和 50 个列分组
      </NAlert>
      <NDataTable
        v-if="pivotColumns.length > 1"
        :columns="pivotColumns"
        :data="pivotRows"
        :scroll-x="pivotScrollX"
        :max-height="420"
        :row-key="row => row.index"
        :single-line="false"
        size="small"
      />
      <NEmpty v-else description="当前结果无法生成透视表" />
    </template>
  </div>
</template>

<style scoped>
.presentation-shell {
  width: 100%;
  min-width: 0;
}

.chart-container {
  width: 100%;
  height: 360px;
  min-height: 280px;
}
</style>
