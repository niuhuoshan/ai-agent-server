package group.aitools.nhs.platform.connector.service;

import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.connector.mapper.ConnectorCatalogMapper;
import group.aitools.nhs.platform.connector.mapper.McpRuntimeMapper;
import group.aitools.nhs.platform.connector.persistence.row.McpAgentUsageRow;
import group.aitools.nhs.platform.connector.web.McpAgentUsageView;
import group.aitools.nhs.platform.connector.web.McpConnectorUsageView;
import group.aitools.nhs.platform.connector.web.McpRuntimeHealthView;
import group.aitools.nhs.platform.connector.web.McpRuntimeMountView;
import group.aitools.nhs.platform.connector.web.McpRuntimeOverviewView;
import group.aitools.nhs.platform.connector.web.McpUsageDetailView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 负责Mcp运行时Observation相关的业务编排与领域规则处理。
 * Applies connector ownership before exposing runtime operational facts. */
@Service
public class McpRuntimeObservationService {

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final ConnectorCatalogMapper connectorMapper;
    private final McpRuntimeMapper runtimeMapper;

    public McpRuntimeObservationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        ConnectorCatalogMapper connectorMapper,
        McpRuntimeMapper runtimeMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.connectorMapper = connectorMapper;
        this.runtimeMapper = runtimeMapper;
    }

    /**
     * 处理{@code overview}并返回对应结果。
     *
     * @param connectorId 资源标识
     * @param mountLimit 数量上限
     * @param usageLimit 数量上限
     * @return 处理结果
     */
    public McpRuntimeOverviewView overview(Long connectorId, int mountLimit, int usageLimit) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "connector", connectorId, null, "view", ResourceState.ACTIVE, true, Set.of(), null
        ));
        AgentConnector connector = connectorMapper.selectConnectorById(connectorId);
        if (connector == null || !visible(principal, connector)) {
            throw new ServiceException("连接器不存在", HttpStatus.NOT_FOUND);
        }
        if (!manageable(principal, connector)) {
            throw new ServiceException("只有连接器维护者可以查看运行明细", HttpStatus.FORBIDDEN);
        }
        if (!"mcp".equals(connector.getProviderType())) {
            throw new ServiceException("只有 MCP 连接器拥有运行观测数据", HttpStatus.BAD_REQUEST);
        }
        return new McpRuntimeOverviewView(
            McpRuntimeHealthView.from(connectorId, runtimeMapper.selectHealth(connectorId)),
            runtimeMapper.selectMounts(connectorId, mountLimit).stream()
                .map(McpRuntimeMountView::from).toList(),
            runtimeMapper.selectUsage(connectorId, usageLimit).stream()
                .map(McpUsageDetailView::from).toList()
        );
    }

    /**
 * 处理{@code usage}并返回对应结果。
 *
     * Summarizes the Agent/version bindings that reference this connector. This is deliberately
     * separate from runtime invocation telemetry: operators need to see both configured usage
     * and actual calls, even when no call has happened yet.
     */
    public McpConnectorUsageView usage(Long connectorId) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "connector", connectorId, null, "view", ResourceState.ACTIVE, true, Set.of(), null
        ));
        AgentConnector connector = connectorMapper.selectConnectorById(connectorId);
        if (connector == null || !visible(principal, connector)) {
            throw new ServiceException("连接器不存在", HttpStatus.NOT_FOUND);
        }
        if (!manageable(principal, connector)) {
            throw new ServiceException("只有连接器维护者可以查看使用情况", HttpStatus.FORBIDDEN);
        }
        if (!"mcp".equals(connector.getProviderType())) {
            throw new ServiceException("只有 MCP 连接器拥有使用情况", HttpStatus.BAD_REQUEST);
        }

        Map<Long, UsageAccumulator> byAgent = new LinkedHashMap<>();
        Set<Long> versions = new LinkedHashSet<>();
        for (McpAgentUsageRow row : runtimeMapper.selectAgentUsage(connectorId)) {
            if (row.getAgentId() == null || row.getAgentVersionId() == null) {
                continue;
            }
            versions.add(row.getAgentVersionId());
            UsageAccumulator usage = byAgent.computeIfAbsent(
                row.getAgentId(), ignored -> new UsageAccumulator(row.getAgentId(), row.getAgentName())
            );
            usage.versionIds.add(row.getAgentVersionId());
            usage.enabled |= "active".equals(row.getAgentStatus());
            usage.active |= usage.enabled
                && "published".equals(row.getAgentVersionStatus())
                && "active".equals(row.getToolStatus())
                && Boolean.TRUE.equals(row.getToolAvailable());
        }
        var agents = byAgent.values().stream()
            .map(value -> new McpAgentUsageView(
                value.id, value.name, value.name, value.enabled, value.active, value.versionIds.size()
            ))
            .toList();
        int activeAgents = (int) agents.stream().filter(McpAgentUsageView::active).count();
        return new McpConnectorUsageView(
            connectorId, agents.size(), activeAgents, versions.size(), agents
        );
    }

    /**
     * 表示{@code UsageAccumulator}相关的领域对象。
     */
    private static final class UsageAccumulator {
        private final Long id;
        private final String name;
        private final Set<Long> versionIds = new LinkedHashSet<>();
        private boolean enabled;
        private boolean active;

        /**
         * 创建 {@code UsageAccumulator} 实例并初始化所需依赖。
         *
         * @param id 资源标识
         * @param name 名称
         */
        private UsageAccumulator(Long id, String name) {
            this.id = id;
            this.name = name == null || name.isBlank() ? String.valueOf(id) : name;
        }
    }

    /**
     * 处理{@code visible}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param connector 连接器参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean visible(CurrentPrincipal principal, AgentConnector connector) {
        return "global".equals(connector.getScopeType())
            || ("personal".equals(connector.getScopeType())
                && principal.id().equals(connector.getOwnerId()));
    }

    /**
     * 处理{@code manageable}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param connector 连接器参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean manageable(CurrentPrincipal principal, AgentConnector connector) {
        if ("personal".equals(connector.getScopeType())) {
            return principal.id().equals(connector.getOwnerId());
        }
        return "global".equals(connector.getScopeType())
            && principal.hasRole(PlatformRole.PLATFORM_ADMIN);
    }
}
