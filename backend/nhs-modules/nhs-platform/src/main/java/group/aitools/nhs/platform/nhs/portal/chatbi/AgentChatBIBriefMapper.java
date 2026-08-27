package group.aitools.nhs.platform.nhs.portal.chatbi;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 创建并保存{@code insert}。
 *
 * 定义智能体对话BIBrief相关的数据访问契约。
 * Persistence boundary for traceable ChatBI briefs. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
@Mapper
public interface AgentChatBIBriefMapper {

    @Insert("""
        INSERT INTO agent_chatbi_brief (
            id, owner_id, conversation_id, result_id, title, brief_payload,
            markdown_content, artifact_payload, created_at, del_flag
        ) VALUES (
            #{id}, #{ownerId}, #{conversationId}, #{resultId}, #{title},
            CAST(#{briefPayload} AS jsonb), #{markdownContent},
            CASE WHEN #{artifactPayload} IS NULL THEN NULL ELSE CAST(#{artifactPayload} AS jsonb) END,
            #{createdAt}, '0'
        )
        """)
    int insert(AgentChatBIBrief brief);

    /**
     * 获取{@code Owned}。
     *
     * @param id 资源标识
     * @param ownerId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, owner_id, conversation_id, result_id, title,
               brief_payload::text AS brief_payload,
               markdown_content, artifact_payload::text AS artifact_payload,
               created_at, updated_at, del_flag
        FROM agent_chatbi_brief
        WHERE id = #{id} AND owner_id = #{ownerId} AND del_flag = '0'
        """)
    AgentChatBIBrief selectOwned(
        @Param("id") String id,
        @Param("ownerId") Long ownerId
    );
}
