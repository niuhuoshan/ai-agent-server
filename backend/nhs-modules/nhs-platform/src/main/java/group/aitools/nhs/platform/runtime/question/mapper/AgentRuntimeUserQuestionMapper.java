package group.aitools.nhs.platform.runtime.question.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.runtime.question.domain.AgentRuntimeUserQuestion;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建并保存追问。
 *
 * 定义智能体运行时用户追问相关的数据访问契约。
 * Owner-scoped persistence for Agent-initiated user questions. */
@Mapper
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface AgentRuntimeUserQuestionMapper {

    @Insert("""
        INSERT INTO agent_runtime_user_question (
            id, question_id, owner_id, conversation_id, execution_id,
            conversation_turn_id, tool_call_id, idempotency_key, question,
            options_json, multi_select, allow_custom_input, context, purpose,
            status, expires_at, created_at, updated_at
        ) VALUES (
            #{id}, #{questionId}, #{ownerId}, #{conversationId}, #{executionId},
            #{conversationTurnId}, #{toolCallId}, #{idempotencyKey}, #{question},
            CAST(#{optionsJson} AS jsonb), #{multiSelect}, #{allowCustomInput},
            #{context}, #{purpose}, 'pending', #{expiresAt}, #{now}, #{now}
        )
        ON CONFLICT DO NOTHING
        """)
    int insertQuestion(
        @Param("id") Long id,
        @Param("questionId") String questionId,
        @Param("ownerId") Long ownerId,
        @Param("conversationId") Long conversationId,
        @Param("executionId") String executionId,
        @Param("conversationTurnId") Long conversationTurnId,
        @Param("toolCallId") String toolCallId,
        @Param("idempotencyKey") String idempotencyKey,
        @Param("question") String question,
        @Param("optionsJson") String optionsJson,
        @Param("multiSelect") boolean multiSelect,
        @Param("allowCustomInput") boolean allowCustomInput,
        @Param("context") String context,
        @Param("purpose") String purpose,
        @Param("expiresAt") LocalDateTime expiresAt,
        @Param("now") LocalDateTime now
    );

    /**
     * 获取By追问Id。
     *
     * @param questionId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, question_id, owner_id, conversation_id, execution_id,
               conversation_turn_id, tool_call_id, idempotency_key, question,
               CAST(options_json AS text) AS options_json, multi_select,
               allow_custom_input, context, purpose, status,
               CAST(selected_option_ids_json AS text) AS selected_option_ids_json,
               custom_input, answer_idempotency_key, decision_key_hash,
               expires_at, answered_at, cancelled_at, created_at, updated_at
        FROM agent_runtime_user_question
        WHERE question_id = #{questionId}
        """)
    AgentRuntimeUserQuestion selectByQuestionId(@Param("questionId") String questionId);

    /**
     * 获取{@code ByCreateIdempotency}。
     *
     * @param ownerId 资源标识
     * @param conversationId 资源标识
     * @param idempotencyKey {@code idempotencyKey}参数
     * @return 处理结果
     */
    @Select("""
        SELECT id, question_id, owner_id, conversation_id, execution_id,
               conversation_turn_id, tool_call_id, idempotency_key, question,
               CAST(options_json AS text) AS options_json, multi_select,
               allow_custom_input, context, purpose, status,
               CAST(selected_option_ids_json AS text) AS selected_option_ids_json,
               custom_input, answer_idempotency_key, decision_key_hash,
               expires_at, answered_at, cancelled_at, created_at, updated_at
        FROM agent_runtime_user_question
        WHERE owner_id = #{ownerId}
          AND conversation_id = #{conversationId}
          AND idempotency_key = #{idempotencyKey}
        FOR UPDATE
        """)
    AgentRuntimeUserQuestion selectByCreateIdempotency(
        @Param("ownerId") Long ownerId,
        @Param("conversationId") Long conversationId,
        @Param("idempotencyKey") String idempotencyKey
    );

    /**
     * 获取{@code Owned}。
     *
     * @param questionId 资源标识
     * @param ownerId 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, question_id, owner_id, conversation_id, execution_id,
               conversation_turn_id, tool_call_id, idempotency_key, question,
               CAST(options_json AS text) AS options_json, multi_select,
               allow_custom_input, context, purpose, status,
               CAST(selected_option_ids_json AS text) AS selected_option_ids_json,
               custom_input, answer_idempotency_key, decision_key_hash,
               expires_at, answered_at, cancelled_at, created_at, updated_at
        FROM agent_runtime_user_question
        WHERE question_id = #{questionId} AND owner_id = #{ownerId}
        FOR UPDATE
        """)
    AgentRuntimeUserQuestion selectOwned(
        @Param("questionId") String questionId,
        @Param("ownerId") Long ownerId
    );

    /**
     * 获取{@code Pending}。
     *
     * @param ownerId 资源标识
     * @param conversationId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, question_id, owner_id, conversation_id, execution_id,
               conversation_turn_id, tool_call_id, idempotency_key, question,
               CAST(options_json AS text) AS options_json, multi_select,
               allow_custom_input, context, purpose, status,
               CAST(selected_option_ids_json AS text) AS selected_option_ids_json,
               custom_input, answer_idempotency_key, decision_key_hash,
               expires_at, answered_at, cancelled_at, created_at, updated_at
        FROM agent_runtime_user_question
        WHERE owner_id = #{ownerId} AND conversation_id = #{conversationId}
          AND status = 'pending'
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit}
        """)
    List<AgentRuntimeUserQuestion> selectPending(
        @Param("ownerId") Long ownerId,
        @Param("conversationId") Long conversationId,
        @Param("limit") int limit
    );

    /**
     * 处理{@code supersedePending}并返回对应结果。
     *
     * @param ownerId 资源标识
     * @param conversationId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_runtime_user_question
        SET status = 'superseded', updated_at = #{now}
        WHERE owner_id = #{ownerId} AND conversation_id = #{conversationId}
          AND status = 'pending' AND expires_at > #{now}
        """)
    int supersedePending(
        @Param("ownerId") Long ownerId,
        @Param("conversationId") Long conversationId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code expirePending}并返回对应结果。
     *
     * @param ownerId 资源标识
     * @param conversationId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_runtime_user_question
        SET status = 'expired', updated_at = #{now}
        WHERE owner_id = #{ownerId} AND status = 'pending'
          AND expires_at <= #{now}
          AND conversation_id = #{conversationId}
        """)
    int expirePending(
        @Param("ownerId") Long ownerId,
        @Param("conversationId") Long conversationId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code expireOwned}并返回对应结果。
     *
     * @param id 资源标识
     * @param ownerId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_runtime_user_question
        SET status = 'expired', updated_at = #{now}
        WHERE id = #{id} AND owner_id = #{ownerId} AND status = 'pending'
          AND expires_at <= #{now}
        """)
    int expireOwned(
        @Param("id") Long id,
        @Param("ownerId") Long ownerId,
        @Param("now") LocalDateTime now
    );

    /**
     * 处理{@code submitAnswer}并返回对应结果。
     *
     * @param id 资源标识
     * @param ownerId 资源标识
     * @param selectedOptionIdsJson {@code selectedOptionIdsJson}参数
     * @param customInput {@code customInput}参数
     * @param answerIdempotencyKey {@code answerIdempotencyKey}参数
     * @param decisionKeyHash {@code decisionKeyHash}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_runtime_user_question
        SET status = 'submitted', selected_option_ids_json = CAST(#{selectedOptionIdsJson} AS jsonb),
            custom_input = #{customInput}, answer_idempotency_key = #{answerIdempotencyKey},
            decision_key_hash = #{decisionKeyHash}, answered_at = #{now}, updated_at = #{now}
        WHERE id = #{id} AND owner_id = #{ownerId} AND status = 'pending'
          AND expires_at > #{now}
        """)
    int submitAnswer(
        @Param("id") Long id,
        @Param("ownerId") Long ownerId,
        @Param("selectedOptionIdsJson") String selectedOptionIdsJson,
        @Param("customInput") String customInput,
        @Param("answerIdempotencyKey") String answerIdempotencyKey,
        @Param("decisionKeyHash") String decisionKeyHash,
        @Param("now") LocalDateTime now
    );

    /**
     * 判断cel追问是否满足要求。
     *
     * @param id 资源标识
     * @param ownerId 资源标识
     * @param answerIdempotencyKey {@code answerIdempotencyKey}参数
     * @param decisionKeyHash {@code decisionKeyHash}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_runtime_user_question
        SET status = 'cancelled', selected_option_ids_json = CAST('[]' AS jsonb),
            custom_input = '', answer_idempotency_key = #{answerIdempotencyKey},
            decision_key_hash = #{decisionKeyHash}, cancelled_at = #{now}, updated_at = #{now}
        WHERE id = #{id} AND owner_id = #{ownerId} AND status = 'pending'
          AND expires_at > #{now}
        """)
    int cancelQuestion(
        @Param("id") Long id,
        @Param("ownerId") Long ownerId,
        @Param("answerIdempotencyKey") String answerIdempotencyKey,
        @Param("decisionKeyHash") String decisionKeyHash,
        @Param("now") LocalDateTime now
    );
}
