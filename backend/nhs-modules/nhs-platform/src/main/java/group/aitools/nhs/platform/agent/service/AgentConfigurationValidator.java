package group.aitools.nhs.platform.agent.service;

import group.aitools.nhs.platform.agent.web.AgentResourceBindingRequest;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 表示智能体配置相关的领域对象。
 * Validates and canonicalizes every client-controlled Agent configuration document. */
@Component
public class AgentConfigurationValidator {

    private static final int MAX_PROMPT_BYTES = 128 * 1024;
    private static final Set<String> AGENT_TYPES = Set.of(
        "general", "assistant", "knowledge", "data", "coding", "supervisor"
    );
    private static final Set<String> ENGINE_KEYS = Set.of(
        "maxIterations", "workspaceAccess", "timeoutSeconds", "responseFormat"
    );
    private static final Set<String> RUNTIME_KEYS = Set.of(
        "maxIterations", "workspaceAccess", "timeoutSeconds", "responseFormat",
        "temperature", "topP"
    );
    private static final Set<String> TOOL_CONFIG_KEYS = Set.of(
        "enabled", "modelId", "temperature", "descriptionOverride", "datasetIds", "timeoutSeconds"
    );
    private static final Set<String> SKILL_CONFIG_KEYS = Set.of("enabled");
    private static final Set<String> KNOWLEDGE_CONFIG_KEYS = Set.of(
        "topK", "scoreThreshold", "citationRequired"
    );
    private static final Set<String> SECRET_KEY_FRAGMENTS = Set.of(
        "apikey", "api_key", "secret", "password", "credential", "authorization", "access_token"
    );

    /**
     * 处理智能体Type并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public String agentType(String value) {
        if (value == null || !AGENT_TYPES.contains(value)) {
            throw badRequest("Agent 类型无效");
        }
        return value;
    }

    /**
     * 处理系统提示词并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public String systemPrompt(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0
            || value.getBytes(StandardCharsets.UTF_8).length > MAX_PROMPT_BYTES) {
            throw badRequest("系统 Prompt 为空或超过 128KB 限制");
        }
        return value.strip();
    }

    /**
     * 处理{@code avatarUrl}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public String avatarUrl(String value) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > 512) {
            throw badRequest("Agent 头像地址超过长度限制");
        }
        if (normalized.startsWith("/")) {
            if (normalized.startsWith("//") || normalized.contains("..") || normalized.indexOf('\\') >= 0) {
                throw badRequest("Agent 头像相对路径无效");
            }
            return normalized;
        }
        try {
            URI uri = new URI(normalized);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw badRequest("Agent 头像必须使用安全 HTTPS 地址或站内路径");
            }
            return uri.toString();
        } catch (URISyntaxException exception) {
            throw badRequest("Agent 头像地址无效");
        }
    }

    /**
     * 处理{@code engineConfig}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public Map<String, Object> engineConfig(Map<String, Object> value) {
        return strictRuntimeMap(value, ENGINE_KEYS, "Agent 引擎配置");
    }

    /**
     * 处理{@code engineType}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public String engineType(String value) {
        String normalized = value == null || value.isBlank()
            ? "agentscope_java" : value.strip().toLowerCase(Locale.ROOT);
        if (!"agentscope_java".equals(normalized)) {
            throw badRequest("Agent 引擎类型无效");
        }
        return normalized;
    }

    /**
     * 处理{@code engineConfig}并返回对应结果。
     *
     * @param engineType 业务类型
     * @param value {@code value}参数
     * @return 处理结果
     */
    public Map<String, Object> engineConfig(String engineType, Map<String, Object> value) {
        engineType(engineType);
        return engineConfig(value);
    }

