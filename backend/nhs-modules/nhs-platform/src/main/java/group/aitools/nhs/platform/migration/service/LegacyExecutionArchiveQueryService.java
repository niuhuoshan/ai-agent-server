package group.aitools.nhs.platform.migration.service;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.migration.mapper.LegacyExecutionArchiveMapper;
import group.aitools.nhs.platform.migration.web.LegacyExecutionArchiveView;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 负责Legacy执行Archive查询相关的业务编排与领域规则处理。
 * Administrator-only lookup for immutable Nhs execution archives. */
@Service
public class LegacyExecutionArchiveQueryService {

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final LegacyExecutionArchiveMapper mapper;

    public LegacyExecutionArchiveQueryService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        LegacyExecutionArchiveMapper mapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.mapper = mapper;
    }

    /**
     * 查询{@code search}列表。
     *
     * @param traceId 资源标识
     * @param executionId 资源标识
     * @param sourceStatus 目标状态
     * @param beforeId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<LegacyExecutionArchiveView> search(
        String traceId,
        String executionId,
        String sourceStatus,
        Long beforeId,
        int limit
    ) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "audit", null, "legacy-execution-archive", "list", ResourceState.ACTIVE, true, Set.of()
        ));
        return mapper.search(
            optionalIdentifier(traceId, 128, "源 Trace ID"),
            optionalIdentifier(executionId, 128, "源执行 ID"),
            optionalIdentifier(sourceStatus, 32, "源状态"),
            beforeId,
            limit
        ).stream().map(LegacyExecutionArchiveView::from).toList();
    }

    /**
     * 处理{@code optionalIdentifier}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String optionalIdentifier(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength || normalized.indexOf('\0') >= 0
            || !normalized.matches("[A-Za-z0-9._:-]+")) {
            throw new ServiceException(label + "无效", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }
}
