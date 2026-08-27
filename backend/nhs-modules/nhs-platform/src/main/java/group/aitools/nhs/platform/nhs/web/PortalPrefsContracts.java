package group.aitools.nhs.platform.nhs.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表示门户PrefsContracts相关的领域对象。
 * Wire contracts for the Nhs portal preference endpoints. */
public final class PortalPrefsContracts {

    private PortalPrefsContracts() {
    }

    /**
 * 封装{@code Preferences}相关的不可变数据。
 *
     * User-scoped preferences persisted in Redis.  The snake-case names are
     * part of the Nhs API contract and therefore are explicit here instead
     * of relying on a global naming strategy.
     */
    public record Preferences(
        @JsonProperty("pinned_group_ids") List<String> pinnedGroupIds,
        @JsonProperty("card_order") List<String> cardOrder,
        @JsonProperty("expanded_group_ids") List<String> expandedGroupIds,
        @JsonProperty("question_clicks") Map<String, Integer> questionClicks,
        @JsonProperty("pinned_kb_dataset_ids") List<String> pinnedKbDatasetIds,
        @JsonProperty("markdown_theme") String markdownTheme,
        @JsonProperty("routing_mode") String routingMode,
        @JsonProperty("expert_agent_id") String expertAgentId,
        @JsonProperty("routing_configured") boolean routingConfigured
    ) {
        /**
         * 创建 {@code Preferences} 实例并初始化所需依赖。
         *
         * @param pinnedGroupIds 资源标识集合
         * @param cardOrder {@code cardOrder}参数
         * @param expandedGroupIds 资源标识集合
         * @param questionClicks 追问Clicks参数
         * @param pinnedKbDatasetIds 资源标识集合
         * @param markdownTheme {@code markdownTheme}参数
         * @param routingMode {@code routingMode}参数
         * @param expertAgentId 资源标识
         * @param routingConfigured {@code routingConfigured}参数
         */
        public Preferences {
            // Keep request values nullable until the application service can
            // apply the same trimming and filtering rules as Nhs.  In
            // particular, an invalid list item must not become a server 500
            // while constructing this transport record.
            pinnedGroupIds = immutableList(pinnedGroupIds);
            cardOrder = immutableList(cardOrder);
            expandedGroupIds = immutableList(expandedGroupIds);
            questionClicks = immutableMap(questionClicks);
            pinnedKbDatasetIds = immutableList(pinnedKbDatasetIds);
            markdownTheme = markdownTheme == null ? "" : markdownTheme;
            routingMode = "expert".equalsIgnoreCase(routingMode) ? "expert" : "auto";
            expertAgentId = expertAgentId == null ? "" : expertAgentId.strip();
        }

        /**
 * 创建 {@code Preferences} 实例并初始化所需依赖。
 * Compatibility constructor for clients predating routing preferences. */
        public Preferences(
            List<String> pinnedGroupIds,
            List<String> cardOrder,
            List<String> expandedGroupIds,
            Map<String, Integer> questionClicks,
            List<String> pinnedKbDatasetIds,
            String markdownTheme
        ) {
            this(pinnedGroupIds, cardOrder, expandedGroupIds, questionClicks,
                pinnedKbDatasetIds, markdownTheme, "auto", "", false);
        }

        /**
         * 处理{@code empty}并返回对应结果。
         *
         * @return 处理结果
         */
        public static Preferences empty() {
            return new Preferences(List.of(), List.of(), List.of(), Map.of(), List.of(), "", "auto", "", false);
        }

        /**
         * 处理{@code immutableList}并返回对应结果。
         *
         * @param value {@code value}参数
         * @return 符合条件的数据集合
         */
        private static <T> List<T> immutableList(List<T> value) {
            return value == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(value));
        }

        /**
         * 处理{@code immutableMap}并返回对应结果。
         *
         * @param value {@code value}参数
         * @return 处理结果
         */
        private static <K, V> Map<K, V> immutableMap(Map<K, V> value) {
            return value == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(value));
        }
    }

    /**
     * 封装{@code MarkdownTheme}相关的不可变数据。
     */
    public record MarkdownThemeRequest(String theme) {
    }

    /**
     * 封装{@code RoutingPreference}相关的不可变数据。
     */
    public record RoutingPreferenceRequest(
        @JsonProperty("routing_mode") String routingMode,
        @JsonProperty("expert_agent_id") String expertAgentId
    ) {
        /**
         * 创建 {@code RoutingPreferenceRequest} 实例并初始化所需依赖。
         *
         * @param routingMode {@code routingMode}参数
         * @param expertAgentId 资源标识
         */
        public RoutingPreferenceRequest {
            routingMode = routingMode == null ? "auto" : routingMode.strip().toLowerCase();
            expertAgentId = expertAgentId == null ? "" : expertAgentId.strip();
        }
    }
}
