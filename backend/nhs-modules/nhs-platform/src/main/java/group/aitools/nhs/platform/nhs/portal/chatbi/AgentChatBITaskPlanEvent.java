package group.aitools.nhs.platform.nhs.portal.chatbi;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体对话BI任务Plan相关的领域对象。
 * Durable event cursor used to replay ChatBI task-plan SSE state. */
@Data
public class AgentChatBITaskPlanEvent {

    private Long id;
    private Long planId;
    private Long ownerId;
    private Long cursor;
    private String eventType;
    private String payloadJson;
    private LocalDateTime createdAt;
}
