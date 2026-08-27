package group.aitools.nhs.platform.iam.domain;

import java.util.List;
import java.util.Objects;

/**
 * 封装权限快照相关的不可变数据。
 * Immutable effective permission snapshot for one decision. */
public record PermissionSnapshot(String version, List<PermissionRule> rules) {

    public PermissionSnapshot {
        version = version == null ? "unversioned" : version;
        rules = List.copyOf(Objects.requireNonNull(rules, "rules must not be null"));
    }

    /**
     * 处理{@code empty}并返回对应结果。
     *
     * @return 处理结果
     */
    public static PermissionSnapshot empty() {
        return new PermissionSnapshot("empty", List.of());
    }
}
