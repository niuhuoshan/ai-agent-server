package group.aitools.nhs.platform.notification.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.notification.domain.AgentNotification;
import group.aitools.nhs.platform.notification.persistence.row.TaskNotificationOwnerRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建并保存通知。
 *
 * 定义智能体通知相关的数据访问契约。
 * Owner-scoped notification persistence with per-recipient idempotency. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface AgentNotificationMapper {

    @Insert("""
        INSERT INTO agent_notification (
            id, user_id, event_key, category, level, title, content,
            resource_type, resource_id, created_at
        ) VALUES (
            #{id}, #{userId}, #{eventKey}, #{category}, #{level}, #{title}, #{content},
            #{resourceType}, #{resourceId}, #{createdAt}
        )
        ON CONFLICT (user_id, event_key) WHERE event_key IS NOT NULL DO NOTHING
        """)
    int insertNotification(AgentNotification notification);

    /**
     * 获取By事件Key。
     *
     * @param userId 资源标识
     * @param eventKey 事件Key参数
     * @return 处理结果
     */
    @Select("""
        SELECT id, user_id, event_key, category, level, title, content,
               resource_type, resource_id, CAST(metadata_json AS text) AS metadata_json,
               read_at, created_at
        FROM agent_notification
        WHERE user_id = #{userId} AND event_key = #{eventKey}
        """)
    AgentNotification selectByEventKey(
        @Param("userId") Long userId,
        @Param("eventKey") String eventKey
    );

    /**
     * 获取{@code Inbox}。
     *
     * @param userId 资源标识
     * @param category {@code category}参数
     * @param unreadOnly {@code unreadOnly}参数
     * @param beforeId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT id, user_id, event_key, category, level, title, content,
               resource_type, resource_id, read_at, created_at
        FROM agent_notification
        WHERE user_id = #{userId}
          <if test="category != null">AND category = #{category}</if>
          <if test="unreadOnly">AND read_at IS NULL</if>
          <if test="beforeId != null">AND id &lt; #{beforeId}</if>
        ORDER BY id DESC
        LIMIT #{limit}
        </script>
        """)
    List<AgentNotification> selectInbox(
        @Param("userId") Long userId,
        @Param("category") String category,
        @Param("unreadOnly") boolean unreadOnly,
        @Param("beforeId") Long beforeId,
        @Param("limit") int limit
    );

    /**
     * 获取{@code InboxPage}。
     *
     * @param userId 资源标识
     * @param category {@code category}参数
     * @param unreadOnly {@code unreadOnly}参数
     * @param offset 起始位置或序号
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT id, user_id, event_key, category, level, title, content,
               resource_type, resource_id, CAST(metadata_json AS text) AS metadata_json,
               read_at, created_at
        FROM agent_notification
        WHERE user_id = #{userId}
          <if test="category != null">AND category = #{category}</if>
          <if test="unreadOnly">AND read_at IS NULL</if>
        ORDER BY id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<AgentNotification> selectInboxPage(
        @Param("userId") Long userId,
        @Param("category") String category,
        @Param("unreadOnly") boolean unreadOnly,
        @Param("offset") int offset,
        @Param("limit") int limit
    );

    /**
     * 处理{@code countUnread}并返回对应结果。
     *
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT count(*) FROM agent_notification
        WHERE user_id = #{userId} AND read_at IS NULL
        """)
    long countUnread(@Param("userId") Long userId);

    /**
     * 处理{@code markRead}并返回对应结果。
     *
     * @param notificationId 资源标识
     * @param userId 资源标识
     * @param readAt {@code readAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_notification
        SET read_at = COALESCE(read_at, #{readAt})
        WHERE id = #{notificationId} AND user_id = #{userId}
        """)
    int markRead(
        @Param("notificationId") Long notificationId,
        @Param("userId") Long userId,
        @Param("readAt") LocalDateTime readAt
    );

    /**
     * 处理{@code markAllRead}并返回对应结果。
     *
     * @param userId 资源标识
     * @param readAt {@code readAt}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_notification SET read_at = #{readAt}
        WHERE user_id = #{userId} AND read_at IS NULL
        """)
    int markAllRead(
        @Param("userId") Long userId,
        @Param("readAt") LocalDateTime readAt
    );

    /**
     * 获取{@code Owned}。
     *
     * @param notificationId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, user_id, event_key, category, level, title, content,
               resource_type, resource_id, CAST(metadata_json AS text) AS metadata_json,
               read_at, created_at
        FROM agent_notification
        WHERE id = #{notificationId} AND user_id = #{userId}
        """)
    AgentNotification selectOwned(
        @Param("notificationId") Long notificationId,
        @Param("userId") Long userId
    );

    /**
     * 删除{@code Read}。
     *
     * @param userId 资源标识
     * @return 处理结果
     */
    @Delete("""
        DELETE FROM agent_notification
        WHERE user_id = #{userId} AND read_at IS NOT NULL
        """)
    int deleteRead(@Param("userId") Long userId);

    /**
     * 删除{@code One}。
     *
     * @param notificationId 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Delete("""
        DELETE FROM agent_notification
        WHERE id = #{notificationId} AND user_id = #{userId}
        """)
    int deleteOne(
        @Param("notificationId") Long notificationId,
        @Param("userId") Long userId
    );

    /**
     * 获取任务Owner。
     *
     * @param taskId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT owner_id, owner_principal_type
        FROM agent_task
        WHERE id = #{taskId} AND del_flag = '0'
        """)
    TaskNotificationOwnerRow selectTaskOwner(@Param("taskId") Long taskId);

    /**
     * 获取审批RecipientIds。
     *
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT DISTINCT u.user_id
        FROM sys_user u
        JOIN sys_user_role ur ON ur.user_id = u.user_id
        JOIN sys_role r ON r.role_id = ur.role_id
        WHERE u.status = '0' AND u.del_flag = '0'
          AND r.status = '0' AND r.del_flag = '0'
          AND r.role_key IN ('approval_user', 'platform_admin', 'superadmin')
        ORDER BY u.user_id
        LIMIT 1000
        """)
    List<Long> selectApprovalRecipientIds();
}
