package group.aitools.nhs.platform.nhs.portal.example;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 创建并保存{@code insert}。
 *
 * 定义智能体对话BIExampleRevision相关的数据访问契约。
 * Persistence for append-only local example history. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
@Mapper
public interface AgentChatBIExampleRevisionMapper {

    @Insert("""
        INSERT INTO agent_chatbi_example_revision (
            id, example_id, action, review_status, user_query, refined_query, context_summary,
            sql_text, sql_metadata_json, category, enhance_status, local_sync_status,
            actor_type, actor_id, reason, content_hash, created_at
        ) VALUES (
            #{id}, #{exampleId}, #{action}, #{reviewStatus}, #{userQuery}, #{refinedQuery}, #{contextSummary},
            #{sqlText}, CAST(#{sqlMetadataJson} AS jsonb), #{category}, #{enhanceStatus}, #{localSyncStatus},
            #{actorType}, #{actorId}, #{reason}, #{contentHash}, #{createdAt}
        )
        """)
    int insert(AgentChatBIExampleRevision value);

    /**
     * 获取历史记录。
     *
     * @param exampleId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, revision_no, example_id, action, review_status, user_query, refined_query,
               context_summary, sql_text, sql_metadata_json::text AS sql_metadata_json, category,
               enhance_status, local_sync_status, actor_type, actor_id, reason, content_hash, created_at
        FROM agent_chatbi_example_revision
        WHERE example_id = #{exampleId}
        ORDER BY revision_no DESC, id DESC
        LIMIT #{limit}
        """)
    List<AgentChatBIExampleRevision> selectHistory(
        @Param("exampleId") Long exampleId,
        @Param("limit") int limit
    );
}
