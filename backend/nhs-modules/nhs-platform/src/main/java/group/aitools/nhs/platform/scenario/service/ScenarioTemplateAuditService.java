package group.aitools.nhs.platform.scenario.service;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 负责Scenario模板审计相关的业务编排与领域规则处理。
 * Append-only, content-free audit for scenario delivery lifecycle operations. */
@Service
public class ScenarioTemplateAuditService {

    private static final int SUMMARY_LIMIT = 1000;

    private final AgentAuditEventMapper mapper;
    private final PlatformIdGenerator idGenerator;

    public ScenarioTemplateAuditService(AgentAuditEventMapper mapper, PlatformIdGenerator idGenerator) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
    }

    /**
     * 处理{@code record}相关逻辑。
     *
     * @param principal 当前操作主体
     * @param action {@code action}参数
     * @param instanceId 资源标识
     * @param decision {@code decision}参数
     * @param reason {@code reason}参数
     * @param summary {@code summary}参数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(CurrentPrincipal principal, String action, Long instanceId, String decision, String reason, String summary) {
        mapper.insertEvent(
            idGenerator.nextId(),
            principal.type() == PrincipalType.HUMAN ? "user" : "service_account",
            principal.id(),
            action,
            "scenario_instance",
            instanceId,
            null,
            decision,
            truncate(reason),
            truncate(summary),
            LocalDateTime.now()
        );
    }

    /**
     * 处理{@code truncate}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String truncate(String value) {
        if (value == null || value.length() <= SUMMARY_LIMIT) {
            return value;
        }
        return value.substring(0, SUMMARY_LIMIT);
    }
}
