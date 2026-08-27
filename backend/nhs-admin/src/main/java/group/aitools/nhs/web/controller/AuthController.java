package group.aitools.nhs.web.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.crypto.digest.BCrypt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import group.aitools.nhs.common.core.constant.GlobalConstants;
import group.aitools.nhs.common.core.constant.SystemConstants;
import group.aitools.nhs.common.core.domain.R;
import group.aitools.nhs.common.core.domain.model.LoginBody;
import group.aitools.nhs.common.core.enums.PushSourceEnum;
import group.aitools.nhs.common.core.enums.PushTypeEnum;
import group.aitools.nhs.common.core.utils.DateUtils;
import group.aitools.nhs.common.core.utils.MessageUtils;
import group.aitools.nhs.common.core.utils.StringUtils;
import group.aitools.nhs.common.core.utils.ValidatorUtils;
import group.aitools.nhs.common.encrypt.annotation.ApiEncrypt;
import group.aitools.nhs.common.json.utils.JsonUtils;
import group.aitools.nhs.common.redis.utils.RedisUtils;
import group.aitools.nhs.common.satoken.utils.LoginHelper;
import group.aitools.nhs.system.api.MessageService;
import group.aitools.nhs.system.api.domain.PushPayloadDTO;
import group.aitools.nhs.system.api.model.RegisterBody;
import group.aitools.nhs.system.domain.vo.SysClientVo;
import group.aitools.nhs.system.domain.vo.SysUserVo;
import group.aitools.nhs.system.service.ISysClientService;
import group.aitools.nhs.system.service.ISysConfigService;
import group.aitools.nhs.system.service.ISysUserService;
import group.aitools.nhs.web.domain.bo.RefreshTokenBody;
import group.aitools.nhs.web.domain.bo.ResetPasswordBody;
import group.aitools.nhs.web.domain.vo.LoginVo;
import group.aitools.nhs.web.service.IAuthStrategy;
import group.aitools.nhs.web.service.SysLoginService;
import group.aitools.nhs.web.service.SysRegisterService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 认证控制器，提供一期本地登录、注册和退出能力。
 *
 * @author Lion Li
 */
