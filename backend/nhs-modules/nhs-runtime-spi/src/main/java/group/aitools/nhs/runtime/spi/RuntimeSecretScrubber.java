package group.aitools.nhs.runtime.spi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 表示运行时SecretScrubber相关的领域对象。
 * Scrubs credentials from values that are safe to expose in runtime projections. */
public final class RuntimeSecretScrubber {

    private static final Set<String> SECRET_KEY_PARTS = Set.of(
        "apikey", "api_key", "password", "secret", "token", "authorization", "credential"
    );
    private static final Set<String> TOKEN_COUNT_KEYS = Set.of(
        "inputtokens", "outputtokens", "cachedtokens", "totaltokens",
        "prompttokens", "completiontokens", "tokencount"
    );
    private static final Pattern QUOTED_NAMED_SECRET = Pattern.compile(
        "(?is)([\\\"']?(?:api[_-]?key|token|password|secret|authorization|credential)"
            + "[\\\"']?\\s*[:=]\\s*)([\\\"'])(.*?)\\2"
    );
    /** Handles a JSON/text delta that ends before the closing quote arrives. */
    private static final Pattern UNTERMINATED_QUOTED_NAMED_SECRET = Pattern.compile(
        "(?is)([\\\"']?(?:api[_-]?key|token|password|secret|authorization|credential)"
            + "[\\\"']?\\s*[:=]\\s*)([\\\"'])(?:(?!\\2).)*$"
    );
    private static final Pattern NAMED_SECRET = Pattern.compile(
        "(?i)(api[_-]?key|token|password|secret|authorization|credential)"
            + "\\s*[:=]\\s*[^\\s,;}\\\"']+"
    );
    private static final Pattern BEARER_SECRET = Pattern.compile("(?i)bearer\\s+[^\\s,;}\\\"']+");
    private static final Pattern OPENAI_SECRET = Pattern.compile("sk-[A-Za-z0-9_-]{8,}");

    /**
     * 创建 {@code RuntimeSecretScrubber} 实例并初始化所需依赖。
     */
    private RuntimeSecretScrubber() {
    }

    /**
     * 处理{@code sanitizeMap}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    public static Map<String, Object> sanitizeMap(Map<?, ?> source) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                String normalizedKey = String.valueOf(key);
                sanitized.put(normalizedKey, sanitizeValue(normalizedKey, value));
            });
        }
        return sanitized;
    }

    /**
     * 处理{@code sanitizeValue}并返回对应结果。
     *
     * @param key {@code key}参数
     * @param value {@code value}参数
     * @return 处理结果
     */
    public static Object sanitizeValue(String key, Object value) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (isSecretKey(key)) {
            return "[REDACTED]";
        }
        if (value instanceof String text) {
            return scrubText(text);
        }
        if (value instanceof Map<?, ?> map) {
            return sanitizeMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> sanitized = new ArrayList<>(list.size());
            list.forEach(item -> sanitized.add(sanitizeValue("", item)));
            return sanitized;
        }
        return value == null || value instanceof Number || value instanceof Boolean
            ? value : scrubText(String.valueOf(value));
    }

    /**
     * 处理{@code scrubText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    public static String scrubText(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String sanitized = QUOTED_NAMED_SECRET.matcher(value).replaceAll("$1$2[REDACTED]$2");
        sanitized = UNTERMINATED_QUOTED_NAMED_SECRET.matcher(sanitized)
            .replaceAll("$1$2[REDACTED]");
        sanitized = NAMED_SECRET.matcher(sanitized).replaceAll("$1=[REDACTED]");
        sanitized = BEARER_SECRET.matcher(sanitized).replaceAll("Bearer [REDACTED]");
        return OPENAI_SECRET.matcher(sanitized).replaceAll("sk-[REDACTED]");
    }

    /**
     * 判断{@code SecretKey}是否满足要求。
     *
     * @param key {@code key}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private static boolean isSecretKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT).replace("-", "_");
        String compact = normalized.replace("_", "");
        if (TOKEN_COUNT_KEYS.contains(compact)) {
            return false;
        }
        return SECRET_KEY_PARTS.stream().anyMatch(normalized::contains);
    }
}
