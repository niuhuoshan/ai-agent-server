<script setup lang="ts">
import { computed, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import SvgIcon from '@/components/custom/svg-icon.vue';
import WorkspaceSwitcher from '@/components/common/workspace-switcher.vue';
import ChatPanel from '@/views/workspace/modules/chat-panel.vue';
import TaskCenter from '@/views/task-center/index.vue';
import ProjectCenter from '@/views/project-center/index.vue';
import { useAuthStore } from '@/store/modules/auth';

type ClientSurface = 'chat' | 'tasks' | 'projects';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const surface = computed<ClientSurface>(() => {
  const value = route.query.view;
  return value === 'tasks' || value === 'projects' ? value : 'chat';
});

const surfaceTitle = computed(() => ({
  chat: '工作台',
  tasks: '任务',
  projects: '项目'
}[surface.value]));
const surfaceMeta = computed(() => ({
  chat: { icon: 'lucide:sparkles', description: '与智能体协作，快速推进您的工作' },
  tasks: { icon: 'lucide:clipboard-list', description: '管理和跟踪您的任务进度' },
  projects: { icon: 'lucide:folder-kanban', description: '集中查看项目进展与协作信息' },
}[surface.value]));
const taskProjectId = computed(() => {
  const value = route.query.projectId;
  return typeof value === 'string' && value ? value : null;
});
const taskProjectName = computed(() => {
  const value = route.query.projectName;
  return typeof value === 'string' && value ? value : null;
});

function go(surfaceName: ClientSurface) {
  void router.replace({ path: '/app', query: surfaceName === 'chat' ? {} : { view: surfaceName } });
}

function clearTaskProjectContext() {
  void router.replace({ path: '/app', query: { view: 'tasks' } });
}

watch(() => route.path, path => {
  if (path === '/') void router.replace('/app');
});
</script>

<template>
  <div class="client-portal">
    <aside v-if="surface !== 'chat'" class="client-sidebar" aria-label="客户端导航">
      <WorkspaceSwitcher mode="client" />

      <nav class="client-nav" aria-label="工作区">
        <button type="button" class="nav-item" @click="go('chat')">
          <SvgIcon icon="lucide:message-square-plus" />
          <span>新对话</span>
        </button>
        <button
          type="button"
          class="nav-item"
          :class="{ active: surface === 'tasks' }"
          @click="go('tasks')"
        >
          <SvgIcon icon="lucide:kanban" />
          <span>任务看板</span>
        </button>
        <button
          type="button"
          class="nav-item"
          :class="{ active: surface === 'projects' }"
          @click="go('projects')"
        >
          <SvgIcon icon="lucide:folder-kanban" />
          <span>项目中心</span>
        </button>
      </nav>

      <div class="sidebar-spacer" />
      <div class="sidebar-user" :aria-label="`当前账户 ${authStore.userInfo.userName || '用户'}`">
        <span class="user-avatar">{{ (authStore.userInfo.userName || 'U').slice(0, 1).toUpperCase() }}</span>
        <span class="sidebar-user-copy">
          <strong>{{ authStore.userInfo.userName || '用户' }}</strong>
          <small>当前账户</small>
        </span>
      </div>
    </aside>

    <section class="client-stage">
      <header v-if="surface !== 'chat'" class="client-stage-header">
        <div class="stage-heading">
          <span class="stage-title-icon" aria-hidden="true">
            <SvgIcon :icon="surfaceMeta.icon" />
          </span>
          <div class="stage-copy">
            <h1>{{ surfaceTitle }}</h1>
            <p>{{ surfaceMeta.description }}</p>
          </div>
        </div>
        <div class="stage-actions">
          <button type="button" class="stage-notification" aria-label="查看通知" title="通知">
            <SvgIcon icon="lucide:bell" />
          </button>
        </div>
      </header>
      <main class="client-stage-content" :class="{ 'chat-surface': surface === 'chat' }">
        <ChatPanel v-if="surface === 'chat'" client-mode>
          <template #client-sidebar-header="{ openCreate }">
            <WorkspaceSwitcher mode="client" />

            <nav class="client-nav" aria-label="工作区">
              <button type="button" class="nav-item" @click="openCreate">
                <SvgIcon icon="lucide:message-square-plus" />
                <span>新对话</span>
              </button>
              <button type="button" class="nav-item" @click="go('tasks')">
                <SvgIcon icon="lucide:kanban" />
                <span>任务看板</span>
              </button>
              <button type="button" class="nav-item" @click="go('projects')">
                <SvgIcon icon="lucide:folder-kanban" />
                <span>项目中心</span>
              </button>
            </nav>
          </template>
          <template #client-sidebar-footer>
            <div class="sidebar-user" :aria-label="`当前账户 ${authStore.userInfo.userName || '用户'}`">
              <span class="user-avatar">{{ (authStore.userInfo.userName || 'U').slice(0, 1).toUpperCase() }}</span>
              <span class="sidebar-user-copy">
                <strong>{{ authStore.userInfo.userName || '用户' }}</strong>
                <small>当前账户</small>
              </span>
            </div>
          </template>
        </ChatPanel>
        <TaskCenter
          v-else-if="surface === 'tasks'"
          client-mode
          :project-id="taskProjectId"
          :project-name="taskProjectName"
          @clear-project-context="clearTaskProjectContext"
        />
        <ProjectCenter v-else client-mode />
      </main>
    </section>
  </div>
</template>

<style scoped lang="scss">
.client-portal {
  display: flex;
  width: 100%;
  height: 100dvh;
  min-height: 620px;
  overflow: hidden;
  background: var(--n-color);
  color: var(--n-text-color);
}

.client-sidebar {
  display: flex;
  width: 256px;
  flex: 0 0 256px;
  padding: 32px 16px 18px;
  border-right: 1px solid var(--n-border-color);
  background: #fafafa;
  flex-direction: column;
  gap: 18px;
}

.client-nav {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.nav-item {
  display: flex;
  width: 100%;
  min-height: 36px;
  padding: 7px 9px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--n-text-color-2);
  cursor: pointer;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  text-align: left;
}

.nav-item > .svg-icon {
  color: var(--n-text-color-3);
  font-size: 16px;
}

.nav-item:hover,
.nav-item.active {
  background: color-mix(in srgb, var(--n-primary-color) 10%, var(--n-color));
  color: var(--n-text-color);
}

.nav-item.active {
  color: var(--n-primary-color);
  font-weight: 600;
  box-shadow: inset 2px 0 0 var(--n-primary-color);
}

.nav-item.active > .svg-icon,
.nav-item:hover > .svg-icon {
  color: var(--n-primary-color);
}

.sidebar-spacer {
  flex: 1;
}

.sidebar-user {
  display: flex;
  width: 100%;
  min-height: 47px;
  padding: 8px 7px;
  border: 0;
  background: transparent;
  color: var(--n-text-color);
  cursor: default;
  align-items: center;
  gap: 9px;
  text-align: left;
}

.user-avatar {
  display: inline-flex;
  width: 27px;
  height: 27px;
  flex: 0 0 27px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: color-mix(in srgb, var(--n-primary-color) 16%, var(--n-color));
  color: var(--n-primary-color);
  font-size: 12px;
  font-weight: 700;
}

.sidebar-user-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 1px;
}

