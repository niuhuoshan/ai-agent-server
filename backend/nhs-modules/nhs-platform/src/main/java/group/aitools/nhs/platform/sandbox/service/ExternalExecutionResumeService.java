package group.aitools.nhs.platform.sandbox.service;

import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeResumeDecision;
import group.aitools.nhs.runtime.spi.RuntimeResumeMode;
import group.aitools.nhs.platform.execution.domain.AgentExecutionEvent;
import group.aitools.nhs.platform.execution.persistence.mapper.AgentExecutionEventMapper;
import group.aitools.nhs.platform.execution.persistence.mapper.AgentRunRuntimeMapper;
import group.aitools.nhs.platform.execution.persistence.mapper.TaskRunCommandMapper;
import group.aitools.nhs.platform.execution.persistence.row.AgentRunRuntimeRow;
import group.aitools.nhs.platform.execution.service.TaskRunExecutionCoordinator;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.sandbox.mapper.ExternalExecutionResumeMapper;
import group.aitools.nhs.platform.sandbox.persistence.row.ExternalExecutionResumeRow;
import group.aitools.nhs.platform.workflow.service.WorkflowRunCoordinator;
import group.aitools.nhs.platform.sandbox.web.ExternalExecutionResumeResult;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 负责External执行Resume相关的业务编排与领域规则处理。
 *
 * Validates and durably claims Nhs external tool results before resuming
 * the frozen AgentScope run. The caller cannot choose a different tool call,
 * run, step, or owner through the request body.
 */
@Service
public class ExternalExecutionResumeService {

    private static final int MAX_RESULTS = 16;
    private static final int MAX_OUTPUT_CHARS = 1_048_576;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CurrentPrincipalProvider principalProvider;
    private final AgentExecutionEventMapper eventMapper;
    private final AgentRunRuntimeMapper runtimeMapper;
    private final TaskRunCommandMapper runMapper;
    private final TaskRunExecutionCoordinator coordinator;
    private final WorkflowRunCoordinator workflowCoordinator;
    private final JsonMapper jsonMapper;
    private final ExternalExecutionResumeMapper resumeMapper;
    private final PlatformIdGenerator idGenerator;

    /**
     * 创建 {@code ExternalExecutionResumeService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param eventMapper 事件Mapper参数
     * @param runtimeMapper 运行时Mapper参数
     * @param runMapper {@code runMapper}参数
     * @param coordinator {@code coordinator}参数
     * @param workflowCoordinator 工作流Coordinator参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param resumeMapper {@code resumeMapper}参数
     * @param idGenerator {@code idGenerator}参数
     */
    @Autowired
    public ExternalExecutionResumeService(
        CurrentPrincipalProvider principalProvider,
        AgentExecutionEventMapper eventMapper,
        AgentRunRuntimeMapper runtimeMapper,
        TaskRunCommandMapper runMapper,
        TaskRunExecutionCoordinator coordinator,
        WorkflowRunCoordinator workflowCoordinator,
        JsonMapper jsonMapper,
        ExternalExecutionResumeMapper resumeMapper,
        PlatformIdGenerator idGenerator
    ) {
        this.principalProvider = principalProvider;
        this.eventMapper = eventMapper;
        this.runtimeMapper = runtimeMapper;
        this.runMapper = runMapper;
        this.coordinator = coordinator;
        this.workflowCoordinator = workflowCoordinator;
        this.jsonMapper = jsonMapper;
        this.resumeMapper = resumeMapper;
        this.idGenerator = idGenerator;
    }

    /**
 * 创建 {@code ExternalExecutionResumeService} 实例并初始化所需依赖。
 * Compatibility constructor for focused tests that predate the idempotency fact. */
    public ExternalExecutionResumeService(
        CurrentPrincipalProvider principalProvider,
        AgentExecutionEventMapper eventMapper,
        AgentRunRuntimeMapper runtimeMapper,
        TaskRunCommandMapper runMapper,
        TaskRunExecutionCoordinator coordinator,
        WorkflowRunCoordinator workflowCoordinator,
        JsonMapper jsonMapper
    ) {
        this(
            principalProvider, eventMapper, runtimeMapper, runMapper, coordinator,
            workflowCoordinator, jsonMapper, null, null
        );
    }

