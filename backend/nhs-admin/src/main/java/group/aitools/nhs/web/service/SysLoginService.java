package group.aitools.nhs.web.service;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.util.ObjectUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import group.aitools.nhs.common.core.constant.CacheNames;
import group.aitools.nhs.common.core.constant.Constants;
import group.aitools.nhs.common.core.enums.LoginType;
import group.aitools.nhs.common.core.exception.user.UserException;
import group.aitools.nhs.common.core.utils.*;
import group.aitools.nhs.common.log.event.LoginInfoEvent;
import group.aitools.nhs.common.mybatis.helper.DataPermissionHelper;
import group.aitools.nhs.common.redis.utils.RedisUtils;
import group.aitools.nhs.common.satoken.utils.LoginHelper;
import group.aitools.nhs.system.api.domain.PostDTO;
import group.aitools.nhs.system.api.domain.RoleDTO;
import group.aitools.nhs.system.api.model.LoginUser;
import group.aitools.nhs.system.domain.SysUser;
import group.aitools.nhs.system.domain.vo.*;
import group.aitools.nhs.system.mapper.SysUserMapper;
import group.aitools.nhs.system.service.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

/**
 * 登录校验方法
 *
 * @author Lion Li
 */
@RequiredArgsConstructor
@Slf4j
@Service
public class SysLoginService {

    /**
     * 最大重试次数。
     */
    @Value("${user.password.maxRetryCount}")
    private Integer maxRetryCount;

    /**
     * 锁定时间。
     */
    @Value("${user.password.lockTime}")
    private Integer lockTime;

    private final ISysPermissionService permissionService;
    private final ISysRoleService roleService;
    private final ISysDeptService deptService;
    private final ISysPostService postService;
    private final SysUserMapper userMapper;


    /**
     * 退出登录
     */
    public void logout() {
        try {
            LoginUser loginUser = LoginHelper.getLoginUser();
            if (ObjectUtil.isNull(loginUser)) {
                return;
            }
            recordLoginInfo(loginUser.getUsername(), Constants.LOGOUT, MessageUtils.message("user.logout.success"));
        } catch (NotLoginException ignored) {
        } finally {
            try {
                StpUtil.logout();
            } catch (NotLoginException ignored) {
            }
        }
    }

    /**
     * 记录登录信息
     *
     * @param username 用户名
     * @param status   状态
     * @param message  消息内容
     */
    public void recordLoginInfo(String username, String status, String message) {
        LoginInfoEvent loginInfoEvent = new LoginInfoEvent();
        loginInfoEvent.setUsername(username);
        loginInfoEvent.setStatus(status);
        loginInfoEvent.setMessage(message);
        HttpServletRequest request = ServletUtils.getRequest();
        if (request != null) {
            loginInfoEvent.setIp(ServletUtils.getClientIP(request));
            loginInfoEvent.setUserAgent(request.getHeader("User-Agent"));
            loginInfoEvent.setClientId(request.getHeader(LoginHelper.CLIENT_KEY));
        }
        SpringUtils.context().publishEvent(loginInfoEvent);
    }

    /**
     * 根据用户视图对象组装登录态上下文。
     *
     * @param user 用户基础信息
     * @return 包含部门、角色、岗位与权限数据的登录用户
     */
    public LoginUser buildLoginUser(SysUserVo user) {
        LoginUser loginUser = new LoginUser();
        Long userId = user.getUserId();
        loginUser.setUserId(userId);
        loginUser.setDeptId(user.getDeptId());
        loginUser.setUsername(user.getUserName());
        loginUser.setNickname(user.getNickName());
        loginUser.setUserType(user.getUserType());
        if (ObjectUtil.isNotNull(user.getDeptId())) {
            Opt<SysDeptVo> deptOpt = Opt.of(user.getDeptId()).map(deptService::selectDeptById);
            loginUser.setDeptName(deptOpt.map(SysDeptVo::getDeptName).orElse(StringUtils.EMPTY));
            loginUser.setDeptCategory(deptOpt.map(SysDeptVo::getDeptCategory).orElse(StringUtils.EMPTY));
        }
        ThreadUtils.virtualInvokeAll(() -> {
            loginUser.setMenuPermission(permissionService.getMenuPermission(userId));
        }, () -> {
            loginUser.setRolePermission(permissionService.getRolePermission(userId));
        }, () -> {
            List<SysRoleVo> roles = roleService.selectRolesByUserId(userId);
            List<RoleDTO> roleDtos = BeanUtil.copyToList(roles, RoleDTO.class);
            loginUser.setRoles(roleDtos);
            loginUser.setDataScopeRoleMap(permissionService.getDataScopeRoleMap(roleDtos));
        }, () -> {
            List<SysPostVo> posts = postService.selectPostsByUserId(userId);
            loginUser.setPosts(BeanUtil.copyToList(posts, PostDTO.class));
        });
        return loginUser;
    }

    /**
     * 更新用户最近一次登录IP与登录时间。
     *
     * @param userId 用户ID
     * @param ip     登录IP
     */
    public void updateLastLoginInfo(Long userId, String ip) {
        SysUser sysUser = new SysUser();
        sysUser.setUserId(userId);
        sysUser.setLoginIp(ip);
        sysUser.setLoginDate(LocalDateTime.now());
        sysUser.setUpdateBy(userId);
        DataPermissionHelper.ignore(() -> userMapper.updateById(sysUser));
    }

    /**
     * 执行登录失败次数校验，并在成功后清空失败计数。
     *
     * @param loginType 登录类型
     * @param username  登录标识
     * @param supplier  返回 {@code true} 表示本次认证失败
     */
    public void checkLogin(LoginType loginType, String username, Supplier<Boolean> supplier) {
        String errorKey = CacheNames.PWD_ERR_CNT_KEY + username;
        String loginFail = Constants.LOGIN_FAIL;

        // 获取用户登录错误次数，默认为0 (可自定义限制策略 例如: key + username + ip)
        int errorNumber = ObjectUtil.defaultIfNull(RedisUtils.getCacheObject(errorKey), 0);
        // 锁定时间内登录 则踢出
        if (errorNumber >= maxRetryCount) {
            recordLoginInfo(username, loginFail, MessageUtils.message(loginType.getRetryLimitExceed(), maxRetryCount, lockTime));
            throw new UserException(loginType.getRetryLimitExceed(), maxRetryCount, lockTime);
        }

        if (supplier.get()) {
            // 错误次数递增
            errorNumber++;
            RedisUtils.setCacheObject(errorKey, errorNumber, Duration.ofMinutes(lockTime));
            // 达到规定错误次数 则锁定登录
            if (errorNumber >= maxRetryCount) {
                recordLoginInfo(username, loginFail, MessageUtils.message(loginType.getRetryLimitExceed(), maxRetryCount, lockTime));
                throw new UserException(loginType.getRetryLimitExceed(), maxRetryCount, lockTime);
            } else {
                // 未达到规定错误次数
                recordLoginInfo(username, loginFail, MessageUtils.message(loginType.getRetryLimitCount(), errorNumber));
                throw new UserException(loginType.getRetryLimitCount(), errorNumber);
            }
        }

        // 登录成功 清空错误次数
        RedisUtils.deleteObject(errorKey);
    }

}
