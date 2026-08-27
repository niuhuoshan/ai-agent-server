import { $t } from '@/locales';

type MenuGroupDefinition = {
  key: string;
  i18nKey: 'menuGroup.overview' | 'menuGroup.agents' | 'menuGroup.data' | 'menuGroup.platform';
  routeKeys: string[];
};

export type GlobalMenuGroup = {
  key: string;
  label: string;
  menus: App.Global.Menu[];
};

const MENU_GROUP_DEFINITIONS: MenuGroupDefinition[] = [
  {
    key: 'overview',
    i18nKey: 'menuGroup.overview',
    routeKeys: ['home', 'dashboard', 'token-stats']
  },
  {
    key: 'agents',
    i18nKey: 'menuGroup.agents',
    routeKeys: [
      'agent-center',
      'scenario-templates',
      'agent-debug',
      'prompt-studio',
      'resource-center',
      'slash-commands'
    ]
  },
  {
    key: 'data',
    i18nKey: 'menuGroup.data',
    routeKeys: ['knowledge', 'data-source', 'data-portal', 'chatbi', 'saved-reports', 'examples', 'memory']
  },
  {
    key: 'platform',
    i18nKey: 'menuGroup.platform',
    routeKeys: ['task-center', 'project-center', 'automation', 'open-api', 'widget-debugger', 'risk-control', 'system']
  }
];

const HIDDEN_MENU_KEYS = new Set(['workspace', 'personal-center']);

/**
 * Convert the permission-filtered flat menu list into non-collapsible sections.
 * Routes remain flat in Vue Router; this only changes the sidebar presentation.
 */
export function getGlobalMenuGroups(menus: App.Global.Menu[]): GlobalMenuGroup[] {
  const menuMap = new Map(menus.map(menu => [menu.key, menu]));
  const assigned = new Set<string>();

  const groups = MENU_GROUP_DEFINITIONS.map(definition => {
    const groupMenus = definition.routeKeys
      .map(key => menuMap.get(key))
      .filter((menu): menu is App.Global.Menu => menu !== undefined)
      .filter(menu => !HIDDEN_MENU_KEYS.has(menu.key));

    groupMenus.forEach(menu => assigned.add(menu.key));

    return {
      key: definition.key,
      label: $t(definition.i18nKey),
      menus: groupMenus
    };
  }).filter(group => group.menus.length > 0);

  // Keep newly added permission routes visible instead of silently dropping them.
  const ungroupedMenus = menus.filter(menu => !assigned.has(menu.key) && !HIDDEN_MENU_KEYS.has(menu.key));
  if (ungroupedMenus.length) {
    groups.push({ key: 'other', label: $t('menuGroup.other'), menus: ungroupedMenus });
  }

  return groups;
}
