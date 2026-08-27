package group.aitools.nhs.platform.memory.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示记忆Embedded相关的领域对象。
 * Internal session-memory projection including a vector for consolidation. */
@Data
public class MemoryEmbeddedRow {
    private Long id;
    private String memoryKey;
    private String content;
    private String metadataJson;
    private String embedding;
    private Long revisionNo;
    private LocalDateTime updatedAt;
}
