package group.aitools.nhs.platform.connector.service;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.skill.service.SkillCatalogService;
import group.aitools.nhs.platform.skill.web.CreateSkillRequest;
import group.aitools.nhs.platform.skill.web.SkillView;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 负责运行时技能Builtin相关的业务编排与领域规则处理。
 * Creates versioned Skills from a frozen runtime identity through the normal Skill domain service. */
@Service
public class RuntimeSkillBuiltinService {

    private final SkillCatalogService skillCatalogService;

    public RuntimeSkillBuiltinService(SkillCatalogService skillCatalogService) {
        this.skillCatalogService = skillCatalogService;
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param principal 当前操作主体
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    public Map<String, Object> create(CurrentPrincipal principal, Map<String, Object> arguments) {
        // 以下流程根据当前类型或状态选择处理分支，并保证每种分支返回明确结果。
        if (principal == null || !principal.isHuman()) {
            throw new ServiceException("服务账号不能创建个人技能", HttpStatus.FORBIDDEN);
        }
        String skillKey = requiredText(arguments, 128, "技能标识", "skill_id", "skillId", "skill_key", "skillKey", "id")
            .toLowerCase(Locale.ROOT);
        String name = requiredText(arguments, 128, "技能名称", "name");
        String description = optionalText(arguments, 12_000, "description");
        String content = requiredText(
            arguments, 32_768, "SKILL.md 内容", "skill_md_content", "skillMdContent", "content", "instructions"
        );
        String rawScope = optionalText(arguments, 32, "scope", "scope_type", "scopeType");
        String scope = rawScope == null ? "personal" : rawScope.toLowerCase(Locale.ROOT);
        String scopeType;
        Long scopeId;
        switch (scope) {
            case "personal", "user" -> {
                scopeType = "user";
                scopeId = principal.id();
            }
            case "global", "system" -> {
                if (!principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
                    throw new ServiceException("只有平台管理员可以创建全局技能", HttpStatus.FORBIDDEN);
                }
                scopeType = "system";
                scopeId = null;
            }
            case "project" -> {
                scopeType = "project";
                scopeId = positiveLong(first(arguments, "project_id", "projectId", "scope_id", "scopeId"), "项目ID");
            }
            default -> throw new ServiceException("技能作用域无效", HttpStatus.BAD_REQUEST);
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("summary", description == null ? name : description);
        Object tags = first(arguments, "tags");
        if (tags != null) {
            manifest.put("tags", tags);
        }
        Map<String, Object> runtimeRequirements = Map.of("workspaceAccess", "read_write");
        SkillView created = skillCatalogService.runAsRuntimePrincipal(principal, () ->
            skillCatalogService.create(new CreateSkillRequest(
                skillKey, name, description, scopeType, scopeId, content,
                Map.copyOf(manifest), runtimeRequirements
            ))
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill_id", created.skillKey());
        result.put("resource_id", created.id());
        result.put("scope", "system".equals(created.scopeType()) ? "global" : created.scopeType());
        result.put("name", created.name());
        result.put("status", created.status());
        result.put("revision", created.revision());
        result.put("marker", "NHS_SKILL_CREATED:" + created.skillKey());
        return result;
    }

    /**
     * 校验{@code dText}，并在条件不满足时终止处理。
     *
     * @param arguments {@code arguments}参数
     * @param maximumLength {@code maximumLength}参数
     * @param label {@code label}参数
     * @param names 名称
     * @return 处理结果
     */
    private String requiredText(
        Map<String, Object> arguments,
        int maximumLength,
        String label,
        String... names
    ) {
        String value = optionalText(arguments, maximumLength, names);
        if (value == null) {
            throw new ServiceException(label + "不能为空", HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    /**
     * 处理{@code optionalText}并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @param maximumLength {@code maximumLength}参数
     * @param names 名称
     * @return 处理结果
     */
    private String optionalText(Map<String, Object> arguments, int maximumLength, String... names) {
        Object value = first(arguments, names);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank() || text.indexOf('\0') >= 0) {
            throw new ServiceException("文本参数无效", HttpStatus.BAD_REQUEST);
        }
        String normalized = text.strip();
        if (normalized.length() > maximumLength) {
            throw new ServiceException("文本参数超过长度限制", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 处理{@code first}并返回对应结果。
     *
     * @param arguments {@code arguments}参数
     * @param names 名称
     * @return 处理结果
     */
    private Object first(Map<String, Object> arguments, String... names) {
        if (arguments == null) {
            return null;
        }
        for (String name : names) {
            if (arguments.containsKey(name)) {
                return arguments.get(name);
            }
        }
        return null;
    }

    /**
     * 处理{@code positiveLong}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Long positiveLong(Object value, String label) {
        if (!(value instanceof Number number) || number.longValue() <= 0
            || number.doubleValue() != number.longValue()) {
            throw new ServiceException(label + "无效", HttpStatus.BAD_REQUEST);
        }
        return number.longValue();
    }
}
