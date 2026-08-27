<script setup lang="ts">
import GlobalContent from '../modules/global-content/index.vue';

defineOptions({
  name: 'ClientLayout'
});
</script>

<template>
  <div class="client-layout">
    <GlobalContent :show-padding="false" />
  </div>
</template>

<style scoped lang="scss">
.client-layout {
  width: 100%;
  height: 100%;
  min-height: 100dvh;
  overflow: hidden;
  background: var(--n-color);
}
</style>
