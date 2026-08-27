package group.aitools.nhs.platform.knowledge.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeBase;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeChunk;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeDirectory;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeDocument;
import group.aitools.nhs.platform.knowledge.persistence.row.KnowledgeParseJobRow;
import group.aitools.nhs.platform.knowledge.persistence.row.KnowledgeRetrievalRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定义知识库目录相关的数据访问契约。
 */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface KnowledgeCatalogMapper {

    String BASE_COLUMNS = """
        id, knowledge_key, name, description, provider_type, connector_id, external_id,
        visibility, status, config_json::text AS config_json, owner_id, revision_no,
        create_by, create_time, update_by, update_time, del_flag, extra_json::text AS extra_json
        """;

    String DOCUMENT_COLUMNS = """
        id, knowledge_base_id, document_key, name, artifact_id, external_id, content_hash,
        parser_type, status, chunk_count, metadata_json::text AS metadata_json, error_summary,
        storage_type, storage_ref, mime_type, size_bytes, directory_id,
        catalog_revision_no, tags_json::text AS tags_json, remark, revision_no, parse_started_at,
        processed_at, created_by, created_at, updated_at, del_flag
        """;

    String DIRECTORY_COLUMNS = """
        d.id, d.knowledge_base_id, d.parent_id, d.directory_key, d.name, d.revision_no,
        d.created_by, d.created_at, d.updated_by, d.updated_at, d.del_flag
        """;

    /**
     * 获取{@code Bases}。
     *
     * @param search {@code search}参数
     * @param includeInactive {@code includeInactive}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT
        """ + BASE_COLUMNS + """
        FROM agent_knowledge_base
        WHERE del_flag = '0'
        <if test="!includeInactive">AND status = 'active'</if>
        <if test="search != null and search != ''">
          AND (position(lower(#{search}) in lower(name)) > 0
               OR position(lower(#{search}) in lower(knowledge_key)) > 0)
        </if>
        ORDER BY name, id
        LIMIT #{limit}
        </script>
        """)
    List<AgentKnowledgeBase> selectBases(
        @Param("search") String search,
        @Param("includeInactive") boolean includeInactive,
        @Param("limit") int limit
    );

    /**
     * 获取{@code BaseById}。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("SELECT " + BASE_COLUMNS + " FROM agent_knowledge_base WHERE id = #{id} AND del_flag = '0'")
    AgentKnowledgeBase selectBaseById(@Param("id") Long id);

    /**
     * 处理{@code lockBase}并返回对应结果。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("SELECT id FROM agent_knowledge_base WHERE id = #{id} AND del_flag = '0' FOR UPDATE")
    Long lockBase(@Param("id") Long id);

    /**
     * 创建并保存{@code Base}。
     *
     * @param base {@code base}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_knowledge_base (
            id, knowledge_key, name, description, provider_type, visibility, status,
            config_json, owner_id, revision_no, create_by, create_time, del_flag, extra_json
        ) VALUES (
            #{id}, #{knowledgeKey}, #{name}, #{description}, #{providerType}, #{visibility},
            #{status}, CAST(#{configJson} AS jsonb), #{ownerId}, #{revisionNo}, #{createBy},
            #{createTime}, '0', CAST(#{extraJson} AS jsonb)
        )
        """)
    int insertBase(AgentKnowledgeBase base);

    /**
     * 更新{@code Base}。
     *
     * @param base {@code base}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_knowledge_base
        SET name = #{name}, description = #{description}, visibility = #{visibility},
            status = #{status}, config_json = CAST(#{configJson} AS jsonb),
            revision_no = revision_no + 1, update_by = #{updateBy}, update_time = #{updateTime}
        WHERE id = #{id} AND del_flag = '0' AND revision_no = #{revisionNo}
        """)
    int updateBase(AgentKnowledgeBase base);

    /**
     * 处理{@code softDeleteBase}并返回对应结果。
     *
     * @param id 资源标识
     * @param revision {@code revision}参数
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_knowledge_base
        SET status = 'disabled', del_flag = '1', revision_no = revision_no + 1,
            update_by = #{actorId}, update_time = #{now}
        WHERE id = #{id} AND del_flag = '0' AND revision_no = #{revision}
        """)
    int softDeleteBase(
        @Param("id") Long id,
        @Param("revision") Long revision,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code countActiveReferences}并返回对应结果。
     *
     * @param baseId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT count(*) FROM agent_agent_version_knowledge b
        JOIN agent_definition_version v ON v.id = b.agent_version_id
        WHERE b.resource_id = #{baseId} AND v.status IN ('draft', 'published')
        """)
    int countActiveReferences(@Param("baseId") Long baseId);

    /**
     * 获取{@code Directories}。
     *
     * @param baseId 资源标识
     * @return 符合条件的数据集合
     */
    @Select("SELECT " + DIRECTORY_COLUMNS + ",\n"
        + "  (SELECT count(*) FROM agent_knowledge_document doc\n"
        + "   WHERE doc.knowledge_base_id = d.knowledge_base_id AND doc.directory_id = d.id\n"
        + "     AND doc.del_flag = '0' AND doc.status <> 'deleted') AS document_count,\n"
        + "  (SELECT count(*) FROM agent_knowledge_directory child\n"
        + "   WHERE child.knowledge_base_id = d.knowledge_base_id AND child.parent_id = d.id\n"
        + "     AND child.del_flag = '0') AS child_directory_count\n"
        + "FROM agent_knowledge_directory d\n"
        + "WHERE d.knowledge_base_id = #{baseId} AND d.del_flag = '0'\n"
        + "ORDER BY lower(d.name), d.id")
    List<AgentKnowledgeDirectory> selectDirectories(@Param("baseId") Long baseId);

    /**
     * 获取目录ById。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("SELECT " + DIRECTORY_COLUMNS + ",\n"
        + "  (SELECT count(*) FROM agent_knowledge_document doc\n"
        + "   WHERE doc.knowledge_base_id = d.knowledge_base_id AND doc.directory_id = d.id\n"
        + "     AND doc.del_flag = '0' AND doc.status <> 'deleted') AS document_count,\n"
        + "  (SELECT count(*) FROM agent_knowledge_directory child\n"
        + "   WHERE child.knowledge_base_id = d.knowledge_base_id AND child.parent_id = d.id\n"
        + "     AND child.del_flag = '0') AS child_directory_count\n"
        + "FROM agent_knowledge_directory d\n"
        + "WHERE d.id = #{id} AND d.del_flag = '0'")
    AgentKnowledgeDirectory selectDirectoryById(@Param("id") Long id);

    /**
     * 处理lock目录并返回对应结果。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("SELECT id FROM agent_knowledge_directory WHERE id = #{id} AND del_flag = '0' FOR UPDATE")
    Long lockDirectory(@Param("id") Long id);

    /**
     * 获取目录NameConflict。
     *
     * @param baseId 资源标识
     * @param parentId 资源标识
     * @param name 名称
     * @param excludeId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id FROM agent_knowledge_directory
        WHERE knowledge_base_id = #{baseId} AND del_flag = '0'
          AND ((parent_id IS NULL AND #{parentId} IS NULL) OR parent_id = #{parentId})
          AND lower(name) = lower(#{name})
          AND (#{excludeId} IS NULL OR id <> #{excludeId})
        LIMIT 1
        """)
    Long selectDirectoryNameConflict(
        @Param("baseId") Long baseId,
        @Param("parentId") Long parentId,
        @Param("name") String name,
        @Param("excludeId") Long excludeId
    );

    /**
     * 判断didateParentContains目录是否满足要求。
     *
     * @param baseId 资源标识
     * @param candidateParentId 资源标识
     * @param directoryId 资源标识
     * @return 判断结果，{@code true} 表示条件成立
     */
    @Select("""
        WITH RECURSIVE ancestors AS (
          SELECT id, parent_id, ARRAY[id] AS path
          FROM agent_knowledge_directory
          WHERE id = #{candidateParentId} AND knowledge_base_id = #{baseId} AND del_flag = '0'
          UNION ALL
          SELECT parent.id, parent.parent_id, ancestors.path || parent.id
          FROM agent_knowledge_directory parent
          JOIN ancestors ON parent.id = ancestors.parent_id
          WHERE parent.knowledge_base_id = #{baseId} AND parent.del_flag = '0'
            AND NOT (parent.id = ANY(ancestors.path))
        )
        SELECT EXISTS(SELECT 1 FROM ancestors WHERE id = #{directoryId})
        """)
    boolean candidateParentContainsDirectory(
        @Param("baseId") Long baseId,
        @Param("candidateParentId") Long candidateParentId,
        @Param("directoryId") Long directoryId
    );

    /**
     * 创建并保存目录。
     *
     * @param directory 目录参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_knowledge_directory (
            id, knowledge_base_id, parent_id, directory_key, name, revision_no,
            created_by, created_at, del_flag
        ) VALUES (
            #{id}, #{knowledgeBaseId}, #{parentId}, #{directoryKey}, #{name}, #{revisionNo},
            #{createdBy}, #{createdAt}, '0'
        )
        """)
    int insertDirectory(AgentKnowledgeDirectory directory);

    /**
     * 更新目录。
     *
     * @param directory 目录参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_knowledge_directory
        SET parent_id = #{parentId}, name = #{name}, revision_no = revision_no + 1,
            updated_by = #{updatedBy}, updated_at = #{updatedAt}
        WHERE id = #{id} AND knowledge_base_id = #{knowledgeBaseId} AND del_flag = '0'
          AND revision_no = #{revisionNo}
        """)
    int updateDirectory(AgentKnowledgeDirectory directory);

    /**
     * 处理count目录Entries并返回对应结果。
     *
     * @param baseId 资源标识
     * @param directoryId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT
          (SELECT count(*) FROM agent_knowledge_directory
           WHERE knowledge_base_id = #{baseId} AND parent_id = #{directoryId} AND del_flag = '0')
          +
          (SELECT count(*) FROM agent_knowledge_document
           WHERE knowledge_base_id = #{baseId} AND directory_id = #{directoryId}
             AND del_flag = '0' AND status <> 'deleted')
        """)
    long countDirectoryEntries(
        @Param("baseId") Long baseId,
        @Param("directoryId") Long directoryId
    );

    /**
     * 处理softDelete目录并返回对应结果。
     *
     * @param baseId 资源标识
     * @param id 资源标识
     * @param revision {@code revision}参数
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_knowledge_directory
        SET del_flag = '1', revision_no = revision_no + 1,
            updated_by = #{actorId}, updated_at = #{now}
        WHERE id = #{id} AND knowledge_base_id = #{baseId} AND del_flag = '0'
          AND revision_no = #{revision}
        """)
    int softDeleteDirectory(
        @Param("baseId") Long baseId,
        @Param("id") Long id,
        @Param("revision") Long revision,
        @Param("actorId") Long actorId,
        @Param("now") LocalDateTime now
    );

    /**
     * 获取{@code Documents}。
     *
     * @param baseId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT
        """ + DOCUMENT_COLUMNS + """
        FROM agent_knowledge_document
        WHERE knowledge_base_id = #{baseId} AND del_flag = '0'
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit}
        """)
    List<AgentKnowledgeDocument> selectDocuments(
        @Param("baseId") Long baseId,
        @Param("limit") int limit
    );

    /**
     * 获取{@code Chunks}。
     *
     * @param documentId 资源标识
     * @param offset 起始位置或序号
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT c.id, c.knowledge_base_id, c.document_id, c.chunk_no, c.content, c.content_hash,
               c.token_count, c.embedding_model_id, c.embedding_dimension,
               c.metadata_json::text AS metadata_json, c.status, c.created_at
        FROM agent_knowledge_chunk c
        JOIN agent_knowledge_document d ON d.id = c.document_id
          AND d.del_flag = '0' AND d.status = 'ready'
        WHERE c.document_id = #{documentId} AND c.status = 'active'
        ORDER BY c.chunk_no, c.id
        OFFSET #{offset} LIMIT #{limit}
        """)
    List<AgentKnowledgeChunk> selectChunks(
        @Param("documentId") Long documentId,
        @Param("offset") int offset,
        @Param("limit") int limit
    );

    /**
     * 获取文档ById。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("SELECT " + DOCUMENT_COLUMNS + " FROM agent_knowledge_document WHERE id = #{id} AND del_flag = '0'")
    AgentKnowledgeDocument selectDocumentById(@Param("id") Long id);

    /**
     * 处理lock文档并返回对应结果。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("SELECT id FROM agent_knowledge_document WHERE id = #{id} AND del_flag = '0' FOR UPDATE")
    Long lockDocument(@Param("id") Long id);

    /**
     * 获取Duplicate文档。
     *
     * @param baseId 资源标识
     * @param contentHash 待处理内容
     * @return 处理结果
     */
    @Select("""
        SELECT id FROM agent_knowledge_document
        WHERE knowledge_base_id = #{baseId} AND content_hash = #{contentHash}
          AND del_flag = '0' AND status != 'deleted'
        LIMIT 1
        """)
    Long selectDuplicateDocument(
        @Param("baseId") Long baseId,
        @Param("contentHash") String contentHash
    );

    /**
     * 创建并保存文档。
     *
     * @param document 文档参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_knowledge_document (
            id, knowledge_base_id, document_key, name, content_hash, parser_type, status,
            chunk_count, metadata_json, storage_type, storage_ref, mime_type, size_bytes,
            directory_id, catalog_revision_no, tags_json, remark,
            revision_no, created_by, created_at, updated_at, del_flag
        ) VALUES (
            #{id}, #{knowledgeBaseId}, #{documentKey}, #{name}, #{contentHash}, #{parserType},
            #{status}, #{chunkCount}, CAST(#{metadataJson} AS jsonb), #{storageType}, #{storageRef},
            #{mimeType}, #{sizeBytes}, #{directoryId}, COALESCE(#{catalogRevisionNo}, 1),
            COALESCE(CAST(#{tagsJson} AS jsonb), '[]'::jsonb), #{remark}, #{revisionNo}, #{createdBy}, #{createdAt},
            #{updatedAt}, '0'
        )
        """)
    int insertDocument(AgentKnowledgeDocument document);

    /**
     * 更新文档目录。
     *
     * @param document 文档参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_knowledge_document
        SET name = #{name}, directory_id = #{directoryId},
            tags_json = CAST(#{tagsJson} AS jsonb), remark = #{remark},
            metadata_json = jsonb_set(
                jsonb_set(COALESCE(metadata_json, '{}'::jsonb), '{tags}',
                    CAST(#{tagsJson} AS jsonb), true),
                '{remark}', to_jsonb(COALESCE(CAST(#{remark} AS text), ''::text)), true
            ),
            catalog_revision_no = catalog_revision_no + 1, updated_at = #{updatedAt}
        WHERE id = #{id} AND knowledge_base_id = #{knowledgeBaseId} AND del_flag = '0'
          AND status <> 'deleted' AND catalog_revision_no = #{catalogRevisionNo}
        """)
    int updateDocumentCatalog(AgentKnowledgeDocument document);

    /**
     * 处理queue文档并返回对应结果。
     *
     * @param id 资源标识
     * @param revision {@code revision}参数
     * @param incrementRevision {@code incrementRevision}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_knowledge_document
        SET status = 'pending', error_summary = NULL,
            revision_no = revision_no + #{incrementRevision}, updated_at = #{now}
        WHERE id = #{id} AND del_flag = '0' AND revision_no = #{revision}
          AND status IN ('pending', 'ready', 'failed')
        """)
    int queueDocument(
        @Param("id") Long id,
        @Param("revision") Long revision,
        @Param("incrementRevision") int incrementRevision,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理mark文档Processing并返回对应结果。
     *
     * @param id 资源标识
     * @param revision {@code revision}参数
     * @param allowResume {@code allowResume}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_knowledge_document
        SET status = 'processing', parse_started_at = #{now}, error_summary = NULL,
            updated_at = #{now}
        WHERE id = #{id} AND del_flag = '0' AND revision_no = #{revision}
          AND (status = 'pending' OR (#{allowResume} = TRUE AND status = 'processing'))
        """)
    int markDocumentProcessing(
        @Param("id") Long id,
        @Param("revision") Long revision,
        @Param("allowResume") boolean allowResume,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理fail文档并返回对应结果。
     *
     * @param id 资源标识
     * @param revision {@code revision}参数
     * @param error {@code error}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_knowledge_document
        SET status = 'failed', error_summary = #{error}, processed_at = #{now}, updated_at = #{now}
        WHERE id = #{id} AND del_flag = '0' AND revision_no = #{revision}
          AND status IN ('pending', 'processing')
        """)
    int failDocument(
        @Param("id") Long id,
        @Param("revision") Long revision,
        @Param("error") String error,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理retry文档并返回对应结果。
     *
     * @param id 资源标识
     * @param revision {@code revision}参数
     * @param error {@code error}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_knowledge_document
        SET status = 'pending', error_summary = #{error}, updated_at = #{now}
        WHERE id = #{id} AND del_flag = '0' AND revision_no = #{revision}
          AND status IN ('pending', 'processing', 'failed')
        """)
    int retryDocument(
        @Param("id") Long id,
        @Param("revision") Long revision,
        @Param("error") String error,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理complete文档并返回对应结果。
     *
     * @param id 资源标识
     * @param revision {@code revision}参数
     * @param parserType 业务类型
     * @param chunkCount {@code chunkCount}参数
     * @param metadataJson 元数据Json参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_knowledge_document
        SET status = 'ready', parser_type = #{parserType}, chunk_count = #{chunkCount},
            metadata_json = CAST(#{metadataJson} AS jsonb), error_summary = NULL,
            processed_at = #{now}, updated_at = #{now}
        WHERE id = #{id} AND del_flag = '0' AND revision_no = #{revision}
          AND status = 'processing'
        """)
    int completeDocument(
        @Param("id") Long id,
        @Param("revision") Long revision,
        @Param("parserType") String parserType,
        @Param("chunkCount") int chunkCount,
        @Param("metadataJson") String metadataJson,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理softDelete文档并返回对应结果。
     *
     * @param baseId 资源标识
     * @param id 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_knowledge_document
        SET status = 'deleted', del_flag = '1', updated_at = #{now}
        WHERE id = #{id} AND knowledge_base_id = #{baseId} AND del_flag = '0'
          AND status != 'processing'
        """)
    int softDeleteDocument(
        @Param("baseId") Long baseId,
        @Param("id") Long id,
        @Param("now") LocalDateTime now
    );

    /**
     * 删除文档Chunks。
     *
     * @param documentId 资源标识
     * @return 处理结果
     */
    @Delete("DELETE FROM agent_knowledge_chunk WHERE document_id = #{documentId}")
    int deleteDocumentChunks(@Param("documentId") Long documentId);

    /**
     * 创建并保存{@code Chunk}。
     *
     * @param chunk {@code chunk}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_knowledge_chunk (
            id, knowledge_base_id, document_id, chunk_no, content, content_hash,
            token_count, embedding_model_id, embedding_dimension, embedding,
            metadata_json, status, created_at
        ) VALUES (
            #{id}, #{knowledgeBaseId}, #{documentId}, #{chunkNo}, #{content}, #{contentHash},
            #{tokenCount}, #{embeddingModelId}, #{embeddingDimension},
            CAST(#{embedding} AS vector), CAST(#{metadataJson} AS jsonb), 'active', #{createdAt}
        )
        """)
    int insertChunk(AgentKnowledgeChunk chunk);

    /**
     * 查询{@code Lexical}列表。
     *
     * @param baseId 资源标识
     * @param query 查询参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT c.id AS chunk_id, c.knowledge_base_id, c.document_id, d.name AS document_name,
               c.chunk_no, c.content, c.metadata_json::text AS metadata_json,
               GREATEST(
                 ts_rank_cd(c.search_vector, plainto_tsquery('simple', #{query})),
                 CASE WHEN position(lower(#{query}) in lower(c.content)) > 0 THEN 1.0 ELSE 0.0 END
               )::double precision AS score
        FROM agent_knowledge_chunk c
        JOIN agent_knowledge_document d ON d.id = c.document_id AND d.del_flag = '0' AND d.status = 'ready'
        WHERE c.knowledge_base_id = #{baseId} AND c.status = 'active'
          AND (c.search_vector @@ plainto_tsquery('simple', #{query})
               OR position(lower(#{query}) in lower(c.content)) > 0)
        ORDER BY score DESC, c.document_id, c.chunk_no
        LIMIT #{limit}
        </script>
        """)
    List<KnowledgeRetrievalRow> searchLexical(
        @Param("baseId") Long baseId,
        @Param("query") String query,
        @Param("limit") int limit
    );

    /**
     * 查询{@code LexicalScoped}列表。
     *
     * @param baseId 资源标识
     * @param query 查询参数
     * @param limit 数量上限
     * @param directoryIds 资源标识集合
     * @param includeRoot {@code includeRoot}参数
     * @param includeAll {@code includeAll}参数
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT c.id AS chunk_id, c.knowledge_base_id, c.document_id, d.name AS document_name,
               c.chunk_no, c.content, c.metadata_json::text AS metadata_json,
               GREATEST(
                 ts_rank_cd(c.search_vector, plainto_tsquery('simple', #{query})),
                 CASE WHEN position(lower(#{query}) in lower(c.content)) > 0 THEN 1.0 ELSE 0.0 END
               )::double precision AS score
        FROM agent_knowledge_chunk c
        JOIN agent_knowledge_document d ON d.id = c.document_id AND d.del_flag = '0' AND d.status = 'ready'
        WHERE c.knowledge_base_id = #{baseId} AND c.status = 'active'
          AND (c.search_vector @@ plainto_tsquery('simple', #{query})
               OR position(lower(#{query}) in lower(c.content)) > 0)
          AND (
            #{includeAll} = TRUE
            OR (#{includeRoot} = TRUE AND d.directory_id IS NULL)
            <if test="directoryIds != null and directoryIds.size() > 0">
              OR d.directory_id IN
              <foreach collection="directoryIds" item="directoryId" open="(" separator="," close=")">
                #{directoryId}
              </foreach>
            </if>
          )
        ORDER BY score DESC, c.document_id, c.chunk_no
        LIMIT #{limit}
        </script>
        """)
    List<KnowledgeRetrievalRow> searchLexicalScoped(
        @Param("baseId") Long baseId,
        @Param("query") String query,
        @Param("limit") int limit,
        @Param("directoryIds") List<Long> directoryIds,
        @Param("includeRoot") boolean includeRoot,
        @Param("includeAll") boolean includeAll
    );

    /**
     * 查询{@code Vector}列表。
     *
     * @param baseId 资源标识
     * @param modelId 资源标识
     * @param dimension {@code dimension}参数
     * @param embedding {@code embedding}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT c.id AS chunk_id, c.knowledge_base_id, c.document_id, d.name AS document_name,
               c.chunk_no, c.content, c.metadata_json::text AS metadata_json,
               (1 - (c.embedding &lt;=> CAST(#{embedding} AS vector)))::double precision AS score
        FROM agent_knowledge_chunk c
        JOIN agent_knowledge_document d ON d.id = c.document_id AND d.del_flag = '0' AND d.status = 'ready'
        WHERE c.knowledge_base_id = #{baseId} AND c.status = 'active'
          AND c.embedding_model_id = #{modelId} AND c.embedding_dimension = #{dimension}
          AND c.embedding IS NOT NULL
        ORDER BY c.embedding &lt;=> CAST(#{embedding} AS vector), c.id
        LIMIT #{limit}
        </script>
        """)
    List<KnowledgeRetrievalRow> searchVector(
        @Param("baseId") Long baseId,
        @Param("modelId") Long modelId,
        @Param("dimension") int dimension,
        @Param("embedding") String embedding,
        @Param("limit") int limit
    );

    /**
     * 查询{@code VectorScoped}列表。
     *
     * @param baseId 资源标识
     * @param modelId 资源标识
     * @param dimension {@code dimension}参数
     * @param embedding {@code embedding}参数
     * @param limit 数量上限
     * @param directoryIds 资源标识集合
     * @param includeRoot {@code includeRoot}参数
     * @param includeAll {@code includeAll}参数
     * @return 符合条件的数据集合
     */
    @Select("""
        <script>
        SELECT c.id AS chunk_id, c.knowledge_base_id, c.document_id, d.name AS document_name,
               c.chunk_no, c.content, c.metadata_json::text AS metadata_json,
               (1 - (c.embedding &lt;=> CAST(#{embedding} AS vector)))::double precision AS score
        FROM agent_knowledge_chunk c
        JOIN agent_knowledge_document d ON d.id = c.document_id AND d.del_flag = '0' AND d.status = 'ready'
        WHERE c.knowledge_base_id = #{baseId} AND c.status = 'active'
          AND c.embedding_model_id = #{modelId} AND c.embedding_dimension = #{dimension}
          AND c.embedding IS NOT NULL
          AND (
            #{includeAll} = TRUE
            OR (#{includeRoot} = TRUE AND d.directory_id IS NULL)
            <if test="directoryIds != null and directoryIds.size() > 0">
              OR d.directory_id IN
              <foreach collection="directoryIds" item="directoryId" open="(" separator="," close=")">
                #{directoryId}
              </foreach>
            </if>
          )
        ORDER BY c.embedding &lt;=> CAST(#{embedding} AS vector), c.id
        LIMIT #{limit}
        </script>
        """)
    List<KnowledgeRetrievalRow> searchVectorScoped(
        @Param("baseId") Long baseId,
        @Param("modelId") Long modelId,
        @Param("dimension") int dimension,
        @Param("embedding") String embedding,
        @Param("limit") int limit,
        @Param("directoryIds") List<Long> directoryIds,
        @Param("includeRoot") boolean includeRoot,
        @Param("includeAll") boolean includeAll
    );

    /**
     * 创建并保存Parse作业。
     *
     * @param id 资源标识
     * @param bizKey {@code bizKey}参数
     * @param payloadJson {@code payloadJson}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_job_queue (
            id, job_type, biz_key, payload_json, status, priority, attempt_no,
            max_attempts, available_at, created_at
        ) VALUES (
            #{id}, 'knowledge_parse', #{bizKey}, CAST(#{payloadJson} AS jsonb), 'queued',
            10, 0, 3, #{now}, #{now}
        )
        """)
    int insertParseJob(
        @Param("id") Long id,
        @Param("bizKey") String bizKey,
        @Param("payloadJson") String payloadJson,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理claimParse作业并返回对应结果。
     *
     * @param workerId 资源标识
     * @return 处理结果
     */
    @Select("""
        WITH candidate AS (
          SELECT id, status AS previous_status FROM agent_job_queue
          WHERE job_type = 'knowledge_parse'
            AND ((status = 'queued' AND available_at <= CURRENT_TIMESTAMP)
                 OR (status = 'running' AND lease_until < CURRENT_TIMESTAMP))
          ORDER BY priority DESC, created_at, id
          FOR UPDATE SKIP LOCKED
          LIMIT 1
        )
        UPDATE agent_job_queue j
        SET status = 'running', attempt_no = attempt_no + 1, worker_id = #{workerId},
            lease_until = CURRENT_TIMESTAMP + INTERVAL '10 minutes', updated_at = CURRENT_TIMESTAMP
        FROM candidate c
        WHERE j.id = c.id
        RETURNING j.id, j.biz_key, j.payload_json::text AS payload_json, j.status,
                  j.attempt_no, j.max_attempts, j.lease_until,
                  (c.previous_status = 'running') AS recovered
        """)
    KnowledgeParseJobRow claimParseJob(@Param("workerId") String workerId);

    /**
     * 处理renewParse作业并返回对应结果。
     *
     * @param id 资源标识
     * @param workerId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_job_queue
        SET lease_until = CURRENT_TIMESTAMP + INTERVAL '10 minutes',
            updated_at = CURRENT_TIMESTAMP
        WHERE id = #{id} AND status = 'running' AND worker_id = #{workerId}
          AND lease_until > CURRENT_TIMESTAMP
        """)
    int renewParseJob(@Param("id") Long id, @Param("workerId") String workerId);

    /**
     * 处理completeParse作业并返回对应结果。
     *
     * @param id 资源标识
     * @param workerId 资源标识
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_job_queue
        SET status = 'success', lease_until = NULL, worker_id = NULL, last_error = NULL,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = #{id} AND status = 'running' AND worker_id = #{workerId}
        """)
    int completeParseJob(@Param("id") Long id, @Param("workerId") String workerId);

    /**
     * 处理failParse作业并返回对应结果。
     *
     * @param id 资源标识
     * @param workerId 资源标识
     * @param error {@code error}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_job_queue
        SET status = CASE WHEN attempt_no >= max_attempts THEN 'dead' ELSE 'queued' END,
            available_at = CURRENT_TIMESTAMP + make_interval(secs => LEAST(300, attempt_no * 5)),
            lease_until = NULL, worker_id = NULL, last_error = #{error},
            updated_at = CURRENT_TIMESTAMP
        WHERE id = #{id} AND status = 'running' AND worker_id = #{workerId}
        """)
    int failParseJob(
        @Param("id") Long id,
        @Param("workerId") String workerId,
        @Param("error") String error
    );
}
