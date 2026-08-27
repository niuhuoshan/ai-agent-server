package group.aitools.nhs.platform.memory.service;

import group.aitools.nhs.platform.execution.persistence.row.TaskRunDefinitionRow;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.memory.domain.AgentMemory;
import group.aitools.nhs.platform.memory.mapper.MemoryCatalogMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责记忆运行时快照相关的业务编排与领域规则处理。
 * Freezes bounded approved memory content into a durable run definition. */
@Service
public class MemoryRuntimeSnapshotService {

    private static final int PER_SCOPE_LIMIT = 10;
    private static final int TOTAL_LIMIT = 24;

    private final MemoryScopeAuthorizationService scopeAuthorization;
    private final MemoryCatalogMapper mapper;

    public MemoryRuntimeSnapshotService(
        MemoryScopeAuthorizationService scopeAuthorization,
        MemoryCatalogMapper mapper
    ) {
        this.scopeAuthorization = scopeAuthorization;
        this.mapper = mapper;
    }

    /**
     * 处理快照并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param definition 定义参数
     * @return 符合条件的数据集合
     */
    public List<Map<String, Object>> snapshot(
        CurrentPrincipal principal,
        TaskRunDefinitionRow definition
    ) {
        if (!principal.isHuman() || !memoryEnabled()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        addAuthorized(result, principal, "task", definition.getTaskId());
        addAuthorized(result, principal, "project", definition.getProjectId());
        if (principal.isHuman()) {
            addAuthorized(result, principal, "user", principal.id());
        }
        return List.copyOf(result.stream().limit(TOTAL_LIMIT).toList());
    }

    /**
 * 处理快照会话并返回对应结果。
 * Freezes project and user preference memory for a private human conversation. */
    public List<Map<String, Object>> snapshotConversation(
        CurrentPrincipal principal,
        Long projectId
    ) {
        if (!principal.isHuman() || !memoryEnabled()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        addAuthorized(result, principal, "project", projectId);
        addAuthorized(result, principal, "user", principal.id());
        return List.copyOf(result.stream().limit(TOTAL_LIMIT).toList());
    }

    /**
     * 处理记忆Enabled并返回对应结果。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean memoryEnabled() {
        var config = mapper.selectRuntimeConfig();
        return config == null || !Boolean.FALSE.equals(config.getEnabled());
    }

    /**
     * 创建并保存{@code Authorized}。
     *
     * @param target {@code target}参数
     * @param principal 当前操作主体
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     */
    private void addAuthorized(
        List<Map<String, Object>> target,
        CurrentPrincipal principal,
        String scopeType,
        Long scopeId
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (scopeId == null || target.size() >= TOTAL_LIMIT
            || !scopeAuthorization.canView(principal, scopeType, scopeId)) {
            return;
        }
        for (AgentMemory memory : mapper.selectApprovedForSnapshot(
            scopeType, scopeId, PER_SCOPE_LIMIT
        )) {
            if (target.size() >= TOTAL_LIMIT) {
                return;
            }
            if ("candidate".equals(memory.getMemoryType())
                || ("user".equals(scopeType) && !"preference".equals(memory.getMemoryType()))
                || memory.getContent() == null || memory.getContent().isBlank()
                || memory.getContent().length() > 4000
                || memory.getContentHash() == null
                || !memory.getContentHash().matches("[0-9a-f]{64}")) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", memory.getId());
            item.put("revisionNo", memory.getRevisionNo());
            item.put("scopeType", memory.getScopeType());
            item.put("scopeId", memory.getScopeId());
            item.put("memoryType", memory.getMemoryType());
            item.put("content", memory.getContent());
            item.put("contentHash", memory.getContentHash());
            target.add(Map.copyOf(item));
        }
    }
}
