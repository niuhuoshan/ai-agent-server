package group.aitools.nhs.platform.audit.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 负责工具调用审计相关的业务编排与领域规则处理。
 * Writes an append-only, secret-free outcome record for each platform tool invocation. */
@Service
public class ToolInvocationAuditService {

    private static final int SUMMARY_LIMIT = 1000;

    private final AgentAuditEventMapper auditMapper;
    private final PlatformIdGenerator idGenerator;
    private final JsonMapper jsonMapper;

    public ToolInvocationAuditService(
        AgentAuditEventMapper auditMapper,
        PlatformIdGenerator idGenerator,
        JsonMapper jsonMapper
    ) {
        this.auditMapper = auditMapper;
        this.idGenerator = idGenerator;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 处理{@code record}相关逻辑。
     *
     * @param request 请求参数
     * @param toolId 资源标识
     * @param argumentsJson {@code argumentsJson}参数
     * @param resultJson 结果Json参数
     * @param succeeded {@code succeeded}参数
     * @param reason {@code reason}参数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
        AgentRunRequest request,
        Long toolId,
        String argumentsJson,
        String resultJson,
        boolean succeeded,
        String reason
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("agentVersionId", request.agentVersionId());
        metadata.put("stepId", request.stepId());
        metadata.put("sessionId", request.sessionId());
        auditMapper.insertToolInvocation(
            idGenerator.nextId(),
            request.executionKey().traceId(),
            actorType(request),
            request.userId(),
            toolId,
            request.taskId(),
            request.runId(),
            succeeded ? "success" : "failure",
            truncate(reason),
            "sha256=" + ContentHashing.sha256(argumentsJson) + ";bytes=" + utf8Length(argumentsJson),
            resultJson == null
                ? "result=none"
                : "sha256=" + ContentHashing.sha256(resultJson) + ";bytes=" + utf8Length(resultJson),
            jsonMapper.writeValueAsString(metadata),
            LocalDateTime.now()
        );
    }

    /**
 * 处理{@code recordUiTest}相关逻辑。
 * Records a human-triggered online test without retaining raw arguments or provider output. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUiTest(
        CurrentPrincipal principal,
        Long toolId,
        String argumentsJson,
        String resultJson,
        boolean succeeded,
        String reason
    ) {
        auditMapper.insertToolInvocation(
            idGenerator.nextId(),
            "tool-test-" + UUID.randomUUID(),
            principal.type() == PrincipalType.SERVICE_ACCOUNT ? "service_account" : "user",
            principal.id(),
            toolId,
            null,
            null,
            succeeded ? "success" : "failure",
            truncate(reason),
            "sha256=" + ContentHashing.sha256(argumentsJson) + ";bytes=" + utf8Length(argumentsJson),
            resultJson == null
                ? "result=none"
                : "sha256=" + ContentHashing.sha256(resultJson) + ";bytes=" + utf8Length(resultJson),
            jsonMapper.writeValueAsString(Map.of("source", "ui_tool_test")),
            LocalDateTime.now()
        );
    }

    /**
     * 处理{@code actorType}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private String actorType(AgentRunRequest request) {
        return "service_account".equals(request.authorizationSnapshot().get("principalType"))
            ? "service_account" : "user";
    }

    /**
     * 处理{@code utf8Length}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private int utf8Length(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
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
