package group.aitools.nhs.platform.skill.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.skill.domain.AgentSkillDependencyInstall;

import java.time.LocalDateTime;

/**
 * 获取{@code select}。
 *
 * 定义技能DependencyInstall相关的数据访问契约。
 * Persistence for explicit, versioned Skill dependency installation attempts. */
public interface SkillDependencyInstallMapper {

    @Select("""
        SELECT id, skill_id, version_id, dependency_hash, status, attempt_no,
               requested_by, requested_at, completed_at, install_root, message
        FROM agent_skill_dependency_install
        WHERE version_id = #{versionId} AND dependency_hash = #{dependencyHash}
        """)
    AgentSkillDependencyInstall select(
        @Param("versionId") Long versionId,
        @Param("dependencyHash") String dependencyHash
    );

    /**
     * 创建并保存{@code insert}。
     *
     * @param install {@code install}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_skill_dependency_install (
            id, skill_id, version_id, dependency_hash, status, attempt_no,
            requested_by, requested_at, install_root, message
        ) VALUES (
            #{id}, #{skillId}, #{versionId}, #{dependencyHash}, #{status},
            #{attemptNo}, #{requestedBy}, #{requestedAt}, #{installRoot}, #{message}
        )
        ON CONFLICT (version_id, dependency_hash) DO NOTHING
        """)
    int insert(AgentSkillDependencyInstall install);

    /**
     * 更新{@code update}。
     *
     * @param versionId 资源标识
     * @param dependencyHash {@code dependencyHash}参数
     * @param status 目标状态
     * @param attemptNo {@code attemptNo}参数
     * @param requestedBy {@code requestedBy}参数
     * @param requestedAt {@code requestedAt}参数
     * @param completedAt {@code completedAt}参数
     * @param installRoot {@code installRoot}参数
     * @param message 待处理内容
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_skill_dependency_install
        SET status = #{status}, attempt_no = #{attemptNo}, requested_by = #{requestedBy},
            requested_at = #{requestedAt}, completed_at = #{completedAt},
            install_root = #{installRoot}, message = #{message}
        WHERE version_id = #{versionId} AND dependency_hash = #{dependencyHash}
        """)
    int update(
        @Param("versionId") Long versionId,
        @Param("dependencyHash") String dependencyHash,
        @Param("status") String status,
        @Param("attemptNo") Integer attemptNo,
        @Param("requestedBy") Long requestedBy,
        @Param("requestedAt") LocalDateTime requestedAt,
        @Param("completedAt") LocalDateTime completedAt,
        @Param("installRoot") String installRoot,
        @Param("message") String message
    );
}
