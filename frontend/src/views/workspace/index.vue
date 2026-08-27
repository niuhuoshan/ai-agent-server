<script setup lang="ts">
import { ref } from 'vue';
import { NButton, NDrawer, NDrawerContent } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import ChatPanel from "./modules/chat-panel.vue";
import BrowserPanel from './modules/browser-panel.vue';

const browserVisible = ref(false);
</script>

<template>
  <div class="workspace-container">
    <ChatPanel />
    <NButton
      class="browser-launcher"
      type="primary"
      circle
      title="打开浏览器控制"
      aria-label="打开浏览器控制"
      @click="browserVisible = true"
    >
      <template #icon><SvgIcon icon="lucide:globe-2" /></template>
    </NButton>
    <NDrawer v-model:show="browserVisible" placement="right" width="min(560px, 100vw)">
      <NDrawerContent title="浏览器控制" closable>
        <BrowserPanel />
      </NDrawerContent>
    </NDrawer>
  </div>
</template>

<style scoped lang="scss">
.workspace-container {
  position: relative;
  height: 100%;
  min-height: 0;
  padding: 12px;
  overflow: hidden;
}

.browser-launcher {
  position: absolute;
  z-index: 10;
  top: 22px;
  right: 28px;
  box-shadow: 0 4px 14px rgb(0 0 0 / 12%);
}

@media (max-width: 640px) {
  .workspace-container {
    padding: 0;
  }
}
</style>
