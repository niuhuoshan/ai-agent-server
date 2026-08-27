package group.aitools.nhs.platform.sandbox.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.conversation.mapper.AgentConversationMapper;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.sandbox.mapper.SandboxRunnerMapper;
import group.aitools.nhs.platform.sandbox.persistence.row.SandboxJobOutputRow;
import group.aitools.nhs.platform.sandbox.persistence.row.SandboxJobRow;
import group.aitools.nhs.platform.sandbox.web.ChatCodeExecutionRequest;
import group.aitools.nhs.platform.sandbox.web.ChatCodeExecutionView;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 负责对话Code执行相关的业务编排与领域规则处理。
 * Owner-isolated control plane for code blocks executed by an external sandbox Runner. */
@Service
public class ChatCodeExecutionService {

    private static final String TEMPLATE_KEY = "code";
    private static final int TIMEOUT_SECONDS = 60;
    private static final int MAX_OUTPUT_BYTES = 100 * 1024;
    private static final int MAX_SCRIPT_BYTES = 1024 * 1024;
    private static final Pattern WORKSPACE_KEY = Pattern.compile(
        "[A-Za-z0-9][A-Za-z0-9._-]{0,127}"
    );

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final AgentConversationMapper conversationMapper;
    private final SandboxRunnerMapper sandboxMapper;
    private final PlatformIdGenerator idGenerator;
    private final JsonMapper jsonMapper;
    private final ThreadLocal<CurrentPrincipal> runtimePrincipal = new ThreadLocal<>();

    /**
     * 创建 {@code ChatCodeExecutionService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param conversationMapper 会话Mapper参数
     * @param sandboxMapper 沙箱Mapper参数
     * @param idGenerator {@code idGenerator}参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public ChatCodeExecutionService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        AgentConversationMapper conversationMapper,
        SandboxRunnerMapper sandboxMapper,
        PlatformIdGenerator idGenerator,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.conversationMapper = conversationMapper;
        this.sandboxMapper = sandboxMapper;
        this.idGenerator = idGenerator;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 执行As运行时操作主体相关的处理流程。
     *
     * @param principal 当前操作主体
     * @param operation 操作参数
     * @return 处理结果
     */
    public <T> T runAsRuntimePrincipal(CurrentPrincipal principal, Supplier<T> operation) {
        if (principal == null || operation == null) {
            throw new IllegalArgumentException("运行时主体和操作不能为空");
        }
        if (runtimePrincipal.get() != null) {
            throw new IllegalStateException("运行时沙箱主体不能嵌套覆盖");
        }
        runtimePrincipal.set(principal);
        try {
            return operation.get();
        } finally {
            runtimePrincipal.remove();
        }
    }

    /**
     * 处理{@code submit}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatCodeExecutionView submit(ChatCodeExecutionRequest request) {
        return submit(request, false);
    }

    /**
 * 处理submit运行时并返回对应结果。
 * Submits a command whose Skill manifest was built from the server-side frozen run snapshot. */
    @Transactional(rollbackFor = Exception.class)
    public ChatCodeExecutionView submitRuntime(ChatCodeExecutionRequest request) {
        return submit(request, true);
    }

