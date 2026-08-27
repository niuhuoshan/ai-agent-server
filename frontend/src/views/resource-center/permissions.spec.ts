import { describe, expect, it } from 'vitest';
import {
  defaultSkillScope,
  getAuthorizedResourceTabs,
  hasResourcePermission,
  normalizeMemoryScope,
  normalizeSkillScope,
  resourcePermissionCodes,
  resolveResourceTab
} from './permissions';

describe('resource center permissions', () => {
  it('keeps only list-authorized tabs in product order', () => {
    const codes = new Set([
      resourcePermissionCodes.skill.list,
      resourcePermissionCodes.memory.list,
      resourcePermissionCodes.skill.create
    ]);

    expect(getAuthorizedResourceTabs(code => codes.has(code))).toEqual(['skill', 'memory']);
  });

  it('keeps an authorized current tab and otherwise selects the first authorized tab', () => {
    expect(resolveResourceTab('memory', ['skill', 'memory'])).toBe('memory');
    expect(resolveResourceTab('model', ['skill', 'memory'])).toBe('skill');
    expect(resolveResourceTab('model', [])).toBeNull();
  });

  it('checks the action-specific resource button code', () => {
    const seen: string[] = [];
    const expectedCode = ['resource', 'skill', 'publish'].join(':');
    const allowed = hasResourcePermission(code => {
      seen.push(code);
      return code === expectedCode;
    }, 'skill', 'publish');

    expect(allowed).toBe(true);
    expect(seen).toEqual([expectedCode]);
  });

  it('forces ordinary-user Skill and Memory scopes to the current user', () => {
    expect(defaultSkillScope(false, '42')).toEqual({ scopeType: 'user', scopeId: '42' });
    expect(normalizeSkillScope('system', '', false, '42')).toEqual({ scopeType: 'user', scopeId: '42' });
    expect(normalizeSkillScope('project', '99', false, '42')).toEqual({ scopeType: 'user', scopeId: '42' });
    expect(normalizeMemoryScope('task', '77', false, '42')).toEqual({ scopeType: 'user', scopeId: '42' });
  });

  it('preserves explicitly selected shared scopes for authorized managers', () => {
    expect(normalizeSkillScope('project', ' 99 ', true, '42')).toEqual({ scopeType: 'project', scopeId: '99' });
    expect(normalizeMemoryScope('task', ' 77 ', true, '42')).toEqual({ scopeType: 'task', scopeId: '77' });
  });
});
