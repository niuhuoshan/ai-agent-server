package group.aitools.nhs.platform.project.web;

import group.aitools.nhs.platform.project.domain.AgentProjectMember;

import java.time.LocalDateTime;

/**
 * 封装项目Member相关的不可变数据。
 * Public project membership without inherited permission internals. */
public record ProjectMemberView(
    Long id,
    Long projectId,
    Long userId,
    String role,
    String status,
    LocalDateTime joinedAt
) {

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param member {@code member}参数
     * @return 处理结果
     */
    public static ProjectMemberView from(AgentProjectMember member) {
        return new ProjectMemberView(
            member.getId(), member.getProjectId(), member.getUserId(), member.getMemberRole(),
            member.getStatus(), member.getJoinedAt()
        );
    }
}
