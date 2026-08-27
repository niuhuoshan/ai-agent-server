package group.aitools.nhs.platform.nhs.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import group.aitools.nhs.platform.nhs.persistence.row.DatasetNavigationCacheRow;
import group.aitools.nhs.platform.nhs.persistence.row.DatasetNavigationClickRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 获取缓存。
 *
 * 定义数据集Navigation相关的数据访问契约。
 * Persistence boundary for private dataset-navigation cache and ranking state. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface DatasetNavigationMapper {

    @Select("""
        SELECT user_id, menu_hash, payload_json::text AS payload_json, generated_at, expires_at
        FROM agent_dataset_navigation_cache
        WHERE user_id = #{userId} AND menu_hash = #{menuHash} AND expires_at > #{now}
        """)
    DatasetNavigationCacheRow selectCache(
        @Param("userId") Long userId,
        @Param("menuHash") String menuHash,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理upsert缓存并返回对应结果。
     *
     * @param userId 资源标识
     * @param menuHash {@code menuHash}参数
     * @param payloadJson {@code payloadJson}参数
     * @param generatedAt {@code generatedAt}参数
     * @param expiresAt {@code expiresAt}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_dataset_navigation_cache (
            user_id, menu_hash, payload_json, generated_at, expires_at, created_at, updated_at
        ) VALUES (
            #{userId}, #{menuHash}, CAST(#{payloadJson} AS jsonb),
            #{generatedAt}, #{expiresAt}, #{generatedAt}, #{generatedAt}
        )
        ON CONFLICT (user_id, menu_hash) DO UPDATE
        SET payload_json = EXCLUDED.payload_json,
            generated_at = EXCLUDED.generated_at,
            expires_at = EXCLUDED.expires_at,
            updated_at = EXCLUDED.updated_at
        """)
    int upsertCache(
        @Param("userId") Long userId,
        @Param("menuHash") String menuHash,
        @Param("payloadJson") String payloadJson,
        @Param("generatedAt") LocalDateTime generatedAt,
        @Param("expiresAt") LocalDateTime expiresAt
    );

    /**
     * 获取{@code Clicks}。
     *
     * @param userId 资源标识
     * @param now {@code now}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT query_text, label, group_id, click_count, last_clicked_at
        FROM agent_dataset_navigation_click
        WHERE user_id = #{userId} AND expires_at > #{now}
        ORDER BY click_count DESC, last_clicked_at DESC, question_hash
        LIMIT #{limit}
        """)
    List<DatasetNavigationClickRow> selectClicks(
        @Param("userId") Long userId,
        @Param("now") LocalDateTime now,
        @Param("limit") int limit
    );

    /**
     * 处理{@code upsertClick}并返回对应结果。
     *
     * @param userId 资源标识
     * @param questionHash 追问Hash参数
     * @param queryText 待处理内容
     * @param label {@code label}参数
     * @param groupId 资源标识
     * @param now {@code now}参数
     * @param expiresAt {@code expiresAt}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_dataset_navigation_click (
            user_id, question_hash, query_text, label, group_id, click_count,
            last_clicked_at, expires_at, created_at, updated_at
        ) VALUES (
            #{userId}, #{questionHash}, #{queryText}, #{label}, #{groupId}, 1,
            #{now}, #{expiresAt}, #{now}, #{now}
        )
        ON CONFLICT (user_id, question_hash) DO UPDATE
        SET query_text = EXCLUDED.query_text,
            label = EXCLUDED.label,
            group_id = EXCLUDED.group_id,
            click_count = agent_dataset_navigation_click.click_count + 1,
            last_clicked_at = EXCLUDED.last_clicked_at,
            expires_at = EXCLUDED.expires_at,
            updated_at = EXCLUDED.updated_at
        """)
    int upsertClick(
        @Param("userId") Long userId,
        @Param("questionHash") String questionHash,
        @Param("queryText") String queryText,
        @Param("label") String label,
        @Param("groupId") String groupId,
        @Param("now") LocalDateTime now,
        @Param("expiresAt") LocalDateTime expiresAt
    );

    /**
     * 删除{@code Click}。
     *
     * @param userId 资源标识
     * @param questionHash 追问Hash参数
     * @return 处理结果
     */
    @Delete("""
        DELETE FROM agent_dataset_navigation_click
        WHERE user_id = #{userId} AND question_hash = #{questionHash}
        """)
    int deleteClick(@Param("userId") Long userId, @Param("questionHash") String questionHash);

    /**
     * 获取{@code RecentQuestions}。
     *
     * @param userId 资源标识
     * @param purpose {@code purpose}参数
     * @param groupHash {@code groupHash}参数
     * @param now {@code now}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT query_text
        FROM agent_dataset_navigation_recent_question
        WHERE user_id = #{userId} AND purpose = #{purpose}
          AND group_hash = #{groupHash} AND expires_at > #{now}
        ORDER BY created_at DESC, question_hash
        LIMIT #{limit}
        """)
    List<String> selectRecentQuestions(
        @Param("userId") Long userId,
        @Param("purpose") String purpose,
        @Param("groupHash") String groupHash,
        @Param("now") LocalDateTime now,
        @Param("limit") int limit
    );

    /**
     * 处理upsertRecent追问并返回对应结果。
     *
     * @param userId 资源标识
     * @param purpose {@code purpose}参数
     * @param groupHash {@code groupHash}参数
     * @param questionHash 追问Hash参数
     * @param queryText 待处理内容
     * @param now {@code now}参数
     * @param expiresAt {@code expiresAt}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_dataset_navigation_recent_question (
            user_id, purpose, group_hash, question_hash, query_text, created_at, expires_at
        ) VALUES (
            #{userId}, #{purpose}, #{groupHash}, #{questionHash}, #{queryText}, #{now}, #{expiresAt}
        )
        ON CONFLICT (user_id, purpose, group_hash, question_hash) DO UPDATE
        SET query_text = EXCLUDED.query_text,
            created_at = EXCLUDED.created_at,
            expires_at = EXCLUDED.expires_at
        """)
    int upsertRecentQuestion(
        @Param("userId") Long userId,
        @Param("purpose") String purpose,
        @Param("groupHash") String groupHash,
        @Param("questionHash") String questionHash,
        @Param("queryText") String queryText,
        @Param("now") LocalDateTime now,
        @Param("expiresAt") LocalDateTime expiresAt
    );

    /**
     * 删除{@code ExpiredCaches}。
     *
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Delete("DELETE FROM agent_dataset_navigation_cache WHERE expires_at <= #{now}")
    int deleteExpiredCaches(@Param("now") LocalDateTime now);

    /**
     * 删除{@code ExpiredClicks}。
     *
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Delete("DELETE FROM agent_dataset_navigation_click WHERE expires_at <= #{now}")
    int deleteExpiredClicks(@Param("now") LocalDateTime now);

    /**
     * 删除{@code ExpiredRecentQuestions}。
     *
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Delete("DELETE FROM agent_dataset_navigation_recent_question WHERE expires_at <= #{now}")
    int deleteExpiredRecentQuestions(@Param("now") LocalDateTime now);
}
