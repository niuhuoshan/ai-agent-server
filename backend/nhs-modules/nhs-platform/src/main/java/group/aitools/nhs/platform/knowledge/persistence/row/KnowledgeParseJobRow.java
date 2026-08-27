package group.aitools.nhs.platform.knowledge.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示知识库Parse作业相关的领域对象。
 */
@Data
public class KnowledgeParseJobRow {
    private Long id;
    private String bizKey;
    private String payloadJson;
    private String status;
    private Integer attemptNo;
    private Integer maxAttempts;
    private LocalDateTime leaseUntil;
    private Boolean recovered;
}
