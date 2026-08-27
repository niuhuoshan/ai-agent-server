package group.aitools.nhs.platform.memory.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.memory.domain.AgentMemory;
import group.aitools.nhs.platform.memory.domain.MemoryRuntimeConfig;
import group.aitools.nhs.platform.memory.domain.MemoryEmbeddedRow;
import group.aitools.nhs.platform.memory.domain.MemoryVectorMatch;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定义记忆目录相关的数据访问契约。
 */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface MemoryCatalogMapper {

    String COLUMNS = """
        id, memory_key, scope_type, scope_id, memory_type, content, content_hash,
        source_type, source_id, confidence, sensitive_level, review_status,
        embedding_model_id, embedding_dimension, expires_at,
        metadata_json::text AS metadata_json, revision_no, reviewed_by, reviewed_at,
        review_comment, created_by, created_at, updated_at, del_flag
        """;

    /**
     * 获取{@code ById}。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("SELECT " + COLUMNS + " FROM agent_memory WHERE id = #{id} AND del_flag = '0'")
    AgentMemory selectById(@Param("id") Long id);

    /**
     * 获取By范围AndKey。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param memoryKey 记忆Key参数
     * @return 处理结果
     */
    @Select("SELECT " + COLUMNS + " FROM agent_memory WHERE scope_type = #{scopeType} "
        + "AND scope_id = #{scopeId} AND memory_key = #{memoryKey} AND del_flag = '0'")
    AgentMemory selectByScopeAndKey(
        @Param("scopeType") String scopeType,
        @Param("scopeId") Long scopeId,
        @Param("memoryKey") String memoryKey
    );

    /**
     * 处理{@code lockById}并返回对应结果。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("SELECT id FROM agent_memory WHERE id = #{id} AND del_flag = '0' FOR UPDATE")
    Long lockById(@Param("id") Long id);

    /**
     * 获取范围Memories。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param includeSensitive {@code includeSensitive}参数
     * @param search {@code search}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT
        """ + COLUMNS + """
        FROM agent_memory
        WHERE scope_type = #{scopeType} AND scope_id = #{scopeId} AND del_flag = '0'
          <if test="!includeSensitive">
            AND sensitive_level IN ('public', 'internal')
          </if>
          <if test="search != null and search != ''">
            AND (search_vector @@ plainto_tsquery('simple', #{search})
                 OR position(lower(#{search}) in lower(content)) > 0
                 OR position(lower(#{search}) in lower(memory_key)) > 0)
          </if>
        ORDER BY CASE review_status WHEN 'pending' THEN 0 WHEN 'approved' THEN 1 ELSE 2 END,
                 updated_at DESC NULLS LAST, created_at DESC, id DESC
        LIMIT #{limit}
        </script>
        """)
    List<AgentMemory> selectScopeMemories(
        @Param("scopeType") String scopeType,
        @Param("scopeId") Long scopeId,
        @Param("includeSensitive") boolean includeSensitive,
        @Param("search") String search,
        @Param("limit") int limit
    );

    /**
     * 判断{@code GeneratedSearchVector}是否满足要求。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    @Select("""
        SELECT EXISTS (
            SELECT 1
            FROM pg_catalog.pg_attribute attribute
            JOIN pg_catalog.pg_class relation ON relation.oid = attribute.attrelid
            JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
            WHERE namespace.nspname = current_schema()
              AND relation.relname = 'agent_memory'
              AND attribute.attname = 'search_vector'
              AND attribute.atttypid = 'tsvector'::regtype
              AND attribute.attgenerated = 's'
              AND NOT attribute.attisdropped
        )
        """)
    boolean hasGeneratedSearchVector();

    /**
     * 判断{@code ValidLexicalIndex}是否满足要求。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    @Select("""
        SELECT EXISTS (
            SELECT 1
            FROM pg_catalog.pg_index index_state
            JOIN pg_catalog.pg_class index_relation ON index_relation.oid = index_state.indexrelid
            JOIN pg_catalog.pg_class table_relation ON table_relation.oid = index_state.indrelid
            JOIN pg_catalog.pg_namespace namespace ON namespace.oid = table_relation.relnamespace
            WHERE namespace.nspname = current_schema()
              AND table_relation.relname = 'agent_memory'
              AND index_relation.relname = 'idx_agent_memory_lexical'
              AND index_state.indisready
              AND index_state.indisvalid
              AND pg_get_indexdef(index_relation.oid) ILIKE '%USING gin (search_vector)%'
        )
        """)
    boolean hasValidLexicalIndex();

    /**
     * 处理{@code countSearchDocuments}并返回对应结果。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT COUNT(*)
        FROM agent_memory
        WHERE scope_type = #{scopeType}
          AND scope_id = #{scopeId}
          AND del_flag = '0'
          AND COALESCE(metadata_json ->> 'kind', '') <> 'memory_config'
        """)
    long countSearchDocuments(
        @Param("scopeType") String scopeType,
        @Param("scopeId") Long scopeId
    );

    /**
     * 获取运行时Config。
     *
     * @return 处理结果
     */
    @Select("""
        SELECT id, enabled, summary_enabled, embedding_model_id, embedding_dimension,
               search_knn_top_k, vector_weight::double precision AS vector_weight,
               consolidation_threshold::double precision AS consolidation_threshold,
               base_half_life_days::double precision AS base_half_life_days,
               summary_ttl_days, revision_no, updated_by, updated_at
        FROM agent_memory_runtime_config
        WHERE id = 1
        """)
    MemoryRuntimeConfig selectRuntimeConfig();

    /**
     * 更新运行时Config。
     *
     * @param config {@code config}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_memory_runtime_config
        SET enabled = #{enabled}, summary_enabled = #{summaryEnabled},
            embedding_model_id = #{embeddingModelId}, embedding_dimension = #{embeddingDimension},
            search_knn_top_k = #{searchKnnTopK}, vector_weight = #{vectorWeight},
            consolidation_threshold = #{consolidationThreshold},
            base_half_life_days = #{baseHalfLifeDays}, summary_ttl_days = #{summaryTtlDays},
            revision_no = revision_no + 1, updated_by = #{updatedBy}, updated_at = #{updatedAt}
        WHERE id = 1 AND revision_no = #{revisionNo}
        """)
    int updateRuntimeConfig(MemoryRuntimeConfig config);

    /**
     * 判断{@code VectorExtension}是否满足要求。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    @Select("SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_extension WHERE extname = 'vector')")
    boolean hasVectorExtension();

    /**
     * 处理{@code countEmbeddedMemories}并返回对应结果。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param modelId 资源标识
     * @param dimension {@code dimension}参数
     * @return 处理结果
     */
    @Select("""
        SELECT COUNT(*)
        FROM agent_memory
        WHERE scope_type = #{scopeType} AND scope_id = #{scopeId}
          AND review_status = 'approved' AND del_flag = '0'
          AND embedding_model_id = #{modelId} AND embedding_dimension = #{dimension}
          AND embedding IS NOT NULL AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
          AND COALESCE(metadata_json ->> 'kind', '') <> 'memory_config'
        """)
    long countEmbeddedMemories(
        @Param("scopeType") String scopeType,
        @Param("scopeId") Long scopeId,
        @Param("modelId") Long modelId,
        @Param("dimension") Integer dimension
    );

    /**
     * 获取{@code MemoriesMissingEmbedding}。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param modelId 资源标识
     * @param dimension {@code dimension}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT
        """ + COLUMNS + """
        FROM agent_memory
        WHERE review_status = 'approved' AND del_flag = '0'
          AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
          AND COALESCE(metadata_json ->> 'kind', '') &lt;&gt; 'memory_config'
          <if test="scopeType != null and scopeType != ''">
            AND scope_type = #{scopeType}
          </if>
          <if test="scopeId != null">
            AND scope_id = #{scopeId}
          </if>
          AND (embedding IS NULL OR embedding_model_id &lt;&gt; #{modelId}
               OR embedding_dimension &lt;&gt; #{dimension})
        ORDER BY COALESCE(updated_at, created_at) DESC, id DESC
        LIMIT #{limit}
        </script>
        """)
    List<AgentMemory> selectMemoriesMissingEmbedding(
        @Param("scopeType") String scopeType,
        @Param("scopeId") Long scopeId,
        @Param("modelId") Long modelId,
        @Param("dimension") Integer dimension,
        @Param("limit") int limit
    );

    /**
     * 更新{@code Embedding}。
     *
     * @param id 资源标识
     * @param modelId 资源标识
     * @param dimension {@code dimension}参数
     * @param embedding {@code embedding}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_memory
        SET embedding_model_id = #{modelId}, embedding_dimension = #{dimension},
            embedding = CAST(#{embedding} AS vector)
        WHERE id = #{id} AND review_status = 'approved' AND del_flag = '0'
        """)
    int updateEmbedding(
        @Param("id") Long id,
        @Param("modelId") Long modelId,
        @Param("dimension") Integer dimension,
        @Param("embedding") String embedding
    );

    /**
     * 查询{@code ByVector}列表。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param modelId 资源标识
     * @param dimension {@code dimension}参数
     * @param embedding {@code embedding}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, memory_key, memory_type, content, source_type, source_id,
               confidence, sensitive_level, metadata_json::text AS metadata_json,
               expires_at, updated_at,
               GREATEST(0.0, LEAST(1.0,
               1.0 - (embedding <=> CAST(#{embedding} AS vector))
               ))::double precision AS vector_score
        FROM agent_memory
        WHERE scope_type = #{scopeType} AND scope_id = #{scopeId}
          AND review_status = 'approved' AND del_flag = '0'
          AND sensitive_level IN ('public', 'internal')
          AND embedding_model_id = #{modelId} AND embedding_dimension = #{dimension}
          AND embedding IS NOT NULL AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
          AND COALESCE(metadata_json ->> 'kind', '') <> 'memory_config'
        ORDER BY embedding <=> CAST(#{embedding} AS vector), id
        LIMIT #{limit}
        """)
    List<MemoryVectorMatch> searchByVector(
        @Param("scopeType") String scopeType,
        @Param("scopeId") Long scopeId,
        @Param("modelId") Long modelId,
        @Param("dimension") Integer dimension,
        @Param("embedding") String embedding,
        @Param("limit") int limit
    );

    /**
     * 获取Embedded会话Memories。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param modelId 资源标识
     * @param dimension {@code dimension}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, memory_key, content, metadata_json::text AS metadata_json,
               embedding::text AS embedding, revision_no, updated_at
        FROM agent_memory
        WHERE scope_type = #{scopeType} AND scope_id = #{scopeId}
          AND memory_type = 'summary' AND source_type = 'conversation'
          AND review_status = 'approved' AND del_flag = '0'
          AND embedding_model_id = #{modelId} AND embedding_dimension = #{dimension}
          AND embedding IS NOT NULL AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
          AND COALESCE(metadata_json ->> 'kind', '') = 'session_summary'
        ORDER BY COALESCE(updated_at, created_at) DESC, id DESC
        LIMIT #{limit}
        """)
    List<MemoryEmbeddedRow> selectEmbeddedSessionMemories(
        @Param("scopeType") String scopeType,
        @Param("scopeId") Long scopeId,
        @Param("modelId") Long modelId,
        @Param("dimension") Integer dimension,
        @Param("limit") int limit
    );

    /**
     * 获取ApprovedFor快照。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT
        """ + COLUMNS + """
        FROM agent_memory
        WHERE scope_type = #{scopeType} AND scope_id = #{scopeId}
          AND review_status = 'approved' AND del_flag = '0'
          AND sensitive_level IN ('public', 'internal')
          AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
        ORDER BY COALESCE(updated_at, created_at) DESC, id DESC
        LIMIT #{limit}
        """)
    List<AgentMemory> selectApprovedForSnapshot(
        @Param("scopeType") String scopeType,
        @Param("scopeId") Long scopeId,
        @Param("limit") int limit
    );

    /**
     * 创建并保存记忆。
     *
     * @param memory 记忆参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_memory (
            id, memory_key, scope_type, scope_id, memory_type, content, content_hash,
            source_type, source_id, confidence, sensitive_level, review_status,
            expires_at, metadata_json, revision_no, reviewed_by, reviewed_at,
            review_comment, created_by, created_at, updated_at, del_flag
        ) VALUES (
            #{id}, #{memoryKey}, #{scopeType}, #{scopeId}, #{memoryType}, #{content},
            #{contentHash}, #{sourceType}, #{sourceId}, #{confidence}, #{sensitiveLevel},
            #{reviewStatus}, #{expiresAt}, CAST(#{metadataJson} AS jsonb), #{revisionNo},
            #{reviewedBy}, #{reviewedAt}, #{reviewComment}, #{createdBy}, #{createdAt},
            #{updatedAt}, '0'
        )
        """)
    int insertMemory(AgentMemory memory);

    /**
     * 更新记忆。
     *
     * @param memory 记忆参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_memory
        SET memory_type = #{memoryType}, content = #{content}, content_hash = #{contentHash},
            source_type = #{sourceType}, source_id = #{sourceId}, confidence = #{confidence},
            sensitive_level = #{sensitiveLevel}, review_status = #{reviewStatus},
            embedding_model_id = NULL, embedding_dimension = NULL, embedding = NULL,
            expires_at = #{expiresAt}, metadata_json = CAST(#{metadataJson} AS jsonb),
            reviewed_by = #{reviewedBy}, reviewed_at = #{reviewedAt},
            review_comment = #{reviewComment}, revision_no = revision_no + 1,
            updated_at = #{updatedAt}
        WHERE id = #{id} AND revision_no = #{revisionNo} AND del_flag = '0'
        """)
    int updateMemory(AgentMemory memory);

    /**
     * 处理review记忆并返回对应结果。
     *
     * @param id 资源标识
     * @param revision {@code revision}参数
     * @param decision {@code decision}参数
     * @param reviewerId 资源标识
     * @param comment {@code comment}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_memory
        SET review_status = #{decision}, reviewed_by = #{reviewerId}, reviewed_at = #{now},
            review_comment = #{comment}, revision_no = revision_no + 1, updated_at = #{now}
        WHERE id = #{id} AND revision_no = #{revision} AND review_status = 'pending'
          AND del_flag = '0'
        """)
    int reviewMemory(
        @Param("id") Long id,
        @Param("revision") Long revision,
        @Param("decision") String decision,
        @Param("reviewerId") Long reviewerId,
        @Param("comment") String comment,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code softDelete}并返回对应结果。
     *
     * @param id 资源标识
     * @param revision {@code revision}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_memory
        SET del_flag = '1', revision_no = revision_no + 1, updated_at = #{now}
        WHERE id = #{id} AND revision_no = #{revision} AND del_flag = '0'
        """)
    int softDelete(
        @Param("id") Long id,
        @Param("revision") Long revision,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code softDeleteBatch}并返回对应结果。
     *
     * @param revisions {@code revisions}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        <script>
        UPDATE agent_memory
        SET del_flag = '1', revision_no = revision_no + 1, updated_at = #{now}
        WHERE del_flag = '0'
          AND (id, revision_no) IN
          <foreach collection="revisions" item="revision" open="(" separator="," close=")">
            (#{revision.id}, #{revision.revisionNo})
          </foreach>
        </script>
        """)
    int softDeleteBatch(
        @Param("revisions") List<MemoryRevision> revisions,
        @Param("now") LocalDateTime now
    );

    /**
     * 封装记忆Revision相关的不可变数据。
     */
    record MemoryRevision(Long id, Long revisionNo) {
    }
}
