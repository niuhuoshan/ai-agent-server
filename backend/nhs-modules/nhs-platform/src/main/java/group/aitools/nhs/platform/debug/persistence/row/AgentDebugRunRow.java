package group.aitools.nhs.platform.debug.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示智能体DebugRun相关的领域对象。
 * Private mapping from one debug attempt to the governed durable task runtime. */
@Data
public class AgentDebugRunRow {

    private Long id;
    private Long ownerId;
    private String idempotencyKey;
    private Long agentId;
    private Long agentVersionId;
    private Long taskId;
    private Long runId;
    private Long parentDebugRunId;
    private String inputText;
    private String inputSha256;
    private LocalDateTime createdAt;
}
