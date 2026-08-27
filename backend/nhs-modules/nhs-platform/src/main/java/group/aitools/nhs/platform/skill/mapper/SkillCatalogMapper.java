package group.aitools.nhs.platform.skill.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.skill.domain.AgentSkill;
import group.aitools.nhs.platform.skill.domain.AgentSkillVersion;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定义技能目录相关的数据访问契约。
 * Skill identities and immutable publication versions. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface SkillCatalogMapper {

    String SKILL_COLUMNS = """
        s.id, s.skill_key, s.name, s.description, s.scope_type, s.scope_id,
        s.owner_id, s.status, s.revision_no, s.create_by, s.create_time,
        s.update_by, s.update_time, s.del_flag, s.extra_json::text AS extra_json,
        pv.id AS published_version_id, pv.version_no AS published_version_no,
        pv.content_hash AS published_content_hash
        """;

    String VERSION_COLUMNS = """
        id, skill_id, version_no, content, content_hash, file_bundle_hash,
        manifest_json::text AS manifest_json,
        runtime_requirements_json::text AS runtime_requirements_json,
        status, published_at, created_by, created_at
        """;

    /**
     * 获取{@code VisibleSkills}。
     *
     * @param principalId 资源标识
     * @param platformAdmin 平台Admin参数
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param search {@code search}参数
     * @param includeInactive {@code includeInactive}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT
        """ + SKILL_COLUMNS + """
        FROM agent_skill s
        LEFT JOIN LATERAL (
          SELECT id, version_no, content_hash
          FROM agent_skill_version
          WHERE skill_id = s.id AND status = 'published'
          ORDER BY version_no DESC LIMIT 1
        ) pv ON TRUE
        WHERE s.del_flag = '0'
          AND (
            #{platformAdmin}
            OR (s.scope_type = 'user' AND s.owner_id = #{principalId})
            OR (s.scope_type = 'system' AND s.status = 'active')
            OR (s.scope_type = 'project' AND EXISTS (
              SELECT 1 FROM agent_project p
              LEFT JOIN agent_project_member pm
                ON pm.project_id = p.id AND pm.user_id = #{principalId} AND pm.status = 'active'
              WHERE p.id = s.scope_id AND p.del_flag = '0'
                AND (p.owner_id = #{principalId} OR pm.id IS NOT NULL)
            ))
          )
        <if test="scopeType != null and scopeType != ''">AND s.scope_type = #{scopeType}</if>
        <if test="scopeId != null">AND s.scope_id = #{scopeId}</if>
        <if test="!includeInactive">AND s.status = 'active' AND pv.id IS NOT NULL</if>
        <if test="search != null and search != ''">
          AND (position(lower(#{search}) in lower(s.name)) &gt; 0
               OR position(lower(#{search}) in lower(s.skill_key)) &gt; 0)
        </if>
        ORDER BY s.name ASC, s.id ASC
        LIMIT #{limit}
        </script>
        """)
    List<AgentSkill> selectVisibleSkills(
        @Param("principalId") Long principalId,
        @Param("platformAdmin") boolean platformAdmin,
        @Param("scopeType") String scopeType,
        @Param("scopeId") Long scopeId,
        @Param("search") String search,
        @Param("includeInactive") boolean includeInactive,
        @Param("limit") int limit
    );

    /**
     * 获取技能。
     *
     * @param skillId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT
        """ + SKILL_COLUMNS + """
        FROM agent_skill s
        LEFT JOIN LATERAL (
          SELECT id, version_no, content_hash
          FROM agent_skill_version
          WHERE skill_id = s.id AND status = 'published'
          ORDER BY version_no DESC LIMIT 1
        ) pv ON TRUE
        WHERE s.id = #{skillId} AND s.del_flag = '0'
        """)
    AgentSkill selectSkill(@Param("skillId") Long skillId);

    /**
     * 处理lock技能并返回对应结果。
     *
     * @param skillId 资源标识
     * @return 处理结果
     */
    @Select("SELECT id FROM agent_skill WHERE id = #{skillId} AND del_flag = '0' FOR UPDATE")
    Long lockSkill(@Param("skillId") Long skillId);

    /**
     * 创建并保存技能。
     *
     * @param skill 技能参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_skill (
            id, skill_key, name, description, scope_type, scope_id, owner_id,
            status, revision_no, create_by, create_time, del_flag, extra_json
        ) VALUES (
            #{id}, #{skillKey}, #{name}, #{description}, #{scopeType}, #{scopeId}, #{ownerId},
            #{status}, #{revisionNo}, #{createBy}, #{createTime}, '0', CAST(#{extraJson} AS jsonb)
        )
        """)
    int insertSkill(AgentSkill skill);

    /**
     * 更新技能。
     *
     * @param skill 技能参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_skill
        SET name = #{name}, description = #{description}, revision_no = revision_no + 1,
            update_by = #{updateBy}, update_time = #{updateTime}
        WHERE id = #{id} AND del_flag = '0' AND revision_no = #{revisionNo}
          AND status <> 'archived'
        """)
    int updateSkill(AgentSkill skill);

    /**
     * 将输入数据转换为uch技能。
     *
     * @param skillId 资源标识
     * @param revision {@code revision}参数
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_skill
        SET revision_no = revision_no + 1, update_by = #{actorId}, update_time = #{now}
        WHERE id = #{skillId} AND del_flag = '0' AND revision_no = #{revision}
          AND status <> 'archived'
        """)
    int touchSkill(
        @Param("skillId") Long skillId,
        @Param("revision") Long revision,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    /**
     * 更新技能Status。
     *
     * @param skillId 资源标识
     * @param revision {@code revision}参数
     * @param status 目标状态
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_skill
        SET status = #{status}, revision_no = revision_no + 1,
            update_by = #{actorId}, update_time = #{now}
        WHERE id = #{skillId} AND del_flag = '0' AND revision_no = #{revision}
        """)
    int updateSkillStatus(
        @Param("skillId") Long skillId,
        @Param("revision") Long revision,
        @Param("status") String status,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理softDeleteUnpublished技能并返回对应结果。
     *
     * @param skillId 资源标识
     * @param revision {@code revision}参数
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_skill
        SET status = 'archived', del_flag = '1', revision_no = revision_no + 1,
            update_by = #{actorId}, update_time = #{now}
        WHERE id = #{skillId} AND del_flag = '0' AND revision_no = #{revision}
          AND status IN ('draft', 'disabled')
          AND NOT EXISTS (
            SELECT 1 FROM agent_skill_version
            WHERE skill_id = #{skillId} AND (status = 'published' OR published_at IS NOT NULL)
          )
        """)
    int softDeleteUnpublishedSkill(
        @Param("skillId") Long skillId,
        @Param("revision") Long revision,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    /**
     * 获取Next版本No。
     *
     * @param skillId 资源标识
     * @return 处理结果
     */
    @Select("SELECT COALESCE(max(version_no), 0) + 1 FROM agent_skill_version WHERE skill_id = #{skillId}")
    int selectNextVersionNo(@Param("skillId") Long skillId);

    /**
     * 创建并保存版本。
     *
     * @param version 版本参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_skill_version (
            id, skill_id, version_no, content, content_hash, file_bundle_hash, manifest_json,
            runtime_requirements_json, status, created_by, created_at
        ) VALUES (
            #{id}, #{skillId}, #{versionNo}, #{content}, #{contentHash}, #{fileBundleHash},
            CAST(#{manifestJson} AS jsonb), CAST(#{runtimeRequirementsJson} AS jsonb),
            #{status}, #{createdBy}, #{createdAt}
        )
        """)
    int insertVersion(AgentSkillVersion version);

    /**
     * 删除{@code UnpublishedVersions}。
     *
     * @param skillId 资源标识
     * @return 处理结果
     */
    @Delete("""
        DELETE FROM agent_skill_version
        WHERE skill_id = #{skillId}
          AND status IN ('draft', 'archived')
          AND published_at IS NULL
        """)
    int deleteUnpublishedVersions(@Param("skillId") Long skillId);

    /**
     * 删除Draft版本。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Delete("""
        DELETE FROM agent_skill_version
        WHERE id = #{versionId} AND skill_id = #{skillId}
          AND status = 'draft' AND published_at IS NULL
        """)
    int deleteDraftVersion(
        @Param("skillId") Long skillId,
        @Param("versionId") Long versionId
    );

    /**
     * 更新{@code DraftContent}。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param content 待处理内容
     * @param contentHash 待处理内容
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_skill_version
        SET content = #{content}, content_hash = #{contentHash}
        WHERE id = #{versionId} AND skill_id = #{skillId} AND status = 'draft'
        """)
    int updateDraftContent(
        @Param("skillId") Long skillId,
        @Param("versionId") Long versionId,
        @Param("content") String content,
        @Param("contentHash") String contentHash
    );

    /**
     * 更新Published系统技能。
     *
     * @param skillId 资源标识
     * @param name 名称
     * @param description {@code description}参数
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_skill
        SET name = #{name}, description = #{description}, status = 'active',
            revision_no = revision_no + 1, update_by = #{actorId}, update_time = #{now}
        WHERE id = #{skillId} AND scope_type = 'system' AND del_flag = '0'
        """)
    int updatePublishedSystemSkill(
        @Param("skillId") Long skillId,
        @Param("name") String name,
        @Param("description") String description,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    /**
     * 获取{@code Versions}。
     *
     * @param skillId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT
        """ + VERSION_COLUMNS + """
        FROM agent_skill_version
        WHERE skill_id = #{skillId}
        ORDER BY version_no DESC
        """)
    List<AgentSkillVersion> selectVersions(@Param("skillId") Long skillId);

    /**
     * 获取版本。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT
        """ + VERSION_COLUMNS + """
        FROM agent_skill_version
        WHERE id = #{versionId} AND skill_id = #{skillId}
        """)
    AgentSkillVersion selectVersion(
        @Param("skillId") Long skillId,
        @Param("versionId") Long versionId
    );

    /**
     * 获取LatestEditable版本Id。
     *
     * @param skillId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id
        FROM agent_skill_version
        WHERE skill_id = #{skillId} AND status = 'draft'
        ORDER BY version_no DESC
        LIMIT 1
        """)
    Long selectLatestEditableVersionId(@Param("skillId") Long skillId);

    /**
     * 获取Latest版本Id。
     *
     * @param skillId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id
        FROM agent_skill_version
        WHERE skill_id = #{skillId}
        ORDER BY CASE status WHEN 'draft' THEN 0 WHEN 'published' THEN 1 ELSE 2 END, version_no DESC
        LIMIT 1
        """)
    Long selectLatestVersionId(@Param("skillId") Long skillId);

    /**
     * 处理{@code archivePreviouslyPublished}并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_skill_version
        SET status = 'archived'
        WHERE skill_id = #{skillId} AND id <> #{versionId} AND status = 'published'
        """)
    int archivePreviouslyPublished(
        @Param("skillId") Long skillId,
        @Param("versionId") Long versionId
    );

    /**
     * 处理{@code publishDraft}并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_skill_version
        SET status = 'published', published_at = #{now}
        WHERE id = #{versionId} AND skill_id = #{skillId} AND status = 'draft'
        """)
    int publishDraft(
        @Param("skillId") Long skillId,
        @Param("versionId") Long versionId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理archive版本并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_skill_version
        SET status = 'archived'
        WHERE id = #{versionId} AND skill_id = #{skillId} AND status IN ('draft', 'published')
        """)
    int archiveVersion(
        @Param("skillId") Long skillId,
        @Param("versionId") Long versionId
    );

    /**
     * 处理{@code countActiveReferences}并返回对应结果。
     *
     * @param skillId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT count(*) FROM agent_agent_version_skill b
        JOIN agent_definition_version v ON v.id = b.agent_version_id
        WHERE b.resource_id = #{skillId} AND v.status IN ('draft', 'published')
        """)
    int countActiveReferences(@Param("skillId") Long skillId);

    /**
     * 处理{@code countPublishedVersions}并返回对应结果。
     *
     * @param skillId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT count(*) FROM agent_skill_version
        WHERE skill_id = #{skillId} AND (status = 'published' OR published_at IS NOT NULL)
        """)
    int countPublishedVersions(@Param("skillId") Long skillId);

    /**
     * 处理{@code countBlockingPublicationReferences}并返回对应结果。
     *
     * @param skillId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT count(*) FROM agent_skill_publication
        WHERE source_skill_id = #{skillId}
          AND (status = 'pending' OR current_public_version_no IS NOT NULL)
        """)
    int countBlockingPublicationReferences(@Param("skillId") Long skillId);

    /**
     * 处理countBlocking版本PublicationReferences并返回对应结果。
     *
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT count(*) FROM agent_skill_publication_version
        WHERE source_skill_version_id = #{versionId} AND status IN ('pending', 'approved')
        """)
    int countBlockingVersionPublicationReferences(@Param("versionId") Long versionId);
}
