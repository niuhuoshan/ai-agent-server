package group.aitools.nhs.platform.audit.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import group.aitools.nhs.platform.data.persistence.row.MetadataChangeRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 查询{@code search}列表。
 *
 * 定义元数据Changelog查询相关的数据访问契约。
 * Cross-dataset metadata change queries used only after administrator authorization. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface MetadataChangelogQueryMapper {

    @Select("""
        <script>
        SELECT id, dataset_id, resource_type, resource_id, action,
               before_json::text AS before_json, after_json::text AS after_json,
               before_hash, after_hash, actor_id, created_at
        FROM agent_data_metadata_change
        WHERE 1 = 1
          <if test="datasetId != null">AND dataset_id = #{datasetId}</if>
          <if test="resourceType != null">AND resource_type = #{resourceType}</if>
          <if test="resourceId != null">AND resource_id = #{resourceId}</if>
          <if test="action != null">AND action = #{action}</if>
          <if test="actorId != null">AND actor_id = #{actorId}</if>
          <if test="createdFrom != null">AND created_at &gt;= #{createdFrom}</if>
          <if test="createdTo != null">AND created_at &lt; #{createdTo}</if>
        ORDER BY created_at DESC, id DESC
        OFFSET #{offset} LIMIT #{limit}
        </script>
        """)
    List<MetadataChangeRow> search(
        @Param("datasetId") Long datasetId,
        @Param("resourceType") String resourceType,
        @Param("resourceId") Long resourceId,
        @Param("action") String action,
        @Param("actorId") Long actorId,
        @Param("createdFrom") LocalDateTime createdFrom,
        @Param("createdTo") LocalDateTime createdTo,
        @Param("offset") int offset,
        @Param("limit") int limit
    );

    /**
     * 处理{@code count}并返回对应结果。
     *
     * @param datasetId 资源标识
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param action {@code action}参数
     * @param actorId 资源标识
     * @param createdFrom {@code createdFrom}参数
     * @param createdTo {@code createdTo}参数
     * @return 处理结果
     */
    @Select("""
        <script>
        SELECT COUNT(*)
        FROM agent_data_metadata_change
        WHERE 1 = 1
          <if test="datasetId != null">AND dataset_id = #{datasetId}</if>
          <if test="resourceType != null">AND resource_type = #{resourceType}</if>
          <if test="resourceId != null">AND resource_id = #{resourceId}</if>
          <if test="action != null">AND action = #{action}</if>
          <if test="actorId != null">AND actor_id = #{actorId}</if>
          <if test="createdFrom != null">AND created_at &gt;= #{createdFrom}</if>
          <if test="createdTo != null">AND created_at &lt; #{createdTo}</if>
        </script>
        """)
    long count(
        @Param("datasetId") Long datasetId,
        @Param("resourceType") String resourceType,
        @Param("resourceId") Long resourceId,
        @Param("action") String action,
        @Param("actorId") Long actorId,
        @Param("createdFrom") LocalDateTime createdFrom,
        @Param("createdTo") LocalDateTime createdTo
    );

    /**
     * 获取{@code ById}。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, dataset_id, resource_type, resource_id, action,
               before_json::text AS before_json, after_json::text AS after_json,
               before_hash, after_hash, actor_id, created_at
        FROM agent_data_metadata_change
        WHERE id = #{id}
        """)
    MetadataChangeRow selectById(@Param("id") Long id);

    /**
     * 处理统计并返回对应结果。
     *
     * @param createdFrom {@code createdFrom}参数
     * @param createdTo {@code createdTo}参数
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT resource_type, action, COUNT(*) AS change_count
        FROM agent_data_metadata_change
        WHERE created_at >= #{createdFrom} AND created_at < #{createdTo}
        GROUP BY resource_type, action
        ORDER BY change_count DESC, resource_type, action
        """)
    List<MetadataChangelogStatisticRow> statistics(
        @Param("createdFrom") LocalDateTime createdFrom,
        @Param("createdTo") LocalDateTime createdTo
    );
}
