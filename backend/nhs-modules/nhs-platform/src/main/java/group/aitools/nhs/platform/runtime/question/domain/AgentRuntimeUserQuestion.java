package group.aitools.nhs.platform.runtime.question.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体运行时用户追问相关的领域对象。
 * Durable, owner-scoped question raised by an Agent runtime. */
@Data
public class AgentRuntimeUserQuestion {

    private Long id;
    private String questionId;
    private Long ownerId;
    private Long conversationId;
    private String executionId;
    private Long conversationTurnId;
    private String toolCallId;
    private String idempotencyKey;
    private String question;
    private String optionsJson;
    private boolean multiSelect;
    private boolean allowCustomInput;
    private String context;
    private String purpose;
    private String status;
    private String selectedOptionIdsJson;
    private String customInput;
    private String answerIdempotencyKey;
    private String decisionKeyHash;
    private LocalDateTime expiresAt;
    private LocalDateTime answeredAt;
    private LocalDateTime cancelledAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
