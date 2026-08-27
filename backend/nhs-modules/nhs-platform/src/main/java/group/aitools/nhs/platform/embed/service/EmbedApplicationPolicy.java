package group.aitools.nhs.platform.embed.service;

import group.aitools.nhs.platform.identity.domain.ApiApplication;
import group.aitools.nhs.platform.identity.mapper.MachineIdentityMapper;
import group.aitools.nhs.platform.identity.service.AuthenticatedServiceAccount;
import group.aitools.nhs.platform.identity.service.EmbedApplicationConfig;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * 表示嵌入式会话应用策略相关的领域对象。
 * Rechecks the current Embed application browser policy after credential authentication. */
@Service
public class EmbedApplicationPolicy {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final MachineIdentityMapper mapper;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code EmbedApplicationPolicy} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public EmbedApplicationPolicy(MachineIdentityMapper mapper, JsonMapper jsonMapper) {
        this.mapper = mapper;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 校验会话Allowed，并在条件不满足时终止处理。
     *
     * @param authenticated {@code authenticated}参数
     * @param origin {@code origin}参数
     * @param agentVersionId 资源标识
     * @param expiresInMinutes {@code expiresInMinutes}参数
     */
    public void requireSessionAllowed(
        AuthenticatedServiceAccount authenticated,
        String origin,
        Long agentVersionId,
        int expiresInMinutes
    ) {
        EmbedApplicationConfig config = currentConfig(authenticated);
        if (origin != null && !origin.isBlank() && !config.allowsOrigin(origin)) {
            throw forbidden("当前宿主Origin不在Embed应用允许列表中");
        }
        if (!config.allowsAgentVersion(agentVersionId)) {
            throw forbidden("Agent版本不在Embed应用允许范围中");
        }
        if (expiresInMinutes > config.maxSessionMinutes()) {
            throw new ServiceException(
                "Embed会话有效期超过应用上限" + config.maxSessionMinutes() + "分钟",
                HttpStatus.BAD_REQUEST
            );
        }
    }

    /**
     * 校验{@code OriginAllowed}，并在条件不满足时终止处理。
     *
     * @param authenticated {@code authenticated}参数
     * @param origin {@code origin}参数
     */
    public void requireOriginAllowed(AuthenticatedServiceAccount authenticated, String origin) {
        if (origin == null || origin.isBlank()) {
            return;
        }
        EmbedApplicationConfig config = currentConfig(authenticated);
        if (!config.allowsOrigin(origin)) {
            throw forbidden("当前宿主Origin不在Embed应用允许列表中");
        }
    }

    /**
     * 校验{@code RequestAllowed}，并在条件不满足时终止处理。
     *
     * @param authenticated {@code authenticated}参数
     * @param origin {@code origin}参数
     * @param agentVersionId 资源标识
     */
    public void requireRequestAllowed(
        AuthenticatedServiceAccount authenticated,
        String origin,
        Long agentVersionId
    ) {
        EmbedApplicationConfig config = currentConfig(authenticated);
        if (origin != null && !origin.isBlank() && !config.allowsOrigin(origin)) {
            throw forbidden("当前宿主Origin不在Embed应用允许列表中");
        }
        if (!config.allowsAgentVersion(agentVersionId)) {
            throw forbidden("Agent版本不在Embed应用允许范围中");
        }
    }

    /**
     * 处理当前Config并返回对应结果。
     *
     * @param authenticated {@code authenticated}参数
     * @return 处理结果
     */
    public EmbedApplicationConfig currentConfig(AuthenticatedServiceAccount authenticated) {
        ApiApplication application = mapper.selectApiApplication(authenticated.applicationId());
        if (application == null || !"embed".equals(application.getAppType())
            || !"active".equals(application.getStatus())) {
            throw forbidden("Embed应用当前不可用");
        }
        EmbedApplicationConfig config = EmbedApplicationConfig.from(map(application.getExtraJson()));
        return config;
    }

    /**
     * 将输入数据转换为{@code map}。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> map(String value) {
        return value == null || value.isBlank() ? Map.of() : jsonMapper.readValue(value, MAP_TYPE);
    }

    /**
     * 处理{@code forbidden}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException forbidden(String message) {
        return new ServiceException(message, HttpStatus.FORBIDDEN);
    }
}
