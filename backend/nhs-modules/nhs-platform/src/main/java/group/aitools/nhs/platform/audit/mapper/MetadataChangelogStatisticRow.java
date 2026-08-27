package group.aitools.nhs.platform.audit.mapper;

import lombok.Data;

/**
 * 表示元数据ChangelogStatistic相关的领域对象。
 */
@Data
public class MetadataChangelogStatisticRow {
    private String resourceType;
    private String action;
    private long changeCount;
}
