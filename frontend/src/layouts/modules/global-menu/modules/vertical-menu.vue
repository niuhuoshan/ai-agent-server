<script setup lang="ts">
import { computed } from 'vue';
import { SimpleScrollbar } from '@sa/materials';
import type { MenuGroupOption } from 'naive-ui';
import { GLOBAL_SIDER_MENU_ID } from '@/constants/app';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import { useRouteStore } from '@/store/modules/route';
import { useRouterPush } from '@/hooks/common/router';
import { useMenu } from '../context';

defineOptions({
  name: 'VerticalMenu'
});

const appStore = useAppStore();
const themeStore = useThemeStore();
const routeStore = useRouteStore();
const { routerPushByKeyWithMetaQuery } = useRouterPush();
const { selectedKey } = useMenu();

const inverted = computed(() => !themeStore.darkMode && themeStore.sider.inverted);

const menuOptions = computed<MenuGroupOption[]>(() =>
  routeStore.menuGroups.map(group => ({
    type: 'group',
    key: `menu-group-${group.key}`,
    label: group.label,
    children: group.menus
  }))
);
</script>

<template>
  <Teleport :to="`#${GLOBAL_SIDER_MENU_ID}`">
    <div class="global-vertical-menu">
      <SimpleScrollbar>
        <NMenu
          mode="vertical"
          :value="selectedKey"
          :collapsed="appStore.siderCollapse"
          :collapsed-width="themeStore.sider.collapsedWidth"
          :collapsed-icon-size="22"
          :options="menuOptions"
          :inverted="inverted"
          :indent="18"
          @update:value="routerPushByKeyWithMetaQuery"
        />
      </SimpleScrollbar>
    </div>
  </Teleport>
</template>

<style scoped>
.global-vertical-menu {
  height: 100%;
  display: flex;
  min-height: 0;
  flex-direction: column;
}

:deep(.n-menu-item-group-title) {
  padding-top: 14px;
  padding-bottom: 4px;
  color: var(--n-group-text-color);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  line-height: 18px;
  text-transform: uppercase;
}
</style>
