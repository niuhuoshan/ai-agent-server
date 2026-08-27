package group.aitools.nhs.platform.data.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable, expiring smart-import preview. */
@Data
@TableName("agent_data_smart_import_preview")
public class AgentDataSmartImportPreview {

    @TableId
    private Long id;
    private Long datasetId;
    private Long profileJobId;
    private String status;
    private Integer datasetRevision;
    private Integer revisionNo;
    private LocalDateTime expiresAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long appliedBy;
    private LocalDateTime appliedAt;
}
