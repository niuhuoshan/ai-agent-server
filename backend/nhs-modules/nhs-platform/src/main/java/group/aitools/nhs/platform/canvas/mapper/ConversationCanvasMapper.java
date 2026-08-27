package group.aitools.nhs.platform.canvas.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.canvas.domain.AgentConversationCanvas;
import group.aitools.nhs.platform.canvas.domain.AgentConversationCanvasVersion;

import java.util.List;

/**
 * 获取{@code OwnedCanvases}。
 *
 * 定义会话画布相关的数据访问契约。
 * SQL boundary that always includes both conversation and owner identifiers. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface ConversationCanvasMapper {

    @Select("""
        SELECT c.id, c.conversation_id, c.owner_id, c.title, c.canvas_type,
               c.current_version_no, c.revision_no, c.metadata_json, c.content_size,
               c.content_sha256, c.create_by, c.create_time, c.update_by, c.update_time,
               c.del_flag
        FROM agent_conversation_canvas c
        WHERE c.conversation_id = #{conversationId}
          AND c.owner_id = #{ownerId}
          AND c.del_flag = '0'
        ORDER BY COALESCE(c.update_time, c.create_time) DESC, c.id DESC
        LIMIT #{limit}
        """)
    List<AgentConversationCanvas> selectOwnedCanvases(
        @Param("conversationId") Long conversationId,
        @Param("ownerId") Long ownerId,
        @Param("limit") int limit
    );

    /**
     * 获取Owned画布。
     *
     * @param conversationId 资源标识
     * @param canvasId 资源标识
     * @param ownerId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT c.id, c.conversation_id, c.owner_id, c.title, c.canvas_type,
               c.current_version_no, c.revision_no, c.metadata_json, c.content_size,
               c.content_sha256, v.content, c.create_by, c.create_time, c.update_by,
               c.update_time, c.del_flag
        FROM agent_conversation_canvas c
        JOIN agent_conversation_canvas_version v
          ON v.canvas_id = c.id AND v.version_no = c.current_version_no
        WHERE c.id = #{canvasId}
          AND c.conversation_id = #{conversationId}
          AND c.owner_id = #{ownerId}
          AND c.del_flag = '0'
        """)
    AgentConversationCanvas selectOwnedCanvas(
        @Param("conversationId") Long conversationId,
        @Param("canvasId") Long canvasId,
        @Param("ownerId") Long ownerId
    );

    /**
     * 获取{@code OwnedVersions}。
     *
     * @param conversationId 资源标识
     * @param canvasId 资源标识
     * @param ownerId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT v.id, v.canvas_id, v.version_no, v.title, v.canvas_type, v.content,
               v.metadata_json, v.content_size, v.content_sha256, v.change_type,
               v.source_version_no, v.created_by, v.created_at
        FROM agent_conversation_canvas_version v
        JOIN agent_conversation_canvas c ON c.id = v.canvas_id
        WHERE c.id = #{canvasId}
          AND c.conversation_id = #{conversationId}
          AND c.owner_id = #{ownerId}
          AND c.del_flag = '0'
        ORDER BY v.version_no DESC
        LIMIT #{limit}
        """)
    List<AgentConversationCanvasVersion> selectOwnedVersions(
        @Param("conversationId") Long conversationId,
        @Param("canvasId") Long canvasId,
        @Param("ownerId") Long ownerId,
        @Param("limit") int limit
    );

    /**
     * 获取Owned版本。
     *
     * @param conversationId 资源标识
     * @param canvasId 资源标识
     * @param ownerId 资源标识
     * @param versionNo 版本No参数
     * @return 处理结果
     */
    @Select("""
        SELECT v.id, v.canvas_id, v.version_no, v.title, v.canvas_type, v.content,
               v.metadata_json, v.content_size, v.content_sha256, v.change_type,
               v.source_version_no, v.created_by, v.created_at
        FROM agent_conversation_canvas_version v
        JOIN agent_conversation_canvas c ON c.id = v.canvas_id
        WHERE c.id = #{canvasId}
          AND c.conversation_id = #{conversationId}
          AND c.owner_id = #{ownerId}
          AND c.del_flag = '0'
          AND v.version_no = #{versionNo}
        """)
    AgentConversationCanvasVersion selectOwnedVersion(
        @Param("conversationId") Long conversationId,
        @Param("canvasId") Long canvasId,
        @Param("ownerId") Long ownerId,
        @Param("versionNo") int versionNo
    );

    /**
     * 创建并保存画布。
     *
     * @param canvas 画布参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_conversation_canvas (
            id, conversation_id, owner_id, title, canvas_type, current_version_no,
            revision_no, metadata_json, content_size, content_sha256, create_by,
            create_time, update_by, update_time, del_flag
        ) VALUES (
            #{id}, #{conversationId}, #{ownerId}, #{title}, #{canvasType},
            #{currentVersionNo}, #{revisionNo}, CAST(#{metadataJson} AS jsonb),
            #{contentSize}, #{contentSha256}, #{createBy}, #{createTime},
            #{updateBy}, #{updateTime}, #{delFlag}
        )
        """)
    int insertCanvas(AgentConversationCanvas canvas);

    /**
     * 创建并保存版本。
     *
     * @param version 版本参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_conversation_canvas_version (
            id, canvas_id, version_no, title, canvas_type, content, metadata_json,
            content_size, content_sha256, change_type, source_version_no, created_by,
            created_at
        ) VALUES (
            #{id}, #{canvasId}, #{versionNo}, #{title}, #{canvasType}, #{content},
            CAST(#{metadataJson} AS jsonb), #{contentSize}, #{contentSha256},
            #{changeType}, #{sourceVersionNo}, #{createdBy}, #{createdAt}
        )
        """)
    int insertVersion(AgentConversationCanvasVersion version);

    /**
     * 处理advance版本并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param canvasId 资源标识
     * @param ownerId 资源标识
     * @param expectedVersion expected版本参数
     * @param nextVersion next版本参数
     * @param title {@code title}参数
     * @param canvasType 业务类型
     * @param metadataJson 元数据Json参数
     * @param contentSize 数量上限
     * @param contentSha256 待处理内容
     * @param updatedAt {@code updatedAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_conversation_canvas
        SET title = #{title},
            canvas_type = #{canvasType},
            current_version_no = #{nextVersion},
            revision_no = revision_no + 1,
            metadata_json = CAST(#{metadataJson} AS jsonb),
            content_size = #{contentSize},
            content_sha256 = #{contentSha256},
            update_by = #{ownerId},
            update_time = #{updatedAt}
        WHERE id = #{canvasId}
          AND conversation_id = #{conversationId}
          AND owner_id = #{ownerId}
          AND current_version_no = #{expectedVersion}
          AND revision_no = #{expectedVersion}
          AND del_flag = '0'
        """)
    int advanceVersion(
        @Param("conversationId") Long conversationId,
        @Param("canvasId") Long canvasId,
        @Param("ownerId") Long ownerId,
        @Param("expectedVersion") int expectedVersion,
        @Param("nextVersion") int nextVersion,
        @Param("title") String title,
        @Param("canvasType") String canvasType,
        @Param("metadataJson") String metadataJson,
        @Param("contentSize") long contentSize,
        @Param("contentSha256") String contentSha256,
        @Param("updatedAt") java.time.LocalDateTime updatedAt
    );

    /**
     * 处理{@code softDelete}并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param canvasId 资源标识
     * @param ownerId 资源标识
     * @param expectedVersion expected版本参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_conversation_canvas
        SET revision_no = revision_no + 1,
            update_by = #{ownerId},
            update_time = CURRENT_TIMESTAMP,
            del_flag = '1'
        WHERE id = #{canvasId}
          AND conversation_id = #{conversationId}
          AND owner_id = #{ownerId}
          AND current_version_no = #{expectedVersion}
          AND revision_no = #{expectedVersion}
          AND del_flag = '0'
        """)
    int softDelete(
        @Param("conversationId") Long conversationId,
        @Param("canvasId") Long canvasId,
        @Param("ownerId") Long ownerId,
        @Param("expectedVersion") int expectedVersion
    );
}
