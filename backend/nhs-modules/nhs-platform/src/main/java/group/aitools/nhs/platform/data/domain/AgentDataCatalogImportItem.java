package group.aitools.nhs.platform.data.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One tamper-evident, selectively applicable catalog-import proposal. */
@Data
@TableName("agent_data_catalog_import_item")
public class AgentDataCatalogImportItem {

    @TableId
    private Long id;
    private Long previewId;
    private String itemType;
    private String resourceKey;
    private String action;
    private String currentHash;
    private String contentHash;
    private String proposedJson;
    private String status;
    private Long appliedResourceId;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
