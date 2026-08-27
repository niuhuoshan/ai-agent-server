package group.aitools.nhs.platform.knowledge.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeDirectoryAcl;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定义知识库目录Acl相关的数据访问契约。
 * Persistence boundary for user-scoped knowledge directory ACLs. */
public interface KnowledgeDirectoryAclMapper {

    String COLUMNS = """
        id, knowledge_base_id, directory_id, user_id, permission, effect,
        inherit_children, revision_no, status, created_by, created_at, updated_by, updated_at
        """;

    /**
     * 获取ActiveFor用户。
     *
     * @param baseId 资源标识
     * @param userId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT """ + COLUMNS + """
        FROM agent_knowledge_directory_acl
        WHERE knowledge_base_id = #{baseId} AND user_id = #{userId} AND status = 'active'
        ORDER BY COALESCE(directory_id, 0), permission, id
        """)
    List<AgentKnowledgeDirectoryAcl> selectActiveForUser(
        @Param("baseId") Long baseId,
        @Param("userId") Long userId
    );

    /**
     * 获取{@code ActiveForBase}。
     *
     * @param baseId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT """ + COLUMNS + """
        FROM agent_knowledge_directory_acl
        WHERE knowledge_base_id = #{baseId} AND status = 'active'
        ORDER BY COALESCE(directory_id, 0), user_id, permission, id
        """)
    List<AgentKnowledgeDirectoryAcl> selectActiveForBase(@Param("baseId") Long baseId);

    /**
     * 获取{@code ById}。
     *
     * @param baseId 资源标识
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT """ + COLUMNS + """
        FROM agent_knowledge_directory_acl
        WHERE id = #{id} AND knowledge_base_id = #{baseId}
        """)
    AgentKnowledgeDirectoryAcl selectById(
        @Param("baseId") Long baseId,
        @Param("id") Long id
    );

    /**
     * 获取{@code ActiveTarget}。
     *
     * @param baseId 资源标识
     * @param directoryId 资源标识
     * @param userId 资源标识
     * @param permission 权限参数
     * @return 处理结果
     */
    @Select("""
        SELECT """ + COLUMNS + """
        FROM agent_knowledge_directory_acl
        WHERE knowledge_base_id = #{baseId}
          AND user_id = #{userId}
          AND permission = #{permission}
          AND ((directory_id IS NULL AND #{directoryId} IS NULL) OR directory_id = #{directoryId})
          AND status = 'active'
        LIMIT 1
        """)
    AgentKnowledgeDirectoryAcl selectActiveTarget(
        @Param("baseId") Long baseId,
        @Param("directoryId") Long directoryId,
        @Param("userId") Long userId,
        @Param("permission") String permission
    );

    /**
     * 创建并保存{@code insert}。
     *
     * @param acl {@code acl}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_knowledge_directory_acl (
            id, knowledge_base_id, directory_id, user_id, permission, effect,
            inherit_children, revision_no, status, created_by, created_at
        ) VALUES (
            #{id}, #{knowledgeBaseId}, #{directoryId}, #{userId}, #{permission}, #{effect},
            #{inheritChildren}, #{revisionNo}, 'active', #{createdBy}, #{createdAt}
        )
        """)
    int insert(AgentKnowledgeDirectoryAcl acl);

    /**
     * 更新{@code update}。
     *
     * @param acl {@code acl}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_knowledge_directory_acl
        SET effect = #{effect}, inherit_children = #{inheritChildren},
            revision_no = revision_no + 1, updated_by = #{updatedBy}, updated_at = #{updatedAt}
        WHERE id = #{id} AND knowledge_base_id = #{knowledgeBaseId}
          AND status = 'active' AND revision_no = #{revisionNo}
        """)
    int update(AgentKnowledgeDirectoryAcl acl);

    /**
     * 处理{@code revoke}并返回对应结果。
     *
     * @param baseId 资源标识
     * @param id 资源标识
     * @param revisionNo {@code revisionNo}参数
     * @param userId 资源标识
     * @param updatedAt {@code updatedAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_knowledge_directory_acl
        SET status = 'revoked', revision_no = revision_no + 1,
            updated_by = #{userId}, updated_at = #{updatedAt}
        WHERE id = #{id} AND knowledge_base_id = #{baseId}
          AND status = 'active' AND revision_no = #{revisionNo}
        """)
    int revoke(
        @Param("baseId") Long baseId,
        @Param("id") Long id,
        @Param("revisionNo") Long revisionNo,
        @Param("userId") Long userId,
        @Param("updatedAt") LocalDateTime updatedAt
    );
}
