package group.aitools.nhs.platform.operations.service;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.operations.domain.PlatformConfiguration;
import group.aitools.nhs.platform.operations.domain.PlatformConfigurationHistory;
import group.aitools.nhs.platform.operations.mapper.PlatformConfigurationMapper;
import group.aitools.nhs.platform.operations.web.PlatformConfigurationHistoryView;
import group.aitools.nhs.platform.operations.web.PlatformConfigurationView;
import group.aitools.nhs.platform.operations.web.PublicPlatformConfigurationView;
import group.aitools.nhs.platform.operations.web.UpdatePlatformConfigurationRequest;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 负责平台配置相关的业务编排与领域规则处理。
 * Versioned private-deployment branding and timezone configuration. */
@Service
public class PlatformConfigurationApplicationService {

    private final CurrentPrincipalProvider principalProvider;
    private final PlatformConfigurationMapper mapper;
    private final PlatformIdGenerator idGenerator;
    private final AgentAuditEventMapper auditMapper;

    public PlatformConfigurationApplicationService(
        CurrentPrincipalProvider principalProvider,
        PlatformConfigurationMapper mapper,
        PlatformIdGenerator idGenerator,
        AgentAuditEventMapper auditMapper
    ) {
        this.principalProvider = principalProvider;
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.auditMapper = auditMapper;
    }

    /**
     * 处理当前并返回对应结果。
     *
     * @return 处理结果
     */
    public PlatformConfigurationView current() {
        requireAdministrator();
        return PlatformConfigurationView.from(requireCurrent());
    }

    /**
     * 处理public配置并返回对应结果。
     *
     * @return 处理结果
     */
    public PublicPlatformConfigurationView publicConfiguration() {
        return PublicPlatformConfigurationView.from(requireCurrent());
    }

    /**
     * 处理历史记录并返回对应结果。
     *
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<PlatformConfigurationHistoryView> history(int limit) {
        requireAdministrator();
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        return mapper.selectHistory(boundedLimit).stream()
            .map(PlatformConfigurationHistoryView::from)
            .toList();
    }

    /**
     * 更新{@code update}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public PlatformConfigurationView update(UpdatePlatformConfigurationRequest request) {
        CurrentPrincipal principal = requireAdministrator();
        PlatformConfiguration current = requireCurrent();
        if (!current.getRevisionNo().equals(request.expectedRevision())) {
            throw conflict("平台配置已被其他管理员更新，请刷新后重试");
        }

        LocalDateTime now = LocalDateTime.now();
        PlatformConfiguration next = normalized(request, principal.id(), now);
        if (mapper.updateCurrent(next, request.expectedRevision()) != 1) {
            throw conflict("平台配置已被其他管理员更新，请刷新后重试");
        }
        next.setId(1L);
        next.setRevisionNo(request.expectedRevision() + 1);

        PlatformConfigurationHistory history = history(next, request.changeReason().strip(), principal.id(), now);
        if (mapper.insertHistory(history) != 1) {
            throw conflict("平台配置历史写入失败");
        }
        auditMapper.insertEvent(
            idGenerator.nextId(), "user", principal.id(), "update", "platform_configuration",
            1L, null, "success", "platform_admin",
            "revision=" + next.getRevisionNo() + ", reason=" + bounded(request.changeReason(), 400), now
        );
        return PlatformConfigurationView.from(next);
    }

    /**
     * 处理{@code normalized}并返回对应结果。
     *
     * @param request 请求参数
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    private PlatformConfiguration normalized(
        UpdatePlatformConfigurationRequest request,
        Long actorId,
        LocalDateTime now
    ) {
        String timezone = request.platformTimezone().strip();
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException exception) {
            throw badRequest("平台时区必须是有效的 IANA 时区");
        }
        PlatformConfiguration value = new PlatformConfiguration();
        value.setId(1L);
        value.setProductName(text(request.productName(), 128, "产品名称"));
        value.setProductShortName(text(request.productShortName(), 32, "产品短名称"));
        value.setLogoUrl(safeAssetUrl(request.logoUrl(), "Logo"));
        value.setFaviconUrl(safeAssetUrl(request.faviconUrl(), "浏览器图标"));
        value.setPrimaryColor(request.primaryColor().strip().toUpperCase());
        value.setPlatformTimezone(timezone);
        value.setDefaultLocale(request.defaultLocale());
        value.setWatermarkEnabled(request.watermarkEnabled());
        value.setUpdateBy(actorId);
        value.setUpdateTime(now);
        return value;
    }

    /**
     * 处理历史记录并返回对应结果。
     *
     * @param value {@code value}参数
     * @param reason {@code reason}参数
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    private PlatformConfigurationHistory history(
        PlatformConfiguration value,
        String reason,
        Long actorId,
        LocalDateTime now
    ) {
        PlatformConfigurationHistory history = new PlatformConfigurationHistory();
        history.setId(idGenerator.nextId());
        history.setConfigurationId(1L);
        history.setProductName(value.getProductName());
        history.setProductShortName(value.getProductShortName());
        history.setLogoUrl(value.getLogoUrl());
        history.setFaviconUrl(value.getFaviconUrl());
        history.setPrimaryColor(value.getPrimaryColor());
        history.setPlatformTimezone(value.getPlatformTimezone());
        history.setDefaultLocale(value.getDefaultLocale());
        history.setWatermarkEnabled(value.getWatermarkEnabled());
        history.setRevisionNo(value.getRevisionNo());
        history.setChangeReason(reason);
        history.setChangedBy(actorId);
        history.setCreatedAt(now);
        return history;
    }

    /**
     * 校验当前，并在条件不满足时终止处理。
     *
     * @return 处理结果
     */
    private PlatformConfiguration requireCurrent() {
        PlatformConfiguration current = mapper.selectCurrent();
        if (current == null) {
            throw new ServiceException("平台配置尚未初始化", HttpStatus.ERROR);
        }
        return current;
    }

    /**
     * 校验{@code Administrator}，并在条件不满足时终止处理。
     *
     * @return 处理结果
     */
    private CurrentPrincipal requireAdministrator() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (principal == null || !principal.isHuman()
            || !principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
            throw new ServiceException("仅平台管理员可以管理平台配置", HttpStatus.FORBIDDEN);
        }
        return principal;
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maximum {@code maximum}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String text(String value, int maximum, String label) {
        String normalized = value == null ? "" : value.replace('\0', ' ').strip();
        if (normalized.isBlank() || normalized.length() > maximum) {
            throw badRequest(label + "为空或超过长度限制");
        }
        return normalized;
    }

    /**
     * 处理{@code safeAssetUrl}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String safeAssetUrl(String value, String label) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace('\0', ' ').strip();
        if (normalized.length() > 512 || normalized.indexOf('\\') >= 0) {
            throw badRequest(label + "地址无效");
        }
        if (normalized.startsWith("/") && !normalized.startsWith("//")) {
            return normalized;
        }
        try {
            URI uri = URI.create(normalized);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw badRequest(label + "仅支持站内相对路径或 HTTPS 地址");
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException exception) {
            throw badRequest(label + "地址无效");
        }
    }

    /**
     * 处理{@code bounded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maximum {@code maximum}参数
     * @return 处理结果
     */
    private String bounded(String value, int maximum) {
        String normalized = value == null ? "" : value.replace('\0', ' ').strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    /**
     * 处理{@code badRequest}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 处理{@code conflict}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }
}
