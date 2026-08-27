package group.aitools.nhs.web.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import cn.hutool.core.util.ObjectUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import group.aitools.nhs.common.core.constant.Constants;
import group.aitools.nhs.common.core.constant.GlobalConstants;
import group.aitools.nhs.common.core.constant.SystemConstants;
import group.aitools.nhs.common.core.enums.LoginType;
import group.aitools.nhs.common.core.exception.user.CaptchaExpireException;
import group.aitools.nhs.common.core.exception.user.UserException;
import group.aitools.nhs.common.core.utils.MessageUtils;
import group.aitools.nhs.common.core.utils.StringUtils;
import group.aitools.nhs.common.core.utils.ValidatorUtils;
import group.aitools.nhs.common.json.utils.JsonUtils;
import group.aitools.nhs.common.redis.utils.RedisUtils;
import group.aitools.nhs.common.satoken.utils.LoginHelper;
import group.aitools.nhs.system.api.model.LoginUser;
import group.aitools.nhs.system.api.model.SmsLoginBody;
import group.aitools.nhs.system.domain.SysUser;
import group.aitools.nhs.system.domain.vo.SysClientVo;
import group.aitools.nhs.system.domain.vo.SysUserVo;
import group.aitools.nhs.system.mapper.SysUserMapper;
import group.aitools.nhs.web.domain.vo.LoginVo;
import group.aitools.nhs.web.service.IAuthStrategy;
import group.aitools.nhs.web.service.SysLoginService;
import org.springframework.stereotype.Service;

/**
 * 短信认证策略
 *
 * @author Michelle.Chung
 */
@Slf4j
@Service("sms" + IAuthStrategy.BASE_NAME)
@RequiredArgsConstructor
public class SmsAuthStrategy implements IAuthStrategy {

    private final SysLoginService loginService;
    private final SysUserMapper userMapper;

    /**
     * 执行短信验证码登录，并按客户端配置生成访问令牌。
     *
     * @param body   登录请求体
     * @param client 当前客户端配置
     * @return 登录结果
     */
    @Override
    public LoginVo login(String body, SysClientVo client) {
        SmsLoginBody loginBody = JsonUtils.parseObject(body, SmsLoginBody.class);
        ValidatorUtils.validate(loginBody);
        String phoneNumber = loginBody.getPhoneNumber();
        String smsCode = loginBody.getSmsCode();
        SysUserVo user = loadUserByPhoneNumber(phoneNumber);
        loginService.checkLogin(LoginType.SMS, user.getUserName(), () -> !validateSmsCode(phoneNumber, smsCode));
        // 此处可根据登录用户的数据不同 自行创建 loginUser 属性不够用继承扩展就行了
        LoginUser loginUser = loginService.buildLoginUser(user);
        loginUser.setClientKey(client.getClientKey());
        loginUser.setDeviceType(client.getDeviceType());
        SaLoginParameter model = IAuthStrategy.buildLoginParameter(client);
        // 生成token
        LoginHelper.login(loginUser, model);

        LoginVo loginVo = new LoginVo();
        loginVo.setAccessToken(StpUtil.getTokenValue());
        loginVo.setExpireIn(StpUtil.getTokenTimeout());
        loginVo.setClientId(client.getClientId());
        return loginVo;
    }

    /**
     * 校验短信验证码是否存在且匹配。
     *
     * @param phoneNumber 手机号
     * @param smsCode     用户输入的短信验证码
     * @return 是否校验通过
     */
    private boolean validateSmsCode(String phoneNumber, String smsCode) {
        String code = RedisUtils.getCacheObject(GlobalConstants.CAPTCHA_CODE_KEY + phoneNumber);
        if (StringUtils.isBlank(code)) {
            loginService.recordLoginInfo(phoneNumber, Constants.LOGIN_FAIL, MessageUtils.message("user.jcaptcha.expire"));
            throw new CaptchaExpireException();
        }
        return code.equals(smsCode);
    }

    /**
     * 按手机号加载可登录用户，并校验是否存在或被停用。
     *
     * @param phoneNumber 手机号
     * @return 用户信息
     */
    private SysUserVo loadUserByPhoneNumber(String phoneNumber) {
        SysUserVo user = userMapper.lambda()
            .eq(SysUser::getPhoneNumber, phoneNumber)
            .voOne();
        if (ObjectUtil.isNull(user)) {
            log.info("登录用户：{} 不存在.", phoneNumber);
            throw new UserException("user.not.exists", phoneNumber);
        } else if (SystemConstants.DISABLE.equals(user.getStatus())) {
            log.info("登录用户：{} 已被停用.", phoneNumber);
            throw new UserException("user.blocked", phoneNumber);
        }
        return user;
    }

}
