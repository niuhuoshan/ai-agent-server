<script setup lang="ts">
import WorkspaceSwitcher from '@/components/common/workspace-switcher.vue';

defineOptions({
  name: 'GlobalLogo'
});

interface Props {
  /** Whether to show the title */
  showTitle?: boolean;
}

withDefaults(defineProps<Props>(), {
  showTitle: true
});
</script>

<template>
  <div class="global-logo-shell">
    <WorkspaceSwitcher mode="admin" :show-title="showTitle" />
  </div>
</template>

<style scoped>
.global-logo-shell {
  display: flex;
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  align-items: center;
  padding: 6px 12px;
}

.global-logo-shell :deep(.n-dropdown) {
  width: 100%;
}
</style>
