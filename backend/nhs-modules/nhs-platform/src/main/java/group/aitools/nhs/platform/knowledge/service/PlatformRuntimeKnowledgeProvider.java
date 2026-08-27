package group.aitools.nhs.platform.knowledge.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeKnowledgeDefinition;
import group.aitools.nhs.runtime.spi.RuntimeKnowledgeProvider;
import group.aitools.nhs.platform.execution.service.FrozenRuntimePrincipalResolver;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeBase;
import group.aitools.nhs.platform.knowledge.mapper.KnowledgeCatalogMapper;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 负责平台运行时知识库相关的转换、解析或处理逻辑。
 * Runtime knowledge gateway using frozen task/Agent bindings and current deny/availability state. */
@Service
public class PlatformRuntimeKnowledgeProvider implements RuntimeKnowledgeProvider {

    private final FrozenRuntimePrincipalResolver principalResolver;
    private final KnowledgeCatalogMapper mapper;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final KnowledgeAuthorizationContextFactory contextFactory;
    private final KnowledgeRetrievalService retrievalService;

    public PlatformRuntimeKnowledgeProvider(
        FrozenRuntimePrincipalResolver principalResolver,
        KnowledgeCatalogMapper mapper,
        AuthorizationEnforcer authorizationEnforcer,
        KnowledgeAuthorizationContextFactory contextFactory,
        KnowledgeRetrievalService retrievalService
    ) {
        this.principalResolver = principalResolver;
        this.mapper = mapper;
        this.authorizationEnforcer = authorizationEnforcer;
        this.contextFactory = contextFactory;
        this.retrievalService = retrievalService;
    }

    /**
     * 获取{@code resolve}。
     *
     * @param request 请求参数
     * @return 符合条件的数据集合
     */
    @Override
    public List<RuntimeKnowledgeDefinition> resolve(AgentRunRequest request) {
        CurrentPrincipal principal = principalResolver.resolve(request);
        List<RuntimeKnowledgeDefinition> result = new ArrayList<>();
        for (FrozenKnowledge frozen : frozenKnowledge(request)) {
            requireTaskGrant(request, frozen.id());
            AgentKnowledgeBase current = current(frozen);
            if (current == null) {
                continue;
            }
            AuthorizationDecision decision = authorizationEnforcer.decide(
                principal, contextFactory.context(principal, current, "read", false)
            );
            if (!decision.allowed() && !decision.requiresApproval()) {
                continue;
            }
            result.add(new RuntimeKnowledgeDefinition(
                frozen.id(), frozen.name(), frozen.description(), decision.requiresApproval()
            ));
        }
        return List.copyOf(result);
    }

    /**
 * 处理accessible目录并返回对应结果。
 * Returns the frozen knowledge directory after current deny and availability checks. */
    public List<Map<String, Object>> accessibleCatalog(AgentRunRequest request) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        CurrentPrincipal principal = principalResolver.resolve(request);
        List<Map<String, Object>> result = new ArrayList<>();
        for (FrozenKnowledge frozen : frozenKnowledge(request)) {
            requireTaskGrant(request, frozen.id());
            AgentKnowledgeBase current = current(frozen);
            if (current == null) {
                continue;
            }
            AuthorizationDecision decision = authorizationEnforcer.decide(
                principal, contextFactory.context(principal, current, "read", false)
            );
            if (!decision.allowed() && !decision.requiresApproval()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", frozen.id());
            item.put("key", frozen.knowledgeKey());
            item.put("name", frozen.name());
            if (frozen.description() != null) {
                item.put("description", frozen.description());
            }
            item.put("permission", "read");
            result.add(Map.copyOf(item));
        }
        return List.copyOf(result);
    }

    /**
     * 查询{@code search}列表。
     *
     * @param request 请求参数
     * @param knowledgeBaseId 资源标识
     * @param query 查询参数
     * @param topK {@code topK}参数
     * @return 处理结果
     */
    @Override
    public Object search(
        AgentRunRequest request,
        Long knowledgeBaseId,
        String query,
        Integer topK
    ) {
        FrozenKnowledge frozen = frozenKnowledge(request).stream()
            .filter(value -> Objects.equals(value.id(), knowledgeBaseId))
            .findFirst()
            .orElseThrow(() -> forbidden("知识库不在 Agent 冻结资源中"));
        requireTaskGrant(request, knowledgeBaseId);
        AgentKnowledgeBase current = current(frozen);
        if (current == null) {
            throw forbidden("知识库当前不可用");
        }
        CurrentPrincipal principal = principalResolver.resolve(request);
        AuthorizationDecision decision = authorizationEnforcer.decide(
            principal, contextFactory.context(principal, current, "read", false)
        );
        if (!decision.allowed() && !decision.requiresApproval()) {
            throw forbidden("知识库当前授权已失效：" + decision.reasonCode());
        }
        return retrievalService.retrieve(
            principal, List.of(knowledgeBaseId), query, topK, null, null, false,
            frozen.config() == null ? Map.of() : Map.of(knowledgeBaseId, frozen.config())
        );
    }