    /**
     * 执行{@code timeConfig}相关的处理流程。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public Map<String, Object> runtimeConfig(Map<String, Object> value) {
        return strictRuntimeMap(value, RUNTIME_KEYS, "Agent 运行配置");
    }

    /**
     * 处理{@code welcomeConfig}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public Map<String, Object> welcomeConfig(Map<String, Object> value) {
        Object normalized = normalizeBounded(value == null ? Map.of() : value, 0, new Counter());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) normalized;
        validateWelcomeConfig(result);
        return Map.copyOf(result);
    }

    /**
     * 校验{@code WelcomeConfig}，并在条件不满足时终止处理。
     *
     * @param config {@code config}参数
     */
    private void validateWelcomeConfig(Map<String, Object> config) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        boolean canonical = config.containsKey("enabled") || config.containsKey("mode")
            || config.containsKey("cards") || config.containsKey("generation_requirement");
        if (!canonical) {
            validateLegacyWelcomeConfig(config);
            return;
        }
        Object enabledValue = config.get("enabled");
        if (enabledValue != null) {
            requireBoolean(enabledValue, "enabled");
        }
        boolean enabled = Boolean.TRUE.equals(enabledValue);
        String mode = welcomeText(config.get("mode"), "manual", 16, "mode").toLowerCase(Locale.ROOT);
        if (!Set.of("manual", "ai").contains(mode)) {
            throw badRequest("Agent 欢迎配置 mode 必须是 manual 或 ai");
        }
        welcomeText(config.get("generation_requirement"), "", 500, "generation_requirement");
        Object cardsValue = config.get("cards");
        if (cardsValue != null && !(cardsValue instanceof List<?>)) {
            throw badRequest("Agent 欢迎配置 cards 必须是数组");
        }
        List<?> cards = cardsValue instanceof List<?> list ? list : List.of();
        if (cards.size() > 3) {
            throw badRequest("Agent 欢迎卡最多配置 3 张");
        }
        if (enabled && "manual".equals(mode) && cards.size() != 3) {
            throw badRequest("开启人工欢迎卡时必须配置完整的 3 张卡片");
        }
        for (Object item : cards) {
            validateWelcomeCard(item);
        }
    }

    /**
     * 校验{@code LegacyWelcomeConfig}，并在条件不满足时终止处理。
     *
     * @param config {@code config}参数
     */
    private void validateLegacyWelcomeConfig(Map<String, Object> config) {
        Object message = config.get("message");
        if (message != null && !(message instanceof String)) {
            throw badRequest("旧版欢迎语 message 必须是文本");
        }
        Object showSuggestions = config.get("showSuggestions");
        if (showSuggestions != null) {
            requireBoolean(showSuggestions, "showSuggestions");
        }
        Object suggestions = config.get("suggestions");
        if (suggestions != null && (!(suggestions instanceof List<?> list)
            || list.stream().anyMatch(item -> !(item instanceof String)))) {
            throw badRequest("旧版欢迎语 suggestions 必须是文本数组");
        }
    }

    /**
     * 校验{@code WelcomeCard}，并在条件不满足时终止处理。
     *
     * @param item {@code item}参数
     */
    private void validateWelcomeCard(Object item) {
        if (!(item instanceof Map<?, ?> card)) {
            throw badRequest("Agent 欢迎卡必须是对象");
        }
        String icon = welcomeText(card.get("icon"), "chat", 16, "icon").toLowerCase(Locale.ROOT);
        if (!Set.of("chart", "knowledge", "workspace", "report", "alert", "chat").contains(icon)) {
            throw badRequest("Agent 欢迎卡 icon 无效");
        }
        requiredWelcomeText(card.get("title"), 40, "title");
        requiredWelcomeText(card.get("subtitle"), 100, "subtitle");
        requiredWelcomeText(card.get("prompt"), 300, "prompt");
    }

    /**
     * 校验{@code dWelcomeText}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param maximum {@code maximum}参数
     * @param key {@code key}参数
     */
    private void requiredWelcomeText(Object value, int maximum, String key) {
        if (welcomeText(value, "", maximum, key).isBlank()) {
            throw badRequest("Agent 欢迎卡 " + key + " 不能为空");
        }
    }

    /**
     * 处理{@code welcomeText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param fallback {@code fallback}参数
     * @param maximum {@code maximum}参数
     * @param key {@code key}参数
     * @return 处理结果
     */
    private String welcomeText(Object value, String fallback, int maximum, String key) {
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String text) || text.strip().length() > maximum) {
            throw badRequest("Agent 欢迎配置 " + key + " 无效");
        }
        return text.strip();
    }

    /**
     * 处理{@code routingTags}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 符合条件的数据集合
     */
    public List<String> routingTags(List<String> value) {
        if (value == null) {
            return List.of();
        }
        if (value.size() > 32) {
            throw badRequest("路由标签最多 32 个");
        }
        return value.stream()
            .map(tag -> tag == null ? "" : tag.strip().toLowerCase(Locale.ROOT))
            .peek(tag -> {
                if (tag.isBlank() || tag.length() > 64 || !tag.matches("[a-z0-9][a-z0-9._-]*")) {
                    throw badRequest("路由标签格式无效");
                }
            })
            .distinct()
            .sorted()
            .toList();
    }

    /**
     * 处理{@code bindings}并返回对应结果。
     *
     * @param resourceType 业务类型
     * @param requests {@code requests}参数
     * @return 符合条件的数据集合
     */
    public List<ValidatedBinding> bindings(
        String resourceType,
        List<AgentResourceBindingRequest> requests
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        List<AgentResourceBindingRequest> source = requests == null ? List.of() : requests;
        if (source.size() > 100) {
            throw badRequest("单类 Agent 资源最多绑定 100 项");
        }
        Set<Long> ids = new java.util.HashSet<>();
        List<ValidatedBinding> result = new ArrayList<>(source.size());
        for (AgentResourceBindingRequest request : source) {
            if (request == null || request.resourceId() == null || request.resourceId() <= 0) {
                throw badRequest("Agent 资源 ID 无效");
            }
            if (!ids.add(request.resourceId())) {
                throw badRequest("Agent 版本不能重复绑定同一资源：" + request.resourceId());
            }
            validatePermission(resourceType, request.permission());
            Set<String> keys = switch (resourceType) {
                case "tool" -> TOOL_CONFIG_KEYS;
                case "skill" -> SKILL_CONFIG_KEYS;
                case "knowledge_base" -> KNOWLEDGE_CONFIG_KEYS;
                default -> throw new IllegalArgumentException("unsupported resource type");
            };
            Map<String, Object> config = strictBindingConfig(request.config(), keys, resourceType);
            result.add(new ValidatedBinding(resourceType, request.resourceId(), request.permission(), config));
        }
        return List.copyOf(result);
    }

    /**
     * 处理strict运行时Map并返回对应结果。
     *
     * @param value {@code value}参数
     * @param supported {@code supported}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Map<String, Object> strictRuntimeMap(
        Map<String, Object> value,
        Set<String> supported,
        String label
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Map<String, Object> source = value == null ? Map.of() : value;
        rejectUnknown(source, supported, label);
        Map<String, Object> result = new TreeMap<>();
        putInteger(source, result, "maxIterations", 1, 100);
        putInteger(source, result, "timeoutSeconds", 1, 86_400);
        putNumber(source, result, "temperature", 0, 2);
        putNumber(source, result, "topP", 0, 1);
        Object workspace = source.get("workspaceAccess");
        if (workspace != null) {
            if (!(workspace instanceof String text)
                || !Set.of("none", "read_only", "read_write").contains(text)) {
                throw badRequest("workspaceAccess 无效");
            }
            result.put("workspaceAccess", workspace);
        }
        Object format = source.get("responseFormat");
        if (format != null) {
            if (!(format instanceof String text) || !Set.of("text", "json").contains(text)) {
                throw badRequest("responseFormat 无效");
            }
            result.put("responseFormat", format);
        }
        return Map.copyOf(result);
    }

    /**
     * 处理{@code strictBindingConfig}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param supported {@code supported}参数
     * @param resourceType 业务类型
     * @return 处理结果
     */
    private Map<String, Object> strictBindingConfig(
        Map<String, Object> value,
        Set<String> supported,
        String resourceType
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Map<String, Object> source = value == null ? Map.of() : value;
        rejectUnknown(source, supported, resourceType + " 绑定配置");
        Map<String, Object> result = new TreeMap<>();
        Object enabled = source.get("enabled");
        if (enabled != null) {
            requireBoolean(enabled, "enabled");
            result.put("enabled", enabled);
        }
        Object citation = source.get("citationRequired");
        if (citation != null) {
            requireBoolean(citation, "citationRequired");
            result.put("citationRequired", citation);
        }
        putInteger(source, result, "modelId", 1, Long.MAX_VALUE);
        putInteger(source, result, "timeoutSeconds", 1, 86_400);
        putInteger(source, result, "topK", 1, 100);
        putNumber(source, result, "temperature", 0, 2);
        putNumber(source, result, "scoreThreshold", 0, 1);
        Object description = source.get("descriptionOverride");
        if (description != null) {
            if (!(description instanceof String text) || text.length() > 2000) {
                throw badRequest("descriptionOverride 无效");
            }
            result.put("descriptionOverride", text);
        }
        Object datasets = source.get("datasetIds");
        if (datasets != null) {
            if (!(datasets instanceof List<?> list) || list.size() > 100
                || list.stream().anyMatch(item -> !(item instanceof Number number) || number.longValue() <= 0)) {
                throw badRequest("datasetIds 无效");
            }
            result.put("datasetIds", list.stream().map(item -> ((Number) item).longValue()).toList());
        }
        return Map.copyOf(result);
    }

    /**
     * 处理{@code normalizeBounded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param depth {@code depth}参数
     * @param counter {@code counter}参数
     * @return 处理结果
     */
    private Object normalizeBounded(Object value, int depth, Counter counter) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (depth > 6) {
            throw badRequest("欢迎配置嵌套层级超过限制");
        }
        if (value == null || value instanceof Boolean || value instanceof Number) {
            if (value instanceof Number number && !Double.isFinite(number.doubleValue())) {
                throw badRequest("欢迎配置包含非有限数值");
            }
            return value;
        }
        if (value instanceof String text) {
            if (text.length() > 2000 || text.indexOf('\0') >= 0) {
                throw badRequest("欢迎配置文本超过限制");
            }
            return text;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 50) {
                throw badRequest("欢迎配置对象字段过多");
            }
            Map<String, Object> result = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.isBlank() || key.length() > 64) {
                    throw badRequest("欢迎配置字段名无效");
                }
                rejectSecretKey(key);
                counter.increment();
                result.put(key, normalizeBounded(entry.getValue(), depth + 1, counter));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            if (list.size() > 20) {
                throw badRequest("欢迎配置数组过长");
            }
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                counter.increment();
                result.add(normalizeBounded(item, depth + 1, counter));
            }
            return List.copyOf(result);
        }
        throw badRequest("欢迎配置包含不支持的数据类型");
    }

    /**
     * 处理{@code rejectSecretKey}相关逻辑。
     *
     * @param key {@code key}参数
     */
    private void rejectSecretKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "_");
        if (SECRET_KEY_FRAGMENTS.stream().anyMatch(normalized::contains)) {
            throw badRequest("Agent 配置不得包含密钥字段：" + key);
        }
    }

    /**
     * 校验权限，并在条件不满足时终止处理。
     *
     * @param resourceType 业务类型
     * @param permission 权限参数
     */
    private void validatePermission(String resourceType, String permission) {
        Set<String> allowed = switch (resourceType) {
            case "tool" -> Set.of("use", "invoke");
            case "skill" -> Set.of("use");
            case "knowledge_base" -> Set.of("read");
            default -> Set.of();
        };
        if (!allowed.contains(permission)) {
            throw badRequest(resourceType + " 绑定权限无效");
        }
    }

    /**
     * 处理{@code rejectUnknown}相关逻辑。
     *
     * @param source 数据源参数
     * @param supported {@code supported}参数
     * @param label {@code label}参数
     */
    private void rejectUnknown(Map<String, Object> source, Set<String> supported, String label) {
        List<String> unknown = source.keySet().stream().filter(key -> !supported.contains(key)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw badRequest(label + "包含不支持的字段：" + unknown);
        }
    }

    /**
     * 处理{@code putInteger}相关逻辑。
     *
     * @param source 数据源参数
     * @param target {@code target}参数
     * @param key {@code key}参数
     * @param minimum {@code minimum}参数
     * @param maximum {@code maximum}参数
     */
    private void putInteger(
        Map<String, Object> source,
        Map<String, Object> target,
        String key,
        long minimum,
        long maximum
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Object raw = source.get(key);
        if (raw == null) {
            return;
        }
        if (!(raw instanceof Number number)) {
            throw badRequest(key + " 必须是整数");
        }
        long value = number.longValue();
        if (number.doubleValue() != value || value < minimum || value > maximum) {
            throw badRequest(key + " 超出允许的整数范围");
        }
        if (value <= Integer.MAX_VALUE) {
            target.put(key, Math.toIntExact(value));
        } else {
            target.put(key, value);
        }
    }

    /**
     * 处理{@code putNumber}相关逻辑。
     *
     * @param source 数据源参数
     * @param target {@code target}参数
     * @param key {@code key}参数
     * @param minimum {@code minimum}参数
     * @param maximum {@code maximum}参数
     */
    private void putNumber(
        Map<String, Object> source,
        Map<String, Object> target,
        String key,
        double minimum,
        double maximum
    ) {
        Object raw = source.get(key);
        if (raw == null) {
            return;
        }
        if (!(raw instanceof Number number)) {
            throw badRequest(key + " 必须是数值");
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw badRequest(key + " 超出允许范围");
        }
        target.put(key, value);
    }

    /**
     * 校验{@code Boolean}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param key {@code key}参数
     */
    private void requireBoolean(Object value, String key) {
        if (!(value instanceof Boolean)) {
            throw badRequest(key + " 必须是布尔值");
        }
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
     * 封装{@code ValidatedBinding}相关的不可变数据。
     */
    public record ValidatedBinding(
        String resourceType,
        Long resourceId,
        String permission,
        Map<String, Object> config
    ) {
    }

    /**
     * 表示{@code Counter}相关的领域对象。
     */
    private static final class Counter {
        private int value;

        /**
         * 处理{@code increment}相关逻辑。
         */
        private void increment() {
            if (++value > 200) {
                throw new ServiceException("欢迎配置总项数超过限制", HttpStatus.BAD_REQUEST);
            }
        }
    }
}
