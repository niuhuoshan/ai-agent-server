<script setup lang="ts">
import { computed } from "vue";
import SvgIcon from "@/components/custom/svg-icon.vue";
import {
  ACTIVE_CODE_EXECUTION_STATUSES,
  type ChatCodeExecutionState,
} from "./chat-code-execution";

const props = defineProps<{
  execution: ChatCodeExecutionState;
}>();

defineEmits<{
  stop: [];
  replay: [];
}>();

const active = computed(() => ACTIVE_CODE_EXECUTION_STATUSES.has(props.execution.status));
const statusText = computed(() => ({
  connecting: "正在提交",
  queued: "排队中",
  leased: "Runner 已领取",
  running: "运行中",
  reconnecting: "输出流重连中",
  succeeded: "执行成功",
  failed: "执行失败",
  cancelled: "已停止",
  expired: "租约已过期",
  timed_out: "执行超时",
})[props.execution.status]);
const statusType = computed(() => {
  if (props.execution.status === "succeeded") return "success";
  if (["failed", "expired", "timed_out"].includes(props.execution.status)) return "error";
  if (props.execution.status === "cancelled") return "warning";
  return "info";
});
</script>

<template>
  <section class="code-execution-card">
    <header>
      <div>
        <NTag size="tiny" :type="statusType" :bordered="false">{{ statusText }}</NTag>
        <span v-if="execution.executionId">#{{ execution.executionId }}</span>
        <span v-if="execution.elapsedMs !== null">{{ execution.elapsedMs }} ms</span>
        <span v-if="execution.exitCode !== null">exit {{ execution.exitCode }}</span>
      </div>
      <NTooltip v-if="active && execution.executionId">
        <template #trigger>
          <NButton circle quaternary size="tiny" aria-label="停止代码执行" @click="$emit('stop')">
            <template #icon><SvgIcon icon="lucide:square" /></template>
          </NButton>
        </template>
        停止执行
      </NTooltip>
      <NTooltip v-else-if="execution.executionId && !execution.outputLoaded">
        <template #trigger>
          <NButton circle quaternary size="tiny" aria-label="加载代码执行输出" @click="$emit('replay')">
            <template #icon><SvgIcon icon="lucide:download" /></template>
          </NButton>
        </template>
        加载输出
      </NTooltip>
    </header>

    <NAlert v-if="execution.errorMessage" type="error" :title="execution.errorCode || '执行错误'" :show-icon="true">
      {{ execution.errorMessage }}
    </NAlert>
    <pre v-if="execution.stdout" class="stdout"><code>{{ execution.stdout }}</code></pre>
    <pre v-if="execution.stderr" class="stderr"><code>{{ execution.stderr }}</code></pre>
    <div v-if="!execution.stdout && !execution.stderr && active" class="output-pending">
      <SvgIcon icon="lucide:loader-circle" />
      等待输出
    </div>
    <NTag v-if="execution.truncated" size="tiny" type="warning" :bordered="false">输出已截断</NTag>
  </section>
</template>

<style scoped>
.code-execution-card {
  display: grid;
  padding: 10px;
  border-top: 1px solid rgba(255, 255, 255, 0.12);
  background: #101419;
  gap: 8px;
}

.code-execution-card header,
.code-execution-card header > div,
.output-pending {
  display: flex;
  align-items: center;
  gap: 8px;
}

.code-execution-card header {
  min-height: 28px;
  padding: 0;
  border: 0;
  justify-content: space-between;
  color: #aab4bf;
}

pre {
  max-height: 300px;
  margin: 0;
  padding: 10px;
  overflow: auto;
  border-radius: 4px;
  color: #dce3ea;
  background: #080b0e;
  white-space: pre-wrap;
}

.stderr {
  color: #ffb4ab;
}

.output-pending {
  min-height: 34px;
  color: #9da8b3;
  font-size: 12px;
}

.output-pending svg {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
