package group.aitools.nhs.platform.conversation.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import group.aitools.nhs.platform.conversation.persistence.row.ConversationHistoryTargetRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 获取链路追踪。
 *
 * 定义会话历史记录Deletion相关的数据访问契约。
 * Durable V1 history hiding facts. No message or execution row is deleted here. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface ConversationHistoryDeletionMapper {

    @Select("""
        SELECT t.conversation_id, t.user_id, t.trace_id, t.status AS turn_status
        FROM agent_conversation_turn t
        INNER JOIN agent_conversation c ON c.id = t.conversation_id
          AND c.user_id = t.user_id AND c.principal_type = 'human'
        WHERE t.trace_id = #{traceId}
        LIMIT 1
        FOR UPDATE OF t
        """)
    ConversationHistoryTargetRow selectTrace(@Param("traceId") String traceId);

    /**
     * 获取会话。
     *
     * @param conversationId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT c.id AS conversation_id, c.user_id, NULL AS trace_id, NULL AS turn_status
        FROM agent_conversation c
        WHERE c.id = #{conversationId} AND c.principal_type = 'human'
        FOR UPDATE
        """)
    ConversationHistoryTargetRow selectConversation(@Param("conversationId") Long conversationId);

    /**
     * 获取Active会话回合Status。
     *
     * @param conversationId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT status
        FROM agent_conversation_turn
        WHERE conversation_id = #{conversationId}
          AND status IN ('running', 'stopping', 'waiting_confirmation', 'waiting_user_question')
        ORDER BY started_at DESC, id DESC
        LIMIT 1
        """)
    String selectActiveTurnStatus(@Param("conversationId") Long conversationId);

    /**
     * 创建并保存{@code Tombstone}。
     *
     * @param id 资源标识
     * @param userId 资源标识
     * @param conversationId 资源标识
     * @param traceId 资源标识
     * @param deletedBy {@code deletedBy}参数
     * @param deletedAt {@code deletedAt}参数
     * @param reason {@code reason}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_conversation_history_tombstone (
            id, user_id, conversation_id, trace_id, deleted_by, deleted_at, reason
        ) VALUES (
            #{id}, #{userId}, #{conversationId}, #{traceId}, #{deletedBy}, #{deletedAt}, #{reason}
        ) ON CONFLICT DO NOTHING
        """)
    int insertTombstone(
        @Param("id") Long id,
        @Param("userId") Long userId,
        @Param("conversationId") Long conversationId,
        @Param("traceId") String traceId,
        @Param("deletedBy") Long deletedBy,
        @Param("deletedAt") LocalDateTime deletedAt,
        @Param("reason") String reason
    );
}
