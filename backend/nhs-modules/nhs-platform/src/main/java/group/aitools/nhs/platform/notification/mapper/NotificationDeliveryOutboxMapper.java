package group.aitools.nhs.platform.notification.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.notification.domain.NotificationDeliveryOutboxEvent;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建并保存{@code insert}。
 *
 * 定义通知DeliveryOutbox相关的数据访问契约。
 * Owner-scoped external notification deliveries stored in the shared platform outbox. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface NotificationDeliveryOutboxMapper {

    @Insert("""
        INSERT INTO agent_outbox_event (
            id, event_type, aggregate_type, aggregate_id, event_key, payload_json,
            status, attempt_no, next_attempt_at, created_at
        ) VALUES (
            #{id}, 'notification.channel.delivery', 'notification_user', #{userId},
            #{eventKey}, CAST(#{payloadJson} AS jsonb), 'pending', 0,
            #{nextAttemptAt}, #{createdAt}
        )
        ON CONFLICT (event_key) DO NOTHING
        """)
    int insert(NotificationDeliveryOutboxEvent event);

    /**
     * 获取By事件Key。
     *
     * @param eventKey 事件Key参数
     * @return 处理结果
     */
    @Select("""
        SELECT id, aggregate_id AS user_id, event_key, payload_json::text AS payload_json,
               status, attempt_no, next_attempt_at, published_at, last_error, created_at
        FROM agent_outbox_event
        WHERE event_type = 'notification.channel.delivery'
          AND aggregate_type = 'notification_user'
          AND event_key = #{eventKey}
        """)
    NotificationDeliveryOutboxEvent selectByEventKey(@Param("eventKey") String eventKey);

    /**
     * 处理{@code lockDue}并返回对应结果。
     *
     * @param now {@code now}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, aggregate_id AS user_id, event_key, payload_json::text AS payload_json,
               status, attempt_no, next_attempt_at, published_at, last_error, created_at
        FROM agent_outbox_event
        WHERE event_type = 'notification.channel.delivery'
          AND aggregate_type = 'notification_user'
          AND status = 'pending' AND next_attempt_at <= #{now}
        ORDER BY next_attempt_at, id
        LIMIT #{limit}
        FOR UPDATE SKIP LOCKED
        """)
    List<NotificationDeliveryOutboxEvent> lockDue(
        @Param("now") LocalDateTime now,
        @Param("limit") int limit
    );

    /**
     * 获取{@code Owned}。
     *
     * @param userId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, aggregate_id AS user_id, event_key, payload_json::text AS payload_json,
               status, attempt_no, next_attempt_at, published_at, last_error, created_at
        FROM agent_outbox_event
        WHERE event_type = 'notification.channel.delivery'
          AND aggregate_type = 'notification_user'
          AND aggregate_id = #{userId}
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit}
        """)
    List<NotificationDeliveryOutboxEvent> selectOwned(
        @Param("userId") Long userId,
        @Param("limit") int limit
    );

    /**
     * 获取{@code OwnedById}。
     *
     * @param id 资源标识
     * @param userId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, aggregate_id AS user_id, event_key, payload_json::text AS payload_json,
               status, attempt_no, next_attempt_at, published_at, last_error, created_at
        FROM agent_outbox_event
        WHERE id = #{id}
          AND event_type = 'notification.channel.delivery'
          AND aggregate_type = 'notification_user'
          AND aggregate_id = #{userId}
        """)
    NotificationDeliveryOutboxEvent selectOwnedById(
        @Param("id") Long id,
        @Param("userId") Long userId
    );

    /**
     * 处理{@code markPublished}并返回对应结果。
     *
     * @param id 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_outbox_event
        SET status = 'published', attempt_no = attempt_no + 1,
            published_at = #{now}, last_error = NULL
        WHERE id = #{id} AND status = 'pending'
          AND event_type = 'notification.channel.delivery'
        """)
    int markPublished(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 处理{@code markFailed}并返回对应结果。
     *
     * @param id 资源标识
     * @param status 目标状态
     * @param nextAttemptAt {@code nextAttemptAt}参数
     * @param error {@code error}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_outbox_event
        SET status = #{status}, attempt_no = attempt_no + 1,
            next_attempt_at = #{nextAttemptAt}, last_error = #{error}
        WHERE id = #{id} AND status = 'pending'
          AND event_type = 'notification.channel.delivery'
        """)
    int markFailed(
        @Param("id") Long id,
        @Param("status") String status,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
        @Param("error") String error
    );

    /**
     * 处理{@code retryOwned}并返回对应结果。
     *
     * @param id 资源标识
     * @param userId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_outbox_event
        SET status = 'pending', attempt_no = 0, next_attempt_at = #{now},
            published_at = NULL, last_error = NULL
        WHERE id = #{id} AND aggregate_id = #{userId}
          AND event_type = 'notification.channel.delivery'
          AND aggregate_type = 'notification_user' AND status = 'failed'
        """)
    int retryOwned(
        @Param("id") Long id,
        @Param("userId") Long userId,
        @Param("now") LocalDateTime now
    );
}
