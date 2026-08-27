package group.aitools.nhs.platform.agent.service;

import group.aitools.nhs.platform.agent.domain.AgentDefinition;
import group.aitools.nhs.platform.agent.mapper.AgentDefinitionMapper;
import group.aitools.nhs.platform.agent.mapper.AgentExecutionHistoryMapper;
import group.aitools.nhs.platform.agent.web.AgentExecutionHistoryView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.time.LocalDateTime;

/**
 * 负责智能体执行历史记录相关的业务编排与领域规则处理。
 * Owner-scoped Agent execution history over durable human conversation facts. */
@Service
public class AgentExecutionHistoryService {

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final AgentDefinitionMapper definitionMapper;
    private final AgentExecutionHistoryMapper historyMapper;

    public AgentExecutionHistoryService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        AgentDefinitionMapper definitionMapper,
        AgentExecutionHistoryMapper historyMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.definitionMapper = definitionMapper;
        this.historyMapper = historyMapper;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param agentId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<AgentExecutionHistoryView> list(Long agentId, int limit) {
        if (limit < 1 || limit > 200) {
            throw new ServiceException("Agent 执行历史条数必须在 1 到 200 之间", HttpStatus.BAD_REQUEST);
        }
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "agent", agentId, null, "view", ResourceState.ACTIVE, true, Set.of(), null
        ));
        AgentDefinition definition = definitionMapper.selectDefinitionById(agentId);
        if (definition == null) {
            throw new ServiceException("Agent 不存在", HttpStatus.NOT_FOUND);
        }
        boolean platformAdmin = principal.hasRole(PlatformRole.PLATFORM_ADMIN);
        return historyMapper.selectExecutions(agentId, principal.id(), platformAdmin, limit).stream()
            .map(AgentExecutionHistoryView::from)
            .toList();
    }

    /**
 * 查询{@code page}列表。
 * Global Nhs V1 history projection with owner/admin filtering and stable pagination. */
    public ExecutionHistoryPage page(
        int page,
        int pageSize,
        Long agentId,
        Long conversationId,
        String username,
        String keyword,
        String status,
        LocalDateTime startDate,
        LocalDateTime endDate,
        boolean groupByConversation
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new ServiceException("历史分页参数无效", HttpStatus.BAD_REQUEST);
        }
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new ServiceException("开始时间不能晚于结束时间", HttpStatus.BAD_REQUEST);
        }
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        boolean platformAdmin = principal.hasRole(PlatformRole.PLATFORM_ADMIN);
        if (agentId != null) {
            authorizationEnforcer.requireAllowed(principal, new PermissionContext(
                "agent", agentId, null, "view", ResourceState.ACTIVE, true, Set.of(), null
            ));
            if (definitionMapper.selectDefinitionById(agentId) == null) {
                throw new ServiceException("Agent 不存在", HttpStatus.NOT_FOUND);
            }
        }
        String effectiveUsername = platformAdmin ? normalize(username) : null;
        String effectiveKeyword = normalize(keyword);
        String effectiveStatus = normalize(status);
        if ("success".equalsIgnoreCase(effectiveStatus)) {
            effectiveStatus = "succeeded";
        }
        long offset = Math.multiplyExact((long) page - 1, pageSize);
        List<AgentExecutionHistoryView> items = historyMapper.selectHistory(
            principal.id(), platformAdmin, agentId, conversationId, effectiveUsername,
            effectiveKeyword, effectiveStatus, startDate, endDate, groupByConversation,
            offset, pageSize
        ).stream().map(AgentExecutionHistoryView::from).toList();
        long total = historyMapper.countHistory(
            principal.id(), platformAdmin, agentId, conversationId, effectiveUsername,
            effectiveKeyword, effectiveStatus, startDate, endDate, groupByConversation
        );
        return new ExecutionHistoryPage(total, page, pageSize, items);
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * 封装执行历史记录Page相关的不可变数据。
     */
    public record ExecutionHistoryPage(
        long total,
        int page,
        int pageSize,
        List<AgentExecutionHistoryView> items
    ) {
    }
}
