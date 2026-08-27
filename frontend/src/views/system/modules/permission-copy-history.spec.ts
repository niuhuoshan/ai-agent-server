import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import PermissionCopyHistory from './permission-copy-history.vue';

const api = vi.hoisted(() => ({ fetch: vi.fn() }));

vi.mock('@/service/api', () => ({ fetchPermissionCopyRecords: api.fetch }));

const users = [
  { userId: '10', userName: 'alice', nickName: 'Alice' },
  { userId: '20', userName: 'bob', nickName: 'Bob' },
  { userId: '1', userName: 'admin', nickName: '管理员' }
];

const record = {
  id: '900', sourceUserId: '10', targetUserId: '20', sourceProfileId: '50', sourceProfileVersion: 3,
  copyMode: 'append_missing', beforeBindingId: '70', afterBindingId: '71',
  diff: { addedRuleCount: 4, retainedRuleCount: 2 }, excluded: { rules: [{ id: 'x' }] },
  idempotencyKey: 'copy-key', createdBy: '1', createdAt: '2026-08-17T01:02:03Z'
};

function success(data: unknown) {
  return { data, error: null, response: { status: 200 } };
}

function failure() {
  const error = Object.assign(new Error('无权读取复制历史'), { response: { status: 403 } });
  return { data: null, error, response: error.response };
}

const stubs = {
  SvgIcon: true,
  NButton: { props: ['loading'], emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
  NAlert: { template: '<div class="alert"><slot /></div>' },
  NDataTable: { props: ['data'], template: '<div class="table"><span v-for="row in data" :key="row.id">{{ row.id }}</span></div>' },
  NEmpty: { props: ['description'], template: '<div class="empty">{{ description }}</div>' },
  NTag: { template: '<span><slot /></span>' }
};

afterEach(() => { vi.clearAllMocks(); });

describe('permission copy history', () => {
  beforeEach(() => { api.fetch.mockResolvedValue(success([record])); });

  it('loads auditable records and resolves user names', async () => {
    const wrapper = mount(PermissionCopyHistory, { props: { users }, global: { stubs } });
    await flushPromises();
    expect(api.fetch).toHaveBeenCalledWith(100);
    expect(wrapper.find('.table').text()).toContain('900');
    expect(wrapper.find('.empty').exists()).toBe(false);
  });

  it('keeps a readable error and does not show an empty success state', async () => {
    api.fetch.mockResolvedValueOnce(failure());
    const wrapper = mount(PermissionCopyHistory, { global: { stubs } });
    await flushPromises();
    expect(wrapper.text()).toContain('无权读取复制历史');
    expect(wrapper.find('.empty').exists()).toBe(false);
  });
});
