export type ResourceTab = 'model' | 'connector' | 'tool' | 'skill' | 'memory';

export type ResourceAction = 'list' | 'create' | 'edit' | 'delete' | 'operate' | 'publish' | 'archive';

export type PermissionChecker = (code: string) => boolean;

type ResourcePermissionMap = { list: string } & Partial<Record<Exclude<ResourceAction, 'list'>, string>>;

function resourcePermissionCode(resource: ResourceTab, action: string) {
  return ['resource', resource, action].join(':');
}

export const resourcePermissionCodes: Record<ResourceTab, ResourcePermissionMap> = {
  model: {
    list: resourcePermissionCode('model', 'list'),
    create: resourcePermissionCode('model', 'create'),
    edit: resourcePermissionCode('model', 'edit'),
    delete: resourcePermissionCode('model', 'delete'),
    operate: resourcePermissionCode('model', 'operate')
  },
  connector: {
    list: resourcePermissionCode('connector', 'list'),
    create: resourcePermissionCode('connector', 'create'),
    edit: resourcePermissionCode('connector', 'edit'),
    delete: resourcePermissionCode('connector', 'delete'),
    operate: resourcePermissionCode('connector', 'operate')
  },
  tool: {
    list: resourcePermissionCode('tool', 'list'),
    create: resourcePermissionCode('tool', 'create'),
    edit: resourcePermissionCode('tool', 'edit'),
    delete: resourcePermissionCode('tool', 'delete'),
    operate: resourcePermissionCode('tool', 'operate')
  },
  skill: {
    list: resourcePermissionCode('skill', 'list'),
    create: resourcePermissionCode('skill', 'create'),
    edit: resourcePermissionCode('skill', 'edit'),
    delete: resourcePermissionCode('skill', 'delete'),
    operate: resourcePermissionCode('skill', 'operate'),
    publish: resourcePermissionCode('skill', 'publish'),
    archive: resourcePermissionCode('skill', 'archive')
  },
  memory: {
    list: resourcePermissionCode('memory', 'list'),
    create: resourcePermissionCode('memory', 'create'),
    edit: resourcePermissionCode('memory', 'edit'),
    delete: resourcePermissionCode('memory', 'delete'),
    operate: resourcePermissionCode('memory', 'operate')
  }
};

export const resourceTabs: ResourceTab[] = ['model', 'connector', 'tool', 'skill', 'memory'];

export function getAuthorizedResourceTabs(hasAuth: PermissionChecker): ResourceTab[] {
  return resourceTabs.filter(tab => hasAuth(resourcePermissionCodes[tab].list));
}

export function resolveResourceTab(current: ResourceTab | null | undefined, authorizedTabs: ResourceTab[]) {
  if (current && authorizedTabs.includes(current)) return current;
  return authorizedTabs[0] || null;
}

export function hasResourcePermission(
  hasAuth: PermissionChecker,
  tab: ResourceTab,
  action: ResourceAction
) {
  const code = resourcePermissionCodes[tab][action];
  return Boolean(code && hasAuth(code));
}

export function defaultSkillScope(isSharedScopeManager: boolean, userId: string) {
  return isSharedScopeManager
    ? { scopeType: 'system' as const, scopeId: '' }
    : { scopeType: 'user' as const, scopeId: userId };
}

export function normalizeSkillScope(
  scopeType: 'system' | 'project' | 'user',
  scopeId: string,
  isSharedScopeManager: boolean,
  userId: string
) {
  if (!isSharedScopeManager) return defaultSkillScope(false, userId);
  if (scopeType === 'system') return { scopeType, scopeId: '' };
  return { scopeType, scopeId: scopeId.trim() };
}

export function normalizeMemoryScope(
  scopeType: 'user' | 'project' | 'task',
  scopeId: string,
  canManageSharedMemory: boolean,
  userId: string
) {
  if (!canManageSharedMemory) return { scopeType: 'user' as const, scopeId: userId };
  return { scopeType, scopeId: scopeId.trim() };
}
