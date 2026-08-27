package group.aitools.nhs.platform.skill.web;

import group.aitools.nhs.platform.skill.domain.AgentSkillDependencyInstall;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 封装技能DependencyInstall相关的不可变数据。
 * Dependency declarations and the latest explicit installation status. */
public record SkillDependencyInstallView(
    Long skillId,
    Long versionId,
    Integer versionNo,
    Map<String, List<String>> dependencies,
    String dependencyHash,
    String status,
    Integer attemptNo,
    LocalDateTime requestedAt,
    LocalDateTime completedAt,
    String installRoot,
    String message,
    boolean installerEnabled
) {

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param versionNo 版本No参数
     * @param dependencies {@code dependencies}参数
     * @param dependencyHash {@code dependencyHash}参数
     * @param state {@code state}参数
     * @param installerEnabled {@code installerEnabled}参数
     * @return 处理结果
     */
    public static SkillDependencyInstallView from(
        Long skillId,
        Long versionId,
        Integer versionNo,
        Map<String, List<String>> dependencies,
        String dependencyHash,
        AgentSkillDependencyInstall state,
        boolean installerEnabled
    ) {
        return new SkillDependencyInstallView(
            skillId, versionId, versionNo, dependencies, dependencyHash,
            state == null ? "not_installed" : state.getStatus(),
            state == null ? 0 : state.getAttemptNo(),
            state == null ? null : state.getRequestedAt(),
            state == null ? null : state.getCompletedAt(),
            state == null ? null : state.getInstallRoot(),
            state == null ? null : state.getMessage(), installerEnabled
        );
    }
}
