package group.aitools.nhs.system.service.impl;

import cn.hutool.core.collection.CollUtil;
import lombok.RequiredArgsConstructor;
import group.aitools.nhs.common.core.constant.SystemConstants;
import group.aitools.nhs.common.core.service.PermissionService;
import group.aitools.nhs.common.core.utils.StreamUtils;
import group.aitools.nhs.common.satoken.utils.LoginHelper;
import group.aitools.nhs.system.api.domain.RoleDTO;
import group.aitools.nhs.system.service.ISysMenuService;
import group.aitools.nhs.system.service.ISysPermissionService;
import group.aitools.nhs.system.service.ISysRoleService;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 用户权限处理
 *
 * @author nhs
 */
@RequiredArgsConstructor
@Service
public class SysPermissionServiceImpl implements ISysPermissionService, PermissionService {

    private final ISysRoleService roleService;
    private final ISysMenuService menuService;

    /**
     * 获取角色数据权限
     *
     * @param userId 用户id
     * @return 角色权限信息
     */
    @Override
    public Set<String> getRolePermission(Long userId) {
        Set<String> roles = new HashSet<>();
        // 管理员拥有所有权限
        if (LoginHelper.isSuperAdmin(userId)) {
            roles.add(SystemConstants.SUPER_ADMIN_ROLE_KEY);
        } else {
            roles.addAll(roleService.selectRolePermissionByUserId(userId));
        }
        return roles;
    }

    /**
     * 获取菜单数据权限
     *
     * @param userId 用户id
     * @return 菜单权限信息
     */
    @Override
    public Set<String> getMenuPermission(Long userId) {
        Set<String> perms = new HashSet<>();
        // 管理员拥有所有权限
        if (LoginHelper.isSuperAdmin(userId)) {
            perms.add("*:*:*");
        } else {
            perms.addAll(menuService.selectMenuPermsByUserId(userId));
        }
        return perms;
    }

    /**
     * 按权限标识汇总具备数据权限的角色集合。
     *
     * @param roles 角色传输对象列表
     * @return key 为权限标识、value 为拥有该权限的角色主键列表
     */
    @Override
    public Map<String, List<Long>> getDataScopeRoleMap(List<RoleDTO> roles) {
        if (CollUtil.isEmpty(roles)) {
            return Map.of();
        }
        List<Long> roleIds = StreamUtils.toList(roles, RoleDTO::getRoleId);
        Map<Long, Set<String>> permsRoleIds = menuService.selectMenuPermsByRoleIds(roleIds);
        Map<String, List<Long>> rolePermsMap = new LinkedHashMap<>();
        permsRoleIds.forEach((roleId, perms) ->
            perms.forEach(perm ->
                rolePermsMap.computeIfAbsent(perm, key -> new ArrayList<>()).add(roleId)
            )
        );
        return rolePermsMap;
    }
}
