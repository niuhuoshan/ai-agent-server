package group.aitools.nhs.system.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.ArrayUtil;
import group.aitools.nhs.common.satoken.utils.LoginHelper;
import group.aitools.nhs.common.sensitive.core.SensitiveService;
import org.springframework.stereotype.Service;

/**
 * 脱敏服务
 * 默认管理员不过滤
 * 需自行根据业务重写实现
 *
 * @author Lion Li
 * @version 3.6.0
 */
@Service
public class SysSensitiveServiceImpl implements SensitiveService {

    /**
     * 是否脱敏
     *
     * @param roleKey 允许查看原文的角色标识列表
     * @param perms   允许查看原文的权限标识列表
     * @return 需要脱敏返回 {@code true}
     */
    @Override
    public boolean isSensitive(String[] roleKey, String[] perms) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (!LoginHelper.isLogin()) {
            return true;
        }
        boolean roleExist = ArrayUtil.isNotEmpty(roleKey);
        boolean permsExist = ArrayUtil.isNotEmpty(perms);
        if (roleExist && permsExist) {
            if (StpUtil.hasRoleOr(roleKey) && StpUtil.hasPermissionOr(perms)) {
                return false;
            }
        } else if (roleExist && StpUtil.hasRoleOr(roleKey)) {
            return false;
        } else if (permsExist && StpUtil.hasPermissionOr(perms)) {
            return false;
        }

        return !LoginHelper.isSuperAdmin();
    }

}
