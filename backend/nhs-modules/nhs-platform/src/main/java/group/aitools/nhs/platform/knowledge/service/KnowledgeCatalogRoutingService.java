package group.aitools.nhs.platform.knowledge.service;

import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeBase;
import group.aitools.nhs.platform.knowledge.mapper.KnowledgeCatalogMapper;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 负责知识库目录Routing相关的业务编排与领域规则处理。
 *
 * Builds the small, owner-scoped knowledge catalog used by turn routing.
 * The catalog is advisory context only; retrieval still re-checks the same
 * user's authorization and directory ACL before returning a chunk.
 */
@Service
public class KnowledgeCatalogRoutingService {

    private static final int MAX_ITEMS = 100;
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fff]+");
    private static final Pattern ASCII_WORD = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{1,}");
    private static final Set<String> GENERIC = Set.of(
        "帮我", "帮忙", "请问", "一下", "看看", "查看", "查询", "查找", "搜索", "了解",
        "如何", "怎么", "什么", "哪些", "是否", "能否", "有没有", "政策", "规定",
        "制度", "规范", "手册", "文档", "资料", "信息", "内容", "the", "and", "for",
        "with", "what", "how", "please"
    );
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() { };

    private final KnowledgeCatalogMapper mapper;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final KnowledgeAuthorizationContextFactory contextFactory;
    private final KnowledgeDirectoryAccessService directoryAccess;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code KnowledgeCatalogRoutingService} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param contextFactory 待处理内容
     * @param directoryAccess 目录Access参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public KnowledgeCatalogRoutingService(
        KnowledgeCatalogMapper mapper,
        AuthorizationEnforcer authorizationEnforcer,
        KnowledgeAuthorizationContextFactory contextFactory,
        KnowledgeDirectoryAccessService directoryAccess,
        JsonMapper jsonMapper
    ) {
        this.mapper = mapper;
        this.authorizationEnforcer = authorizationEnforcer;
        this.contextFactory = contextFactory;
        this.directoryAccess = directoryAccess;
        this.jsonMapper = jsonMapper;
    }

    /**
 * 处理快照并返回对应结果。
 *
     * Returns an owner-scoped snapshot. A catalog read failure is represented
     * as unavailable so a transient metadata issue never becomes a fake match.
     */
    public CatalogSnapshot snapshot(CurrentPrincipal principal) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (principal == null) {
            return CatalogSnapshot.empty();
        }
        try {
            List<AgentKnowledgeBase> bases = mapper.selectBases(null, false, MAX_ITEMS);
            List<Item> items = new ArrayList<>();
            for (AgentKnowledgeBase base : bases == null ? List.<AgentKnowledgeBase>of() : bases) {
                if (base == null || base.getId() == null || !"active".equals(base.getStatus())) {
                    continue;
                }
                AuthorizationDecision decision = authorizationEnforcer.decide(
                    principal, contextFactory.context(principal, base, "read", false)
                );
                if (decision == null || !decision.allowed()) {
                    continue;
                }
                KnowledgeDirectoryAccessService.DirectoryAccess directoryScope =
                    directoryAccess.access(principal, base.getId(), "read");
                if (!directoryScope.allDirectories() && !directoryScope.rootAllowed()
                    && directoryScope.directoryIds().isEmpty()) {
                    continue;
                }
                Item item = item(base);
                if (!item.name().isBlank()) {
                    items.add(item);
                }
            }
            return items.isEmpty()
                ? CatalogSnapshot.empty()
                : new CatalogSnapshot("available", List.copyOf(items), "");
        } catch (RuntimeException exception) {
            return new CatalogSnapshot("unavailable", List.of(), boundedError(exception));
        }
    }

    /**
     * 处理{@code item}并返回对应结果。
     *
     * @param base {@code base}参数
     * @return 处理结果
     */
    private Item item(AgentKnowledgeBase base) {
        Map<String, Object> config = jsonMap(base.getConfigJson());
        Map<String, Object> extra = jsonMap(base.getExtraJson());
        List<String> tags = strings(first(config, extra, "tags", "labels"));
        String notes = text(first(config, extra, "notes", "remark", "note"));
        return new Item(
            base.getId(), clean(base.getKnowledgeKey()), clean(base.getName()),
            clean(base.getDescription()), tags, notes
        );
    }

    /**
     * 处理{@code jsonMap}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> jsonMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = jsonMapper.readValue(value, JSON_MAP);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    /**
     * 处理{@code first}并返回对应结果。
     *
     * @param primary {@code primary}参数
     * @param secondary {@code secondary}参数
     * @param keys {@code keys}参数
     * @return 处理结果
     */
    private Object first(Map<String, Object> primary, Map<String, Object> secondary, String... keys) {
        for (String key : keys) {
            if (primary.containsKey(key)) {
                return primary.get(key);
            }
            if (secondary.containsKey(key)) {
                return secondary.get(key);
            }
        }
        return null;
    }

    /**
     * 处理{@code strings}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 符合条件的数据集合
     */
    private List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(this::text).filter(item -> !item.isBlank()).limit(16).toList();
        }
        String text = text(value);
        if (text.isBlank()) {
            return List.of();
        }
        return List.of(text);
    }

    /**
     * 处理{@code boundedError}并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @return 处理结果
     */
    private String boundedError(RuntimeException exception) {
        String message = exception.getMessage();
        String normalized = clean(message == null || message.isBlank()
            ? exception.getClass().getSimpleName() : message);
        return normalized.substring(0, Math.min(160, normalized.length()));
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return clean(String.valueOf(value));
        }
        return clean(String.valueOf(value));
    }

    /**
     * 处理{@code clean}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String clean(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').strip()
            .replaceAll("\\s+", " ");
    }

    /**
     * 处理{@code signals}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 符合条件的数据集合
     */
    private static Set<String> signals(String value) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Set<String> result = new HashSet<>();
        Matcher cjk = CJK_RUN.matcher(value.toLowerCase(Locale.ROOT));
        while (cjk.find()) {
            String run = cjk.group();
            for (int index = 0; index + 1 < run.length(); index++) {
                String fragment = run.substring(index, index + 2);
                if (!GENERIC.contains(fragment)) {
                    result.add(fragment);
                }
            }
        }
        Matcher words = ASCII_WORD.matcher(value.toLowerCase(Locale.ROOT));
        while (words.find()) {
            String token = words.group();
            if (!GENERIC.contains(token)) {
                result.add(token);
            }
        }
        return result;
    }

    /**
     * 处理{@code score}并返回对应结果。
     *
     * @param query 查询参数
     * @param item {@code item}参数
     * @return 处理结果
     */
    private static double score(Set<String> query, Item item) {
        Set<String> all = signals(item.name() + " " + item.description() + " "
            + String.join(" ", item.tags()) + " " + item.notes() + " " + item.knowledgeKey());
        Set<String> names = signals(item.name() + " " + item.knowledgeKey());
        Set<String> overlap = new HashSet<>(query);
        overlap.retainAll(all);
        if (overlap.isEmpty()) {
            return 0D;
        }
        Set<String> nameOverlap = new HashSet<>(query);
        nameOverlap.retainAll(names);
        return 0.68D * overlap.size() / Math.max(query.size(), 1)
            + 0.22D * overlap.size() / Math.max(all.size(), 1)
            + Math.min(nameOverlap.size(), 3) * 0.1D;
    }

    /**
     * 封装目录快照相关的不可变数据。
     */
    public record CatalogSnapshot(String status, List<Item> items, String error) {
        /**
         * 创建 {@code CatalogSnapshot} 实例并初始化所需依赖。
         *
         * @param status 目标状态
         * @param items {@code items}参数
         * @param error {@code error}参数
         */
        public CatalogSnapshot {
            status = status == null || status.isBlank() ? "empty" : status;
            items = items == null ? List.of() : List.copyOf(items);
            error = error == null ? "" : error;
        }

        /**
         * 处理{@code empty}并返回对应结果。
         *
         * @return 处理结果
         */
        public static CatalogSnapshot empty() {
            return new CatalogSnapshot("empty", List.of(), "");
        }

        /**
         * 判断Effective范围是否满足要求。
         *
         * @return 判断结果，{@code true} 表示条件成立
         */
        public boolean hasEffectiveScope() {
            return "available".equals(status) && !items.isEmpty();
        }

        /**
         * 处理{@code match}并返回对应结果。
         *
         * @param query 查询参数
         * @return 处理结果
         */
        public Match match(String query) {
            if (!"available".equals(status)) {
                return new Match(status, List.of(), "none");
            }
            Set<String> querySignals = signals(query == null ? "" : query.strip());
            if (querySignals.isEmpty() || items.isEmpty()) {
                return new Match("available", List.of(), "none");
            }
            List<Scored> scored = items.stream()
                .map(item -> new Scored(item, score(querySignals, item)))
                .filter(value -> value.score() > 0D)
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .toList();
            if (scored.isEmpty()) {
                return new Match("available", List.of(), "none");
            }
            Scored best = scored.getFirst();
            Set<String> overlap = signals(best.item().name() + " " + best.item().knowledgeKey()
                + " " + best.item().description() + " " + String.join(" ", best.item().tags())
                + " " + best.item().notes());
            overlap.retainAll(querySignals);
            Set<String> nameOverlap = signals(best.item().name() + " " + best.item().knowledgeKey());
            nameOverlap.retainAll(querySignals);
            String confidence = overlap.size() >= 2
                && (best.score() >= 0.24D || nameOverlap.size() >= 2) ? "strong" : "weak";
            return new Match("available", List.of(best.item().id()), confidence);
        }
    }

    /**
     * 封装{@code Item}相关的不可变数据。
     */
    public record Item(
        Long id,
        String knowledgeKey,
        String name,
        String description,
        List<String> tags,
        String notes
    ) {
        /**
         * 创建 {@code Item} 实例并初始化所需依赖。
         *
         * @param id 资源标识
         * @param knowledgeKey 知识库Key参数
         * @param name 名称
         * @param description {@code description}参数
         * @param tags {@code tags}参数
         * @param notes {@code notes}参数
         */
        public Item {
            tags = tags == null ? List.of() : List.copyOf(tags);
            name = name == null ? "" : name;
            knowledgeKey = knowledgeKey == null ? "" : knowledgeKey;
            description = description == null ? "" : description;
            notes = notes == null ? "" : notes;
        }
    }

    /**
     * 封装{@code Match}相关的不可变数据。
     */
    public record Match(String status, List<Long> matchedIds, String confidence) {
        /**
         * 创建 {@code Match} 实例并初始化所需依赖。
         *
         * @param status 目标状态
         * @param matchedIds 资源标识集合
         * @param confidence {@code confidence}参数
         */
        public Match {
            status = status == null ? "unavailable" : status;
            matchedIds = matchedIds == null ? List.of() : List.copyOf(matchedIds);
            confidence = confidence == null ? "none" : confidence;
        }
    }

    /**
     * 封装{@code Scored}相关的不可变数据。
     */
    private record Scored(Item item, double score) { }
}
