package group.aitools.nhs.platform.agent.service;

import group.aitools.nhs.platform.agent.domain.AgentDefinition;
import group.aitools.nhs.platform.agent.domain.AgentDefinitionVersion;
import group.aitools.nhs.platform.agent.mapper.AgentDefinitionMapper;
import group.aitools.nhs.platform.agent.mapper.AgentDefinitionVersionMapper;
import group.aitools.nhs.platform.agent.web.WelcomeCardView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.nhs.portal.chatbi.PortalChatBIModelGateway;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import group.aitools.nhs.common.redis.utils.RedisUtils;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 负责智能体WelcomeCard相关的业务编排与领域规则处理。
 * Resolves Nhs-compatible welcome cards from the active immutable Agent version. */
@Service
public class AgentWelcomeCardService {

    private static final Set<String> ICONS = Set.of(
        "chart", "knowledge", "workspace", "report", "alert", "chat"
    );
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String CACHE_PREFIX = "agent:welcome_cards:v1:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final AgentDefinitionMapper definitionMapper;
    private final AgentDefinitionVersionMapper versionMapper;
    private final JsonMapper jsonMapper;
    private final PortalChatBIModelGateway modelGateway;

    /**
     * 创建 {@code AgentWelcomeCardService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param definitionMapper 定义Mapper参数
     * @param versionMapper 版本Mapper参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param modelGateway 模型Gateway参数
     */
    public AgentWelcomeCardService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        AgentDefinitionMapper definitionMapper,
        AgentDefinitionVersionMapper versionMapper,
        JsonMapper jsonMapper,
        PortalChatBIModelGateway modelGateway
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.definitionMapper = definitionMapper;
        this.versionMapper = versionMapper;
        this.jsonMapper = jsonMapper;
        this.modelGateway = modelGateway;
    }

    /**
 * 查询{@code list}列表。
 * Returns cards for the portal, enforcing the same permission as Agent use. */
    public List<WelcomeCardView> list(Long agentId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentDefinition definition = requireActiveDefinition(agentId);
        Long versionId = definition.getPublishedVersionId();
        authorizationEnforcer.requireAllowed(
            principal,
            new PermissionContext(
                "agent_version", versionId, definition.getAgentKey(), "use",
                ResourceState.ACTIVE, true, Set.of(), null
            )
        );
        AgentDefinitionVersion version = versionMapper.selectVersion(agentId, versionId);
        if (version == null) {
            throw new ServiceException("Agent 已发布版本不存在", HttpStatus.NOT_FOUND);
        }
        return resolve(definition, version);
    }

    /**
 * 查询For版本列表。
 * Returns cards for an already authenticated Embed version capability. */
    public List<WelcomeCardView> listForVersion(Long versionId) {
        AgentDefinitionVersion version = versionMapper.selectPublishedVersionById(versionId);
        if (version == null) {
            return List.of();
        }
        AgentDefinition definition = definitionMapper.selectDefinitionById(version.getAgentId());
        if (definition == null || !"active".equals(definition.getStatus())) {
            return List.of();
        }
        return resolve(definition, version);
    }

    /**
     * 校验Active定义，并在条件不满足时终止处理。
     *
     * @param agentId 资源标识
     * @return 处理结果
     */
    private AgentDefinition requireActiveDefinition(Long agentId) {
        AgentDefinition definition = definitionMapper.selectDefinitionById(agentId);
        if (definition == null) {
            throw new ServiceException("Agent 不存在", HttpStatus.NOT_FOUND);
        }
        if (!"active".equals(definition.getStatus()) || definition.getPublishedVersionId() == null) {
            throw new ServiceException("Agent 没有可用的已发布版本", HttpStatus.NOT_FOUND);
        }
        return definition;
    }

    /**
     * 获取{@code resolve}。
     *
     * @param definition 定义参数
     * @param version 版本参数
     * @return 符合条件的数据集合
     */
    private List<WelcomeCardView> resolve(AgentDefinition definition, AgentDefinitionVersion version) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Map<String, Object> config = parseMap(version.getWelcomeConfigJson());
        boolean legacy = config.containsKey("suggestions") || config.containsKey("showSuggestions");
        boolean canonical = config.containsKey("enabled") || config.containsKey("mode")
            || config.containsKey("cards") || config.containsKey("generation_requirement");
        boolean enabled = booleanValue(config.get("enabled"), canonical ? false
            : legacy ? booleanValue(config.get("showSuggestions"), true) : false);
        if (!enabled) {
            return List.of();
        }

        String mode = text(config.get("mode"), "manual").toLowerCase(Locale.ROOT);
        if (!Set.of("manual", "ai").contains(mode)) {
            return List.of();
        }
        if (!"ai".equals(mode)) {
            List<WelcomeCardView> configured = cards(config.get("cards"));
            if (!configured.isEmpty()) {
                return configured;
            }
            return legacyCards(config.get("suggestions"), config.get("message"));
        }

        String requirement = text(config.get("generation_requirement"),
            text(config.get("generationRequirement"), ""));
        String cacheKey = CACHE_PREFIX + ContentHashing.sha256(
            definition.getId() + "|" + version.getId() + "|" + requirement
        );
        List<WelcomeCardView> cached = readCache(cacheKey);
        if (!cached.isEmpty()) {
            return cached;
        }
        List<WelcomeCardView> generated = generate(definition, version, requirement);
        if (!generated.isEmpty()) {
            writeCache(cacheKey, generated);
        }
        return generated;
    }

    /**
     * 处理{@code generate}并返回对应结果。
     *
     * @param definition 定义参数
     * @param version 版本参数
     * @param requirement {@code requirement}参数
     * @return 符合条件的数据集合
     */
    private List<WelcomeCardView> generate(
        AgentDefinition definition,
        AgentDefinitionVersion version,
        String requirement
    ) {
        String system = "你是企业智能体欢迎页文案设计师。只输出 JSON，不要 Markdown 或解释。";
        String user = "请为以下智能体生成恰好 3 张可点击欢迎卡片，返回 "
            + "{\"cards\":[{\"icon\":\"chart|knowledge|workspace|report|alert|chat\","
            + "\"title\":\"不超过16字\",\"subtitle\":\"不超过40字\","
            + "\"prompt\":\"用户点击后发送的完整问题\"}]}。\n"
            + "名称：" + bounded(definition.getName(), 128) + "\n"
            + "描述：" + bounded(definition.getDescription(), 500) + "\n"
            + "系统提示词：" + bounded(version.getSystemPrompt(), 1200) + "\n"
            + "额外要求：" + bounded(requirement, 500);
        try {
            String raw = modelGateway.complete(system, user).content();
            String json = stripFence(raw);
            Map<String, Object> payload = jsonMapper.readValue(json, MAP_TYPE);
            return cards(payload.get("cards"));
        } catch (RuntimeException ignored) {
            // A welcome recommendation must never make an otherwise usable Agent unavailable.
            return List.of();
        }
    }

    /**
     * 处理{@code cards}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @return 符合条件的数据集合
     */
    private List<WelcomeCardView> cards(Object raw) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        List<WelcomeCardView> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String icon = text(map.get("icon"), "chat").toLowerCase(Locale.ROOT);
            if (!ICONS.contains(icon)) {
                icon = "chat";
            }
            String title = bounded(text(map.get("title"), ""), 40);
            String subtitle = bounded(text(map.get("subtitle"), ""), 100);
            String prompt = bounded(text(map.get("prompt"), ""), 300);
            if (!title.isBlank() && !subtitle.isBlank() && !prompt.isBlank()) {
                result.add(new WelcomeCardView(icon, title, subtitle, prompt));
            }
            if (result.size() == 3) {
                break;
            }
        }
        return result.size() == 3 ? List.copyOf(result) : List.of();
    }

    /**
     * 处理{@code legacyCards}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @param legacyMessage 待处理内容
     * @return 符合条件的数据集合
     */
    private List<WelcomeCardView> legacyCards(Object raw, Object legacyMessage) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        String subtitle = bounded(text(legacyMessage, "点击开始提问"), 100);
        if (subtitle.isBlank()) {
            subtitle = "点击开始提问";
        }
        List<WelcomeCardView> result = new ArrayList<>();
        for (Object value : values) {
            String prompt = bounded(text(value, ""), 300);
            if (prompt.isBlank()) {
                continue;
            }
            String title = prompt.length() > 16 ? prompt.substring(0, 16) + "…" : prompt;
            result.add(new WelcomeCardView("chat", title, subtitle, prompt));
            if (result.size() == 3) {
                break;
            }
        }
        return List.copyOf(result);
    }

    /**
     * 处理read缓存并返回对应结果。
     *
     * @param key {@code key}参数
     * @return 符合条件的数据集合
     */
    private List<WelcomeCardView> readCache(String key) {
        try {
            String value = RedisUtils.getCacheObject(key);
            if (value == null || value.isBlank()) {
                return List.of();
            }
            Map<String, Object> payload = jsonMapper.readValue(value, MAP_TYPE);
            return cards(payload.get("cards"));
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    /**
     * 处理write缓存相关逻辑。
     *
     * @param key {@code key}参数
     * @param cards {@code cards}参数
     */
    private void writeCache(String key, List<WelcomeCardView> cards) {
        try {
            RedisUtils.setCacheObject(
                key, jsonMapper.writeValueAsString(Map.of("cards", cards)), CACHE_TTL
            );
        } catch (RuntimeException ignored) {
            // Redis is an optimization; the active version remains the source of truth.
        }
    }

    /**
     * 处理{@code parseMap}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> parseMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return jsonMapper.readValue(value, MAP_TYPE);
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    /**
     * 处理{@code booleanValue}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param fallback {@code fallback}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean booleanValue(Object value, boolean fallback) {
        return value instanceof Boolean bool ? bool : fallback;
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param fallback {@code fallback}参数
     * @return 处理结果
     */
    private String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value).strip();
    }

    /**
     * 处理{@code bounded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param max {@code max}参数
     * @return 处理结果
     */
    private String bounded(String value, int max) {
        String normalized = value == null ? "" : value.strip().replaceAll("[\\r\\n]+", " ");
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    /**
     * 处理{@code stripFence}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String stripFence(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.startsWith("```") && normalized.endsWith("```")) {
            int newline = normalized.indexOf('\n');
            return newline > 0 ? normalized.substring(newline + 1, normalized.length() - 3).strip() : "";
        }
        return normalized;
    }
}