@Slf4j
@SaIgnore
@RequiredArgsConstructor
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final SysLoginService loginService;
    private final SysRegisterService registerService;
    private final ISysConfigService configService;
    private final ISysClientService clientService;
    private final ISysUserService userService;
    private final ScheduledExecutorService scheduledExecutorService;
    private final MessageService messageService;


    /**
     * 登录方法
     *
     * @param body 登录信息
     * @return 结果
     */
    @ApiEncrypt
    @PostMapping("/login")
    public R<LoginVo> login(@RequestBody String body) {
        LoginBody loginBody = JsonUtils.parseObject(body, LoginBody.class);
        ValidatorUtils.validate(loginBody);
        // 授权类型和客户端id
        String clientId = loginBody.getClientId();
        String grantType = loginBody.getGrantType();
        SysClientVo client = clientService.queryByClientId(clientId);
        // 查询不到 client 或 client 内不包含 grantType
        if (ObjectUtil.isNull(client) || !StringUtils.contains(client.getGrantType(), grantType)) {
            log.info("客户端id: {} 认证类型：{} 异常!.", clientId, grantType);
            return R.fail(MessageUtils.message("auth.grant.type.error"));
        } else if (!SystemConstants.NORMAL.equals(client.getStatus())) {
            return R.fail(MessageUtils.message("auth.grant.type.blocked"));
        }
        // 登录
        LoginVo loginVo = IAuthStrategy.login(body, client, grantType);
        // Sa-Token keeps one opaque bearer session for the private SPA. The
        // refresh handle therefore has the same absolute lifetime as the token.
        if (loginVo.getRefreshToken() == null && loginVo.getAccessToken() != null) {
            loginVo.setRefreshToken(loginVo.getAccessToken());
            loginVo.setRefreshExpireIn(loginVo.getExpireIn());
        }

        Long userId = LoginHelper.getUserId();
        scheduledExecutorService.schedule(() -> {
            messageService.publishMessage(
                List.of(userId),
                PushPayloadDTO.of(
                    PushTypeEnum.MESSAGE,
                    PushSourceEnum.BACKEND,
                    DateUtils.getTodayHour(new Date()) + "好，欢迎登录企业级智能体工作平台",
                    null
                )
            );
        }, 5, TimeUnit.SECONDS);
        return R.ok(loginVo);
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public R<Void> logout() {
        loginService.logout();
        return R.ok("退出成功");
    }

    /**
     * 用户注册。
     *
     * @param user 注册信息
     * @return 操作结果
     */
    @ApiEncrypt
    @PostMapping("/register")
    public R<Void> register(@Validated @RequestBody RegisterBody user) {
        if (!configService.selectRegisterEnabled()) {
            return R.fail("当前系统没有开启注册功能！");
        }
        registerService.register(user);
        return R.ok();
    }

    /**
 * 清理或重置{@code Password}。
 *
     * Reset a local account password after a verified SMS challenge.
     * The challenge is intentionally consumed before changing the password so
     * a retried request cannot reuse the same code.
     */
    @ApiEncrypt
    @PostMapping("/resetPassword")
    public R<Void> resetPassword(@Validated @RequestBody ResetPasswordBody body) {
        String key = GlobalConstants.CAPTCHA_CODE_KEY + body.phoneNumber();
        String expected = RedisUtils.getCacheObject(key);
        RedisUtils.deleteObject(key);
        if (expected == null || !expected.equals(body.smsCode())) {
            return R.fail("手机号或验证码无效");
        }
        SysUserVo user = userService.selectUserByPhoneNumber(body.phoneNumber());
        if (user == null || !SystemConstants.NORMAL.equals(user.getStatus())) {
            return R.fail("手机号或验证码无效");
        }
        if (BCrypt.checkpw(body.newPassword(), user.getPassword())) {
            return R.fail("新密码不能与原密码相同");
        }
        int rows = userService.resetUserPwd(user.getUserId(), BCrypt.hashpw(body.newPassword()));
        return rows > 0 ? R.ok() : R.fail("密码重置失败，请联系管理员");
    }

    /**
 * 处理refresh令牌并返回对应结果。
 *
     * Restore the activity lease of an otherwise valid private-deployment session.
     *
     * <p>The fixed token TTL remains the absolute login lifetime. Refreshing only
     * updates Sa-Token's last-active timestamp and therefore cannot resurrect an
     * expired/replaced token or extend its absolute lifetime.</p>
     */
    @PostMapping("/refreshToken")
    public R<LoginVo> refreshToken(@Validated @RequestBody RefreshTokenBody body) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        String token = body.refreshToken().strip();
        try {
            Object loginId = StpUtil.getLoginIdByTokenNotThinkFreeze(token);
            long absoluteTimeout = StpUtil.getTokenTimeout(token);
            if (loginId == null || (absoluteTimeout != -1 && absoluteTimeout <= 0)) {
                return R.fail("刷新令牌已失效，请重新登录");
            }
            // This API is deliberately allowed to recover an active-timeout freeze,
            // while the token-to-login mapping and its fixed TTL must still exist.
            StpUtil.getStpLogic().updateLastActiveToNow(token);
            if (StpUtil.getLoginIdByToken(token) == null) {
                return R.fail("刷新令牌已失效，请重新登录");
            }
            long remainingTimeout = StpUtil.getTokenTimeout(token);
            if (remainingTimeout != -1 && remainingTimeout <= 0) {
                return R.fail("刷新令牌已失效，请重新登录");
            }
            LoginVo loginVo = new LoginVo();
            loginVo.setAccessToken(token);
            loginVo.setRefreshToken(token);
            loginVo.setExpireIn(remainingTimeout);
            loginVo.setRefreshExpireIn(remainingTimeout);
            return R.ok(loginVo);
        } catch (RuntimeException exception) {
            return R.fail("刷新令牌已失效，请重新登录");
        }
    }

}
