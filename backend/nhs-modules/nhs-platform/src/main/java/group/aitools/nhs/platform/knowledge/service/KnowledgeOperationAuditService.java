package group.aitools.nhs.platform.knowledge.service;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 负责知识库操作审计相关的业务编排与领域规则处理。
 * Content-free, independently committed audit records for knowledge catalog mutations. */
@Service
public class KnowledgeOperationAuditService {

    private static final int SUMMARY_LIMIT = 1000;

    private final AgentAuditEventMapper mapper;
    private final PlatformIdGenerator idGenerator;

    public KnowledgeOperationAuditService(AgentAuditEventMapper mapper, PlatformIdGenerator idGenerator) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
    }

    /**
     * 处理{@code record}相关逻辑。
     *
     * @param principal 当前操作主体
     * @param action {@code action}参数
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param summary {@code summary}参数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
        CurrentPrincipal principal,
        String action,
        String resourceType,
        Long resourceId,
        String summary
    ) {
        int inserted = mapper.insertEvent(
            idGenerator.nextId(),
            principal.type() == PrincipalType.HUMAN ? "user" : "service_account",
            principal.id(),
            bounded(action, 64),
            bounded(resourceType, 32),
            resourceId,
            null,
            "success",
            "knowledge_catalog_mutation",
            bounded(summary, SUMMARY_LIMIT),
            LocalDateTime.now()
        );
        if (inserted != 1) {
            throw new ServiceException("知识目录操作审计写入失败", 503);
        }
    }

    /**
     * 处理{@code bounded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    private String bounded(String value, int limit) {
        if (value == null) {
            return null;
        }
        String sanitized = value.replace('\0', ' ').replace('\r', ' ').replace('\n', ' ').strip();
        return sanitized.length() <= limit ? sanitized : sanitized.substring(0, limit);
    }
}
