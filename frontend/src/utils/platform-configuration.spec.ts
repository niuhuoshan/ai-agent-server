import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  applyPublicPlatformConfiguration,
  platformConfigurationUpdateEvent,
  platformDocumentTitle,
  readPublicPlatformConfiguration
} from './platform-configuration';

const configuration = {
  productName: '示例智能体工作平台',
  productShortName: '示例平台',
  logoUrl: '/brand/logo.svg',
  faviconUrl: '/brand/favicon.svg',
  primaryColor: '#1570EF',
  platformTimezone: 'Asia/Shanghai',
  defaultLocale: 'zh-CN' as const,
  watermarkEnabled: true
};

describe('platform configuration runtime', () => {
  beforeEach(() => {
    sessionStorage.clear();
    document.head.innerHTML = '<link rel="icon" href="/favicon.svg">';
    document.documentElement.lang = 'en';
  });

  it('applies non-sensitive public branding to the current document', () => {
    const listener = vi.fn();
    window.addEventListener(platformConfigurationUpdateEvent(), listener);

    applyPublicPlatformConfiguration(configuration);

    expect(readPublicPlatformConfiguration()).toEqual(configuration);
    expect(document.documentElement.lang).toBe('zh-CN');
    expect(document.head.querySelector('link[rel="icon"]')?.getAttribute('href')).toBe('/brand/favicon.svg');
    expect(document.head.querySelector('meta[name="theme-color"]')?.getAttribute('content')).toBe('#1570EF');
    expect(listener).toHaveBeenCalledOnce();
    window.removeEventListener(platformConfigurationUpdateEvent(), listener);
  });

  it('builds route titles from the configured product name', () => {
    applyPublicPlatformConfiguration(configuration);

    expect(platformDocumentTitle('系统管理')).toBe('系统管理 | 示例智能体工作平台');
    expect(platformDocumentTitle('')).toBe('示例智能体工作平台');
  });
});
