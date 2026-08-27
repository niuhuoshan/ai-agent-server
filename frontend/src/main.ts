import { createApp } from 'vue';
import './plugins/assets';
import { setupVueRootValidator } from 'vite-plugin-vue-transition-root-validator/client';
import { setupAppVersionNotification, setupDayjs, setupIconifyOffline, setupLoading, setupNProgress } from './plugins';
import { setupStore } from './store';
import { setupRouter } from './router';
import { getLocale, setupI18n } from './locales';
import { fetchPublicPlatformConfiguration } from './service/api';
import { useAppStore } from './store/modules/app';
import { useThemeStore } from './store/modules/theme';
import { applyPublicPlatformConfiguration } from './utils/platform-configuration';
import App from './App.vue';

async function setupApp() {
  setupLoading();

  setupNProgress();

  setupIconifyOffline();

  setupDayjs();

  const app = createApp(App);

  setupStore(app);

  await setupRouter(app);

  setupI18n(app);

  setupAppVersionNotification();

  setupVueRootValidator(app, {
    lang: getLocale() === 'zh-CN' ? 'zh' : 'en'
  });

  app.mount('#app');

  void hydratePlatformConfiguration();
}

async function hydratePlatformConfiguration() {
  const result = await fetchPublicPlatformConfiguration();
  if (result.error) return;
  applyPublicPlatformConfiguration(result.data);
  const themeStore = useThemeStore();
  themeStore.updateThemeColors('primary', result.data.primaryColor);
  themeStore.watermark.visible = result.data.watermarkEnabled;
  useAppStore().updateDocumentTitleByLocale();
}

setupApp().catch(error => {
  console.error('[nhs] application bootstrap failed', error);
});