    /**
     * 处理{@code resume}并返回对应结果。
     *
     * @param externalRequestId 资源标识
     * @param rawResults {@code rawResults}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ExternalExecutionResumeResult resume(
        String externalRequestId,
        List<Map<String, Object>> rawResults
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String replyId = normalizeReplyId(externalRequestId);
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentExecutionEvent required = eventMapper.selectExternalExecutionEvent(
            replyId, principal.id()
        );
        if (required == null || required.getRunId() == null || required.getStepId() == null) {
            throw new ServiceException("外部执行请求不存在或无权访问", HttpStatus.NOT_FOUND);
        }
        AgentRunRuntimeRow runtime = runtimeMapper.selectRuntimeSnapshotByRunAndStep(
            required.getRunId(), required.getStepId()
        );
        if (runtime == null || runtime.getRuntimeSnapshotJson() == null) {
            throw conflict("外部执行缺少服务端运行快照");
        }
        AgentRunRequest frozen = readRuntime(runtime.getRuntimeSnapshotJson());
        if (!principal.id().equals(frozen.userId())
            || !required.getRunId().equals(frozen.runId())
            || !required.getStepId().equals(frozen.stepId())
            || !required.getTraceId().equals(frozen.executionKey().traceId())) {
            throw new ServiceException("外部执行运行身份不一致", HttpStatus.FORBIDDEN);
        }
        List<Map<String, Object>> actions = validateResults(required, rawResults);
        String resultsJson = canonicalJson(actions);
        String resultsHash = ContentHashing.sha256(resultsJson);
        if (resumeMapper != null) {
            ExternalExecutionResumeRow existing = resumeMapper.selectForUpdate(
                principal.id(), replyId
            );
            if (existing != null) {
                verifyExisting(existing, frozen, resultsHash);
                if ("dispatched".equals(existing.getStatus())) {
                    return new ExternalExecutionResumeResult(
                        existing.getTaskId(), existing.getRunId(), existing.getStepId(), true
                    );
                }
                throw conflict("外部执行恢复正在处理中，请勿重复提交");
            }
            ExternalExecutionResumeRow pending = new ExternalExecutionResumeRow();
            pending.setId(idGenerator.nextId());
            pending.setUserId(principal.id());
            pending.setReplyId(replyId);
            pending.setTaskId(frozen.taskId());
            pending.setRunId(frozen.runId());
            pending.setStepId(frozen.stepId());
            pending.setTraceId(frozen.executionKey().traceId());
            pending.setResultsHash(resultsHash);
            pending.setResultsJson(resultsJson);
            pending.setCreatedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
            if (resumeMapper.insertPending(pending) != 1) {
                ExternalExecutionResumeRow raced = resumeMapper.selectForUpdate(
                    principal.id(), replyId
                );
                if (raced == null) {
                    throw conflict("外部执行恢复幂等事实写入失败");
                }
                verifyExisting(raced, frozen, resultsHash);
                if ("dispatched".equals(raced.getStatus())) {
                    return new ExternalExecutionResumeResult(
                        raced.getTaskId(), raced.getRunId(), raced.getStepId(), true
                    );
                }
                throw conflict("外部执行恢复正在处理中，请勿重复提交");
            }
        }
        if (runtime.getWorkflowVersionId() != null) {
            if (!workflowCoordinator.resumeWaitingStep(frozen.runId(), frozen.stepId())) {
                throw conflict("外部执行步骤已被恢复或不再等待输入");
            }
        } else {
            if (runMapper.claimResumedRun(
                frozen.taskId(), frozen.runId(), coordinator.workerId()
            ) != 1) {
                throw conflict("外部执行运行已被恢复或不再等待输入");
            }
            if (runMapper.startStep(frozen.runId(), frozen.stepId()) != 1) {
                throw conflict("外部执行步骤不能恢复");
            }
            runMapper.markTaskRunning(frozen.taskId(), frozen.runId(), principal.id());
        }
        if (resumeMapper != null && resumeMapper.markDispatched(
            principal.id(), replyId, java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
        ) != 1) {
            throw conflict("外部执行恢复幂等事实状态更新失败");
        }
        AgentResumeRequest request = new AgentResumeRequest(
            frozen.executionKey(), frozen.userId(), frozen.conversationId(), frozen.taskId(),
            frozen.runId(), frozen.stepId(), frozen.sessionId(), replyId,
            RuntimeResumeDecision.APPROVE, actions,
            Map.of("source", "nhs_external_execution", "actorId", principal.id()),
            RuntimeResumeMode.EXTERNAL_EXECUTION
        ).withRuntimeContext(frozen);
        launchAfterCommit(request);
        return new ExternalExecutionResumeResult(
            frozen.taskId(), frozen.runId(), frozen.stepId(), false
        );
    }

    /**
     * 校验{@code Existing}，并在条件不满足时终止处理。
     *
     * @param existing {@code existing}参数
     * @param frozen {@code frozen}参数
     * @param resultsHash {@code resultsHash}参数
     */
    private void verifyExisting(
        ExternalExecutionResumeRow existing,
        AgentRunRequest frozen,
        String resultsHash
    ) {
        if (!resultsHash.equals(existing.getResultsHash())
            || !frozen.runId().equals(existing.getRunId())
            || !frozen.stepId().equals(existing.getStepId())
            || !frozen.taskId().equals(existing.getTaskId())
            || !frozen.executionKey().traceId().equals(existing.getTraceId())) {
            throw conflict("外部执行结果与已提交恢复事实不一致");
        }
    }

