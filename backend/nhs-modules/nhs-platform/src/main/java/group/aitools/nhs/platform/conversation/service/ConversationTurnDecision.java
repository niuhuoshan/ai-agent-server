package group.aitools.nhs.platform.conversation.service;

import group.aitools.nhs.platform.knowledge.service.KnowledgeCatalogRoutingService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 封装会话会话回合Decision相关的不可变数据。
 *
 * Immutable, provider-neutral evidence for one conversation turn.
 *
 * <p>The source Nhs runtime keeps this decision separate from Agent
 * selection.  Keeping the same boundary here prevents a routing tag or a
 * configured Agent from silently turning an unrelated question into a
 * knowledge or ChatBI request.</p>
 */
public record ConversationTurnDecision(
    String decisionVersion,
    String turnKind,
    String routeStatus,
    String source,
    String capability,
    double confidence,
    String reasoning,
    String referenceMode,
    String contextStrategy,
    boolean needsFreshData,
    boolean requiresSourceTimestamp,
    boolean allowsDataRoute,
    boolean shouldDelegate,
    String delegateCapability,
    boolean requiresKnowledgeSearch,
    String semanticIntent,
    String semanticDomain,
    String semanticOperation,
    String factKind,
    List<String> evidence,
    String knowledgeCatalogStatus,
    List<Long> knowledgeCatalogMatchIds,
    String knowledgeCatalogMatchConfidence,
    boolean knowledgeFallbackAllowed
) {

    private static final int MAX_REASONING_LENGTH = 256;
    private static final int MAX_EVIDENCE_ITEMS = 16;

    /**
     * 创建 {@code ConversationTurnDecision} 实例并初始化所需依赖。
     *
     * @param decisionVersion decision版本参数
     * @param turnKind 会话回合Kind参数
     * @param routeStatus 目标状态
     * @param source 数据源参数
     * @param capability {@code capability}参数
     * @param confidence {@code confidence}参数
     * @param reasoning {@code reasoning}参数
     * @param referenceMode {@code referenceMode}参数
     * @param contextStrategy 待处理内容
     * @param needsFreshData needsFresh数据参数
     * @param requiresSourceTimestamp requires数据源Timestamp参数
     * @param allowsDataRoute allows数据Route参数
     * @param shouldDelegate {@code shouldDelegate}参数
     * @param delegateCapability {@code delegateCapability}参数
     * @param requiresKnowledgeSearch requires知识库Search参数
     * @param semanticIntent {@code semanticIntent}参数
     * @param semanticDomain {@code semanticDomain}参数
     * @param semanticOperation semantic操作参数
     * @param factKind {@code factKind}参数
     * @param evidence {@code evidence}参数
     * @param knowledgeCatalogStatus 目标状态
     * @param knowledgeCatalogMatchIds 资源标识集合
     * @param knowledgeCatalogMatchConfidence 知识库目录MatchConfidence参数
     * @param knowledgeFallbackAllowed 知识库FallbackAllowed参数
     */
    public ConversationTurnDecision {
        decisionVersion = boundedText(decisionVersion, "v1", 32);
        turnKind = boundedText(turnKind, "general", 32);
        routeStatus = boundedText(routeStatus, "resolved", 32);
        source = boundedText(source, "unknown", 64);
        capability = boundedText(capability, "answer", 64);
        confidence = boundedConfidence(confidence);
        reasoning = boundedText(reasoning, "未识别到更强的来源边界", MAX_REASONING_LENGTH);
        referenceMode = boundedText(referenceMode, "unknown", 32);
        contextStrategy = boundedText(contextStrategy, "UNCERTAIN", 16);
        delegateCapability = optionalText(delegateCapability, 64);
        semanticIntent = optionalText(semanticIntent, 64);
        semanticDomain = boundedText(semanticDomain, "unknown", 64);
        semanticOperation = boundedText(semanticOperation, "unknown", 64);
        factKind = boundedText(factKind, "unknown", 64);
        knowledgeCatalogStatus = optionalText(knowledgeCatalogStatus, 32);
        knowledgeCatalogMatchIds = knowledgeCatalogMatchIds == null
            ? List.of() : knowledgeCatalogMatchIds.stream()
                .filter(value -> value != null && value > 0).distinct().limit(16).toList();
        knowledgeCatalogMatchConfidence = boundedText(knowledgeCatalogMatchConfidence, "none", 16);
        List<String> normalizedEvidence = new ArrayList<>();
        for (String item : evidence == null ? List.<String>of() : evidence) {
            String value = optionalText(item, 64);
            if (value != null && !normalizedEvidence.contains(value)) {
                normalizedEvidence.add(value);
                if (normalizedEvidence.size() >= MAX_EVIDENCE_ITEMS) {
                    break;
                }
            }
        }
        evidence = List.copyOf(normalizedEvidence);
    }

    /**
 * 处理{@code classify}并返回对应结果。
 *
     * Deterministic boundary classifier used before an AgentScope worker is
     * released.  It intentionally favors a safe general route when evidence
     * is ambiguous; the model may still use the mounted tools afterwards.
     */
    public static ConversationTurnDecision classify(
        String rawInput,
        String routeSource,
        String agentKey
    ) {
        return classify(rawInput, routeSource, agentKey, null);
    }

    /**
     * 处理{@code classify}并返回对应结果。
     *
     * @param rawInput {@code rawInput}参数
     * @param routeSource route数据源参数
     * @param agentKey 智能体Key参数
     * @param knowledgeCatalog 知识库目录参数
     * @return 处理结果
     */
    public static ConversationTurnDecision classify(
        String rawInput,
        String routeSource,
        String agentKey,
        KnowledgeCatalogRoutingService.CatalogSnapshot knowledgeCatalog
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String input = normalize(rawInput);
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> evidence = new ArrayList<>();
        if (routeSource != null && !routeSource.isBlank()) {
            evidence.add("agent_route:" + boundedText(routeSource, "unknown", 48));
        }
        if (agentKey != null && !agentKey.isBlank()) {
            evidence.add("agent:" + boundedText(agentKey, "unknown", 48));
        }

        if (input.isBlank()) {
            return decision(
                "general", "unknown", "answer", 0.0D,
                "请求内容为空", "unknown", "UNCERTAIN", false, false,
                false, false, null, false, null, "unknown", "unknown", "unknown",
                evidence
            );
        }
        if (looksLikeGreeting(lower) || looksLikeMetaAction(lower)) {
            evidence.add(looksLikeGreeting(lower) ? "greeting" : "meta_action");
            return decision(
                "general", "general", "answer", 0.95D,
                "问候或平台元操作由主助手处理", "unknown", "KEEP", false, false,
                false, false, null, false, "general", "general", "answer",
                "general", evidence
            );
        }
        if (looksLikePlatformSelfHelp(lower)) {
            evidence.add("platform_self_help");
            return decision(
                "general", "platform_self_help", "runtime_tool", 0.94D,
                "平台资源或配置查询优先使用自助工具", "unknown", "KEEP", true, false,
                false, false, null, false, "platform_self_help", "general", "lookup",
                "platform_fact", evidence
            );
        }
        if (looksLikePublicWeb(lower)) {
            evidence.add("public_web_signal");
            return decision(
                "general", "public_web", "web_search", 0.92D,
                "问题需要刷新公网证据", "new_query", "BREAK", true, true,
                false, false, null, false, "web_search", "public_web", "lookup",
                "public_fact", evidence
            );
        }
        if (looksLikeRuntimeDiagnostic(lower)) {
            evidence.add("runtime_diagnostic");
            return decision(
                "general", "runtime_diagnostic", "runtime_tool", 0.90D,
                "问题需要当前运行环境事实", "new_query", "BREAK", true, true,
                false, false, null, false, "runtime_diagnostic", "runtime_environment", "inspect",
                "runtime_fact", evidence
            );
        }
        if (looksLikeKnowledge(lower)) {
            evidence.add("knowledge_signal");
            ConversationTurnDecision knowledgeDecision = decision(
                "knowledge", "internal_docs", "knowledge_search", 0.86D,
                "问题涉及内部制度、SOP或知识文档", "new_query", "BREAK", false, false,
                true, true, "knowledge_base", true, "knowledge_base", "internal_docs", "search",
                "document", evidence
            );
            if (knowledgeCatalog == null) {
                return knowledgeDecision;
            }
            KnowledgeCatalogRoutingService.Match match = knowledgeCatalog.match(input);
            if ("strong".equals(match.confidence())) {
                return knowledgeDecision.withKnowledgeCatalog(knowledgeCatalog, match, false);
            }
            return decision(
                "general", "general", "answer", 0.72D,
                "授权知识库目录未得到高置信匹配，安全降级到通用助手", "unknown", "UNCERTAIN",
                false, false, false, false, null, false, "general", "general", "answer",
                "unknown", evidence
            ).withKnowledgeCatalog(knowledgeCatalog, match, knowledgeCatalog.hasEffectiveScope());
        }
        if (looksLikeContextFollowup(lower)) {
            evidence.add("context_followup");
            boolean uncertain = lower.length() <= 12 && !containsAny(
                lower, "导出", "下载", "图表", "明细", "同比", "环比", "换成", "改成"
            );
            return decision(
                "context_action", "conversation_context", "context_transform",
                uncertain ? 0.68D : 0.88D,
                uncertain ? "短文本可能依赖上一轮上下文，等待运行时确认"
                    : "问题操作上一轮对话结果",
                "reuse_previous", uncertain ? "UNCERTAIN" : "KEEP", false, true,
                false, false, null, false, "context_action", "conversation_context",
                "transform", "conversation_result", evidence
            );
        }
        if (looksLikeData(lower)) {
            evidence.add("structured_data_signal");
            return decision(
                "data_query", "internal_structured_data", "data_query", 0.88D,
                "问题需要授权数据集或指标的实时查询", "new_query", "BREAK", true, true,
                true, true, "data_query", false, "data_query", "chatbi_business_data", "query",
                "business_fact", evidence
            );
        }

        evidence.add("general_fallback");
        return decision(
            "general", "general", "answer", 0.80D,
            "未识别到可靠的内部来源边界，保留通用回答路径", "unknown", "UNCERTAIN",
            false, false, false, false, null, false, "general", "general", "answer",
            "unknown", evidence
        );
    }

    /**
 * 将输入数据转换为{@code Map}。
 * Converts the frozen decision to a JSON-safe runtime attribute map. */
    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("decisionVersion", decisionVersion);
        value.put("turnKind", turnKind);
        value.put("routeStatus", routeStatus);
        value.put("source", source);
        value.put("capability", capability);
        value.put("confidence", confidence);
        value.put("reasoning", reasoning);
        value.put("referenceMode", referenceMode);
        value.put("contextStrategy", contextStrategy);
        value.put("needsFreshData", needsFreshData);
        value.put("requiresSourceTimestamp", requiresSourceTimestamp);
        value.put("allowsDataRoute", allowsDataRoute);
        value.put("shouldDelegate", shouldDelegate);
        value.put("requiresKnowledgeSearch", requiresKnowledgeSearch);
        value.put("semanticDomain", semanticDomain);
        value.put("semanticOperation", semanticOperation);
        value.put("factKind", factKind);
        if (knowledgeCatalogStatus != null) {
            value.put("knowledgeCatalogStatus", knowledgeCatalogStatus);
        }
        value.put("knowledgeCatalogMatchIds", knowledgeCatalogMatchIds);
        value.put("knowledgeCatalogMatchConfidence", knowledgeCatalogMatchConfidence);
        value.put("knowledgeFallbackAllowed", knowledgeFallbackAllowed);
        if (delegateCapability != null) {
            value.put("delegateCapability", delegateCapability);
        }
        if (semanticIntent != null) {
            value.put("semanticIntent", semanticIntent);
        }
        value.put("evidence", evidence);
        return Map.copyOf(value);
    }

    /**
     * 处理{@code decision}并返回对应结果。
     *
     * @param turnKind 会话回合Kind参数
     * @param source 数据源参数
     * @param capability {@code capability}参数
     * @param confidence {@code confidence}参数
     * @param reasoning {@code reasoning}参数
     * @param referenceMode {@code referenceMode}参数
     * @param contextStrategy 待处理内容
     * @param needsFreshData needsFresh数据参数
     * @param requiresSourceTimestamp requires数据源Timestamp参数
     * @param allowsDataRoute allows数据Route参数
     * @param shouldDelegate {@code shouldDelegate}参数
     * @param delegateCapability {@code delegateCapability}参数
     * @param requiresKnowledgeSearch requires知识库Search参数
     * @param semanticIntent {@code semanticIntent}参数
     * @param semanticDomain {@code semanticDomain}参数
     * @param semanticOperation semantic操作参数
     * @param factKind {@code factKind}参数
     * @param evidence {@code evidence}参数
     * @return 处理结果
     */
    private static ConversationTurnDecision decision(
        String turnKind,
        String source,
        String capability,
        double confidence,
        String reasoning,
        String referenceMode,
        String contextStrategy,
        boolean needsFreshData,
        boolean requiresSourceTimestamp,
        boolean allowsDataRoute,
        boolean shouldDelegate,
        String delegateCapability,
        boolean requiresKnowledgeSearch,
        String semanticIntent,
        String semanticDomain,
        String semanticOperation,
        String factKind,
        List<String> evidence
    ) {
        return new ConversationTurnDecision(
            "v1", turnKind, "resolved", source, capability, confidence, reasoning,
            referenceMode, contextStrategy, needsFreshData, requiresSourceTimestamp,
            allowsDataRoute, shouldDelegate, delegateCapability, requiresKnowledgeSearch,
            semanticIntent, semanticDomain, semanticOperation, factKind, evidence,
            null, List.of(), "none", false
        );
    }

    /**
     * 处理with知识库目录并返回对应结果。
     *
     * @param catalog 目录参数
     * @param match {@code match}参数
     * @param fallbackAllowed {@code fallbackAllowed}参数
     * @return 处理结果
     */
    private ConversationTurnDecision withKnowledgeCatalog(
        KnowledgeCatalogRoutingService.CatalogSnapshot catalog,
        KnowledgeCatalogRoutingService.Match match,
        boolean fallbackAllowed
    ) {
        return new ConversationTurnDecision(
            decisionVersion, turnKind, routeStatus, source, capability, confidence, reasoning,
            referenceMode, contextStrategy, needsFreshData, requiresSourceTimestamp,
            allowsDataRoute, shouldDelegate, delegateCapability, requiresKnowledgeSearch,
            semanticIntent, semanticDomain, semanticOperation, factKind, evidence,
            catalog.status(), match.matchedIds(), match.confidence(), fallbackAllowed
        );
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * 处理{@code looksLikeGreeting}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private static boolean looksLikeGreeting(String value) {
        if (value.length() > 24) {
            return false;
        }
        if (containsAny(value, "你好", "您好", "嗨", "早上好", "下午好", "晚上好")) {
            return true;
        }
        return value.matches("(?:hello|hi|hey)[!！,.。?？\\s]*");
    }

    /**
     * 处理{@code looksLikeMetaAction}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private static boolean looksLikeMetaAction(String value) {
        return containsAny(value, "重新生成", "换一个智能体", "切换智能体", "清空会话", "删除会话",
            "当前会话", "停止生成", "取消生成", "导出对话");
    }

    /**
     * 处理looksLike平台SelfHelp并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private static boolean looksLikePlatformSelfHelp(String value) {
        return containsAny(value, "我能访问哪些数据集", "有哪些数据集", "可访问数据集", "有哪些知识库",
            "可访问知识库", "我的权限", "我的资料", "当前用户", "有哪些工具", "有哪些技能");
    }

    /**
     * 处理{@code looksLikePublicWeb}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private static boolean looksLikePublicWeb(String value) {
        return containsAny(value, "联网搜索", "网上搜", "网页搜索", "百度", "必应", "最新新闻",
            "今天的新闻", "官网", "当前股价", "实时天气", "公开资料");
    }

    /**
     * 处理looksLike运行时Diagnostic并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private static boolean looksLikeRuntimeDiagnostic(String value) {
        return containsAny(value, "系统状态", "运行状态", "服务健康", "健康检查", "trace", "日志",
            "进程", "浏览器会话状态", "worker状态", "配置是否生效");
    }

    /**
     * 处理looksLike知识库并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private static boolean looksLikeKnowledge(String value) {
        return containsAny(value, "知识库", "知识库中", "制度", "sop", "操作规程", "流程规范",
            "内部文档", "员工手册", "政策", "说明文档", "根据文档");
    }

    /**
     * 处理looksLike上下文Followup并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private static boolean looksLikeContextFollowup(String value) {
        return containsAny(value, "刚才", "上面的", "上一轮", "上次结果", "继续", "按刚才",
            "这个结果", "该结果", "导出", "下载", "图表", "明细", "同比", "环比", "换成", "改成");
    }

    /**
     * 处理looksLike数据并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private static boolean looksLikeData(String value) {
        return containsAny(value, "数据集", "指标", "销售额", "收入", "订单", "客户数", "统计",
            "查询数据", "查数据", "报表", "sql", "数据库", "同比", "环比", "趋势", "排名", "明细");
    }

    /**
     * 处理{@code containsAny}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param terms {@code terms}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 处理{@code boundedConfidence}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private static double boundedConfidence(double value) {
        if (!Double.isFinite(value)) {
            return 0D;
        }
        return Math.max(0D, Math.min(1D, value));
    }

    /**
     * 处理{@code boundedText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param fallback {@code fallback}参数
     * @param maxLength {@code maxLength}参数
     * @return 处理结果
     */
    private static String boundedText(String value, String fallback, int maxLength) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            normalized = fallback;
        }
        return normalized.length() <= maxLength
            ? normalized : normalized.substring(0, maxLength);
    }

    /**
     * 处理{@code optionalText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @return 处理结果
     */
    private static String optionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip().substring(0, Math.min(value.strip().length(), maxLength));
    }
}
