package group.aitools.nhs.runtime.agentscope;

import group.aitools.nhs.runtime.spi.RuntimeKnowledgeDefinition;
import group.aitools.nhs.runtime.spi.RuntimeToolDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表示运行时工具Nudge策略相关的领域对象。
 *
 * Builds one bounded tool-first instruction from the tools actually mounted
 * for the current run.  It never names a tool that is absent from the frozen
 * runtime definition.
 */
final class RuntimeToolNudgePolicy {

    private static final Set<String> EXCLUDED = Set.of(
        "update_user_preference", "delete_user_preference", "memory_search",
        "fetch_user_long_term_memory", "create_skills", "sub_agent_call",
        "sub_agent_batch_call"
    );
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fff]+");
    private static final Pattern WORD = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{1,}");
    private static final Set<String> STOP = Set.of(
        "帮我", "帮忙", "一下", "一个", "请问", "可以", "怎么", "如何", "什么", "哪些",
        "有没有", "能否", "现在", "目前", "我想", "我要", "你能", "麻烦", "看看",
        "the", "and", "for", "with", "this", "that", "what", "how", "please", "help"
    );

    /**
     * 创建 {@code RuntimeToolNudgePolicy} 实例并初始化所需依赖。
     */
    private RuntimeToolNudgePolicy() {
    }

    /**
     * 构建{@code build}。
     *
     * @param query 查询参数
     * @param decision {@code decision}参数
     * @param tools {@code tools}参数
     * @param knowledge 知识库参数
     * @return 处理结果
     */
    static String build(
        String query,
        Map<String, Object> decision,
        List<RuntimeToolDefinition> tools,
        List<RuntimeKnowledgeDefinition> knowledge
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        String input = query == null ? "" : query.strip();
        if (input.length() < 4) {
            return "";
        }
        List<Candidate> candidates = new ArrayList<>();
        for (RuntimeToolDefinition tool : tools == null ? List.<RuntimeToolDefinition>of() : tools) {
            candidates.add(new Candidate(tool.name(), tool.description()));
        }
        for (RuntimeKnowledgeDefinition definition : knowledge == null
            ? List.<RuntimeKnowledgeDefinition>of() : knowledge) {
            candidates.add(new Candidate(
                "search_knowledge_" + definition.id(),
                "Search the approved knowledge base " + definition.name() + " " + definition.description()
            ));
        }
        if (candidates.isEmpty()) {
            return "";
        }

        Candidate explicitQuestion = exact(candidates, "ask_user_question");
        if (explicitQuestion != null && looksLikeQuestionRequest(input)) {
            return force(explicitQuestion.name(),
                "用户明确要求通过提问收集信息，必须先调用该工具展示结构化问题；不要直接猜测缺失信息。");
        }

        Candidate subAgent = exact(candidates, "sub_agent_call");
        Candidate batchSubAgent = exact(candidates, "sub_agent_batch_call");
        if ((subAgent != null || batchSubAgent != null) && looksLikeExplicitSubAgent(input)) {
            if (batchSubAgent != null && looksLikeBatchDelegation(input)) {
                return force(batchSubAgent.name(),
                    "用户明确要求并行或批量委派，必须优先调用 sub_agent_batch_call；按用户指定范围收集结果，失败项如实说明。");
            }
            if (subAgent != null) {
                return force(subAgent.name(),
                    "用户明确要求调用子 Agent，必须优先委派给已授权的子 Agent；不要在未调用工具前自行完成，也不要改派未挂载的 Agent。");
            }
        }

        Candidate todo = exact(candidates, "todo_write");
        if (todo != null && looksLikeMultiStep(input)) {
            return force(todo.name(),
                "用户请求包含多个连续步骤，必须先调用 todo_write 写入完整任务清单，再继续执行；不要只输出计划。");
        }

        String capability = text(decision, "capability");
        String source = text(decision, "source");
        if ("knowledge_search".equals(capability) || "internal_docs".equals(source)) {
            Candidate knowledgeTool = firstKnowledge(candidates, decision);
            if (knowledgeTool != null) {
                return force(knowledgeTool.name(),
                    "本轮属于授权知识检索，必须先调用该知识工具获取带引用的真实内容；资料不足时明确说明，不要编造。");
            }
        }
        if ("data_query".equals(capability)) {
            Candidate dataSubAgent = exact(candidates, "sub_agent_call");
            if (dataSubAgent != null && Boolean.TRUE.equals(decision.get("shouldDelegate"))) {
                return force(dataSubAgent.name(),
                    "本轮是授权数据查询，必须先委派数据子 Agent 获取真实结果；不要凭空生成数字。");
            }
            Candidate sql = first(candidates, "execute_sql_query", "get_dataset_schema");
            if (sql != null) {
                return force(sql.name(),
                    "本轮需要授权数据事实，必须先调用该数据工具；说明查询口径和结果状态，不要伪造成功。");
            }
        }
        if ("web_search".equals(capability) || "public_web".equals(source)) {
            Candidate web = firstByNameOrText(candidates, Set.of(
                "web_search_baidu", "web_search_baidu_http", "web_search_bing_http",
                "fetch_static_web_url", "system_http_request"
            ), "search", "搜索", "网页");
            if (web != null) {
                return force(web.name(),
                    "本轮需要公网实时证据，必须先调用该搜索/抓取工具并保留来源；不要用记忆替代事实。");
            }
        }
        if (looksLikeProfile(input)) {
            Candidate profile = exact(candidates, "get_myinfo");
            if (profile != null) {
                return force(profile.name(),
                    "用户正在查询当前登录主体资料，必须先调用 get_myinfo；不要查询其他用户或编造权限。");
            }
        }
        if (looksLikeCatalog(input)) {
            Candidate catalog = containsAny(input, "知识库")
                ? exact(candidates, "list_accessible_knowledge_bases")
                : exact(candidates, "list_accessible_datasets");
            if (catalog == null) {
                catalog = first(candidates, "list_accessible_knowledge_bases", "list_accessible_datasets");
            }
            if (catalog != null) {
                return force(catalog.name(),
                    "用户询问权限内资源目录，必须先调用目录工具；不要用正文检索或编造清单。");
            }
        }
        Candidate notification = notificationCandidate(input, candidates);
        if (notification != null) {
            return force(notification.name(),
                "用户明确要求发送通知，必须先调用该已挂载渠道工具；只有工具返回成功后才能声称已发送，失败或未配置时如实说明。");
        }

        if (containsAny(input, "你好", "您好", "嗨", "谢谢", "再见")
            || input.matches("(?i)(?:hello|hi|hey)[!！,.。?？\\s]*")) {
            return "";
        }
        Set<String> signals = signals(input);
        if (signals.size() < 2) {
            return "";
        }
        Candidate best = null;
        double bestScore = 0D;
        for (Candidate candidate : candidates) {
            if (EXCLUDED.contains(candidate.name())) {
                continue;
            }
            double score = score(signals, normalize(candidate.name() + " " + candidate.description()));
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best == null || bestScore < 0.25D) {
            return "";
        }
        return "【本轮工具优先】问题与已挂载工具「" + best.name()
            + "」相关（相关度 " + String.format(Locale.ROOT, "%.2f", bestScore)
            + "），请先调用该工具获取真实结果；工具失败时如实说明，不要伪造成功。";
    }

    /**
     * 处理{@code force}并返回对应结果。
     *
     * @param name 名称
     * @param rule {@code rule}参数
     * @return 处理结果
     */
    private static String force(String name, String rule) {
        return "【本轮工具优先】必须先调用已挂载工具「" + name + "」。" + rule;
    }

    /**
     * 处理first知识库并返回对应结果。
     *
     * @param candidates {@code candidates}参数
     * @param decision {@code decision}参数
     * @return 处理结果
     */
    private static Candidate firstKnowledge(List<Candidate> candidates, Map<String, Object> decision) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        List<String> preferred = positiveIds(decision == null ? null : decision.get("knowledgeCatalogMatchIds"));
        for (String id : preferred) {
            Candidate candidate = exact(candidates, "search_knowledge_" + id);
            if (candidate != null) {
                return candidate;
            }
        }
        // When a catalog was loaded but no high-confidence match exists, the
        // platform may perform at most one direct fallback only when the
        // decision explicitly permits it. An empty or unavailable scope must
        // never turn into a guessed knowledge-base call.
        if (decision != null && decision.containsKey("knowledgeCatalogStatus")
            && !Boolean.TRUE.equals(decision.get("knowledgeFallbackAllowed"))) {
            return null;
        }
        for (Candidate candidate : candidates) {
            if (candidate.name().startsWith("search_knowledge_")) {
                return candidate;
            }
        }
        return exact(candidates, "search_knowledge_base");
    }

    /**
     * 处理{@code positiveIds}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 符合条件的数据集合
     */
    private static List<String> positiveIds(Object value) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (!(value instanceof Iterable<?> values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            String text = item == null ? "" : String.valueOf(item).strip();
            if (text.matches("[1-9][0-9]*") && !result.contains(text)) {
                result.add(text);
            }
            if (result.size() >= 16) {
                break;
            }
        }
        return result;
    }

    /**
     * 处理{@code first}并返回对应结果。
     *
     * @param candidates {@code candidates}参数
     * @param names 名称
     * @return 处理结果
     */
    private static Candidate first(List<Candidate> candidates, String... names) {
        for (String name : names) {
            Candidate candidate = exact(candidates, name);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 处理{@code firstByNameOrText}并返回对应结果。
     *
     * @param candidates {@code candidates}参数
     * @param names 名称
     * @param textTerms 待处理内容
     * @return 处理结果
     */
    private static Candidate firstByNameOrText(
        List<Candidate> candidates,
        Set<String> names,
        String... textTerms
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        for (Candidate candidate : candidates) {
            if (names.contains(candidate.name())) {
                return candidate;
            }
        }
        for (Candidate candidate : candidates) {
            String text = normalize(candidate.name() + " " + candidate.description());
            if (containsAny(text, textTerms)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 处理{@code exact}并返回对应结果。
     *
     * @param candidates {@code candidates}参数
     * @param name 名称
     * @return 处理结果
     */
    private static Candidate exact(List<Candidate> candidates, String name) {
        for (Candidate candidate : candidates) {
            if (name.equals(candidate.name())) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 处理{@code signals}并返回对应结果。
     *
     * @param query 查询参数
     * @return 符合条件的数据集合
     */
    private static Set<String> signals(String query) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Set<String> result = new HashSet<>();
        Matcher cjk = CJK_RUN.matcher(query.toLowerCase(Locale.ROOT));
        while (cjk.find()) {
            String run = cjk.group();
            for (int i = 0; i + 1 < run.length(); i++) {
                String part = run.substring(i, i + 2);
                if (!STOP.contains(part)) {
                    result.add(part);
                }
            }
        }
        Matcher word = WORD.matcher(query.toLowerCase(Locale.ROOT));
        while (word.find()) {
            String part = word.group();
            if (!STOP.contains(part)) {
                result.add(part);
            }
        }
        return result;
    }

    /**
     * 处理{@code score}并返回对应结果。
     *
     * @param signals {@code signals}参数
     * @param text 待处理内容
     * @return 处理结果
     */
    private static double score(Set<String> signals, String text) {
        if (signals.isEmpty() || text.isBlank()) {
            return 0D;
        }
        int matched = 0;
        for (String signal : signals) {
            if (text.contains(signal)) {
                matched++;
            }
        }
        return (double) matched / signals.size();
    }

    /**
     * 处理looksLike追问Request并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private static boolean looksLikeQuestionRequest(String value) {
        return containsAny(value, "问我", "提问我", "采访我", "访谈我", "小测验", "你来问", "引导我")
            && !containsAny(value, "不要问", "不用问", "别问", "直接回答");
    }

    /**
     * 处理{@code looksLikeMultiStep}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private static boolean looksLikeMultiStep(String value) {
        int hits = 0;
        for (String term : new String[]{"然后", "接着", "最后", "并且", "同时", "分别", "先", "再"}) {
            if (value.contains(term)) {
                hits++;
            }
        }
        return hits >= 2 || containsAny(value, "完整流程", "端到端", "从头到尾", "全流程");
    }

    /**
     * 处理looksLikeExplicitSub智能体并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private static boolean looksLikeExplicitSubAgent(String value) {
        return containsAny(value, "子Agent", "子 Agent", "子智能体", "sub_agent", "sub-agent", "委派")
            && containsAny(value, "调用", "委派", "分配", "交给", "让", "使用", "用", "call", "delegate", "ask");
    }

    /**
     * 处理{@code looksLikeBatchDelegation}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private static boolean looksLikeBatchDelegation(String value) {
        return containsAny(value, "并行", "批量", "同时", "分别", "一起", "concurrent", "batch", "parallel");
    }

    /**
     * 处理通知Candidate并返回对应结果。
     *
     * @param value {@code value}参数
     * @param candidates {@code candidates}参数
     * @return 处理结果
     */
    private static Candidate notificationCandidate(String value, List<Candidate> candidates) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        boolean action = containsAny(value, "发送", "推送", "通知", "发到", "发给", "发一下",
            "发邮件", "发钉钉", "发企微", "发站内", "send", "push", "notify");
        if (!action) {
            return null;
        }
        if (containsAny(value, "站内", "站内信", "铃铛", "inbox", "消息中心")) {
            Candidate candidate = exact(candidates, "send_portal_notification");
            if (candidate != null) return candidate;
        }
        if (containsAny(value, "钉钉", "dingtalk")) {
            Candidate candidate = exact(candidates, "send_dingtalk_message");
            if (candidate != null) return candidate;
        }
        if (containsAny(value, "企微", "企业微信", "wechat work", "wecom")) {
            Candidate candidate = exact(candidates, "send_wechat_work_message");
            if (candidate != null) return candidate;
        }
        if (containsAny(value, "邮件", "邮箱", "email", "mail")) {
            Candidate candidate = exact(candidates, "send_email");
            if (candidate != null) return candidate;
        }
        return null;
    }

    /**
     * 处理looksLike配置档案并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private static boolean looksLikeProfile(String value) {
        return containsAny(value, "我的资料", "我的信息", "我的权限", "当前用户", "我是谁");
    }

    /**
     * 处理looksLike目录并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private static boolean looksLikeCatalog(String value) {
        return containsAny(value, "有哪些数据集", "可访问数据集", "有哪些知识库", "可访问知识库",
            "权限内数据集", "权限内知识库");
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
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param key {@code key}参数
     * @return 处理结果
     */
    private static String text(Map<String, Object> value, String key) {
        Object raw = value == null ? null : value.get(key);
        return raw == null ? "" : String.valueOf(raw).strip().toLowerCase(Locale.ROOT);
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /**
     * 封装{@code Candidate}相关的不可变数据。
     */
    private record Candidate(String name, String description) {
    }
}
