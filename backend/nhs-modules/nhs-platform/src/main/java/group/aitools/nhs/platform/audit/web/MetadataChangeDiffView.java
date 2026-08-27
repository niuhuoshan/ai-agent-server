package group.aitools.nhs.platform.audit.web;

import java.util.List;

/**
 * 封装元数据ChangeDiff相关的不可变数据。
 */
public record MetadataChangeDiffView(
    Long id,
    Long datasetId,
    String resourceType,
    Long resourceId,
    String operation,
    String summary,
    List<FieldChangeView> changes
) {
    /**
     * 封装{@code FieldChange}相关的不可变数据。
     */
    public record FieldChangeView(String field, Object oldValue, Object newValue) {
    }
}
