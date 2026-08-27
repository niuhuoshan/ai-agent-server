package group.aitools.nhs.platform.memory.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeMemoryDefinition;
import group.aitools.nhs.runtime.spi.RuntimeMemoryProvider;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.execution.service.FrozenRuntimePrincipalResolver;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.memory.domain.AgentMemory;
import group.aitools.nhs.platform.memory.mapper.MemoryCatalogMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 负责平台运行时记忆相关的转换、解析或处理逻辑。
 * Revalidates frozen memory revisions and current scope access before prompt injection. */
@Service
public class PlatformRuntimeMemoryProvider implements RuntimeMemoryProvider {

    private static final int MAX_ENTRIES = 24;

    private final FrozenRuntimePrincipalResolver principalResolver;
    private final MemoryScopeAuthorizationService scopeAuthorization;
    private final MemoryCatalogMapper mapper;

    public PlatformRuntimeMemoryProvider(
        FrozenRuntimePrincipalResolver principalResolver,
        MemoryScopeAuthorizationService scopeAuthorization,
        MemoryCatalogMapper mapper
    ) {
        this.principalResolver = principalResolver;
        this.scopeAuthorization = scopeAuthorization;
        this.mapper = mapper;
    }

    /**
     * 获取{@code resolve}。
     *
     * @param request 请求参数
     * @return 符合条件的数据集合
     */
    @Override
    public List<RuntimeMemoryDefinition> resolve(AgentRunRequest request) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Object rawSnapshot = request.attributes().get("memorySnapshot");
        if (rawSnapshot == null) {
            return List.of();
        }
        if (!(rawSnapshot instanceof List<?> entries) || entries.size() > MAX_ENTRIES) {
            throw new SecurityException("运行记忆快照无效或超过 24 条限制");
        }
        CurrentPrincipal principal = principalResolver.resolve(request);
        Set<Long> seen = new HashSet<>();
        List<RuntimeMemoryDefinition> result = new ArrayList<>();
        for (Object value : entries) {
            Map<String, Object> frozen = requiredMap(value);
            Long id = positiveLong(frozen.get("id"), "记忆 ID");
            if (!seen.add(id)) {
                throw new SecurityException("运行记忆快照包含重复 ID");
            }
            Long revision = positiveLong(frozen.get("revisionNo"), "记忆修订号");
            String scopeType = requiredText(frozen.get("scopeType"), "记忆作用域");
            Long scopeId = positiveLong(frozen.get("scopeId"), "记忆作用域 ID");
            String memoryType = requiredText(frozen.get("memoryType"), "记忆类型");
            String content = requiredText(frozen.get("content"), "记忆正文");
            String contentHash = requiredText(frozen.get("contentHash"), "记忆正文哈希");
            if (!ContentHashing.sha256(content).equals(contentHash)) {
                throw new SecurityException("运行记忆快照正文哈希不一致");
            }
            AgentMemory current = mapper.selectById(id);
            if (!currentlyUsable(
                current, revision, scopeType, scopeId, memoryType, contentHash
            ) || !scopeAuthorization.canView(principal, scopeType, scopeId)) {
                continue;
            }
            result.add(new RuntimeMemoryDefinition(
                id, scopeType, scopeId, memoryType, content
            ));
        }
        return List.copyOf(result);
    }

    /**
     * 处理{@code currentlyUsable}并返回对应结果。
     *
     * @param current 当前参数
     * @param revision {@code revision}参数
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param memoryType 业务类型
     * @param contentHash 待处理内容
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean currentlyUsable(
        AgentMemory current,
        Long revision,
        String scopeType,
        Long scopeId,
        String memoryType,
        String contentHash
    ) {
        return current != null
            && Objects.equals(revision, current.getRevisionNo())
            && Objects.equals(scopeType, current.getScopeType())
            && Objects.equals(scopeId, current.getScopeId())
            && Objects.equals(memoryType, current.getMemoryType())
            && Objects.equals(contentHash, current.getContentHash())
            && "approved".equals(current.getReviewStatus())
            && Set.of("public", "internal").contains(current.getSensitiveLevel())
            && (current.getExpiresAt() == null || current.getExpiresAt().isAfter(LocalDateTime.now()));
    }

    /**
     * 校验{@code dMap}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> requiredMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new SecurityException("运行记忆快照条目无效");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    /**
     * 处理{@code positiveLong}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Long positiveLong(Object value, String label) {
        if (!(value instanceof Number number) || number.longValue() <= 0
            || number.doubleValue() != number.longValue()) {
            throw new SecurityException(label + "无效");
        }
        return number.longValue();
    }

    /**
     * 校验{@code dText}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String requiredText(Object value, String label) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new SecurityException(label + "无效");
        }
        return text.strip();
    }
}