    /**
     * 处理{@code submit}并返回对应结果。
     *
     * @param request 请求参数
     * @param allowFrozenSkillManifest allowFrozen技能Manifest参数
     * @return 处理结果
     */
    private ChatCodeExecutionView submit(
        ChatCodeExecutionRequest request,
        boolean allowFrozenSkillManifest
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (request == null) {
            throw badRequest("代码执行请求不能为空");
        }
        CurrentPrincipal principal = currentPrincipal();
        Long conversationId = positiveId(request.conversation_id(), "会话ID");
        requireOwnedConversation(principal, conversationId, "invoke");
        String language = language(request.language());
        String script = script(request.code());
        String workspaceKey = normalizeWorkspaceKey(request.workspace_key());
        SandboxSkillManifest.Normalized skillManifest = SandboxSkillManifest.fromJson(
            request.skill_manifest_json(), jsonMapper
        );
        if (!allowFrozenSkillManifest && !skillManifest.empty()) {
            throw badRequest("公共代码执行不允许提交 Skill manifest");
        }
        if (!skillManifest.empty()) {
            if (workspaceKey == null) {
                throw badRequest("包含 Skill 的代码执行必须绑定工作区");
            }
            if (!workspaceKey.equals(skillManifest.workspaceKey())) {
                throw badRequest("Skill manifest 与工作区标识不一致");
            }
        }
        LocalDateTime now = utcNow();
        if (sandboxMapper.countAvailableRunners(TEMPLATE_KEY, now) < 1) {
            throw new ServiceException(
                "sandbox_unavailable: 当前没有可用的代码执行Runner",
                503
            );
        }
        Long id = idGenerator.nextId();
        String traceId = ContentHashing.sha256(idGenerator.nextUuid());
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("sourceType", "chat_code");
        canonical.put("ownerUserId", principal.id());
        canonical.put("conversationId", conversationId);
        canonical.put("language", language);
        canonical.put("scriptHash", ContentHashing.sha256(script));
        canonical.put("workspaceKey", workspaceKey);
        canonical.put("skillManifestHash", skillManifest.hash());
        canonical.put("traceId", traceId);
        String requestHash = ContentHashing.sha256(jsonMapper.writeValueAsString(canonical));
        int inserted = skillManifest.empty()
            ? sandboxMapper.insertChatCodeJob(
                id, principal.id(), conversationId, traceId, requestHash, TEMPLATE_KEY,
                language, script, "[\"__chat_code__\"]", ".", TIMEOUT_SECONDS,
                512, 1000, 128, MAX_OUTPUT_BYTES, 10, now
            )
            : sandboxMapper.insertChatCodeJobWithManifest(
                id, principal.id(), conversationId, traceId, requestHash, TEMPLATE_KEY,
                language, script, "[\"__chat_code__\"]", ".", workspaceKey,
                skillManifest.json(), skillManifest.hash(), TIMEOUT_SECONDS,
                512, 1000, 128, MAX_OUTPUT_BYTES, 10, now
            );
        if (inserted != 1) {
            throw new ServiceException("代码执行提交冲突", HttpStatus.CONFLICT);
        }
        return ChatCodeExecutionView.from(requireOwnedJob(id, principal.id()));
    }

    /**
     * 处理{@code status}并返回对应结果。
     *
     * @param executionId 资源标识
     * @return 处理结果
     */
    public ChatCodeExecutionView status(Long executionId) {
        CurrentPrincipal principal = currentPrincipal();
        SandboxJobRow job = requireOwnedJob(executionId, principal.id());
        requireOwnedConversation(principal, job.getConversationId(), "view");
        return ChatCodeExecutionView.from(job);
    }

    /**
     * 查询{@code list}列表。
     *
     * @param rawConversationId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ChatCodeExecutionView> list(String rawConversationId, int limit) {
        CurrentPrincipal principal = currentPrincipal();
        Long conversationId = positiveId(rawConversationId, "会话ID");
        requireOwnedConversation(principal, conversationId, "view");
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        return sandboxMapper.selectOwnedChatJobs(
            principal.id(), conversationId, boundedLimit
        ).stream().map(ChatCodeExecutionView::from).toList();
    }

    /**
     * 判断{@code cel}是否满足要求。
     *
     * @param executionId 资源标识
     * @param rawConversationId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatCodeExecutionView cancel(Long executionId, String rawConversationId) {
        CurrentPrincipal principal = currentPrincipal();
        Long conversationId = positiveId(rawConversationId, "会话ID");
        SandboxJobRow job = sandboxMapper.selectOwnedChatJob(
            executionId, principal.id(), conversationId
        );
        if (job == null) {
            throw notFound();
        }
        requireOwnedConversation(principal, conversationId, "stop");
        sandboxMapper.cancelOwnedChatJob(executionId, principal.id(), conversationId, utcNow());
        SandboxJobRow current = sandboxMapper.selectOwnedChatJob(
            executionId, principal.id(), conversationId
        );
        return ChatCodeExecutionView.from(current);
    }

    /**
 * 判断celAllFor会话是否满足要求。
 *
     * Cancels every queued or leased chat-code job for a conversation.  The
     * lease/token is consumed atomically, so a Runner currently executing the
     * job loses its lease on the next renewal/output write and terminates.
     */
    @Transactional(rollbackFor = Exception.class)
    public int cancelAllForConversation(Long conversationId, String reason) {
        CurrentPrincipal principal = currentPrincipal();
        Long id = positiveId(
            conversationId == null ? null : String.valueOf(conversationId), "会话ID"
        );
        requireOwnedConversation(principal, id, "stop");
        String normalized = reason == null || reason.isBlank()
            ? "sandbox execution cancelled by conversation owner" : reason.strip();
        if (normalized.length() > 1000) {
            normalized = normalized.substring(0, 1000);
        }
        return sandboxMapper.cancelOwnedChatJobs(principal.id(), id, normalized, utcNow());
    }

