package group.aitools.nhs.platform.sandbox.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示沙箱作业Output相关的领域对象。
 */
@Data
public class SandboxJobOutputRow {
    private Long id;
    private Long jobId;
    private Integer attemptNo;
    private Long sequenceNo;
    private Long runnerSequenceNo;
    private String stream;
    private String content;
    private Integer contentBytes;
    private LocalDateTime createdAt;
}
