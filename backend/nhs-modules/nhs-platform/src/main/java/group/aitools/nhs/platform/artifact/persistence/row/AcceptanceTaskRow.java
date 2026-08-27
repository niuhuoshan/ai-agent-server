package group.aitools.nhs.platform.artifact.persistence.row;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 表示验收任务相关的领域对象。
 * Frozen task/run acceptance context. */
@Data
@EqualsAndHashCode(callSuper = true)
public class AcceptanceTaskRow extends ArtifactTaskRow {

    private String acceptanceMode;
    private String acceptanceSnapshotJson;
    private String runStatus;
}
