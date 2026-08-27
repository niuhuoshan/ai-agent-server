package group.aitools.nhs.platform.artifact.web;

import group.aitools.nhs.platform.artifact.domain.AgentAcceptanceRecord;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 封装验收相关的不可变数据。
 * Public append-only acceptance fact. */
public record AcceptanceView(
    Long id,
    Long taskId,
    Long runId,
    List<Long> artifactIds,
    String acceptanceType,
    String result,
    Map<String, Object> ruleResult,
    String comment,
    Long reviewerId,
    String reviewerPrincipalType,
    Integer reworkNo,
    LocalDateTime createdAt
) {

    private static final TypeReference<List<Long>> ID_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param record {@code record}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    public static AcceptanceView from(AgentAcceptanceRecord record, JsonMapper jsonMapper) {
        List<Long> ids = record.getArtifactIdsJson() == null
            ? List.of()
            : jsonMapper.readValue(record.getArtifactIdsJson(), ID_LIST);
        Map<String, Object> rules = record.getRuleResultJson() == null
            ? Map.of()
            : jsonMapper.readValue(record.getRuleResultJson(), MAP_TYPE);
        return new AcceptanceView(
            record.getId(), record.getTaskId(), record.getRunId(), ids,
            record.getAcceptanceType(), record.getResult(), rules, record.getComment(),
            record.getReviewerId(), record.getReviewerPrincipalType(),
            record.getReworkNo(), record.getCreatedAt()
        );
    }
}
