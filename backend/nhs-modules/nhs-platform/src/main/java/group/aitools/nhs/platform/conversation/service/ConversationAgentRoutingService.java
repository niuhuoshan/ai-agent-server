package group.aitools.nhs.platform.conversation.service;

import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.web.CreateConversationTurnRequest;
import group.aitools.nhs.platform.embed.mapper.EmbedChatMapper;
import group.aitools.nhs.platform.embed.persistence.row.EmbedAgentRuntimeRow;
import group.aitools.nhs.platform.conversation.persistence.row.ConversationMessageRow;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.knowledge.service.KnowledgeCatalogRoutingService;
import group.aitools.nhs.platform.nhs.portal.chatbi.PortalChatBIModelGateway;
import group.aitools.nhs.platform.nhs.service.PortalPrefsApplicationService;
import group.aitools.nhs.platform.nhs.web.PortalPrefsContracts.Preferences;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 负责会话智能体Routing相关的业务编排与领域规则处理。
 *
 * Resolves explicit, mention, bound and default Agent routes. On the first
 * unbound turn, published routing tags provide a deterministic, permission
 * filtered fast path; an unmatched or ambiguous request stays on the default
 * Agent instead of guessing from an unauthorized candidate.
 */
@Service
public class ConversationAgentRoutingService {

    private static final int MAX_INPUT_BYTES = 128 * 1024;
    private static final double MIN_ROUTER_CONFIDENCE = 0.65D;
    private static final TypeReference<Map<String, Object>> ROUTER_PAYLOAD = new TypeReference<>() { };
    private static final TypeReference<List<String>> ROUTING_TAGS = new TypeReference<>() { };
    private static final Pattern LEADING_MENTION = Pattern.compile(
        "^@([\\p{L}\\p{N}_.-]{1,64})(?:\\s+|$)"
    );
    private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Set<String> GREETING_WORDS = Set.of(
        "你好", "您好", "嗨", "hello", "hi", "hey", "早上好", "下午好", "晚上好"
    );
    private static final List<String> GENERAL_FOLLOWUP_PREFIXES = List.of(
        "继续", "那", "那么", "再说说", "再讲讲", "再详细", "接着", "然后呢",
        "还有呢", "还有吗", "还有什么", "那么呢", "那你", "那它", "那这",
        "能再", "可以再", "请继续", "请再", "说下去", "接下来", "那这个",
        "这个呢", "那个呢", "它呢", "它是", "然后", "continue", "go on",
        "and then", "what else"
    );
    private static final List<String> GENERAL_FOLLOWUP_SUFFIXES = List.of("呢", "吗", "?", "？", "呀", "啊");
    private static final List<String> GENERAL_FOLLOWUP_BLOCKERS = List.of(
        "查询", "数据", "sql", "报表", "指标", "数据库", "知识库", "文档", "检索",
        "搜索", "联网", "新闻", "资讯", "公司", "品牌", "帮我", "写一个", "生成",
        "翻译", "总结这段", "分析这段"
    );

    private final EmbedChatMapper mapper;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final JsonMapper jsonMapper;
    private final PortalChatBIModelGateway routingModelGateway;
    private final KnowledgeCatalogRoutingService knowledgeCatalogRoutingService;
    private final PortalPrefsApplicationService portalPrefsService;

    /**
     * 创建 {@code ConversationAgentRoutingService} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param routingModelGateway routing模型Gateway参数
     * @param knowledgeCatalogRoutingService 知识库目录RoutingService参数
     * @param portalPrefsService 门户PrefsService参数
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ConversationAgentRoutingService(
        EmbedChatMapper mapper,
        AuthorizationEnforcer authorizationEnforcer,
        JsonMapper jsonMapper,
        PortalChatBIModelGateway routingModelGateway,
        KnowledgeCatalogRoutingService knowledgeCatalogRoutingService,
        PortalPrefsApplicationService portalPrefsService
    ) {
        this.mapper = mapper;
        this.authorizationEnforcer = authorizationEnforcer;
        this.jsonMapper = jsonMapper;
        this.routingModelGateway = routingModelGateway;
        this.knowledgeCatalogRoutingService = knowledgeCatalogRoutingService;
        this.portalPrefsService = portalPrefsService;
    }

    /**
     * 创建 {@code ConversationAgentRoutingService} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param routingModelGateway routing模型Gateway参数
     */
    public ConversationAgentRoutingService(
        EmbedChatMapper mapper,
        AuthorizationEnforcer authorizationEnforcer,
        JsonMapper jsonMapper,
        PortalChatBIModelGateway routingModelGateway
    ) {
        this(mapper, authorizationEnforcer, jsonMapper, routingModelGateway, null, null);
    }

