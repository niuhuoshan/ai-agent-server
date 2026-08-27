package group.aitools.nhs.platform.operations.service;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.operations.web.RedisDeleteRequest;
import group.aitools.nhs.platform.operations.web.RedisFlushRequest;
import group.aitools.nhs.platform.operations.web.RedisKeyDetailView;
import group.aitools.nhs.platform.operations.web.RedisKeyListView;
import group.aitools.nhs.platform.operations.web.RedisKeyView;
import group.aitools.nhs.platform.operations.web.RedisMutationView;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.redisson.api.RBucket;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 负责{@code RedisOperations}相关的业务编排与领域规则处理。
 *
 * Bounded Redis administration for a private deployment.
 *
 * <p>Values are deliberately capped and recursively sanitized. Redis is an
 * operational dependency, not a second application database, so this service
 * never exposes credentials, binary payloads or unbounded collections.</p>
 */
@Service
public class RedisOperationsApplicationService {

    private static final int MAX_KEYS = 5000;
    private static final int MAX_PATTERN_LENGTH = 128;
    private static final int MAX_KEY_LENGTH = 512;
    private static final int MAX_VALUE_ITEMS = 200;
    private static final int MAX_VALUE_TEXT = 4096;

    private final CurrentPrincipalProvider principalProvider;
    private final ObjectProvider<RedissonClient> redisProvider;
    private final AgentAuditEventMapper auditMapper;
    private final PlatformIdGenerator idGenerator;

    public RedisOperationsApplicationService(
        CurrentPrincipalProvider principalProvider,
        ObjectProvider<RedissonClient> redisProvider,
        AgentAuditEventMapper auditMapper,
        PlatformIdGenerator idGenerator
    ) {
        this.principalProvider = principalProvider;
        this.redisProvider = redisProvider;
        this.auditMapper = auditMapper;
        this.idGenerator = idGenerator;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param rawPattern {@code rawPattern}参数
     * @return 处理结果
     */
    public RedisKeyListView list(String rawPattern) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CurrentPrincipal principal = requireAdministrator();
        String pattern = normalizePattern(rawPattern);
        RedissonClient client = requireClient();
        try {
            long total = client.getKeys().count();
            List<RedisKeyView> result = new ArrayList<>();
            boolean truncated = false;
            for (String key : client.getKeys().getKeysByPattern(pattern)) {
                if (result.size() >= MAX_KEYS) {
                    truncated = true;
                    break;
                }
                result.add(metadata(client, key));
            }
            audit(principal, "list", "pattern=" + safeAuditKey(pattern) + ", returned=" + result.size());
            return new RedisKeyListView(total, result.size(), truncated, pattern, List.copyOf(result));
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            auditFailure(principal, "list", "pattern=" + pattern);
            throw unavailable("Redis 扫描失败");
        }
    }

    /**
     * 处理{@code detail}并返回对应结果。
     *
     * @param rawKey {@code rawKey}参数
     * @return 处理结果
     */
    public RedisKeyDetailView detail(String rawKey) {
        CurrentPrincipal principal = requireAdministrator();
        String key = normalizeKey(rawKey);
        RedissonClient client = requireClient();
        try {
            RType type = client.getKeys().getType(key);
            if (type == null) {
                throw new ServiceException("Redis 键不存在", HttpStatus.NOT_FOUND);
            }
            long ttl = client.getKeys().remainTimeToLive(key);
            SanitizedValue sanitized = readValue(client, key, type);
            audit(principal, "detail", "key=" + safeAuditKey(key));
            return new RedisKeyDetailView(key, redisType(type), ttl, sanitized.value(), sanitized.truncated());
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            auditFailure(principal, "detail", "key=" + safeAuditKey(key));
            throw unavailable("Redis 键详情读取失败");
        }
    }

