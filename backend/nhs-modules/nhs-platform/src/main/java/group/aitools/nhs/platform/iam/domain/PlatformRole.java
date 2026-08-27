package group.aitools.nhs.platform.iam.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 定义平台角色相关的可选值。
 * Fixed, coarse-grained roles used by the agent platform. */
public enum PlatformRole {

    PLATFORM_ADMIN("platform_admin"),
    MEMBER("member"),
    APPROVAL_USER("approval_user"),
    SERVICE_ACCOUNT("service_account");

    private final String key;

    /**
     * 创建 {@code PlatformRole} 实例并初始化所需依赖。
     *
     * @param key {@code key}参数
     */
    PlatformRole(String key) {
        this.key = key;
    }

    /**
     * 处理{@code key}并返回对应结果。
     *
     * @return 处理结果
     */
    public String key() {
        return key;
    }

    /**
     * 处理{@code fromKey}并返回对应结果。
     *
     * @param key {@code key}参数
     * @return 可能为空的处理结果
     */
    public static Optional<PlatformRole> fromKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        String normalizedKey = key.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
            .filter(role -> role.key.equals(normalizedKey))
            .findFirst();
    }
}
