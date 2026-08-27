package group.aitools.nhs.platform.nhs.service;

import group.aitools.nhs.platform.embed.mapper.EmbedChatMapper;
import group.aitools.nhs.platform.embed.persistence.row.EmbedAgentRuntimeRow;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.nhs.web.PortalPrefsContracts.Preferences;
import group.aitools.nhs.platform.nhs.web.PortalPrefsContracts.RoutingPreferenceRequest;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 负责门户Prefs相关的业务编排与领域规则处理。
 *
 * Persists the Nhs portal preferences for the current human user.
 *
 * <p>Preferences are deliberately kept in Redis because they are UI state,
 * not platform business data.  A missing or unhealthy Redis dependency is
 * surfaced as HTTP 503 by the compatibility controller; silently returning
 * defaults would make user changes appear to be lost.</p>
 */
@Service
public class PortalPrefsApplicationService {

    public static final String KEY_PREFIX = "agent:portal_prefs:";
    private static final int MAX_PINNED_GROUPS = 50;
    private static final int MAX_CARD_ORDER = 200;
    private static final int MAX_QUESTION_CLICKS = 500;

    private final CurrentPrincipalProvider principalProvider;
    private final ObjectProvider<RedissonClient> redisProvider;
    private final JsonMapper jsonMapper;
    private final EmbedChatMapper embedChatMapper;
    private final AuthorizationEnforcer authorizationEnforcer;

    @Autowired
    public PortalPrefsApplicationService(
        CurrentPrincipalProvider principalProvider,
        ObjectProvider<RedissonClient> redisProvider,
        JsonMapper jsonMapper,
        EmbedChatMapper embedChatMapper,
        AuthorizationEnforcer authorizationEnforcer
    ) {
        this.principalProvider = principalProvider;
        this.redisProvider = redisProvider;
        this.jsonMapper = jsonMapper;
        this.embedChatMapper = embedChatMapper;
        this.authorizationEnforcer = authorizationEnforcer;
    }

    /**
 * 创建 {@code PortalPrefsApplicationService} 实例并初始化所需依赖。
 * Compatibility constructor for focused tests that do not exercise Agent validation. */
    public PortalPrefsApplicationService(
        CurrentPrincipalProvider principalProvider,
        ObjectProvider<RedissonClient> redisProvider,
        JsonMapper jsonMapper
    ) {
        this(principalProvider, redisProvider, jsonMapper, null, null);
    }

    /**
     * 获取{@code get}。
     *
     * @return 处理结果
     */
    public Preferences get() {
        String raw = readRaw();
        if (raw == null || raw.isBlank()) {
            return Preferences.empty();
        }
        try {
            return normalize(jsonMapper.readValue(raw, Preferences.class));
        } catch (RuntimeException ignored) {
            // A stale or hand-edited value must not break the portal.  Redis
            // itself was reachable, so returning a clean empty preference is
            // preferable to misreporting the dependency as unavailable.
            return Preferences.empty();
        }
    }

    /**
     * 更新{@code update}。
     *
     * @param requested {@code requested}参数
     * @return 处理结果
     */
    public Preferences update(Preferences requested) {
        Preferences normalized = normalize(requested);
        Preferences existing = get();
        Preferences merged = new Preferences(
            normalized.pinnedGroupIds(), normalized.cardOrder(), normalized.expandedGroupIds(),
            normalized.questionClicks(), normalized.pinnedKbDatasetIds(), normalized.markdownTheme(),
            existing.routingMode(), existing.expertAgentId(), existing.routingConfigured()
        );
        writeRaw(merged);
        return merged;
    }

