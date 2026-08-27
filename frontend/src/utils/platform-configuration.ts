import type { PublicPlatformConfiguration } from '@/service/api/operations';

const STORAGE_KEY = 'agent-platform-public-configuration';
const UPDATE_EVENT = 'agent-platform-configuration-updated';

export function readPublicPlatformConfiguration(): PublicPlatformConfiguration | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const value = JSON.parse(raw) as Partial<PublicPlatformConfiguration>;
    if (!value.productName || !/^#[0-9A-Fa-f]{6}$/.test(value.primaryColor || '')) return null;
    return value as PublicPlatformConfiguration;
  } catch {
    return null;
  }
}

export function applyPublicPlatformConfiguration(configuration: PublicPlatformConfiguration) {
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(configuration));
  document.documentElement.lang = configuration.defaultLocale;
  setHeadLink('icon', configuration.faviconUrl || '/favicon.svg');
  setThemeColor(configuration.primaryColor);
  window.dispatchEvent(new CustomEvent(UPDATE_EVENT, { detail: configuration }));
}

export function platformDocumentTitle(routeTitle?: string | null) {
  const productName = readPublicPlatformConfiguration()?.productName || '牛火山企业智能体平台';
  const normalized = routeTitle?.trim();
  return !normalized || normalized === productName ? productName : `${normalized} | ${productName}`;
}

export function platformConfigurationUpdateEvent() {
  return UPDATE_EVENT;
}

function setHeadLink(rel: string, href: string) {
  let link = document.head.querySelector<HTMLLinkElement>(`link[rel="${rel}"]`);
  if (!link) {
    link = document.createElement('link');
    link.rel = rel;
    document.head.appendChild(link);
  }
  link.href = href;
}

function setThemeColor(color: string) {
  let meta = document.head.querySelector<HTMLMetaElement>('meta[name="theme-color"]');
  if (!meta) {
    meta = document.createElement('meta');
    meta.name = 'theme-color';
    document.head.appendChild(meta);
  }
  meta.content = color;
}
