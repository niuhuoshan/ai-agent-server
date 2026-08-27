package group.aitools.nhs.platform.data.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable DDL/YAML catalog-import preview without the raw source content. */
@Data
@TableName("agent_data_catalog_import_preview")
public class AgentDataCatalogImportPreview {

    @TableId
    private Long id;
    private Long datasetId;
    private String sourceType;
    private String sourceHash;
    private String status;
    private Integer datasetRevision;
    private Integer revisionNo;
    private Integer tableCount;
    private Integer columnCount;
    private String diagnosticsJson;
    private LocalDateTime expiresAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long appliedBy;
    private LocalDateTime appliedAt;
}