    /**
     * 判断{@code onicalJson}是否满足要求。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String canonicalJson(Object value) {
        return jsonMapper.writeValueAsString(canonicalValue(value));
    }

    /**
     * 判断{@code onicalValue}是否满足要求。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalValue(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::canonicalValue).toList();
        }
        return value;
    }

    /**
     * 校验{@code Results}，并在条件不满足时终止处理。
     *
     * @param required {@code required}参数
     * @param rawResults {@code rawResults}参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> validateResults(
        AgentExecutionEvent required,
        List<Map<String, Object>> rawResults
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Map<String, Object> payload = readMap(required.getPayloadJson());
        Object rawCalls = payload.get("toolCalls");
        List<?> calls = rawCalls instanceof List<?> values ? values : List.of();
        if (calls.isEmpty() || calls.size() > MAX_RESULTS) {
            rawCalls = payload.get("tool_calls");
            calls = rawCalls instanceof List<?> alternate ? alternate : List.of();
        }
        if (!(rawResults instanceof List<?> results)
            || results.isEmpty() || results.size() != calls.size()
            || results.size() > MAX_RESULTS) {
            throw new ServiceException("外部执行结果数量与服务端请求不一致", HttpStatus.BAD_REQUEST);
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (Object call : calls) {
            if (!(call instanceof Map<?, ?> map)) {
                throw conflict("服务端外部工具调用快照无效");
            }
            String id = text(map.get("id"));
            String name = text(map.get("name"));
            if (id == null || name == null || expected.put(id, name) != null) {
                throw conflict("服务端外部工具调用身份无效");
            }
        }
        List<Map<String, Object>> actions = new ArrayList<>(results.size());
        Set<String> received = new HashSet<>();
        for (Object result : results) {
            if (!(result instanceof Map<?, ?> map)) {
                throw new ServiceException("外部执行结果必须为对象", HttpStatus.BAD_REQUEST);
            }
            String id = text(map.get("id"));
            String name = text(map.get("name"));
            String output = textAllowEmpty(map.get("output"));
            String state = text(map.get("state"));
            if (id == null || name == null || output == null || output.length() > MAX_OUTPUT_CHARS) {
                throw new ServiceException("外部执行结果字段无效或超过1MB", HttpStatus.BAD_REQUEST);
            }
            String expectedName = expected.get(id);
            if (expectedName == null || !expectedName.equals(name) || !received.add(id)) {
                throw new ServiceException("外部执行结果工具调用身份不匹配", HttpStatus.BAD_REQUEST);
            }
            String normalizedState = state == null ? "success" : state.toLowerCase(Locale.ROOT);
            if (!Set.of("success", "succeeded", "error", "failed", "cancelled")
                .contains(normalizedState)) {
                throw new ServiceException("外部执行结果状态无效", HttpStatus.BAD_REQUEST);
            }
            Map<String, Object> resultValue = new LinkedHashMap<>();
            resultValue.put("output", output);
            resultValue.put("state", normalizedState);
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("id", id);
            action.put("name", name);
            action.put("succeeded", Set.of("success", "succeeded").contains(normalizedState));
            action.put("result", resultValue);
            actions.add(Map.copyOf(action));
        }
        if (received.size() != expected.size()) {
            throw new ServiceException("外部执行结果缺少工具调用", HttpStatus.BAD_REQUEST);
        }
        return List.copyOf(actions);
    }

    /**
     * 处理read运行时并返回对应结果。
     *
     * @param json {@code json}参数
     * @return 处理结果
     */
    private AgentRunRequest readRuntime(String json) {
        try {
            return jsonMapper.readValue(json, AgentRunRequest.class);
        } catch (RuntimeException exception) {
            throw conflict("外部执行运行快照无法解析");
        }
    }

    /**
     * 处理{@code readMap}并返回对应结果。
     *
     * @param json {@code json}参数
     * @return 处理结果
     */
    private Map<String, Object> readMap(String json) {
        try {
            return jsonMapper.readValue(json, MAP_TYPE);
        } catch (RuntimeException exception) {
            throw conflict("外部执行请求快照无法解析");
        }
    }

    /**
     * 处理{@code normalizeReplyId}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeReplyId(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > 128
            || !normalized.matches("[A-Za-z0-9._:-]+")) {
            throw new ServiceException("external_execution_request_id无效", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).strip();
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 处理{@code textAllowEmpty}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String textAllowEmpty(Object value) {
        return value instanceof String text && text.length() <= MAX_OUTPUT_CHARS ? text : null;
    }

    /**
     * 处理{@code conflict}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }

    /**
     * 处理{@code launchAfterCommit}相关逻辑。
     *
     * @param request 请求参数
     */
    private void launchAfterCommit(AgentResumeRequest request) {
        Runnable launch = () -> coordinator.launchResumeOrMarkFailed(request);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            launch.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * 处理{@code afterCommit}相关逻辑。
             */
            @Override
            public void afterCommit() {
                launch.run();
            }
        });
    }
}
