package group.aitools.nhs.platform.sandbox.persistence.row;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表示沙箱Runner相关的领域对象。
 */
@Data
public class SandboxRunnerRow {
    private Long id;
    private String runnerKey;
    private String name;
    private String secretHash;
    private String status;
    private String capabilitiesJson;
    private Integer maxConcurrency;
    private Integer activeJobCount;
    private String runnerVersion;
    private LocalDateTime lastHeartbeatAt;
    private LocalDateTime heartbeatExpiresAt;
}
