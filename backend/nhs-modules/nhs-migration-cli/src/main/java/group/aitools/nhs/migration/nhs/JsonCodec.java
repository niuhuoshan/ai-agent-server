package group.aitools.nhs.migration.nhs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 表示{@code JsonCodec}相关的领域对象。
 */
final class JsonCodec {

    private static final Set<String> SECRET_KEYS = Set.of(
        "password", "password_hash", "api_key", "api_key_encrypted", "api_key_hash",
        "auth_headers", "headers", "secret", "token", "access_token", "refresh_token",
        "credential", "credentials", "db_user"
    );

    private final ObjectMapper mapper = new ObjectMapper()
        .findAndRegisterModules()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    /**
     * 处理{@code write}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize migration JSON", exception);
        }
    }

    /**
     * 处理{@code writePretty}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    String writePretty(Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize migration report", exception);
        }
    }

    /**
     * 处理{@code readMap}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> readMap(String value) {
        try {
            Object parsed = mapper.readValue(value, Object.class);
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("expected a JSON object");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), normalize(item)));
            return result;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid JSON object", exception);
        }
    }

    /**
     * 处理{@code parseLenient}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    Object parseLenient(Object value) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> || value instanceof Collection<?> || value instanceof Number
            || value instanceof Boolean) {
            return normalize(value);
        }
        String text = String.valueOf(value).strip();
        if (text.isEmpty()) {
            return null;
        }
        if ((text.startsWith("{") && text.endsWith("}"))
            || (text.startsWith("[") && text.endsWith("]"))) {
            try {
                return normalize(mapper.readValue(text, Object.class));
            } catch (JsonProcessingException ignored) {
                return text;
            }
        }
        return text;
    }

    /**
     * 处理{@code sanitizeRow}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    Map<String, Object> sanitizeRow(Map<String, Object> source) {
        Map<String, Object> sanitized = new TreeMap<>();
        source.forEach((key, value) -> {
            String normalizedKey = key.toLowerCase(Locale.ROOT);
            if (!SECRET_KEYS.contains(normalizedKey) && !looksSecret(normalizedKey)) {
                sanitized.put(key, sanitizeValue(value));
            }
        });
        return Collections.unmodifiableMap(sanitized);
    }

    /**
     * 处理{@code sanitizeValue}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Object sanitizeValue(Object value) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (value != null && "org.postgresql.util.PGobject".equals(value.getClass().getName())) {
            value = String.valueOf(value);
        }
        if (value instanceof String text) {
            String stripped = text.strip();
            if ((stripped.startsWith("{") && stripped.endsWith("}"))
                || (stripped.startsWith("[") && stripped.endsWith("]"))) {
                try {
                    value = mapper.readValue(stripped, Object.class);
                } catch (JsonProcessingException ignored) {
                    // Preserve ordinary text that only resembles JSON.
                }
            }
        }
        Object normalized = normalize(value);
        if (normalized instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> {
                String text = String.valueOf(key);
                String normalizedKey = text.toLowerCase(Locale.ROOT);
                if (!SECRET_KEYS.contains(normalizedKey) && !looksSecret(normalizedKey)) {
                    result.put(text, sanitizeValue(item));
                }
            });
            return result;
        }
        if (normalized instanceof Collection<?> collection) {
            return collection.stream().map(this::sanitizeValue).toList();
        }
        return normalized;
    }

    /**
     * 处理{@code looksSecret}并返回对应结果。
     *
     * @param key {@code key}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean looksSecret(String key) {
        return key.endsWith("_password") || key.endsWith("_secret") || key.endsWith("_token")
            || key.endsWith("_api_key") || key.contains("credential_value");
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    Object normalize(Object value) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof TemporalAccessor temporal) {
            return temporal.toString();
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof Array sqlArray) {
            try {
                return normalize(sqlArray.getArray());
            } catch (SQLException exception) {
                throw new IllegalStateException("cannot read SQL array", exception);
            }
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), normalize(item)));
            return result;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> result = new ArrayList<>(collection.size());
            collection.forEach(item -> result.add(normalize(item)));
            return result;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> result = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                result.add(normalize(java.lang.reflect.Array.get(value, index)));
            }
            return result;
        }
        return String.valueOf(value);
    }

    /**
     * 处理{@code sha256}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    String sha256(Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = write(normalize(value)).getBytes(StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * 处理{@code aggregateHash}并返回对应结果。
     *
     * @param hashes {@code hashes}参数
     * @return 处理结果
     */
    String aggregateHash(List<String> hashes) {
        return sha256(hashes.stream().sorted().toList());
    }

    /**
     * 处理{@code linkedMap}并返回对应结果。
     *
     * @param entries {@code entries}参数
     * @return 处理结果
     */
    Map<String, Object> linkedMap(Object... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("entries must be key/value pairs");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put(String.valueOf(entries[index]), normalize(entries[index + 1]));
        }
        return result;
    }
}
