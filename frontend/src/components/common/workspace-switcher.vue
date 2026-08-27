<script setup lang="ts">
import { computed, h } from 'vue';
import { useRouter } from 'vue-router';
import type { DropdownOption } from 'naive-ui';
import { NDropdown } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';

type WorkspaceMode = 'client' | 'admin';

interface Props {
  mode: WorkspaceMode;
  showTitle?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  showTitle: true
});

const router = useRouter();
const canOpenAdmin = computed(() => router.hasRoute('home'));
const title = computed(() => (props.mode === 'client' ? '用户工作台' : '管理平台'));

const options = computed<DropdownOption[]>(() => [
  {
    label: '用户工作台',
    key: 'client',
    icon: () => h(SvgIcon, { icon: 'lucide:layout-dashboard' })
  },
  {
    label: '管理平台',
    key: 'admin',
    disabled: !canOpenAdmin.value,
    icon: () => h(SvgIcon, { icon: 'lucide:settings-2' })
  }
]);

function handleSelect(key: string | number) {
  if (key === props.mode) return;
  if (key === 'client') {
    void router.replace({ path: '/app' });
  } else if (key === 'admin' && canOpenAdmin.value) {
    void router.replace({ path: '/home' });
  }
}
</script>

<template>
  <NDropdown trigger="click" :options="options" @select="handleSelect">
    <button
      type="button"
      class="workspace-switcher"
      :class="{ 'workspace-switcher--collapsed': !showTitle }"
      :aria-label="`切换工作区，当前为${title}`"
    >
      <SystemLogo class="workspace-switcher-logo" />
      <span v-if="showTitle" class="workspace-switcher-copy">
        <strong>{{ title }}</strong>
        <small>工作区模式</small>
      </span>
      <SvgIcon v-if="showTitle" class="workspace-switcher-chevron" icon="lucide:chevron-down" />
    </button>
  </NDropdown>
</template>

<style scoped>
.workspace-switcher {
  display: flex;
  width: 100%;
  min-height: 52px;
  box-sizing: border-box;
  align-items: center;
  gap: 9px;
  padding: 6px 8px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--n-text-color);
  cursor: pointer;
  text-align: left;
}

.workspace-switcher:hover,
.workspace-switcher:focus-visible {
  background: color-mix(in srgb, var(--n-primary-color) 7%, transparent);
}

.workspace-switcher:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--n-primary-color) 35%, transparent);
  outline-offset: 2px;
}

.workspace-switcher-logo {
  width: 32px;
  height: 32px;
  flex: 0 0 32px;
}

.workspace-switcher-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 1px;
}

.workspace-switcher-copy strong {
  overflow: hidden;
  color: var(--n-primary-color);
  font-size: 16px;
  font-weight: 700;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workspace-switcher-copy small {
  color: var(--n-text-color-3);
  font-size: 10px;
  font-weight: 400;
  line-height: 13px;
  white-space: nowrap;
}

.workspace-switcher-chevron {
  flex: none;
  margin-left: auto;
  color: var(--n-text-color-3);
  font-size: 14px;
}

.workspace-switcher--collapsed {
  justify-content: center;
  padding-inline: 0;
}

@media (max-width: 560px) {
  .workspace-switcher:not(.workspace-switcher--collapsed) {
    width: 42px;
    justify-content: center;
    padding-inline: 0;
  }

  .workspace-switcher:not(.workspace-switcher--collapsed) .workspace-switcher-copy,
  .workspace-switcher:not(.workspace-switcher--collapsed) .workspace-switcher-chevron {
    display: none;
  }
}
</style>