    /**
     * 处理{@code reader}并返回对应结果。
     *
     * @param executionId 资源标识
     * @param expectedConversationId 资源标识
     * @return 处理结果
     */
    public EventStreamReader reader(Long executionId, String expectedConversationId) {
        CurrentPrincipal principal = currentPrincipal();
        SandboxJobRow job = requireOwnedJob(executionId, principal.id());
        if (expectedConversationId != null && !expectedConversationId.isBlank()
            && !job.getConversationId().equals(positiveId(expectedConversationId, "会话ID"))) {
            throw notFound();
        }
        requireOwnedConversation(principal, job.getConversationId(), "view");
        Long ownerId = principal.id();
        Long conversationId = job.getConversationId();
        return (afterSequence, limit) -> {
            SandboxJobRow current = sandboxMapper.selectOwnedChatJob(
                executionId, ownerId, conversationId
            );
            if (current == null) {
                throw notFound();
            }
            List<SandboxJobOutputRow> outputs = sandboxMapper.selectOutputs(
                executionId, Math.max(0, afterSequence), Math.max(1, Math.min(limit, 200))
            );
            return new EventBatch(current, outputs);
        };
    }

    /**
     * 校验Owned作业，并在条件不满足时终止处理。
     *
     * @param executionId 资源标识
     * @param ownerUserId 资源标识
     * @return 处理结果
     */
    private SandboxJobRow requireOwnedJob(Long executionId, Long ownerUserId) {
        if (executionId == null || executionId <= 0) {
            throw notFound();
        }
        SandboxJobRow job = sandboxMapper.selectChatJobOwnedByUser(executionId, ownerUserId);
        if (job == null) {
            throw notFound();
        }
        return job;
    }

    /**
     * 校验Owned会话，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param conversationId 资源标识
     * @param action {@code action}参数
     */
    private void requireOwnedConversation(
        CurrentPrincipal principal,
        Long conversationId,
        String action
    ) {
        if (conversationMapper.selectOwnedConversation(conversationId, principal.id()) == null) {
            throw new ServiceException("会话不存在", HttpStatus.NOT_FOUND);
        }
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "conversation", conversationId, null, action,
            ResourceState.ACTIVE, true, Set.of(), null
        ));
    }

    /**
     * 处理{@code language}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String language(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "python", "python3" -> "python";
            case "shell", "bash" -> "bash";
            case "sh" -> "sh";
            default -> throw badRequest("暂不支持运行语言: " + (normalized.isEmpty() ? "未指定" : normalized));
        };
    }

    /**
     * 处理{@code script}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String script(String value) {
        if (value == null || value.isBlank()) {
            throw badRequest("代码不能为空");
        }
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_SCRIPT_BYTES) {
            throw new ServiceException("代码超过1MB限制", 413);
        }
        return value;
    }

    /**
     * 处理normalize工作空间Key并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeWorkspaceKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > 128 || !WORKSPACE_KEY.matcher(normalized).matches()
            || ".".equals(normalized) || "..".equals(normalized)) {
            throw badRequest("工作区标识无效");
        }
        return normalized;
    }

    /**
     * 处理{@code positiveId}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Long positiveId(String value, String label) {
        try {
            long parsed = Long.parseLong(value == null ? "" : value.strip());
            if (parsed <= 0) {
                throw new NumberFormatException("not positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw badRequest(label + "无效");
        }
    }

    /**
     * 处理{@code utcNow}并返回对应结果。
     *
     * @return 处理结果
     */
    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * 处理{@code badRequest}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 处理{@code notFound}并返回对应结果。
     *
     * @return 处理结果
     */
    private ServiceException notFound() {
        return new ServiceException("代码执行不存在", HttpStatus.NOT_FOUND);
    }

    /**
     * 处理当前操作主体并返回对应结果。
     *
     * @return 处理结果
     */
    private CurrentPrincipal currentPrincipal() {
        CurrentPrincipal principal = runtimePrincipal.get();
        return principal == null ? principalProvider.currentPrincipal() : principal;
    }

    /**
     * 封装事件Batch相关的不可变数据。
     */
    public record EventBatch(SandboxJobRow job, List<SandboxJobOutputRow> outputs) {
    }

    /**
     * 定义事件StreamReader相关能力的服务契约。
     */
    @FunctionalInterface
    public interface EventStreamReader {
        /**
         * 处理{@code read}并返回对应结果。
         *
         * @param afterSequence 起始位置或序号
         * @param limit 数量上限
         * @return 处理结果
         */
        EventBatch read(long afterSequence, int limit);
    }
}
