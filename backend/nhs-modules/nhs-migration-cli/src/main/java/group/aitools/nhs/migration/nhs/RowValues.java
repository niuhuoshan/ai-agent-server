package group.aitools.nhs.migration.nhs;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;

/**
 * 表示{@code RowValues}相关的领域对象。
 */
final class RowValues {

    private final Map<String, Object> values;

    /**
     * 创建 {@code RowValues} 实例并初始化所需依赖。
     *
     * @param values {@code values}参数
     */
    RowValues(Map<String, Object> values) {
        this.values = values;
    }

    /**
     * 处理{@code raw}并返回对应结果。
     *
     * @param key {@code key}参数
     * @return 处理结果
     */
    Object raw(String key) {
        return values.get(key);
    }

    /**
     * 处理{@code id}并返回对应结果。
     *
     * @return 处理结果
     */
    String id() {
        String result = text("id");
        if (result == null) {
            result = text("config_key");
        }
        if (result == null) {
            throw new IllegalArgumentException("source row has no stable identity");
        }
        return result;
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param key {@code key}参数
     * @return 处理结果
     */
    String text(String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        String result = String.valueOf(value).strip();
        return result.isEmpty() ? null : result;
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param key {@code key}参数
     * @param fallback {@code fallback}参数
     * @return 处理结果
     */
    String text(String key, String fallback) {
        String result = text(key);
        return result == null ? fallback : result;
    }

    /**
     * 处理{@code longValue}并返回对应结果。
     *
     * @param key {@code key}参数
     * @param fallback {@code fallback}参数
     * @return 处理结果
     */
    long longValue(String key, long fallback) {
        Object value = values.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /**
     * 处理{@code integer}并返回对应结果。
     *
     * @param key {@code key}参数
     * @param fallback {@code fallback}参数
     * @return 处理结果
     */
    int integer(String key, int fallback) {
        long value = longValue(key, fallback);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            return fallback;
        }
        return (int) value;
    }

    /**
     * 处理{@code decimal}并返回对应结果。
     *
     * @param key {@code key}参数
     * @return 处理结果
     */
    BigDecimal decimal(String key) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * 处理{@code bool}并返回对应结果。
     *
     * @param key {@code key}参数
     * @param fallback {@code fallback}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    boolean bool(String key, boolean fallback) {
        Object value = values.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return switch (String.valueOf(value).strip().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "y", "1", "enabled", "active", "published" -> true;
            case "false", "no", "n", "0", "disabled", "inactive", "archived" -> false;
            default -> fallback;
        };
    }

    /**
     * 处理{@code instant}并返回对应结果。
     *
     * @param key {@code key}参数
     * @return 处理结果
     */
    Instant instant(String key) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC);
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toInstant(ZoneOffset.UTC);
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * 将输入数据转换为{@code map}。
     *
     * @return 处理结果
     */
    Map<String, Object> map() {
        return values;
    }
}
