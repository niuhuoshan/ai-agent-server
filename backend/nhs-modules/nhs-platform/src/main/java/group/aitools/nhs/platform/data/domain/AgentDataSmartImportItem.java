package group.aitools.nhs.platform.data.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One tamper-evident change candidate in a smart-import preview. */
@Data
@TableName("agent_data_smart_import_item")
public class AgentDataSmartImportItem {

    @TableId
    private Long id;
    private Long previewId;
    private String itemType;
    private Long resourceId;
    private String contentHash;
    private String proposedJson;
    private String status;
    private Long appliedResourceId;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