.sidebar-user-copy strong {
  overflow: hidden;
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar-user-copy small {
  color: var(--n-text-color-3);
  font-size: 10px;
}

.client-stage {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex: 1;
  flex-direction: column;
}

.client-stage-header {
  display: flex;
  min-height: 112px;
  padding: 24px 40px;
  border-bottom: 1px solid var(--n-border-color);
  align-items: center;
  justify-content: space-between;
}

.stage-heading,
.stage-actions {
  display: flex;
  align-items: center;
}

.stage-heading {
  min-width: 0;
  gap: 12px;
}

.stage-title-icon {
  display: inline-flex;
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  background: color-mix(in srgb, var(--n-primary-color) 10%, var(--n-color));
  color: var(--n-primary-color);
  font-size: 19px;
}

.stage-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.stage-kicker {
  display: block;
  margin-bottom: 2px;
  color: var(--n-text-color-3);
  font-size: 11px;
}

.client-stage-header h1 {
  margin: 0;
  color: var(--n-text-color);
  font-size: 20px;
  font-weight: 650;
}

.stage-copy p {
  margin: 3px 0 0;
  color: var(--n-text-color-3);
  font-size: 12px;
}

.stage-actions {
  gap: 12px;
}

.stage-notification {
  display: inline-flex;
  width: 32px;
  height: 32px;
  padding: 0;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--n-text-color-2);
  cursor: pointer;
  align-items: center;
  justify-content: center;

  &:hover,
  &:focus-visible {
    background: #f0f0f3;
    color: var(--n-text-color);
    outline: 0;
  }

  .svg-icon {
    font-size: 17px;
  }
}

.client-stage-content {
  min-height: 0;
  flex: 1;
  overflow: auto;
  padding: 34px 0 24px;
  background: #f6f7f9;
}

.client-stage-content.chat-surface {
  padding: 0;
  overflow: hidden;
}

@media (max-width: 720px) {
  .client-sidebar {
    width: 208px;
    flex-basis: 208px;
    padding-inline: 9px;
  }

  .client-stage-content {
    padding: 10px;
  }

  .client-stage-header {
    min-height: 70px;
    padding: 12px 16px;
  }

  .stage-copy p {
    display: none;
  }

}

@media (max-width: 560px) {
  .client-sidebar {
    width: 56px;
    flex-basis: 56px;
    align-items: center;
    gap: 12px;
    padding: 12px 6px;
  }

  .client-sidebar :deep(.workspace-switcher),
  .client-sidebar .sidebar-user {
    width: 42px;
    justify-content: center;
    padding-inline: 0;
  }

  .client-sidebar :deep(.workspace-switcher-copy),
  .client-sidebar :deep(.workspace-switcher-chevron),
  .client-sidebar .sidebar-user-copy {
    display: none;
  }

  .client-sidebar .client-nav {
    width: 100%;
  }

  .client-sidebar .nav-item {
    justify-content: center;
    gap: 0;
    padding-inline: 0;
  }

  .client-sidebar .nav-item span {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
    white-space: nowrap;
    clip-path: inset(50%);
  }

  .client-stage-header {
    padding-inline: 12px;
  }

  .stage-heading {
    gap: 8px;
  }

  .stage-title-icon {
    width: 32px;
    height: 32px;
    flex-basis: 32px;
  }
}
</style>
