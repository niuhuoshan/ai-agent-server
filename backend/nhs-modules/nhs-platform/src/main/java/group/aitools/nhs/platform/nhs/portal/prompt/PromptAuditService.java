package group.aitools.nhs.platform.nhs.portal.prompt;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 负责提示词审计相关的业务编排与领域规则处理。
 * Writes content-free Prompt Studio mutations to the platform audit ledger. */
@Service
public class PromptAuditService {

    private static final int SUMMARY_LIMIT = 1000;

    private final AgentAuditEventMapper auditMapper;
    private final PlatformIdGenerator idGenerator;
    private final CurrentPrincipalProvider principalProvider;

    public PromptAuditService(
        AgentAuditEventMapper auditMapper,
        PlatformIdGenerator idGenerator,
        CurrentPrincipalProvider principalProvider
    ) {
        this.auditMapper = auditMapper;
        this.idGenerator = idGenerator;
        this.principalProvider = principalProvider;
    }

    /**
     * 处理{@code recordSave}相关逻辑。
     *
     * @param source 数据源参数
     * @param targetId 资源标识
     * @param result 结果参数
     * @param content 待处理内容
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSave(
        String source,
        String targetId,
        PortalPromptService.SaveResult result,
        String content
    ) {
        record(
            "prompt_save",
            result.versionId(),
            result.changed() ? "changed" : "unchanged",
            summary(source, targetId, result.versionNumber(), content)
        );
    }

    /**
     * 处理{@code recordRestore}相关逻辑。
     *
     * @param source 数据源参数
     * @param targetId 资源标识
     * @param result 结果参数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRestore(
        String source,
        String targetId,
        PortalPromptService.RestoreResult result
    ) {
        record(
            "prompt_restore",
            result.restoredVersionId(),
            "source_version=" + result.sourceVersionNumber(),
            "source=" + source + ",target=" + targetId
                + ",sourceVersion=" + result.sourceVersionNumber()
                + ",restoredVersion=" + result.restoredVersionNumber()
        );
    }

    /**
     * 处理{@code record}相关逻辑。
     *
     * @param action {@code action}参数
     * @param resourceId 资源标识
     * @param reason {@code reason}参数
     * @param summary {@code summary}参数
     */
    private void record(String action, Long resourceId, String reason, String summary) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        String actorType = principal.type() == PrincipalType.HUMAN ? "user" : "service_account";
        auditMapper.insertEvent(
            idGenerator.nextId(),
            actorType,
            principal.id(),
            action,
            "agent_version",
            resourceId,
            null,
            "success",
            truncate(reason),
            truncate(summary),
            LocalDateTime.now()
        );
    }

    /**
     * 处理{@code summary}并返回对应结果。
     *
     * @param source 数据源参数
     * @param targetId 资源标识
     * @param versionNumber 版本Number参数
     * @param content 待处理内容
     * @return 处理结果
     */
    private String summary(String source, String targetId, int versionNumber, String content) {
        int length = content == null ? 0 : content.length();
        return "source=" + source + ",target=" + targetId
            + ",version=" + versionNumber + ",contentLength=" + length;
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
