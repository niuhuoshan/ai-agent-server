import { request } from '../request';

export type BrowserSessionStatus = 'opening' | 'open' | 'closing' | 'closed' | 'failed';
export type BrowserHandoffStatus = 'none' | 'requested' | 'human_control' | 'returned' | 'expired';

export interface BrowserSessionView {
  id: string;
  ownerId: string;
  sessionKey: string;
  workerSessionId: string | null;
  profileKey: string | null;
  status: BrowserSessionStatus;
  currentUrl: string | null;
  pageTitle: string | null;
  createdAt: string;
  updatedAt: string;
  closedAt: string | null;
  handoffStatus: BrowserHandoffStatus;
  handoffReason: string | null;
  handoffUserId: string | null;
  handoffRequestedAt: string | null;
  handoffStartedAt: string | null;
  handoffReturnedAt: string | null;
}

export interface BrowserSnapshotView {
  sessionId: string;
  url: string | null;
  title: string | null;
  text: string;
  screenshotBase64: string | null;
  capturedAt: string;
  activeTabId?: string | null;
  tabs?: BrowserTabView[];
}

export interface BrowserTabView {
  tabId: string;
  url: string;
  title: string;
  active: boolean;
}

export interface BrowserActionResult extends BrowserSessionView {
  snapshot?: BrowserSnapshotView;
  replayed?: boolean;
  worker?: Record<string, unknown>;
}

export interface BrowserWorkerHealthView {
  available: boolean;
  status: string;
  workerGeneration?: string | null;
  startedAt?: string | null;
  sessions?: number | null;
  maxSessions?: number | null;
  maxTabsPerSession?: number | null;
  invalidatedSessions: number;
  checkedAt: string;
  error?: string | null;
}

export function fetchBrowserWorkerHealth() {
  return request<BrowserWorkerHealthView>({
    url: '/platform/browser/health',
    method: 'get'
  });
}

export interface CreateBrowserSessionPayload {
  profileKey?: string;
  startUrl?: string;
}

export function fetchBrowserSessions(limit = 30) {
  return request<BrowserSessionView[]>({
    url: '/platform/browser/sessions',
    method: 'get',
    params: { limit }
  });
}

export function createBrowserSession(data: CreateBrowserSessionPayload = {}) {
  return request<BrowserActionResult>({
    url: '/platform/browser/sessions',
    method: 'post',
    data
  });
}

export function fetchBrowserSnapshot(sessionId: string) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}/snapshot`,
    method: 'get'
  });
}

export function requestBrowserHandoff(sessionId: string, reason?: string) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}/handoff/request`,
    method: 'post',
    data: reason ? { reason } : {}
  });
}

export function takeBrowserHandoff(sessionId: string) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}/handoff/takeover`,
    method: 'post'
  });
}

export function returnBrowserHandoff(sessionId: string) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}/handoff/return`,
    method: 'post'
  });
}

export function navigateBrowserSession(sessionId: string, url: string) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}/navigate`,
    method: 'post',
    data: { url }
  });
}

export function clickBrowserSession(sessionId: string, selector: string) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}/click`,
    method: 'post',
    data: { selector }
  });
}

export function fillBrowserSession(sessionId: string, selector: string, value: string) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}/fill`,
    method: 'post',
    data: { selector, value }
  });
}

export function pressBrowserSession(sessionId: string, key: string) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}/press`,
    method: 'post',
    data: { key }
  });
}

export type BrowserHistoryAction = 'back' | 'forward' | 'reload';

export function navigateBrowserHistory(sessionId: string, action: BrowserHistoryAction) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}/${action}`,
    method: 'post'
  });
}

export function waitForBrowserCondition(
  sessionId: string,
  data: { condition: 'text' | 'url' | 'target' | 'page_state'; value?: string; timeoutMs?: number }
) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}/wait-for`,
    method: 'post',
    data
  });
}

export function selectBrowserOption(
  sessionId: string,
  data: { selector: string; value?: string; label?: string }
) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}/select-option`,
    method: 'post',
    data
  });
}

export function readVisibleBrowserText(sessionId: string) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}/read-visible`,
    method: 'get'
  });
}

export function dragBrowserSession(sessionId: string, sourceSelector: string, targetSelector: string) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}/drag`,
    method: 'post',
    data: { sourceSelector, targetSelector }
  });
}

export function downloadBrowserSession(sessionId: string, selector: string) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}/download`,
    method: 'post',
    data: { selector }
  });
}

export interface BrowserManualInputPayload {
  event: 'mouse_click' | 'mouse_down' | 'mouse_move' | 'mouse_up' | 'key' | 'text' | 'scroll';
  x?: number;
  y?: number;
  key?: string;
  text?: string;
  deltaY?: number;
}

export function manualInputBrowserSession(sessionId: string, data: BrowserManualInputPayload) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}/manual-input`, method: 'post', data
  });
}

export function scrollBrowserSession(sessionId: string, data: { x?: number; y?: number; selector?: string }) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}/scroll`, method: 'post', data
  });
}

export function hoverBrowserSession(sessionId: string, selector: string) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}/hover`, method: 'post', data: { selector }
  });
}

export function uploadBrowserSession(sessionId: string, selector: string, files: string[]) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}/upload`, method: 'post', data: { selector, files }
  });
}

export function fetchBrowserTabs(sessionId: string) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}/tabs`, method: 'get'
  });
}

export function openBrowserTab(sessionId: string, url?: string) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}/tabs`, method: 'post', data: url ? { url } : {}
  });
}

export function activateBrowserTab(sessionId: string, tabId: string) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}/tabs/${encodeURIComponent(tabId)}/activate`, method: 'post'
  });
}

export function switchBrowserTab(sessionId: string, tabId: string) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}/tabs/${encodeURIComponent(tabId)}/activate`,
    method: 'post'
  });
}

export function closeBrowserTab(sessionId: string, tabId: string) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}/tabs/${encodeURIComponent(tabId)}`, method: 'delete'
  });
}

export function closeBrowserTabCompat(sessionId: string, tabId: string) {
  return closeBrowserTab(sessionId, tabId);
}

export function closeBrowserSession(sessionId: string) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}`,
    method: 'delete'
  });
}

export function clearBrowserProfiles() {
  return request<{ cleared: boolean; clearedSessions: number }>({
    url: '/platform/browser/profiles/clear',
    method: 'delete'
  });
}

export function clearBrowserSessionProfile(sessionId: string) {
  return request<BrowserActionResult>({
    url: `/platform/browser/sessions/${encodeURIComponent(sessionId)}?destroyProfile=true`,
    method: 'delete'
  });
}
