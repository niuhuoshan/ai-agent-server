package group.aitools.nhs.platform.identity.service;

import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 封装嵌入式会话应用相关的不可变数据。
 * Validated, secret-free browser policy stored with an Embed API application. */
public record EmbedApplicationConfig(
    List<String> allowedOrigins,
    Set<Long> agentVersionIds,
    String displayName,
    String primaryColor,
    boolean watermark,
    int maxSessionMinutes
) {

    private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");

    /**
     * 创建 {@code EmbedApplicationConfig} 实例并初始化所需依赖。
     *
     * @param allowedOrigins {@code allowedOrigins}参数
     * @param agentVersionIds 资源标识集合
     * @param displayName 名称
     * @param primaryColor {@code primaryColor}参数
     * @param watermark {@code watermark}参数
     * @param maxSessionMinutes max会话Minutes参数
     */
    public EmbedApplicationConfig {
        allowedOrigins = List.copyOf(allowedOrigins);
        agentVersionIds = Set.copyOf(agentVersionIds);
    }

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    public static EmbedApplicationConfig from(Map<String, Object> source) {
        Map<String, Object> value = source == null ? Map.of() : source;
        Set<String> supported = Set.of(
            "allowedOrigins", "agentVersionIds", "displayName", "primaryColor",
            "watermark", "maxSessionMinutes"
        );
        value.keySet().forEach(key -> {
            if (!supported.contains(key)) {
                throw badRequest("Embed应用配置包含不支持的字段：" + key);
            }
        });
        List<String> origins = origins(value.get("allowedOrigins"));
        Set<Long> versions = positiveIds(value.get("agentVersionIds"));
        if (origins.isEmpty()) {
            throw badRequest("Embed应用必须至少配置一个宿主Origin");
        }
        if (versions.isEmpty()) {
            throw badRequest("Embed应用必须至少配置一个Agent版本");
        }
        String displayName = optionalText(value.get("displayName"), 128, "Embed显示名称");
        String primaryColor = optionalText(value.get("primaryColor"), 7, "Embed品牌色");
        if (primaryColor != null && !COLOR.matcher(primaryColor).matches()) {
            throw badRequest("Embed品牌色必须是六位十六进制颜色");
        }
        boolean watermark = booleanValue(value.get("watermark"), true, "Embed水印开关");
        int maxSessionMinutes = integer(value.get("maxSessionMinutes"), 60, 1, 1440, "Embed会话上限");
        return new EmbedApplicationConfig(
            origins, versions, displayName,
            primaryColor == null ? "#18a058" : primaryColor.toLowerCase(Locale.ROOT),
            watermark, maxSessionMinutes
        );
    }

    /**
     * 将输入数据转换为{@code Map}。
     *
     * @return 处理结果
     */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allowedOrigins", allowedOrigins);
        result.put("agentVersionIds", agentVersionIds.stream().sorted().toList());
        if (displayName != null) {
            result.put("displayName", displayName);
        }
        result.put("primaryColor", primaryColor);
        result.put("watermark", watermark);
        result.put("maxSessionMinutes", maxSessionMinutes);
        return Map.copyOf(result);
    }

    /**
     * 处理{@code allowsOrigin}并返回对应结果。
     *
     * @param origin {@code origin}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean allowsOrigin(String origin) {
        return allowedOrigins.contains(normalizeOrigin(origin));
    }

    /**
     * 处理allows智能体版本并返回对应结果。
     *
     * @param agentVersionId 资源标识
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean allowsAgentVersion(Long agentVersionId) {
        return agentVersionIds.isEmpty() || agentVersionIds.contains(agentVersionId);
    }

    /**
     * 处理{@code normalizeOrigin}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @return 处理结果
     */
    public static String normalizeOrigin(String raw) {
        String value = optionalText(raw, 512, "Embed宿主Origin");
        if (value == null) {
            throw badRequest("Embed宿主Origin不能为空");
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("https".equals(scheme) || "http".equals(scheme))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
                throw badRequest("Embed宿主Origin必须是无路径的HTTP(S)来源");
            }
            int port = uri.getPort();
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            boolean defaultPort = port < 0 || ("https".equals(scheme) && port == 443)
                || ("http".equals(scheme) && port == 80);
            return scheme + "://" + host + (defaultPort ? "" : ":" + port);
        } catch (IllegalArgumentException exception) {
            throw badRequest("Embed宿主Origin格式无效");
        }
    }

    /**
     * 处理{@code origins}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @return 符合条件的数据集合
     */
    private static List<String> origins(Object raw) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> values) || values.size() > 50) {
            throw badRequest("Embed宿主Origin必须是最多50项的数组");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof String text)) {
                throw badRequest("Embed宿主Origin必须是文本");
            }
            result.add(normalizeOrigin(text));
        }
        return List.copyOf(result);
    }

    /**
     * 处理{@code positiveIds}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @return 符合条件的数据集合
     */
    private static Set<Long> positiveIds(Object raw) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (raw == null) {
            return Set.of();
        }
        if (!(raw instanceof List<?> values) || values.size() > 100) {
            throw badRequest("Embed Agent版本必须是最多100项的数组");
        }
        Set<Long> result = new LinkedHashSet<>();
        for (Object value : values) {
            Long id = positiveId(value);
            if (id == null) {
                throw badRequest("Embed Agent版本ID无效");
            }
            result.add(id);
        }
        return Set.copyOf(result);
    }

    /**
     * 处理{@code positiveId}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private static Long positiveId(Object value) {
        if (value instanceof Number number && number.longValue() > 0
            && number.doubleValue() == number.longValue()) {
            return number.longValue();
        }
        if (value instanceof String text && text.matches("[1-9][0-9]{0,18}")) {
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 处理{@code optionalText}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @param maximum {@code maximum}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private static String optionalText(Object raw, int maximum, String label) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String text)) {
            throw badRequest(label + "必须是文本");
        }
        String value = text.strip();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > maximum || value.indexOf('\0') >= 0) {
            throw badRequest(label + "过长或包含非法字符");
        }
        return value;
    }

    /**
     * 处理{@code booleanValue}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @param fallback {@code fallback}参数
     * @param label {@code label}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private static boolean booleanValue(Object raw, boolean fallback, String label) {
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Boolean value)) {
            throw badRequest(label + "必须是布尔值");
        }
        return value;
    }

    /**
     * 处理{@code integer}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @param fallback {@code fallback}参数
     * @param minimum {@code minimum}参数
     * @param maximum {@code maximum}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private static int integer(Object raw, int fallback, int minimum, int maximum, String label) {
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Number number) || number.doubleValue() != number.intValue()
            || number.intValue() < minimum || number.intValue() > maximum) {
            throw badRequest(label + "必须在" + minimum + "到" + maximum + "之间");
        }
        return number.intValue();
    }

    /**
     * 处理{@code badRequest}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private static ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }
}
