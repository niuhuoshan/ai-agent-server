package group.aitools.nhs.platform.iam.service.impl;

import lombok.extern.slf4j.Slf4j;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
import group.aitools.nhs.platform.iam.domain.PermissionRule;
import group.aitools.nhs.platform.iam.domain.PermissionSnapshot;
import group.aitools.nhs.platform.iam.domain.PermissionSource;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.persistence.mapper.PermissionRuleQueryMapper;
import group.aitools.nhs.platform.iam.persistence.row.PermissionBindingRow;
import group.aitools.nhs.platform.iam.persistence.row.PermissionRuleRow;
import group.aitools.nhs.platform.iam.persistence.row.TaskAccessRuleRow;
import group.aitools.nhs.platform.iam.service.PermissionSnapshotResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 负责Database权限快照相关的转换、解析或处理逻辑。
 * Resolves published profiles, snapshots, overrides, temporary grants and task ACLs. */
@Slf4j
@Component
public final class DatabasePermissionSnapshotResolver implements PermissionSnapshotResolver {

    private final PermissionRuleQueryMapper queryMapper;
    private final JsonMapper jsonMapper;

    public DatabasePermissionSnapshotResolver(PermissionRuleQueryMapper queryMapper, JsonMapper jsonMapper) {
        this.queryMapper = Objects.requireNonNull(queryMapper, "queryMapper must not be null");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
    }

    /**
     * 获取{@code resolve}。
     *
     * @param principal 当前操作主体
     * @param context 待处理内容
     * @return 处理结果
     */
    @Override
    public PermissionSnapshot resolve(CurrentPrincipal principal, PermissionContext context) {
        List<PermissionRule> rules = new ArrayList<>();
        PermissionBindingRow binding = null;
        if (principal.type() == PrincipalType.HUMAN) {
            binding = queryMapper.selectActiveBinding(principal.id());
            rules.addAll(queryMapper.selectEffectiveRelationalRules(principal.id()).stream()
                .map(this::toRule)
                .toList());
        } else {
            rules.addAll(queryMapper.selectEffectiveServiceAccountRules(principal.id()).stream()
                .map(this::toRule)
                .toList());
        }

        if (binding != null && "snapshot".equals(binding.getBindingType())) {
            rules.addAll(parseSnapshot(binding, context));
        }
        rules.addAll(resolveAccessRules(principal, context));

        String version = principal.type() == PrincipalType.SERVICE_ACCOUNT
            ? "service-account:" + principal.id()
            : binding == null
            ? "unbound"
            : "binding:%d:profile:%s".formatted(binding.getId(), binding.getProfileVersion());
        return new PermissionSnapshot(version, rules);
    }

    /**
     * 获取{@code AccessRules}。
     *
     * @param principal 当前操作主体
     * @param context 待处理内容
     * @return 符合条件的数据集合
     */
    private List<PermissionRule> resolveAccessRules(CurrentPrincipal principal, PermissionContext context) {
        Long taskId = context.taskId();
        if (taskId == null && "task".equals(context.resourceType())) {
            taskId = context.resourceId();
        }
        if (taskId == null || !("task".equals(context.resourceType()) || "artifact".equals(context.resourceType()))) {
            return List.of();
        }

        Long artifactId = "artifact".equals(context.resourceType()) ? context.resourceId() : null;
        return queryMapper.selectActiveTaskAccessRules(taskId, artifactId, context.action()).stream()
            .filter(row -> matchesPrincipal(row, principal))
            .map(row -> new PermissionRule(
                context.resourceType(),
                context.resourceId(),
                context.resourceKey(),
                context.action(),
                parseEffect(row.getEffect()),
                PermissionSource.TASK_ACCESS_RULE,
                "task-access-rule:" + row.getId(),
                "explicit task or artifact access rule"
            ))
            .toList();
    }