    /**
     * 更新{@code MarkdownTheme}。
     *
     * @param theme {@code theme}参数
     * @return 处理结果
     */
    public String updateMarkdownTheme(String theme) {
        Preferences existing = get();
        Preferences updated = new Preferences(
            existing.pinnedGroupIds(), existing.cardOrder(), existing.expandedGroupIds(),
            existing.questionClicks(), existing.pinnedKbDatasetIds(), normalizeTheme(theme),
            existing.routingMode(), existing.expertAgentId(), existing.routingConfigured()
        );
        writeRaw(updated);
        return updated.markdownTheme();
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Preferences normalize(Preferences value) {
        if (value == null) {
            return Preferences.empty();
        }
        return new Preferences(
            cleanList(value.pinnedGroupIds(), MAX_PINNED_GROUPS),
            cleanList(value.cardOrder(), MAX_CARD_ORDER),
            cleanList(value.expandedGroupIds(), Integer.MAX_VALUE),
            cleanQuestionClicks(value.questionClicks()),
            cleanList(value.pinnedKbDatasetIds(), Integer.MAX_VALUE),
            normalizeTheme(value.markdownTheme()), value.routingMode(), value.expertAgentId(),
            value.routingConfigured()
        );
    }

    /**
 * 更新{@code Routing}。
 * Validates and persists the user's default routing choice. */
    public Preferences updateRouting(RoutingPreferenceRequest request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String mode = request == null || request.routingMode() == null
            ? "auto" : request.routingMode().strip().toLowerCase();
        if (!"auto".equals(mode) && !"expert".equals(mode)) {
            throw new ServiceException("路由模式仅支持 auto 或 expert", 422);
        }
        String agentId = request == null || request.expertAgentId() == null
            ? "" : request.expertAgentId().strip();
        if ("expert".equals(mode)) {
            if (!agentId.matches("[1-9][0-9]{0,18}")) {
                throw new ServiceException("默认智能体标识无效", HttpStatus.BAD_REQUEST);
            }
            if (embedChatMapper == null || authorizationEnforcer == null) {
                throw new ServiceException("路由智能体校验能力未启用", 503);
            }
            EmbedAgentRuntimeRow runtime = embedChatMapper.selectPublishedAgentRuntime(Long.valueOf(agentId));
            if (runtime == null || !"active".equals(runtime.getAgentStatus())
                || runtime.getPublishedAt() == null
                || !"published".equals(runtime.getVersionStatus())) {
                throw new ServiceException("智能体不存在、已停用或尚未发布", HttpStatus.NOT_FOUND);
            }
            authorizationEnforcer.requireAllowed(
                principalProvider.currentPrincipal(),
                new PermissionContext(
                    "agent_version", runtime.getAgentVersionId(), runtime.getAgentKey(), "use",
                    ResourceState.ACTIVE, true, Set.of(), null
                )
            );
        } else {
            agentId = "";
        }
        Preferences existing = get();
        Preferences updated = new Preferences(
            existing.pinnedGroupIds(), existing.cardOrder(), existing.expandedGroupIds(),
            existing.questionClicks(), existing.pinnedKbDatasetIds(), existing.markdownTheme(),
            mode, agentId, true
        );
        writeRaw(updated);
        return updated;
    }

    /**
     * 处理{@code cleanList}并返回对应结果。
     *
     * @param values {@code values}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    private List<String> cleanList(List<String> values, int limit) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String cleaned = value.strip();
            if (!cleaned.isEmpty()) {
                seen.add(cleaned);
                if (seen.size() >= limit) {
                    break;
                }
            }
        }
        return List.copyOf(seen);
    }

    /**
     * 处理clean追问Clicks并返回对应结果。
     *
     * @param values {@code values}参数
     * @return 处理结果
     */
    private Map<String, Integer> cleanQuestionClicks(Map<String, Integer> values) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().strip();
            Integer count = entry.getValue();
            if (!key.isEmpty() && count != null && count > 0) {
                cleaned.put(key, count);
                if (cleaned.size() >= MAX_QUESTION_CLICKS) {
                    break;
                }
            }
        }
        return Map.copyOf(cleaned);
    }

    /**
     * 处理{@code normalizeTheme}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeTheme(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * 处理{@code readRaw}并返回对应结果。
     *
     * @return 处理结果
     */
    private String readRaw() {
        RBucket<String> bucket = bucket();
        try {
            return bucket.get();
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    /**
     * 处理{@code writeRaw}相关逻辑。
     *
     * @param value {@code value}参数
     */
    private void writeRaw(Preferences value) {
        final String payload;
        try {
            payload = jsonMapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            throw new ServiceException("偏好设置序列化失败", HttpStatus.ERROR);
        }
        try {
            bucket().set(payload);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    /**
     * 处理{@code bucket}并返回对应结果。
     *
     * @return 处理结果
     */
    private RBucket<String> bucket() {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (principal == null || !principal.isHuman()) {
            throw new ServiceException("仅支持人类用户保存门户偏好", HttpStatus.FORBIDDEN);
        }
        final RedissonClient client;
        try {
            client = redisProvider.getIfAvailable();
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        if (client == null) {
            throw unavailable(null);
        }
        try {
            return client.getBucket(KEY_PREFIX + principal.id());
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    /**
     * 处理{@code unavailable}并返回对应结果。
     *
     * @param cause {@code cause}参数
     * @return 处理结果
     */
    private ServiceException unavailable(Throwable cause) {
        ServiceException exception = new ServiceException("Redis 服务不可用", 503);
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }
}