    /**
     * 创建 {@code ConversationAgentRoutingService} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    ConversationAgentRoutingService(
        EmbedChatMapper mapper,
        AuthorizationEnforcer authorizationEnforcer,
        JsonMapper jsonMapper
    ) {
        this(mapper, authorizationEnforcer, jsonMapper, null, null, null);
    }

    /**
 * 创建 {@code ConversationAgentRoutingService} 实例并初始化所需依赖。
 * Package-private constructor keeps focused routing tests lightweight. */
    ConversationAgentRoutingService(
        EmbedChatMapper mapper,
        AuthorizationEnforcer authorizationEnforcer
    ) {
        this(mapper, authorizationEnforcer, JsonMapper.builder().build(), null, null, null);
    }

    /**
     * 处理{@code route}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param conversation 会话参数
     * @param request 请求参数
     * @return 处理结果
     */
    public RoutedAgent route(
        CurrentPrincipal principal,
        AgentConversation conversation,
        CreateConversationTurnRequest request
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String input = normalizeInput(request.input());
        Matcher mention = LEADING_MENTION.matcher(input);
        String routeToken = mention.find() ? mention.group(1) : null;
        if (routeToken != null) {
            input = normalizeInput(input.substring(mention.end()));
        }

        EmbedAgentRuntimeRow definition;
        String routeSource;
        double routeConfidence;
        String routeReason;
        int candidateCount = 0;
        if (request.agentVersionId() != null) {
            definition = mapper.selectAgentRuntime(request.agentVersionId());
            if (definition != null && request.agentId() != null
                && !request.agentId().equals(definition.getAgentId())) {
                throw badRequest("agentId与agentVersionId不属于同一Agent");
            }
            routeSource = "explicit_agent_version";
            routeConfidence = 1D;
            routeReason = "请求显式指定Agent版本";
        } else if (request.agentId() != null) {
            definition = mapper.selectPublishedAgentRuntime(request.agentId());
            routeSource = "explicit_agent";
            routeConfidence = 1D;
            routeReason = "请求显式指定Agent";
        } else if (routeToken != null) {
            definition = mapper.selectAgentRuntimeByRouteToken(routeToken);
            routeSource = "mention";
            routeConfidence = 1D;
            routeReason = "消息通过@Agent指定路由";
        } else if (conversation.getAgentVersionId() != null) {
            definition = mapper.selectAgentRuntime(conversation.getAgentVersionId());
            routeSource = "conversation_binding";
            routeConfidence = 1D;
            routeReason = "沿用会话绑定的Agent版本";
        } else if (conversation.getAgentId() != null) {
            definition = mapper.selectPublishedAgentRuntime(conversation.getAgentId());
            routeSource = "conversation_binding";
            routeConfidence = 1D;
            routeReason = "沿用会话绑定的Agent";
        } else {
            PreferredSelection preferred = preferredSelection(principal);
            AutomaticSelection sticky = preferred.configured() ? null : generalFollowupSelection(
                principal, conversation, input
            );
            AutomaticSelection selection = preferred.configured()
                ? preferred.definition() == null
                    ? defaultSelection("已保存的默认Agent不可用，安全回退默认Agent", 0)
                    : new AutomaticSelection(
                        preferred.definition(), "portal_routing_preference", 1D,
                        "使用当前用户已授权的持久化路由偏好", 1
                    )
                : sticky == null ? selectAutomaticCandidate(principal, input) : sticky;
            definition = selection.definition();
            routeSource = selection.source();
            routeConfidence = selection.confidence();
            routeReason = selection.reason();
            candidateCount = selection.candidateCount();
        }
        if (definition == null) {
            String message = routeToken == null ? "没有可用的默认Agent" : "@Agent不存在或未发布";
            throw new ServiceException(message, HttpStatus.NOT_FOUND);
        }
        if (isAutomaticRoute(routeSource) && isKnowledgeRoute(definition)
            && knowledgeCatalogRoutingService != null) {
            KnowledgeCatalogRoutingService.CatalogSnapshot catalog =
                knowledgeCatalogRoutingService.snapshot(principal);
            if (!"strong".equals(catalog.match(input).confidence())) {
                EmbedAgentRuntimeRow fallback = mapper.selectDefaultAgentRuntime();
                if (fallback != null && isUsable(fallback)) {
                    definition = fallback;
                    routeSource = "default";
                    routeConfidence = 0D;
                    routeReason = "授权知识库目录未高置信匹配，自动路由安全回退默认Agent";
                }
            }
        }
        if (!"active".equals(definition.getAgentStatus())
            || definition.getPublishedAt() == null
            || !Set.of("published", "archived").contains(definition.getVersionStatus())) {
            throw conflict("所选Agent版本当前不可用");
        }
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "agent_version", definition.getAgentVersionId(), definition.getAgentKey(), "use",
            ResourceState.ACTIVE, true, Set.of(), null
        ));
        return new RoutedAgent(
            definition, input, routeToken, routeSource, routeConfidence,
            routeReason, candidateCount
        );
    }

    /**
     * 处理{@code generalFollowupSelection}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param conversation 会话参数
     * @param input {@code input}参数
     * @return 处理结果
     */
    private AutomaticSelection generalFollowupSelection(
        CurrentPrincipal principal,
        AgentConversation conversation,
        String input
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (!looksLikeGeneralFollowup(input)) return null;
        List<ConversationMessageRow> messages = mapper.selectMessages(conversation.getId(), 6);
        if (messages == null) return null;
        for (int index = messages.size() - 1; index >= 0; index--) {
            ConversationMessageRow message = messages.get(index);
            if (message.getAgentVersionId() == null) continue;
            EmbedAgentRuntimeRow definition = mapper.selectAgentRuntime(message.getAgentVersionId());
            if (!isUsable(definition)) continue;
            var decision = authorizationEnforcer.decide(principal, new PermissionContext(
                "agent_version", definition.getAgentVersionId(), definition.getAgentKey(), "use",
                ResourceState.ACTIVE, true, Set.of(), null
            ));
            if (decision == null || !decision.allowed()) continue;
            return new AutomaticSelection(
                definition, "general_followup_affinity", 0.93D,
                "通用追问/指代延续，沿用最近一轮已授权Agent", 1
            );
        }
        return null;
    }

    /**
     * 处理{@code looksLikeGeneralFollowup}并返回对应结果。
     *
     * @param rawInput {@code rawInput}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean looksLikeGeneralFollowup(String rawInput) {
        String input = normalizeInput(rawInput);
        if (input.isBlank() || input.length() > 60 || isGreeting(input)) return false;
        String lower = input.toLowerCase(Locale.ROOT);
        if (GENERAL_FOLLOWUP_BLOCKERS.stream().anyMatch(lower::contains)) return false;
        if (GENERAL_FOLLOWUP_PREFIXES.stream().anyMatch(lower::startsWith)) return true;
        return input.length() <= 8 && GENERAL_FOLLOWUP_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    /**
     * 处理{@code preferredSelection}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @return 处理结果
     */
    private PreferredSelection preferredSelection(CurrentPrincipal principal) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (portalPrefsService == null) {
            return new PreferredSelection(false, null);
        }
        Preferences preferences;
        try {
            preferences = portalPrefsService.get();
        } catch (ServiceException exception) {
            if (exception.getCode() == 503) {
                return new PreferredSelection(false, null);
            }
            throw exception;
        }
        if (!preferences.routingConfigured() || !"expert".equals(preferences.routingMode())) {
            return new PreferredSelection(false, null);
        }
        String rawId = preferences.expertAgentId();
        if (rawId == null || !rawId.matches("[1-9][0-9]{0,18}")) {
            return new PreferredSelection(true, null);
        }
        EmbedAgentRuntimeRow definition = mapper.selectPublishedAgentRuntime(Long.valueOf(rawId));
        if (!isUsable(definition)) {
            return new PreferredSelection(true, null);
        }
        var decision = authorizationEnforcer.decide(principal, new PermissionContext(
            "agent_version", definition.getAgentVersionId(), definition.getAgentKey(), "use",
            ResourceState.ACTIVE, true, Set.of(), null
        ));
        return new PreferredSelection(true, decision != null && decision.allowed() ? definition : null);
    }

    /**
     * 获取{@code AutomaticCandidate}。
     *
     * @param principal 当前操作主体
     * @param input {@code input}参数
     * @return 处理结果
     */
    private AutomaticSelection selectAutomaticCandidate(
        CurrentPrincipal principal,
        String input
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        List<EmbedAgentRuntimeRow> candidates = mapper.selectPublishedAgentRuntimes();
        if (candidates == null || candidates.isEmpty()) {
            return defaultSelection("没有可用于自动路由的候选Agent", 0);
        }

        // Greetings are deliberately kept on the general/default Agent. This
        // mirrors Nhs's low-cost shortcut and avoids making a domain Agent
        // answer a message that has no business intent.
        if (isGreeting(input)) {
            return defaultSelection("问候语使用默认Agent", 0);
        }

        List<EmbedAgentRuntimeRow> authorized = new ArrayList<>();
        for (EmbedAgentRuntimeRow candidate : candidates) {
            if (!isUsable(candidate)) {
                continue;
            }
            PermissionContext context = new PermissionContext(
                "agent_version", candidate.getAgentVersionId(), candidate.getAgentKey(), "use",
                ResourceState.ACTIVE, true, Set.of(), null
            );
            // Candidate discovery is never an authorization fallback. A
            // denied candidate is removed before tag scoring and is audited.
            var decision = authorizationEnforcer.decide(principal, context);
            if (decision == null || !decision.allowed()) {
                continue;
            }
            authorized.add(candidate);
        }

        List<ScoredCandidate> scored = new ArrayList<>();
        for (EmbedAgentRuntimeRow candidate : authorized) {
            int score = tagScore(candidate, input);
            if (score > 0) {
                scored.add(new ScoredCandidate(candidate, score));
            }
        }
        scored.sort(Comparator.comparingInt(ScoredCandidate::score).reversed()
            .thenComparing(item -> item.definition().getAgentVersionId()));
        if (!scored.isEmpty()
            && (scored.size() == 1 || scored.get(0).score() > scored.get(1).score())) {
            ScoredCandidate selected = scored.get(0);
            double confidence = Math.min(0.95D, 0.65D + selected.score() / 20D);
            return new AutomaticSelection(
                selected.definition(), "routing_tag_fast_path", confidence,
                "授权候选中只有一个标签高分命中", authorized.size()
            );
        }
        ModelSelection modelDecision = routeWithModel(authorized, input);
        if (modelDecision != null) {
            return new AutomaticSelection(
                modelDecision.definition(), "routing_model", modelDecision.confidence(),
                modelDecision.reason(), authorized.size()
            );
        }
        return defaultSelection(
            "标签无唯一命中且模型路由不可用或置信度不足", authorized.size()
        );
    }

    /**
     * 处理{@code defaultSelection}并返回对应结果。
     *
     * @param reason {@code reason}参数
     * @param candidateCount {@code candidateCount}参数
     * @return 处理结果
     */
    private AutomaticSelection defaultSelection(String reason, int candidateCount) {
        return new AutomaticSelection(
            mapper.selectDefaultAgentRuntime(), "default", 0D, reason, candidateCount
        );
    }

    /**
     * 处理routeWith模型并返回对应结果。
     *
     * @param candidates {@code candidates}参数
     * @param input {@code input}参数
     * @return 处理结果
     */
    private ModelSelection routeWithModel(
        List<EmbedAgentRuntimeRow> candidates,
        String input
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (routingModelGateway == null || candidates.isEmpty()) {
            return null;
        }
        String systemPrompt = """
            你是企业智能体平台的路由器，只负责选择 Agent，不回答用户问题。
            只能从候选清单中选择一个 agent_key。根据 Agent 名称、描述、路由标签、
            用户问题和会话连续性选择最合适的候选；无法高置信度判断时返回 null。
            只返回 JSON，不要 Markdown：
            {"agent_key":"候选 key 或 null","confidence":0.0,"reason":"不超过40字"}
            """;
        StringBuilder candidatesPrompt = new StringBuilder();
        for (EmbedAgentRuntimeRow candidate : candidates) {
            candidatesPrompt.append("- agent_key=")
                .append(candidate.getAgentKey())
                .append("; name=").append(safe(candidate.getAgentName()))
                .append("; description=").append(safe(candidate.getAgentDescription()))
                .append("; tags=").append(safe(candidate.getRoutingTagsJson()))
                .append('\n');
        }
        String userPrompt = "候选 Agent：\n" + candidatesPrompt
            + "\n用户最新问题：\n" + input;
        try {
            PortalChatBIModelGateway.Completion completion = routingModelGateway.complete(
                systemPrompt, userPrompt
            );
            if (completion == null || completion.content() == null
                || completion.content().isBlank()) {
                return null;
            }
            Map<String, Object> payload = parseRouterPayload(completion.content());
            String agentKey = text(payload.get("agent_key"));
            double confidence = number(payload.get("confidence"));
            if (agentKey.isBlank() || "null".equalsIgnoreCase(agentKey)
                || confidence < MIN_ROUTER_CONFIDENCE) {
                return null;
            }
            EmbedAgentRuntimeRow definition = candidates.stream()
                .filter(candidate -> agentKey.equalsIgnoreCase(candidate.getAgentKey()))
                .findFirst()
                .orElse(null);
            return definition == null
                ? null : new ModelSelection(
                    definition, confidence, boundedReason(text(payload.get("reason")))
                );
        } catch (RuntimeException ignored) {
            // Provider outage, malformed JSON and unconfigured models all
            // use the same safe default path. No model answer is exposed as a
            // business response and no unauthorized candidate is accepted.
            return null;
        }
    }

    /**
     * 处理{@code parseRouterPayload}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> parseRouterPayload(String value) {
        String normalized = value.strip();
        if (normalized.startsWith("```") && normalized.endsWith("```")) {
            int firstLine = normalized.indexOf('\n');
            normalized = firstLine >= 0
                ? normalized.substring(firstLine + 1, normalized.length() - 3).strip()
                : normalized.substring(3, normalized.length() - 3).strip();
        }
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("路由模型未返回 JSON 对象");
        }
        Map<String, Object> payload = jsonMapper.readValue(
            normalized.substring(start, end + 1), ROUTER_PAYLOAD
        );
        return payload == null ? Map.of() : new LinkedHashMap<>(payload);
    }

    /**
     * 处理{@code safe}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("[\\r\\n]+", " ").strip();
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    /**
     * 处理{@code boundedReason}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String boundedReason(String value) {
        if (value == null || value.isBlank()) {
            return "模型路由返回高置信度候选";
        }
        return value.length() <= 256 ? value : value.substring(0, 256);
    }

    /**
     * 处理{@code number}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0D : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0D;
        }
    }

    /**
     * 处理{@code tagScore}并返回对应结果。
     *
     * @param candidate {@code candidate}参数
     * @param input {@code input}参数
     * @return 处理结果
     */
    private int tagScore(EmbedAgentRuntimeRow candidate, String input) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        List<String> tags = routingTags(candidate.getRoutingTagsJson());
        if (tags.isEmpty()) {
            return 0;
        }
        String normalized = input.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String tag : tags) {
            if (tag.isBlank()) {
                continue;
            }
            if (normalized.contains(tag)) {
                score += tag.length() >= 4 ? 3 : 1;
            }
            Matcher matcher = ASCII_WORD.matcher(normalized);
            while (matcher.find()) {
                if (matcher.group().equals(tag)) {
                    score += 2;
                    break;
                }
            }
        }
        return score;
    }

    /**
     * 处理{@code routingTags}并返回对应结果。
     *
     * @param json {@code json}参数
     * @return 符合条件的数据集合
     */
    private List<String> routingTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = jsonMapper.readValue(json, ROUTING_TAGS);
            return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.strip().toLowerCase(Locale.ROOT))
                .toList();
        } catch (RuntimeException ignored) {
            // Published versions were validated before persistence. If old
            // data is malformed, fail closed to the default route.
            return List.of();
        }
    }

    /**
     * 判断{@code Greeting}是否满足要求。
     *
     * @param input {@code input}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isGreeting(String input) {
        String normalized = input.strip().toLowerCase(Locale.ROOT);
        return GREETING_WORDS.contains(normalized)
            || normalized.matches("(?:你好|您好|嗨|hello|hi|hey)[!！,.。 ]*");
    }

    /**
     * 判断{@code AutomaticRoute}是否满足要求。
     *
     * @param source 数据源参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isAutomaticRoute(String source) {
        return "routing_tag_fast_path".equals(source) || "routing_model".equals(source);
    }

    /**
     * 判断知识库Route是否满足要求。
     *
     * @param definition 定义参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isKnowledgeRoute(EmbedAgentRuntimeRow definition) {
        String text = String.join(" ", safe(definition.getAgentKey()), safe(definition.getAgentName()),
            safe(definition.getAgentDescription()), safe(definition.getRoutingTagsJson()),
            safe(definition.getRuntimeConfigJson())).toLowerCase(Locale.ROOT);
        return text.contains("knowledge") || text.contains("知识库")
            || text.contains("知识问答") || text.contains("rag");
    }

    /**
     * 判断{@code Usable}是否满足要求。
     *
     * @param candidate {@code candidate}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isUsable(EmbedAgentRuntimeRow candidate) {
        return candidate != null
            && candidate.getAgentVersionId() != null
            && "active".equals(candidate.getAgentStatus())
            && candidate.getPublishedAt() != null
            && "published".equals(candidate.getVersionStatus());
    }

    /**
     * 封装{@code ScoredCandidate}相关的不可变数据。
     */
    private record ScoredCandidate(EmbedAgentRuntimeRow definition, int score) { }

    /**
     * 处理{@code normalizeInput}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeInput(String value) {
        if (value == null || value.isBlank()) {
            throw badRequest("消息不能为空");
        }
        String normalized = value.strip();
        if (normalized.indexOf('\0') >= 0
            || normalized.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            throw badRequest("消息包含非法字符或超过128KB");
        }
        return normalized;
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

    /**
     * 封装Routed智能体相关的不可变数据。
     */
    public record RoutedAgent(
        EmbedAgentRuntimeRow definition,
        String input,
        String mentionToken,
        String routeSource,
        double routeConfidence,
        String routeReason,
        int candidateCount
    ) {
    }

    /**
     * 封装{@code AutomaticSelection}相关的不可变数据。
     */
    private record AutomaticSelection(
        EmbedAgentRuntimeRow definition,
        String source,
        double confidence,
        String reason,
        int candidateCount
    ) { }

    /**
     * 封装模型Selection相关的不可变数据。
     */
    private record ModelSelection(
        EmbedAgentRuntimeRow definition,
        double confidence,
        String reason
    ) { }

    /**
     * 封装{@code PreferredSelection}相关的不可变数据。
     */
    private record PreferredSelection(boolean configured, EmbedAgentRuntimeRow definition) { }
}
