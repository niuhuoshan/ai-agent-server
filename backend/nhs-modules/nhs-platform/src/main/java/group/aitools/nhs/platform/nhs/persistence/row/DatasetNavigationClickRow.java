package group.aitools.nhs.platform.nhs.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示数据集NavigationClick相关的领域对象。
 * One live per-user question ranking entry. */
@Data
public class DatasetNavigationClickRow {
    private String queryText;
    private String label;
    private String groupId;
    private Long clickCount;
    private LocalDateTime lastClickedAt;
}