    /**
     * 处理当前并返回对应结果。
     *
     * @param frozen {@code frozen}参数
     * @return 处理结果
     */
    private AgentKnowledgeBase current(FrozenKnowledge frozen) {
        AgentKnowledgeBase base = mapper.selectBaseById(frozen.id());
        if (base == null || !"active".equals(base.getStatus())
            || !Objects.equals(frozen.knowledgeKey(), base.getKnowledgeKey())
            || !Objects.equals(frozen.providerType(), base.getProviderType())) {
            return null;
        }
        return base;
    }

    /**
     * 处理frozen知识库并返回对应结果。
     *
     * @param request 请求参数
     * @return 符合条件的数据集合
     */
    private List<FrozenKnowledge> frozenKnowledge(AgentRunRequest request) {
        if (!(request.attributes().get("resourceBindings") instanceof List<?> bindings)) {
            throw new SecurityException("运行快照缺少 Agent 资源绑定");
        }
        List<FrozenKnowledge> result = new ArrayList<>();
        for (Object value : bindings) {
            if (!(value instanceof Map<?, ?> raw) || !"knowledge_base".equals(raw.get("resourceType"))) {
                continue;
            }
            Map<String, Object> binding = stringMap(raw);
            Long id = positiveLong(binding.get("resourceId"), "知识库资源 ID");
            Map<String, Object> config = requiredMap(binding.get("config"), "知识库绑定配置");
            Map<String, Object> snapshot = requiredMap(
                config.get("resourceSnapshot"), "知识库资源快照"
            );
            String providerType = requiredText(snapshot.get("providerType"), "知识库 Provider");
            result.add(new FrozenKnowledge(
                id,
                requiredText(snapshot.get("knowledgeKey"), "知识库标识"),
                requiredText(snapshot.get("name"), "知识库名称"),
                optionalText(snapshot.get("description")),
                providerType,
                "postgres_pgvector".equals(providerType)
                    ? KnowledgeBaseConfig.from(optionalMap(snapshot.get("config"))) : null
            ));
        }
        return List.copyOf(result);
    }

    /**
     * 校验任务Grant，并在条件不满足时终止处理。
     *
     * @param request 请求参数
     * @param baseId 资源标识
     */
    private void requireTaskGrant(AgentRunRequest request, Long baseId) {
        Map<String, Object> snapshot = requiredMap(
            request.attributes().get("taskResourceSnapshot"), "任务资源快照"
        );
        if (!request.agentVersionId().equals(positiveLong(
            snapshot.get("agentVersionId"), "任务 Agent 版本 ID"
        ))) {
            throw new SecurityException("任务资源快照与 Agent 版本不一致");
        }
        if (!(snapshot.get("resources") instanceof List<?> resources)) {
            throw new SecurityException("任务资源快照缺少授权资源");
        }
        boolean granted = resources.stream().anyMatch(value -> {
            if (!(value instanceof Map<?, ?> resource) || !(resource.get("resourceId") instanceof Number id)) {
                return false;
            }
            return "knowledge_base".equals(resource.get("resourceType"))
                && id.doubleValue() == id.longValue() && baseId.longValue() == id.longValue()
                && Set.of("read", "admin").contains(resource.get("permission"));
        });
        if (!granted) {
            throw new SecurityException("知识库不在任务冻结授权中");
        }
    }

    /**
     * 校验{@code dMap}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Map<String, Object> requiredMap(Object value, String label) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new SecurityException(label + "无效");
        }
        return stringMap(raw);
    }

    /**
     * 处理{@code optionalMap}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> optionalMap(Object value) {
        return value instanceof Map<?, ?> raw ? stringMap(raw) : Map.of();
    }

    /**
     * 处理{@code stringMap}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    private Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return java.util.Collections.unmodifiableMap(result);
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
        String text = optionalText(value);
        if (text == null) {
            throw new SecurityException(label + "无效");
        }
        return text;
    }

    /**
     * 处理{@code optionalText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String optionalText(Object value) {
        return value instanceof String text && !text.isBlank() ? text.strip() : null;
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

    /**
     * 封装Frozen知识库相关的不可变数据。
     */
    private record FrozenKnowledge(
        Long id,
        String knowledgeKey,
        String name,
        String description,
        String providerType,
        KnowledgeBaseConfig config
    ) {
    }
}
