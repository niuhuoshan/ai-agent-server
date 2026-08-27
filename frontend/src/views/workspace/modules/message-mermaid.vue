<script setup lang="ts">
import DOMPurify from "dompurify";
import { onBeforeUnmount, ref, watch } from "vue";

const props = defineProps<{
  content: string;
  complete: boolean;
}>();

const svg = ref("");
const renderError = ref<string | null>(null);
let generation = 0;

async function renderDiagram() {
  const currentGeneration = ++generation;
  if (!props.complete || !props.content.trim()) {
    svg.value = "";
    renderError.value = null;
    return;
  }
  try {
    const { default: mermaid } = await import("mermaid");
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: "strict",
      suppressErrorRendering: true,
      theme: "neutral",
    });
    const id = `agent-mermaid-${crypto.randomUUID().replaceAll("-", "")}`;
    const rendered = await mermaid.render(id, props.content);
    if (generation !== currentGeneration) return;
    svg.value = DOMPurify.sanitize(rendered.svg, {
      USE_PROFILES: { svg: true, svgFilters: true },
    });
    renderError.value = null;
  } catch (error) {
    if (generation !== currentGeneration) return;
    svg.value = "";
    renderError.value = error instanceof Error ? error.message : "Mermaid 渲染失败";
  }
}

watch(() => [props.content, props.complete], () => void renderDiagram(), { immediate: true });
onBeforeUnmount(() => {
  generation += 1;
});
</script>

<template>
  <div class="message-mermaid">
    <NAlert v-if="!complete" type="info" :show-icon="true">正在接收 Mermaid 图表</NAlert>
    <NAlert v-else-if="renderError" type="error" :show-icon="true">{{ renderError }}</NAlert>
    <div v-else class="message-mermaid-svg" v-html="svg" />
  </div>
</template>

<style scoped>
.message-mermaid {
  width: 100%;
  min-width: 0;
  overflow-x: auto;
}

.message-mermaid-svg :deep(svg) {
  display: block;
  max-width: 100%;
  height: auto;
  margin: 0 auto;
}
</style>
