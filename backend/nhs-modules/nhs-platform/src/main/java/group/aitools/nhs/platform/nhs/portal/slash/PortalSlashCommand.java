package group.aitools.nhs.platform.nhs.portal.slash;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 封装门户Slash操作的请求参数。
 * Durable user or system slash command exposed by the Nhs portal. */
@Data
public class PortalSlashCommand {

    private Long id;
    private String label;
    private String command;
    private Integer sortOrder;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String delFlag;
}