    /**
     * 判断操作主体是否满足要求。
     *
     * @param row {@code row}参数
     * @param principal 当前操作主体
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean matchesPrincipal(TaskAccessRuleRow row, CurrentPrincipal principal) {
        return switch (row.getSubjectType()) {
            case "user" -> principal.type() == PrincipalType.HUMAN
                && principal.id().equals(row.getSubjectId());
            case "service_account" -> principal.type() == PrincipalType.SERVICE_ACCOUNT
                && principal.id().equals(row.getSubjectId());
            case "platform_role" -> principal.roles().stream()
                .anyMatch(role -> role.key().equals(row.getSubjectKey()));
            default -> false;
        };
    }

    /**
     * 将输入数据转换为{@code Rule}。
     *
     * @param row {@code row}参数
     * @return 处理结果
     */
    private PermissionRule toRule(PermissionRuleRow row) {
        return new PermissionRule(
            row.getResourceType(),
            row.getResourceId(),
            row.getResourceKey(),
            row.getAction(),
            parseEffect(row.getEffect()),
            parseSource(row.getSource()),
            row.getSourceReference(),
            row.getReason()
        );
    }

    /**
     * 处理parse快照并返回对应结果。
     *
     * @param binding {@code binding}参数
     * @param context 待处理内容
     * @return 符合条件的数据集合
     */
    private List<PermissionRule> parseSnapshot(PermissionBindingRow binding, PermissionContext context) {
        if (binding.getSnapshotJson() == null || binding.getSnapshotJson().isBlank()) {
            return List.of(failClosedSnapshotRule(binding, context, "snapshot payload is empty"));
        }
        try {
            PermissionSnapshotDocument document = jsonMapper.readValue(
                binding.getSnapshotJson(), PermissionSnapshotDocument.class
            );
            if (document == null || document.rules() == null) {
                return List.of(failClosedSnapshotRule(binding, context, "snapshot rules are missing"));
            }
            return document.rules().stream()
                .map(rule -> new PermissionRule(
                    rule.resourceType(),
                    rule.resourceId(),
                    rule.resourceKey(),
                    rule.action(),
                    parseEffect(rule.effect()),
                    PermissionSource.PROFILE,
                    "snapshot-binding:" + binding.getId(),
                    rule.reason()
                ))
                .toList();
        } catch (RuntimeException exception) {
            log.warn("Permission snapshot binding {} is invalid and was denied", binding.getId());
            return List.of(failClosedSnapshotRule(binding, context, "snapshot payload is invalid"));
        }
    }

    /**
     * 处理failClosed快照Rule并返回对应结果。
     *
     * @param binding {@code binding}参数
     * @param context 待处理内容
     * @param reason {@code reason}参数
     * @return 处理结果
     */
    private PermissionRule failClosedSnapshotRule(
        PermissionBindingRow binding,
        PermissionContext context,
        String reason
    ) {
        return new PermissionRule(
            context.resourceType(),
            context.resourceId(),
            context.resourceKey(),
            context.action(),
            PermissionEffect.DENY,
            PermissionSource.PROFILE,
            "snapshot-binding:" + binding.getId(),
            reason
        );
    }

    /**
     * 处理{@code parseEffect}并返回对应结果。
     *
     * @param effect {@code effect}参数
     * @return 处理结果
     */
    private PermissionEffect parseEffect(String effect) {
        if (effect == null) {
            return PermissionEffect.DENY;
        }
        try {
            return PermissionEffect.valueOf(effect.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return PermissionEffect.DENY;
        }
    }

    /**
     * 处理parse数据源并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    private PermissionSource parseSource(String source) {
        if (source == null) {
            return PermissionSource.DEFAULT_POLICY;
        }
        try {
            return PermissionSource.valueOf(source.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return PermissionSource.DEFAULT_POLICY;
        }
    }

    /**
     * 封装权限快照文档相关的不可变数据。
     */
    private record PermissionSnapshotDocument(String version, List<PermissionSnapshotRuleDocument> rules) {
    }

    /**
     * 封装权限快照Rule文档相关的不可变数据。
     */
    private record PermissionSnapshotRuleDocument(
        String resourceType,
        Long resourceId,
        String resourceKey,
        String action,
        String effect,
        String reason
    ) {
    }
}
