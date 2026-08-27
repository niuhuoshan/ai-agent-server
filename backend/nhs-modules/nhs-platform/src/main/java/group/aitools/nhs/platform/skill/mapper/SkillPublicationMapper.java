package group.aitools.nhs.platform.skill.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.skill.domain.AgentSkillPublication;
import group.aitools.nhs.platform.skill.domain.AgentSkillPublicationFile;
import group.aitools.nhs.platform.skill.domain.AgentSkillPublicationVersion;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定义技能Publication相关的数据访问契约。
 * Persistence boundary for immutable personal Skill publication requests. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface SkillPublicationMapper {

    String PUBLICATION_COLUMNS = """
        id, source_skill_id, source_owner_id, system_skill_id,
        current_public_version_no, status, created_at, updated_at
        """;

    String VERSION_COLUMNS = """
        id, publication_id, version_no, source_skill_version_id,
        source_skill_key_snapshot, name_snapshot, description_snapshot, content_snapshot,
        manifest_json::text AS manifest_json,
        runtime_requirements_json::text AS runtime_requirements_json,
        status, content_hash, file_bundle_hash, file_count, total_size_bytes,
        submitted_by, submitted_at, reviewed_by, reviewed_at, review_comment,
        withdrawn_by, withdrawn_at, published_system_skill_id, published_system_version_id
        """;

    String FILE_COLUMNS = """
        id, publication_version_id, path, file_kind, content, content_bytes,
        content_encoding, content_hash, size_bytes
        """;

    /**
     * 获取By数据源技能。
     *
     * @param sourceSkillId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT
        """ + PUBLICATION_COLUMNS + """
        FROM agent_skill_publication
        WHERE source_skill_id = #{sourceSkillId}
        """)
    AgentSkillPublication selectBySourceSkill(@Param("sourceSkillId") Long sourceSkillId);

    /**
     * 获取{@code Publication}。
     *
     * @param publicationId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT
        """ + PUBLICATION_COLUMNS + """
        FROM agent_skill_publication
        WHERE id = #{publicationId}
        """)
    AgentSkillPublication selectPublication(@Param("publicationId") Long publicationId);

    /**
     * 处理lockBy数据源技能并返回对应结果。
     *
     * @param sourceSkillId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id FROM agent_skill_publication
        WHERE source_skill_id = #{sourceSkillId}
        FOR UPDATE
        """)
    Long lockBySourceSkill(@Param("sourceSkillId") Long sourceSkillId);

    /**
     * 创建并保存{@code Publication}。
     *
     * @param publication {@code publication}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_skill_publication (
            id, source_skill_id, source_owner_id, system_skill_id,
            current_public_version_no, status, created_at, updated_at
        ) VALUES (
            #{id}, #{sourceSkillId}, #{sourceOwnerId}, #{systemSkillId},
            #{currentPublicVersionNo}, #{status}, #{createdAt}, #{updatedAt}
        )
        """)
    int insertPublication(AgentSkillPublication publication);

    /**
     * 更新{@code Status}。
     *
     * @param publicationId 资源标识
     * @param status 目标状态
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_skill_publication
        SET status = #{status}, updated_at = #{now}
        WHERE id = #{publicationId}
        """)
    int updateStatus(
        @Param("publicationId") Long publicationId,
        @Param("status") String status,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code markPublished}并返回对应结果。
     *
     * @param publicationId 资源标识
     * @param systemSkillId 资源标识
     * @param versionNo 版本No参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_skill_publication
        SET system_skill_id = #{systemSkillId}, current_public_version_no = #{versionNo},
            status = 'published', updated_at = #{now}
        WHERE id = #{publicationId}
        """)
    int markPublished(
        @Param("publicationId") Long publicationId,
        @Param("systemSkillId") Long systemSkillId,
        @Param("versionNo") Integer versionNo,
        @Param("now") LocalDateTime now
    );

    /**
     * 获取Pending版本。
     *
     * @param publicationId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT
        """ + VERSION_COLUMNS + """
        FROM agent_skill_publication_version
        WHERE publication_id = #{publicationId} AND status = 'pending'
        LIMIT 1
        """)
    AgentSkillPublicationVersion selectPendingVersion(@Param("publicationId") Long publicationId);

    /**
     * 获取Latest版本。
     *
     * @param publicationId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT
        """ + VERSION_COLUMNS + """
        FROM agent_skill_publication_version
        WHERE publication_id = #{publicationId}
        ORDER BY version_no DESC
        LIMIT 1
        """)
    AgentSkillPublicationVersion selectLatestVersion(@Param("publicationId") Long publicationId);

    /**
     * 获取版本。
     *
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT
        """ + VERSION_COLUMNS + """
        FROM agent_skill_publication_version
        WHERE id = #{versionId}
        """)
    AgentSkillPublicationVersion selectVersion(@Param("versionId") Long versionId);

    /**
     * 处理lock版本并返回对应结果。
     *
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id FROM agent_skill_publication_version
        WHERE id = #{versionId}
        FOR UPDATE
        """)
    Long lockVersion(@Param("versionId") Long versionId);

    /**
     * 获取{@code PendingVersions}。
     *
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT
        """ + VERSION_COLUMNS + """
        FROM agent_skill_publication_version
        WHERE status = 'pending'
        ORDER BY submitted_at ASC, id ASC
        LIMIT #{limit}
        """)
    List<AgentSkillPublicationVersion> selectPendingVersions(@Param("limit") int limit);

    /**
     * 获取Next版本No。
     *
     * @param publicationId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT COALESCE(MAX(version_no), 0) + 1
        FROM agent_skill_publication_version
        WHERE publication_id = #{publicationId}
        """)
    int selectNextVersionNo(@Param("publicationId") Long publicationId);

    /**
     * 创建并保存版本。
     *
     * @param version 版本参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_skill_publication_version (
            id, publication_id, version_no, source_skill_version_id,
            source_skill_key_snapshot, name_snapshot, description_snapshot, content_snapshot,
            manifest_json, runtime_requirements_json, status, content_hash, file_bundle_hash,
            file_count, total_size_bytes, submitted_by, submitted_at
        ) VALUES (
            #{id}, #{publicationId}, #{versionNo}, #{sourceSkillVersionId},
            #{sourceSkillKeySnapshot}, #{nameSnapshot}, #{descriptionSnapshot}, #{contentSnapshot},
            CAST(#{manifestJson} AS jsonb), CAST(#{runtimeRequirementsJson} AS jsonb),
            #{status}, #{contentHash}, #{fileBundleHash}, #{fileCount}, #{totalSizeBytes},
            #{submittedBy}, #{submittedAt}
        )
        """)
    int insertVersion(AgentSkillPublicationVersion version);

    /**
     * 创建并保存文件。
     *
     * @param file 文件参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_skill_publication_file (
            id, publication_version_id, path, file_kind, content, content_bytes,
            content_encoding, content_hash, size_bytes
        ) VALUES (
            #{id}, #{publicationVersionId}, #{path}, #{fileKind}, #{content}, #{contentBytes},
            #{contentEncoding}, #{contentHash}, #{sizeBytes}
        )
        """)
    int insertFile(AgentSkillPublicationFile file);

    /**
     * 获取{@code Files}。
     *
     * @param versionId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT
        """ + FILE_COLUMNS + """
        FROM agent_skill_publication_file
        WHERE publication_version_id = #{versionId}
        ORDER BY path ASC
        """)
    List<AgentSkillPublicationFile> selectFiles(@Param("versionId") Long versionId);

    /**
     * 处理withdraw版本并返回对应结果。
     *
     * @param versionId 资源标识
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_skill_publication_version
        SET status = 'withdrawn', withdrawn_by = #{actorId}, withdrawn_at = #{now}
        WHERE id = #{versionId} AND status = 'pending'
        """)
    int withdrawVersion(
        @Param("versionId") Long versionId,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理reject版本并返回对应结果。
     *
     * @param versionId 资源标识
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @param comment {@code comment}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_skill_publication_version
        SET status = 'rejected', reviewed_by = #{actorId}, reviewed_at = #{now},
            review_comment = #{comment}
        WHERE id = #{versionId} AND status = 'pending'
        """)
    int rejectVersion(
        @Param("versionId") Long versionId,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now,
        @Param("comment") String comment
    );

    /**
     * 处理{@code supersedeApproved}并返回对应结果。
     *
     * @param publicationId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_skill_publication_version
        SET status = 'superseded'
        WHERE publication_id = #{publicationId} AND id <> #{versionId} AND status = 'approved'
        """)
    int supersedeApproved(
        @Param("publicationId") Long publicationId,
        @Param("versionId") Long versionId
    );

    /**
     * 处理approve版本并返回对应结果。
     *
     * @param versionId 资源标识
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @param systemSkillId 资源标识
     * @param systemVersionId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_skill_publication_version
        SET status = 'approved', reviewed_by = #{actorId}, reviewed_at = #{now},
            published_system_skill_id = #{systemSkillId},
            published_system_version_id = #{systemVersionId}
        WHERE id = #{versionId} AND status = 'pending'
        """)
    int approveVersion(
        @Param("versionId") Long versionId,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now,
        @Param("systemSkillId") Long systemSkillId,
        @Param("systemVersionId") Long systemVersionId
    );
}
