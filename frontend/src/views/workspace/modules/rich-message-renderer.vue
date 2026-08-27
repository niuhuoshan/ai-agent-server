<script setup lang="ts">
import { computed, ref } from "vue";
import SvgIcon from "@/components/custom/svg-icon.vue";
import {
  ACTIVE_CODE_EXECUTION_STATUSES,
  chatCodeExecutionKey,
  type ChatCodeExecutionState,
} from "./chat-code-execution";
import MessageChart from "./message-chart.vue";
import MessageCodeExecution from "./message-code-execution.vue";
import MessageMermaid from "./message-mermaid.vue";
import MessageSqlPlan from "./message-sql-plan.vue";
import { parseRichMessage } from "./message-content";

const props = withDefaults(defineProps<{
  content: string;
  streaming?: boolean;
  codeExecutions?: Record<string, ChatCodeExecutionState>;
  codeActions?: boolean;
}>(), {
  streaming: false,
  codeExecutions: () => ({}),
  codeActions: true,
});

const emit = defineEmits<{
  runCode: [payload: { language: string; code: string }];
  stopCode: [execution: ChatCodeExecutionState];
  citation: [citationId: string];
}>();

const segments = computed(() => parseRichMessage(props.content));
const copiedSegmentId = ref<string | null>(null);

function executionFor(language: string, code: string) {
  return props.codeExecutions[chatCodeExecutionKey(language, code)];
}

function executionActive(language: string, code: string) {
  const execution = executionFor(language, code);
  return Boolean(execution && ACTIVE_CODE_EXECUTION_STATUSES.has(execution.status));
}

async function copyCode(segmentId: string, code: string) {
  try {
    await navigator.clipboard.writeText(code);
    copiedSegmentId.value = segmentId;
    window.setTimeout(() => {
      if (copiedSegmentId.value === segmentId) copiedSegmentId.value = null;
    }, 1500);
  } catch {
    window.$message?.error("复制失败");
  }
}

function handleMarkdownClick(event: MouseEvent) {
  const target = event.target as HTMLElement;
  const citation = target.closest<HTMLElement>("[data-cite-id]");
  if (citation?.dataset.citeId) {
    emit("citation", citation.dataset.citeId);
    event.preventDefault();
    return;
  }
  const link = target.closest<HTMLAnchorElement>("a");
  if (link?.href) {
    const protocol = new URL(link.href, window.location.href).protocol;
    if (!["http:", "https:", "mailto:"].includes(protocol)) event.preventDefault();
  }
}
</script>

<template>
  <div class="rich-message" :class="{ streaming }">
    <template v-for="segment in segments" :key="segment.id">
      <div
        v-if="segment.type === 'markdown'"
        class="markdown-body"
        @click="handleMarkdownClick"
        v-html="segment.html"
      />

      <div v-else-if="segment.type === 'code'" class="code-block">
        <header>
          <span>{{ segment.language }}</span>
          <NSpace :size="4" :wrap="false">
            <NTooltip v-if="segment.runnable && codeActions">
              <template #trigger>
                <NButton
                  circle
                  quaternary
                  size="tiny"
                  :disabled="!segment.complete || executionActive(segment.language, segment.content)"
                  aria-label="运行代码"
                  @click="emit('runCode', { language: segment.language, code: segment.content })"
                >
                  <template #icon><SvgIcon icon="lucide:play" /></template>
                </NButton>
              </template>
              运行代码
            </NTooltip>
            <NTooltip>
              <template #trigger>
                <NButton
                  circle
                  quaternary
                  size="tiny"
                  aria-label="复制代码"
                  @click="copyCode(segment.id, segment.content)"
                >
                  <template #icon>
                    <SvgIcon :icon="copiedSegmentId === segment.id ? 'lucide:check' : 'lucide:copy'" />
                  </template>
                </NButton>
              </template>
              {{ copiedSegmentId === segment.id ? "已复制" : "复制代码" }}
            </NTooltip>
          </NSpace>
        </header>
        <NCode :code="segment.content" :language="segment.language" word-wrap />
        <MessageCodeExecution
          v-if="executionFor(segment.language, segment.content)"
          :execution="executionFor(segment.language, segment.content)!"
          @stop="emit('stopCode', executionFor(segment.language, segment.content)!)"
        />
      </div>

      <MessageMermaid
        v-else-if="segment.type === 'mermaid'"
        :content="segment.content"
        :complete="segment.complete || !streaming"
      />

      <template v-else-if="segment.type === 'chart'">
        <NAlert v-if="!segment.complete && streaming" type="info" :show-icon="true">
          正在接收图表配置
        </NAlert>
        <NAlert v-else-if="segment.error" type="error" :show-icon="true">
          {{ segment.error }}
        </NAlert>
        <MessageChart v-else-if="segment.option" :option="segment.option" />
      </template>

      <template v-else-if="segment.type === 'sql-plan'">
        <NAlert v-if="segment.error" type="error" :show-icon="true">
          {{ segment.error }}
        </NAlert>
        <MessageSqlPlan v-else-if="segment.plan" :plan="segment.plan" />
      </template>
    </template>
    <span v-if="streaming" class="stream-caret" aria-label="生成中" />
  </div>
