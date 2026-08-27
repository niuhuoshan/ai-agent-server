<script setup lang="ts">
import * as echarts from "echarts";
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";

const props = defineProps<{
  option: Record<string, unknown>;
}>();

const container = ref<HTMLElement | null>(null);
const renderError = ref<string | null>(null);
let instance: echarts.ECharts | null = null;
let resizeObserver: ResizeObserver | null = null;

async function renderChart() {
  await nextTick();
  if (!container.value) return;
  try {
    instance ||= echarts.init(container.value);
    instance.setOption(props.option, { notMerge: true });
    renderError.value = null;
  } catch (error) {
    instance?.clear();
    renderError.value = error instanceof Error ? error.message : "图表渲染失败";
  }
}

onMounted(() => {
  if (container.value) {
    resizeObserver = new ResizeObserver(() => instance?.resize());
    resizeObserver.observe(container.value);
  }
  void renderChart();
});

watch(() => props.option, () => void renderChart(), { deep: true });

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  instance?.dispose();
});
</script>

<template>
  <div class="message-chart-shell">
    <NAlert v-if="renderError" type="error" :show-icon="true">
      {{ renderError }}
    </NAlert>
    <div v-show="!renderError" ref="container" class="message-chart" />
  </div>
</template>

<style scoped>
.message-chart-shell {
  width: 100%;
  min-width: 0;
}

.message-chart {
  width: 100%;
  height: 320px;
  min-height: 240px;
}
</style>