    /**
     * 删除{@code delete}。
     *
     * @param rawKey {@code rawKey}参数
     * @param confirm {@code confirm}参数
     * @return 处理结果
     */
    public RedisMutationView delete(String rawKey, boolean confirm) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CurrentPrincipal principal = requireAdministrator();
        if (!confirm) {
            throw new ServiceException("删除 Redis 键需要明确确认", HttpStatus.BAD_REQUEST);
        }
        String key = normalizeKey(rawKey);
        RedissonClient client = requireClient();
        try {
            long deleted = client.getKeys().delete(key);
            if (deleted == 0) {
                throw new ServiceException("Redis 键不存在", HttpStatus.NOT_FOUND);
            }
            audit(principal, "delete", "key=" + safeAuditKey(key));
            return new RedisMutationView("success", deleted, 0, "Redis 键已删除");
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            auditFailure(principal, "delete", "key=" + safeAuditKey(key));
            throw unavailable("Redis 键删除失败");
        }
    }

    /**
     * 删除{@code Batch}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    public RedisMutationView deleteBatch(RedisDeleteRequest request) {
        CurrentPrincipal principal = requireAdministrator();
        if (request == null || !request.confirm()) {
            throw new ServiceException("删除 Redis 键需要明确确认", HttpStatus.BAD_REQUEST);
        }
        List<String> keys = normalizeKeys(request.keys());
        RedissonClient client = requireClient();
        try {
            long deleted = 0;
            for (int from = 0; from < keys.size(); from += 500) {
                List<String> batch = keys.subList(from, Math.min(from + 500, keys.size()));
                deleted += client.getKeys().delete(batch.toArray(String[]::new));
            }
            audit(principal, "delete_batch", "requested=" + keys.size() + ", deleted=" + deleted);
            return new RedisMutationView("success", deleted, 0, "已删除 " + deleted + " 个 Redis 键");
        } catch (Exception exception) {
            auditFailure(principal, "delete_batch", "requested=" + keys.size());
            throw unavailable("Redis 批量删除失败");
        }
    }

    /**
     * 处理{@code flush}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    public RedisMutationView flush(RedisFlushRequest request) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CurrentPrincipal principal = requireAdministrator();
        if (request == null || !request.confirm()) {
            throw new ServiceException("清理 Redis 需要明确确认", HttpStatus.BAD_REQUEST);
        }
        if (!request.preserveConversations()) {
            throw new ServiceException("当前版本只允许保留会话数据的选择性清理", HttpStatus.BAD_REQUEST);
        }
        RedissonClient client = requireClient();
        try {
            long deleted = 0;
            List<String> toDelete = new ArrayList<>();
            long preserved = 0;
            for (String key : client.getKeys().getKeysByPattern("*")) {
                if (key.startsWith("conversation:")) {
                    preserved++;
                } else {
                    toDelete.add(key);
                    if (toDelete.size() >= 500) {
                        deleted += client.getKeys().delete(toDelete.toArray(String[]::new));
                        toDelete.clear();
                    }
                }
            }
            if (!toDelete.isEmpty()) {
                deleted += client.getKeys().delete(toDelete.toArray(String[]::new));
            }
            // The batched deletes above are intentionally counted by a second
            // bounded scan so a concurrent writer cannot make the response lie.
            long remaining = client.getKeys().count();
            audit(principal, "flush", "deletedAtLeast=" + deleted + ", preserved=" + preserved);
            return new RedisMutationView("success", deleted, preserved,
                "已清理非会话缓存；当前仍有 " + remaining + " 个键（含并发写入）");
        } catch (Exception exception) {
            auditFailure(principal, "flush", "preserveConversations=true");
            throw unavailable("Redis 清理失败");
        }
    }

    /**
     * 处理元数据并返回对应结果。
     *
     * @param client 客户端参数
     * @param key {@code key}参数
     * @return 处理结果
     */
    private RedisKeyView metadata(RedissonClient client, String key) {
        RType type = client.getKeys().getType(key);
        return new RedisKeyView(
            bounded(key, MAX_KEY_LENGTH),
            type == null ? "unknown" : redisType(type),
            client.getKeys().remainTimeToLive(key)
        );
    }

    /**
     * 处理{@code readValue}并返回对应结果。
     *
     * @param client 客户端参数
     * @param key {@code key}参数
     * @param type 业务类型
     * @return 处理结果
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private SanitizedValue readValue(RedissonClient client, String key, RType type) {
        if (isSensitiveName(key)) {
            return new SanitizedValue("<redacted>", false);
        }
        return switch (type) {
            case OBJECT, JSON -> sanitize(((RBucket) client.getBucket(key)).get());
            case MAP -> sanitize(((RMap) client.getMap(key)).readAllMap());
            case LIST -> sanitize(((RList) client.getList(key)).readAll());
            case SET -> sanitize(((RSet) client.getSet(key)).readAll());
            case ZSET -> sanitize(((RScoredSortedSet) client.getScoredSortedSet(key)).readAll());
            default -> new SanitizedValue(Map.of("type", redisType(type), "message", "该 Redis 类型不支持值读取"), false);
        };
    }

    /**
     * 处理{@code sanitize}并返回对应结果。
     *
     * @param input {@code input}参数
     * @return 处理结果
     */
    private SanitizedValue sanitize(Object input) {
        Counter counter = new Counter();
        Object value = sanitize(input, counter, 0);
        return new SanitizedValue(value, counter.truncated);
    }

    /**
     * 处理{@code sanitize}并返回对应结果。
     *
     * @param input {@code input}参数
     * @param counter {@code counter}参数
     * @param depth {@code depth}参数
     * @return 处理结果
     */
    private Object sanitize(Object input, Counter counter, int depth) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (input == null || depth > 4) {
            return input;
        }
        if (input instanceof byte[] bytes) {
            counter.truncated = true;
            return "<binary " + bytes.length + " bytes>";
        }
        if (input instanceof CharSequence || input instanceof Number || input instanceof Boolean) {
            String text = String.valueOf(input);
            if (text.length() > MAX_VALUE_TEXT) {
                counter.truncated = true;
                return text.substring(0, MAX_VALUE_TEXT) + "…";
            }
            return input;
        }
        if (input instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count++ >= MAX_VALUE_ITEMS) {
                    counter.truncated = true;
                    break;
                }
                String key = bounded(String.valueOf(entry.getKey()), MAX_VALUE_TEXT);
                result.put(key, isSensitiveName(key) ? "<redacted>" : sanitize(entry.getValue(), counter, depth + 1));
            }
            return result;
        }
        if (input instanceof Collection<?> collection) {
            List<Object> result = new ArrayList<>();
            int count = 0;
            for (Object item : collection) {
                if (count++ >= MAX_VALUE_ITEMS) {
                    counter.truncated = true;
                    break;
                }
                result.add(sanitize(item, counter, depth + 1));
            }
            return result;
        }
        if (input instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            int count = 0;
            for (Object item : iterable) {
                if (count++ >= MAX_VALUE_ITEMS) {
                    counter.truncated = true;
                    break;
                }
                result.add(sanitize(item, counter, depth + 1));
            }
            return result;
        }
        return bounded(String.valueOf(input), MAX_VALUE_TEXT);
    }

    /**
     * 校验{@code Administrator}，并在条件不满足时终止处理。
     *
     * @return 处理结果
     */
    private CurrentPrincipal requireAdministrator() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (principal == null || !principal.isHuman() || !principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
            throw new ServiceException("仅平台管理员可以管理 Redis", HttpStatus.FORBIDDEN);
        }
        return principal;
    }

    /**
     * 校验客户端，并在条件不满足时终止处理。
     *
     * @return 处理结果
     */
    private RedissonClient requireClient() {
        RedissonClient client = redisProvider.getIfAvailable();
        if (client == null || client.isShutdown() || client.isShuttingDown()) {
            throw unavailable("Redis 客户端未就绪");
        }
        return client;
    }

    /**
     * 处理{@code normalizePattern}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizePattern(String value) {
        String pattern = value == null || value.isBlank() ? "*" : value.strip();
        if (pattern.length() > MAX_PATTERN_LENGTH || pattern.indexOf('\0') >= 0
            || pattern.indexOf('\r') >= 0 || pattern.indexOf('\n') >= 0) {
            throw new ServiceException("Redis 匹配模式无效", HttpStatus.BAD_REQUEST);
        }
        return pattern;
    }

    /**
     * 处理{@code normalizeKey}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeKey(String value) {
        if (value == null || value.isBlank()) {
            throw new ServiceException("Redis 键不能为空", HttpStatus.BAD_REQUEST);
        }
        String key = value.strip();
        if (key.length() > MAX_KEY_LENGTH || key.indexOf('\0') >= 0 || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0) {
            throw new ServiceException("Redis 键无效", HttpStatus.BAD_REQUEST);
        }
        return key;
    }

    /**
     * 处理{@code normalizeKeys}并返回对应结果。
     *
     * @param rawKeys {@code rawKeys}参数
     * @return 符合条件的数据集合
     */
    private List<String> normalizeKeys(List<String> rawKeys) {
        if (rawKeys == null || rawKeys.isEmpty()) {
            throw new ServiceException("至少选择一个 Redis 键", HttpStatus.BAD_REQUEST);
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String key : rawKeys) {
            unique.add(normalizeKey(key));
        }
        return List.copyOf(unique);
    }

    /**
     * 处理{@code redisType}并返回对应结果。
     *
     * @param type 业务类型
     * @return 处理结果
     */
    private String redisType(RType type) {
        return type == null ? "unknown" : type.getValue();
    }

    /**
     * 处理safe审计Key并返回对应结果。
     *
     * @param key {@code key}参数
     * @return 处理结果
     */
    private String safeAuditKey(String key) {
        return bounded(key.replaceAll("(?i)(token|secret|password|api[-_]?key)[^:]*:[^:]*", "$1:<redacted>"), 256);
    }

    /**
     * 判断{@code SensitiveName}是否满足要求。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isSensitiveName(String value) {
        String normalized = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("password") || normalized.contains("passwd")
            || normalized.contains("secret") || normalized.contains("credential")
            || normalized.contains("api_key") || normalized.contains("api-key")
            || normalized.contains("access_token") || normalized.contains("refresh_token");
    }

    /**
     * 处理{@code bounded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maximum {@code maximum}参数
     * @return 处理结果
     */
    private String bounded(String value, int maximum) {
        if (value == null) return "";
        String normalized = value.replace('\0', ' ').replace('\r', ' ').replace('\n', ' ');
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum) + "…";
    }

    /**
     * 处理{@code unavailable}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException unavailable(String message) {
        return new ServiceException(message, 503);
    }

    /**
     * 处理审计相关逻辑。
     *
     * @param principal 当前操作主体
     * @param action {@code action}参数
     * @param summary {@code summary}参数
     */
    private void audit(CurrentPrincipal principal, String action, String summary) {
        auditMapper.insertEvent(
            idGenerator.nextId(), "user", principal.id(), action, "redis", null,
            null, "success", "platform_admin", bounded(summary, 800), LocalDateTime.now()
        );
    }

    /**
     * 处理审计Failure相关逻辑。
     *
     * @param principal 当前操作主体
     * @param action {@code action}参数
     * @param summary {@code summary}参数
     */
    private void auditFailure(CurrentPrincipal principal, String action, String summary) {
        try {
            auditMapper.insertEvent(
                idGenerator.nextId(), "user", principal.id(), action, "redis", null,
                null, "failure", "redis_unavailable", bounded(summary, 800), LocalDateTime.now()
            );
        } catch (RuntimeException ignored) {
            // Preserve the original operational error. The audit outbox/DB
            // monitor reports a persistence failure separately.
        }
    }

    /**
     * 封装{@code SanitizedValue}相关的不可变数据。
     */
    private record SanitizedValue(Object value, boolean truncated) {
    }

    /**
     * 表示{@code Counter}相关的领域对象。
     */
    private static final class Counter {
        private boolean truncated;
    }
}