</template>

<style scoped lang="scss">
.rich-message {
  min-width: 0;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.rich-message > * + * {
  margin-top: 12px;
}

.markdown-body :deep(p),
.markdown-body :deep(ul),
.markdown-body :deep(ol),
.markdown-body :deep(blockquote) {
  margin: 0 0 10px;
}

.markdown-body :deep(p:last-child),
.markdown-body :deep(ul:last-child),
.markdown-body :deep(ol:last-child),
.markdown-body :deep(blockquote:last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 16px 0 8px;
  line-height: 1.35;
  letter-spacing: 0;
}

.markdown-body :deep(h1) { font-size: 20px; }
.markdown-body :deep(h2) { font-size: 18px; }
.markdown-body :deep(h3) { font-size: 16px; }
.markdown-body :deep(h4) { font-size: 14px; }

.markdown-body :deep(a) {
  color: var(--n-primary-color);
  text-decoration: none;
}

.markdown-body :deep(a:hover) {
  text-decoration: underline;
}

.markdown-body :deep(blockquote) {
  padding: 8px 12px;
  border-left: 3px solid var(--n-border-color);
  color: var(--n-text-color-2);
  background: rgba(128, 128, 128, 0.06);
}

.markdown-body :deep(table) {
  display: block;
  width: max-content;
  min-width: 100%;
  max-width: 100%;
  border-collapse: collapse;
  overflow-x: auto;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  padding: 7px 10px;
  border: 1px solid var(--n-border-color);
  text-align: left;
  white-space: nowrap;
}

.markdown-body :deep(th) {
  background: rgba(128, 128, 128, 0.08);
}

.markdown-body :deep(code) {
  padding: 1px 4px;
  border-radius: 3px;
  background: rgba(128, 128, 128, 0.1);
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
}

.markdown-body :deep(.citation-badge) {
  display: inline-flex;
  padding: 1px 5px;
  border: 1px solid rgba(32, 128, 240, 0.35);
  border-radius: 4px;
  color: var(--n-primary-color);
  background: rgba(32, 128, 240, 0.08);
  cursor: pointer;
  font: inherit;
  font-size: 12px;
}

.code-block {
  border: 1px solid var(--n-border-color);
  border-radius: 6px;
  overflow: hidden;
  background: rgba(28, 31, 35, 0.98);
  color: #e6edf3;
}

.code-block header {
  display: flex;
  min-height: 34px;
  padding: 4px 6px 4px 12px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.12);
  align-items: center;
  justify-content: space-between;
  color: #b9c1ca;
  font-size: 12px;
}

.code-block :deep(pre) {
  max-height: 520px;
  margin: 0;
  padding: 14px;
  overflow: auto;
  background: transparent;
}

.stream-caret {
  display: inline-block;
  width: 7px;
  height: 16px;
  margin-left: 3px;
  vertical-align: -2px;
  background: var(--n-primary-color);
  animation: blink 1s steps(1) infinite;
}

@keyframes blink {
  50% { opacity: 0; }
}
</style>
