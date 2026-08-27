import { createMemoryHistory, createRouter } from 'vue-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const state = vi.hoisted(() => ({
  token: 'stale-token',
  authStore: {
    userInfo: { roles: [] as string[] },
    isStaticSuper: false
  },
  routeStore: {
    isInitConstantRoute: true,
    isInitAuthRoute: false,
    routeHome: 'home',
    initConstantRoute: vi.fn(),
    initAuthRoute: vi.fn(),
    onRouteSwitchWhenLoggedIn: vi.fn(),
    onRouteSwitchWhenNotLoggedIn: vi.fn(),
    getIsAuthRouteExist: vi.fn()
  }
}));

vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => state.authStore }));
vi.mock('@/store/modules/route', () => ({ useRouteStore: () => state.routeStore }));
vi.mock('@/utils/storage', () => ({
  localStg: {
    get: (key: string) => key === 'token' ? state.token : null
  }
}));
vi.mock('@/router/elegant/transform', () => ({ getRouteName: () => null }));

import { createRouteGuard } from './route';

describe('route guard session restoration', () => {
  beforeEach(() => {
    state.token = 'stale-token';
    state.routeStore.isInitConstantRoute = true;
    state.routeStore.isInitAuthRoute = false;
    vi.clearAllMocks();
  });

  it('redirects a hard-refreshed protected path after session restoration clears the token', async () => {
    state.routeStore.initAuthRoute.mockImplementation(async () => {
      await Promise.resolve();
      state.token = '';
    });

    const component = { template: '<div />' };
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { name: 'login', path: '/login', component, meta: { title: 'login', constant: true } },
        { name: '403', path: '/403', component, meta: { title: '403', constant: true } },
        {
          name: 'not-found',
          path: '/:pathMatch(.*)*',
          component,
          meta: { title: 'not-found', constant: true }
        }
      ]
    });
    createRouteGuard(router);

    await router.push('/system');
    await router.isReady();

    expect(router.currentRoute.value.name).toBe('login');
    expect(router.currentRoute.value.query.redirect).toBe('/system');
    expect(state.routeStore.initAuthRoute).toHaveBeenCalledOnce();
    expect(state.routeStore.getIsAuthRouteExist).not.toHaveBeenCalled();
  });
});
