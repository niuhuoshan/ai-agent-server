package group.aitools.nhs.platform.model.web;

import group.aitools.nhs.platform.model.persistence.row.ModelReferenceRow;

import java.util.ArrayList;
import java.util.List;

/**
 * 封装模型Reference相关的不可变数据。
 * One active model usage that prevents destructive deletion. */
public record ModelReferenceView(
    String kind,
    Long agentId,
    String agentName,
    Long versionId,
    Integer versionNo,
    String versionStatus,
    List<String> slots
) {

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param row {@code row}参数
     * @return 处理结果
     */
    public static ModelReferenceView from(ModelReferenceRow row) {
        List<String> slots = new ArrayList<>(2);
        if (Boolean.TRUE.equals(row.getPrimaryModel())) {
            slots.add("primary");
        }
        if (Boolean.TRUE.equals(row.getSynthesisModel())) {
            slots.add("synthesis");
        }
        return new ModelReferenceView(
            "agent_version",
            row.getAgentId(),
            row.getAgentName(),
            row.getVersionId(),
            row.getVersionNo(),
            row.getVersionStatus(),
            List.copyOf(slots)
        );
    }
}
