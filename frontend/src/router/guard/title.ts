import type { Router } from 'vue-router';
import { useTitle } from '@vueuse/core';
import { $t } from '@/locales';
import { platformDocumentTitle } from '@/utils/platform-configuration';

export function createDocumentTitleGuard(router: Router) {
  router.afterEach(to => {
    const { i18nKey, title } = to.meta;

    const documentTitle = platformDocumentTitle(i18nKey ? $t(i18nKey) : title);

    useTitle(documentTitle);
  });
}
