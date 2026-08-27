package group.aitools.nhs.platform.skill.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.skill.domain.AgentSkillFile;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 获取{@code Files}。
 *
 * 定义技能文件相关的数据访问契约。
 * Persistence for versioned Skill bundle files. */
public interface SkillFileMapper {

    @Select("""
        SELECT id, skill_id, version_id, path, file_kind, content, content_bytes,
               content_encoding, content_hash,
               size_bytes, create_by, create_time, update_by, update_time, del_flag
        FROM agent_skill_file
        WHERE skill_id = #{skillId} AND version_id = #{versionId} AND del_flag = '0'
        ORDER BY path ASC
        """)
    List<AgentSkillFile> selectFiles(@Param("skillId") Long skillId, @Param("versionId") Long versionId);

    /**
     * 获取文件。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param path {@code path}参数
     * @return 处理结果
     */
    @Select("""
        SELECT id, skill_id, version_id, path, file_kind, content, content_bytes,
               content_encoding, content_hash,
               size_bytes, create_by, create_time, update_by, update_time, del_flag
        FROM agent_skill_file
        WHERE skill_id = #{skillId} AND version_id = #{versionId} AND path = #{path} AND del_flag = '0'
        """)
    AgentSkillFile selectFile(@Param("skillId") Long skillId, @Param("versionId") Long versionId, @Param("path") String path);

    /**
     * 处理{@code upsert}并返回对应结果。
     *
     * @param file 文件参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_skill_file (
            id, skill_id, version_id, path, file_kind, content, content_bytes, content_encoding,
            content_hash, size_bytes,
            create_by, create_time, del_flag
        ) VALUES (
            #{id}, #{skillId}, #{versionId}, #{path}, #{fileKind}, #{content}, #{contentBytes},
            #{contentEncoding}, #{contentHash}, #{sizeBytes},
            #{createBy}, #{createTime}, '0'
        )
        ON CONFLICT (version_id, path) DO UPDATE SET
            file_kind = EXCLUDED.file_kind, content = EXCLUDED.content,
            content_bytes = EXCLUDED.content_bytes, content_encoding = EXCLUDED.content_encoding,
            content_hash = EXCLUDED.content_hash, size_bytes = EXCLUDED.size_bytes,
            update_by = EXCLUDED.create_by, update_time = EXCLUDED.create_time, del_flag = '0'
        """)
    int upsert(AgentSkillFile file);

    /**
     * 处理{@code softDeleteTree}并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param path {@code path}参数
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_skill_file
        SET del_flag = '1', update_by = #{actorId}, update_time = #{now}
        WHERE skill_id = #{skillId} AND version_id = #{versionId}
          AND (path = #{path} OR path LIKE CONCAT(#{path}, '/%')) AND del_flag = '0'
        """)
    int softDeleteTree(
        @Param("skillId") Long skillId,
        @Param("versionId") Long versionId,
        @Param("path") String path,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理softDelete版本并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_skill_file SET del_flag = '1', update_by = #{actorId}, update_time = #{now}
        WHERE skill_id = #{skillId} AND version_id = #{versionId} AND del_flag = '0'
        """)
    int softDeleteVersion(@Param("skillId") Long skillId, @Param("versionId") Long versionId,
                          @Param("actorId") Long actorId, @Param("now") LocalDateTime now);

    /**
     * 删除版本Files。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Delete("DELETE FROM agent_skill_file WHERE skill_id = #{skillId} AND version_id = #{versionId}")
    int deleteVersionFiles(@Param("skillId") Long skillId, @Param("versionId") Long versionId);

    /**
     * 删除技能Files。
     *
     * @param skillId 资源标识
     * @return 处理结果
     */
    @Delete("DELETE FROM agent_skill_file WHERE skill_id = #{skillId}")
    int deleteSkillFiles(@Param("skillId") Long skillId);

    /**
     * 处理{@code refreshBundleHash}并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param bundleHash {@code bundleHash}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_skill_version
        SET file_bundle_hash = #{bundleHash}
        WHERE id = #{versionId} AND skill_id = #{skillId} AND status = 'draft'
        """)
    int refreshBundleHash(
        @Param("skillId") Long skillId,
        @Param("versionId") Long versionId,
        @Param("bundleHash") String bundleHash
    );
}
