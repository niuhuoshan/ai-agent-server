package group.aitools.nhs.platform.connector.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.embed.mapper.EmbedChatMapper;
import group.aitools.nhs.platform.embed.persistence.row.EmbedAgentRuntimeRow;
import group.aitools.nhs.platform.embed.service.EmbedRuntimeSnapshotFactory;
import group.aitools.nhs.platform.execution.service.AgentRuntimeExecutionService;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.connector.domain.AgentRuntimeConfirmation;
import group.aitools.nhs.platform.connector.mapper.RuntimeAuxiliaryBuiltinMapper;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.nhs.service.GeneratedFileService;
import group.aitools.nhs.platform.nhs.service.NhsWorkspaceService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 负责运行时AuxiliaryBuiltin相关的业务编排与领域规则处理。
 *
 * Stateful Nhs-compatible builtins which are not naturally represented by a connector.
 * Every operation is bound to the frozen human principal and returns an explicit state rather
 * than silently pretending that an external provider or asynchronous operation completed.
 */
@Service
public class RuntimeAuxiliaryBuiltinService {

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> OBJECT_LIST = new TypeReference<>() {
    };
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Pattern SESSION = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern CELL_RANGE = Pattern.compile("^([A-Za-z]+)([0-9]+)(?::([A-Za-z]+)([0-9]+))?$");
    private static final Pattern SQL_PLAN_BLOCK = Pattern.compile(
        "<sql_plan\\b[^>]*>.*?</sql_plan\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern SQL_PLAN_REMAINDER = Pattern.compile(
        "<sql_plan\\b[^>]*>.*$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final int MAX_SQL_BYTES = 64 * 1024;
    private static final int MAX_ROWS = 100;
    private static final int MAX_COLUMNS = 50;
    private static final int DEFAULT_DELEGATION_MAX_DEPTH = 1;
    private static final int DEFAULT_DELEGATION_RESULT_MAX_CHARS = 8_000;
    private static final int MAX_DELEGATION_CAPTURE_CHARS = 64 * 1024;
    private static final long MAX_READ_IMAGE_BYTES = 20L * 1024 * 1024;
    private static final int MAX_INLINE_IMAGE_BYTES = 512 * 1024;
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "gif", "bmp");
    private static final String EMPTY_DELEGATION_RESULT_MESSAGE =
        "子 Agent 已执行完成，但未产生可交付正文。请勿使用相同参数重复委派，"
            + "请根据现有结果向用户说明，或建议直接打开对应 Agent 会话。";
    private static final Set<String> DELEGATION_FINAL_STATUSES = Set.of(
        "succeeded", "approval_required", "timed_out", "failed", "cancelled"
    );

    private final RuntimeAuxiliaryBuiltinMapper mapper;
    private final PlatformIdGenerator idGenerator;
    private final JsonMapper jsonMapper;
    private final NhsWorkspaceService workspaceService;
    private final GeneratedFileService generatedFileService;
    private final Path scratchpadRoot;
    private final String configuredJiraUrl;
    private final HttpClient httpClient;
    private final EmbedChatMapper agentMapper;
    private final EmbedRuntimeSnapshotFactory runtimeSnapshotFactory;
    private final ObjectProvider<AgentRuntimeExecutionService> runtimeProvider;

    /**
     * 创建 {@code RuntimeAuxiliaryBuiltinService} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param idGenerator {@code idGenerator}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param workspaceService 工作空间Service参数
     * @param generatedFileService generated文件Service参数
     * @param scratchpadRoot {@code scratchpadRoot}参数
     * @param jiraUrl {@code jiraUrl}参数
     * @param agentMapper 智能体Mapper参数
     * @param runtimeSnapshotFactory 运行时快照Factory参数
     * @param runtimeProvider 运行时提供方参数
     */
    @Autowired
    public RuntimeAuxiliaryBuiltinService(
        RuntimeAuxiliaryBuiltinMapper mapper,
        PlatformIdGenerator idGenerator,
        JsonMapper jsonMapper,
        NhsWorkspaceService workspaceService,
        GeneratedFileService generatedFileService,
        @Value("${agent.platform.scratchpad-root:./data/agent-scratchpads}") String scratchpadRoot,
        @Value("${agent.platform.jira.url:}") String jiraUrl,
        EmbedChatMapper agentMapper,
        EmbedRuntimeSnapshotFactory runtimeSnapshotFactory,
        ObjectProvider<AgentRuntimeExecutionService> runtimeProvider
    ) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.jsonMapper = jsonMapper;
        this.workspaceService = workspaceService;
        this.generatedFileService = generatedFileService;
        this.scratchpadRoot = Path.of(scratchpadRoot).toAbsolutePath().normalize();
        this.configuredJiraUrl = jiraUrl == null ? "" : jiraUrl.strip();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.agentMapper = agentMapper;
        this.runtimeSnapshotFactory = runtimeSnapshotFactory;
        this.runtimeProvider = runtimeProvider;
    }

    /**
 * 创建 {@code RuntimeAuxiliaryBuiltinService} 实例并初始化所需依赖。
 * Backwards-compatible constructor for focused builtin tests and embedders. */
    public RuntimeAuxiliaryBuiltinService(
        RuntimeAuxiliaryBuiltinMapper mapper,
        PlatformIdGenerator idGenerator,
        JsonMapper jsonMapper,
        NhsWorkspaceService workspaceService,
        GeneratedFileService generatedFileService,
        String scratchpadRoot,
        String jiraUrl
    ) {
        this(
            mapper, idGenerator, jsonMapper, workspaceService, generatedFileService,
            scratchpadRoot, jiraUrl, null, null, null
        );
    }

    /**
     * 更新Dashboard上下文。
     *
     * @param principal 当前操作主体
     * @param conversationId 资源标识
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    public Map<String, Object> updateDashboardContext(
        CurrentPrincipal principal,
        Long conversationId,
        Map<String, Object> arguments
    ) {
        requireHuman(principal, "update_dashboard_context");
        String room = optionalText(arguments.get("room_name"), 255);
        String metric = optionalText(arguments.get("metric_name"), 255);
        String range = optionalText(arguments.get("time_range"), 128);
        if (room == null && metric == null && range == null) {
            throw badRequest("至少提供 room_name、metric_name 或 time_range");
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("room_name", room);
        context.put("metric_name", metric);
        context.put("time_range", range);
        context.put("updated_by", principal.id());
        context.put("updated_at", LocalDateTime.now().toString());
        LocalDateTime now = LocalDateTime.now();
        mapper.upsertDashboardContext(
            idGenerator.nextId(), principal.id(), conversationId, room, metric, range,
            jsonMapper.writeValueAsString(context), now
        );
        return Map.of(
            "status", "updated", "conversation_id", conversationId == null ? 0 : conversationId,
            "context", context
        );
    }

    /**
     * 处理{@code sqliteScratchpad}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    public Map<String, Object> sqliteScratchpad(
        CurrentPrincipal principal,
        Map<String, Object> arguments
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        requireHuman(principal, "sqlite_scratchpad");
        String session = requiredText(arguments.get("session_id"), "session_id", 128);
        if (!SESSION.matcher(session).matches()) {
            throw badRequest("session_id 只能包含字母、数字、点、下划线、冒号或短横线");
        }
        String sql = requiredText(arguments.get("sql"), "sql", MAX_SQL_BYTES);
        String normalized = sql.strip().toLowerCase(Locale.ROOT);
        for (String forbidden : List.of("attach ", "detach ", "load_extension", "vacuum into", "pragma ")) {
            if (normalized.contains(forbidden)) {
                throw forbidden("SQLite 临时沙箱不允许执行 " + forbidden.strip());
            }
        }
        String sessionKey = session;
        String fileKey = Integer.toHexString(Objects.hash(principal.id(), session));
        Path dbPath;
        try {
            Files.createDirectories(scratchpadRoot);
            dbPath = scratchpadRoot.resolve(principal.id() + "-" + fileKey + ".db").normalize();
            if (!dbPath.startsWith(scratchpadRoot)) {
                throw forbidden("临时沙箱路径越权");
            }
            mapper.touchScratchpad(idGenerator.nextId(), principal.id(), sessionKey, dbPath.toString(), LocalDateTime.now());
        } catch (IOException exception) {
            throw new ServiceException("SQLite 临时沙箱目录无法初始化", HttpStatus.ERROR);
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            connection.setAutoCommit(false);
            importRows(connection, arguments.get("import_data"));
            if (normalized.startsWith("select") || normalized.startsWith("with") || normalized.startsWith("pragma")) {
                return querySql(connection, sql);
            }
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(10);
                int affected = statement.executeUpdate(sql);
                connection.commit();
                return Map.of("status", "ok", "affected_rows", Math.max(affected, 0), "session_id", session);
            }
        } catch (SQLException exception) {
            throw new ServiceException("SQLite 临时沙箱执行失败：" + safeSqlMessage(exception), HttpStatus.BAD_REQUEST);
        }
    }

    /**
 * 将输入数据转换为{@code doWrite}。
 *
     * Replaces the current run's UI-only checklist.  The checklist is deliberately
     * not persisted as a business task: the structured result is projected onto
     * the existing tool-result execution event and can therefore be replayed with
     * the execution timeline without introducing another table or permission scope.
     */
    public Map<String, Object> todoWrite(
        CurrentPrincipal principal,
        Map<String, Object> arguments
    ) {
        // 以下流程根据当前类型或状态选择处理分支，并保证每种分支返回明确结果。
        requireHuman(principal, "todo_write");
        if (arguments == null || !arguments.keySet().equals(Set.of("todos"))) {
            throw badRequest("todo_write 只接受 todos 参数");
        }
        Object rawTodos = arguments.get("todos");
        if (!(rawTodos instanceof List<?> rawItems) || rawItems.size() > 20) {
            throw badRequest("任务清单必须是最多20项的数组");
        }
        List<Map<String, Object>> todos = new ArrayList<>(rawItems.size());
        Set<String> seenContent = new java.util.HashSet<>();
        int pending = 0;
        int inProgress = 0;
        int completed = 0;
        for (Object rawItem : rawItems) {
            if (!(rawItem instanceof Map<?, ?> item)
                || !item.keySet().stream().allMatch(key -> "content".equals(String.valueOf(key))
                    || "status".equals(String.valueOf(key)))) {
                throw badRequest("任务项只允许 content 和 status 字段");
            }
            Object rawContent = item.get("content");
            if (!(rawContent instanceof String content)) {
                throw badRequest("任务描述不能为空");
            }
            String normalizedContent = content.strip();
            if (normalizedContent.isEmpty()) {
                throw badRequest("任务描述不能为空");
            }
            if (normalizedContent.length() > 200) {
                throw badRequest("任务描述不能超过200个字符");
            }
            if (!seenContent.add(normalizedContent)) {
                throw badRequest("任务描述不能重复");
            }
            Object rawStatus = item.get("status");
            if (!(rawStatus instanceof String status)
                || !Set.of("pending", "in_progress", "completed").contains(status)) {
                throw badRequest("status 必须是 pending、in_progress 或 completed");
            }
            switch (status) {
                case "pending" -> pending++;
                case "in_progress" -> inProgress++;
                case "completed" -> completed++;
                default -> throw new IllegalStateException("unreachable todo status");
            }
            todos.add(Map.of("content", normalizedContent, "status", status));
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("pending", pending);
        counts.put("in_progress", inProgress);
        counts.put("completed", completed);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "todo_update");
        result.put("todos", List.copyOf(todos));
        result.put("counts", Map.copyOf(counts));
        return result;
    }

    /**
 * 处理会话Status并返回对应结果。
 * Returns only non-secret facts from the frozen runtime request. */
    public Map<String, Object> sessionStatus(CurrentPrincipal principal, AgentRunRequest request) {
        requireHuman(principal, "session_status");
        Objects.requireNonNull(request, "request must not be null");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "active");
        result.put("observed_at", java.time.Instant.now().toString());
        result.put("principal_id", principal.id());
        result.put("principal_type", principal.type().name().toLowerCase(Locale.ROOT));
        result.put("roles", principal.roles().stream().map(Enum::name).map(String::toLowerCase).sorted().toList());
        result.put("session_id", request.sessionId());
        result.put("execution_id", request.executionKey().executionId());
        result.put("trace_id", request.executionKey().traceId());
        result.put("agent_version_id", request.agentVersionId());
        result.put("agent_name", request.agentName());
        result.put("model_provider", request.model().provider());
        result.put("model_id", request.model().modelName());
        result.put("max_iterations", request.maxIterations());
        putIfPresent(result, "conversation_id", request.conversationId());
        putIfPresent(result, "task_id", request.taskId());
        putIfPresent(result, "run_id", request.runId());
        putIfPresent(result, "step_id", request.stepId());
        putIfPresent(result, "workspace_key", request.workspaceKey());
        result.put("resource_counts", frozenResourceCounts(request));
        return Map.copyOf(result);
    }

    /**
 * 处理{@code readImage}并返回对应结果。
 *
     * Reads a workspace image after the same owner/path checks as file tools.  Small images are
     * returned as a bounded data URL so a multimodal runtime can consume them; larger images
     * remain available by their verified workspace path and are never silently truncated.
     */
    public Map<String, Object> readImage(CurrentPrincipal principal, Map<String, Object> arguments) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        requireHuman(principal, "read_image");
        String requestedPath = requiredText(arguments == null ? null : arguments.get("path"), "path", 512);
        Path file = workspaceService.resolveRuntimeFile(principal, requestedPath, false);
        String extension = extension(file);
        if (!IMAGE_EXTENSIONS.contains(extension)) {
            throw badRequest("read_image 仅支持 PNG、JPEG、WEBP、GIF 或 BMP 图片");
        }
        try {
            long size = Files.size(file);
            if (size <= 0 || size > MAX_READ_IMAGE_BYTES) {
                throw new ServiceException("图片为空或超过20MB限制", 413);
            }
            byte[] bytes = Files.readAllBytes(file);
            BufferedImage decoded = ImageIO.read(file.toFile());
            if (decoded == null) {
                throw badRequest("文件不是有效图片");
            }
            String mime = imageMime(extension);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ready");
            result.put("path", requestedPath);
            result.put("mime_type", mime);
            result.put("size_bytes", bytes.length);
            result.put("sha256", ContentHashing.sha256(bytes));
            result.put("width", decoded.getWidth());
            result.put("height", decoded.getHeight());
            String question = optionalText(arguments == null ? null : arguments.get("question"), 2000);
            if (question != null) {
                result.put("question", question);
            }
            if (bytes.length <= MAX_INLINE_IMAGE_BYTES) {
                result.put("inline_data_url", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes));
                result.put("analysis_status", "inline_ready");
            } else {
                result.put("analysis_status", "multimodal_model_required");
                result.put("analysis_message", "图片已安全读取，但超过内联上限；请使用已配置的多模态模型或缩小图片后重试。");
            }
            return Map.copyOf(result);
        } catch (IOException exception) {
            throw new ServiceException("图片读取失败", HttpStatus.ERROR);
        }
    }

    /**
 * 处理{@code delegateBatch}并返回对应结果。
 * Runs up to four independent delegations concurrently and preserves input order. */
    public Map<String, Object> delegateBatch(
        CurrentPrincipal principal,
        AgentRunRequest parentRequest,
        String executionId,
        Long conversationId,
        Map<String, Object> arguments
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        requireHuman(principal, "sub_agent_batch_call");
        Object rawCalls = arguments == null ? null : arguments.get("calls");
        if (!(rawCalls instanceof List<?> calls) || calls.isEmpty() || calls.size() > 4) {
            throw badRequest("calls 必须是1到4项的数组");
        }
        List<Map<String, Object>> normalized = new ArrayList<>(calls.size());
        for (Object raw : calls) {
            if (!(raw instanceof Map<?, ?> item)
                || item.size() != 2 || !item.containsKey("agent_name") || !item.containsKey("query")) {
                throw badRequest("每个委派项只能包含 agent_name 和 query");
            }
            normalized.add(Map.of(
                "agent_name", requiredText(item.get("agent_name"), "agent_name", 128),
                "query", requiredText(item.get("query"), "query", 12000)
            ));
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Map<String, Object>>> futures = normalized.stream()
                .map(item -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return delegate(principal, parentRequest, executionId, conversationId, item);
                    } catch (RuntimeException exception) {
                        return Map.of(
                            "status", "failed",
                            "agent_name", item.get("agent_name"),
                            "error", safeSummary(exception)
                        );
                    }
                }, executor))
                .toList();
            List<Map<String, Object>> results = new ArrayList<>(futures.size());
            for (CompletableFuture<Map<String, Object>> future : futures) {
                try {
                    results.add(future.join());
                } catch (CompletionException exception) {
                    results.add(Map.of("status", "failed", "error", safeSummary(exception)));
                }
            }
            int completedCount = 0;
            int failedCount = 0;
            int pendingCount = 0;
            for (Map<String, Object> result : results) {
                String status = String.valueOf(result.getOrDefault("status", "failed"))
                    .strip().toLowerCase(Locale.ROOT);
                if ("succeeded".equals(status) || "completed".equals(status)) {
                    completedCount++;
                } else if (Set.of(
                    "approval_required", "external_execution_required", "awaiting_user",
                    "queued", "running", "pending"
                ).contains(status)) {
                    pendingCount++;
                } else {
                    // A missing or unknown status is a failed child, never a successful batch.
                    failedCount++;
                }
            }
            String batchStatus = failedCount > 0
                ? "failed"
                : pendingCount > 0 ? "pending" : "completed";
            return Map.of(
                "status", batchStatus,
                "execution_mode", "parallel_bounded",
                "count", results.size(),
                "completed_count", completedCount,
                "failed_count", failedCount,
                "pending_count", pendingCount,
                "results", List.copyOf(results)
            );
        }
    }

    /**
     * 处理request用户Confirmation并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param request 请求参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    public Map<String, Object> requestUserConfirmation(
        CurrentPrincipal principal,
        AgentRunRequest request,
        Map<String, Object> arguments
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        requireHuman(principal, "request_user_confirmation");
        Objects.requireNonNull(request, "request must not be null");
        AgentRuntimeConfirmation confirmed = mapper.selectUnconsumedConfirmed(
            principal.id(), request.executionKey().executionId()
        );
        if (confirmed != null) {
            if (mapper.consumeConfirmation(
                confirmed.getId(), principal.id(), LocalDateTime.now()
            ) != 1) {
                throw new ServiceException("业务确认结果已被其他恢复操作消费", HttpStatus.CONFLICT);
            }
            Map<String, Object> ui = parseObject(confirmed.getUiJson());
            ui = new LinkedHashMap<>(ui);
            ui.put("status", "confirmed");
            ui.put("confirmation_id", confirmed.getConfirmationKey());
            return Map.of(
                "status", "confirmed",
                "confirmation_id", confirmed.getConfirmationKey(),
                "message", "用户已确认，继续执行。",
                "fields", parseList(confirmed.getFieldsJson()),
                "ui", ui
            );
        }
        String executionId = requiredText(
            request.executionKey().executionId(), "execution_id", 128
        );
        Long conversationId = request.conversationId();
        String title = requiredText(arguments.get("title"), "title", 255);
        Object rawFields = arguments.get("fields");
        if (!(rawFields instanceof List<?> fields) || fields.isEmpty() || fields.size() > 32) {
            throw badRequest("fields 至少包含一项且不得超过32项");
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object raw : fields) {
            if (!(raw instanceof Map<?, ?> source)) {
                throw badRequest("confirmation fields 必须是对象");
            }
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("key", requiredText(source.get("key"), "field.key", 128));
            field.put("label", requiredText(source.get("label"), "field.label", 255));
            field.put("value", source.containsKey("value") ? source.get("value") : "");
            field.put("editable", source.containsKey("editable") ? source.get("editable") : true);
            field.put("value_type", source.containsKey("value_type") ? source.get("value_type") : "string");
            normalized.add(field);
        }
        String key = "bc_" + idGenerator.nextUuid().replace("-", "").substring(0, 16);
        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("title", title);
        ui.put("summary", optionalText(arguments.get("summary"), 2000));
        ui.put("fields", normalized);
        ui.put("confirm_label", defaultText(arguments.get("confirm_label"), "确定", 64));
        ui.put("cancel_label", defaultText(arguments.get("cancel_label"), "取消", 64));
        ui.put("risk_note", optionalText(arguments.get("risk_note"), 2000));
        LocalDateTime now = LocalDateTime.now();
        mapper.insertConfirmation(
            idGenerator.nextId(), key, principal.id(), requiredText(executionId, "execution_id", 128),
            conversationId, title, jsonMapper.writeValueAsString(normalized),
            jsonMapper.writeValueAsString(ui), now.plusHours(24), now
        );
        return Map.of(
            "status", "awaiting_user", "confirmation_id", key,
            "message", "已向用户展示确认卡，请等待用户确定或取消后再继续。", "ui", ui
        );
    }

    /**
     * 处理{@code delegate}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param executionId 资源标识
     * @param conversationId 资源标识
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    public Map<String, Object> delegate(
        CurrentPrincipal principal,
        String executionId,
        Long conversationId,
        Map<String, Object> arguments
    ) {
        return delegate(principal, null, executionId, conversationId, arguments);
    }

    /**
 * 处理{@code delegate}并返回对应结果。
 *
     * Runs a delegated Agent synchronously inside the same governed runtime.
     * The old queue-only response made the tool look successful while never
     * starting a child execution; this path freezes the target Agent's own
     * published resources and returns its bounded text result to the caller.
     */
    public Map<String, Object> delegate(
        CurrentPrincipal principal,
        AgentRunRequest parentRequest,
        String executionId,
        Long conversationId,
        Map<String, Object> arguments
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        requireHuman(principal, "sub_agent_call");
        String agentName = requiredText(arguments.get("agent_name"), "agent_name", 128);
        String query = requiredText(arguments.get("query"), "query", 12000);
        int parentDepth = delegationDepth(parentRequest);
        int maxDepth = delegationMaxDepth();
        if (parentDepth >= maxDepth) {
            throw badRequest(
                "检测到多级 Agent 嵌套委派（当前深度 " + parentDepth
                    + "，最大深度 " + maxDepth + "），已拒绝执行以防循环"
            );
        }
        String currentAgent = parentRequest == null
            ? optionalText(arguments.get("current_agent_name"), 128)
            : parentRequest.agentName();
        List<String> parentAgentChain = delegationAgentChain(parentRequest);
        if (sameAgentName(agentName, currentAgent)
            || containsAgentName(parentAgentChain, agentName)) {
            throw badRequest("主 Agent 不能委派给自身");
        }
        String key = "deleg_" + idGenerator.nextUuid().replace("-", "").substring(0, 16);
        LocalDateTime now = LocalDateTime.now();
        String parentExecutionId = requiredText(executionId, "execution_id", 128);
        int inserted;
        try {
            inserted = mapper.insertDelegation(
                idGenerator.nextId(), key, principal.id(), parentExecutionId,
                conversationId, agentName, query, now
            );
        } catch (RuntimeException exception) {
            throw new ServiceException(
                "子 Agent 委派记录写入失败：" + safeSummary(exception), HttpStatus.ERROR
            );
        }
        if (inserted != 1) {
            throw conflict("子 Agent 委派记录写入失败");
        }
        if (parentRequest == null || agentMapper == null || runtimeSnapshotFactory == null
            || runtimeProvider == null) {
            persistDelegationState(
                key, principal.id(), "failed", null,
                "子 Agent 运行时未配置", LocalDateTime.now()
            );
            throw unavailable("sub_agent_call", "子 Agent 运行时未配置");
        }

        EmbedAgentRuntimeRow target;
        try {
            target = agentMapper.selectAgentRuntimeByRouteToken(agentName);
        } catch (RuntimeException exception) {
            String error = "目标 Agent 查询失败：" + safeSummary(exception);
            persistDelegationState(
                key, principal.id(), "failed", null, error, LocalDateTime.now()
            );
            return Map.of(
                "status", "failed", "delegation_id", key,
                "agent_name", agentName, "error", error
            );
        }
        if (target == null) {
            persistDelegationState(
                key, principal.id(), "failed", null,
                "未找到已发布的目标 Agent", LocalDateTime.now()
            );
            return Map.of(
                "status", "failed", "delegation_id", key, "agent_name", agentName,
                "error", "未找到已发布的目标 Agent"
            );
        }
        if (sameAgentName(target.getAgentName(), currentAgent)
            || containsAgentName(parentAgentChain, target.getAgentName())
            || delegationVersionChain(parentRequest).contains(target.getAgentVersionId())) {
            persistDelegationState(
                key, principal.id(), "failed", null,
                "检测到目标 Agent 已存在于委派链路", LocalDateTime.now()
            );
            return Map.of(
                "status", "failed", "delegation_id", key,
                "agent_name", target.getAgentName(), "error", "主 Agent 不能循环委派"
            );
        }

        Long childTurnId = idGenerator.nextId();
        // Child runs keep their own execution/run IDs but stay on the parent's
        // trace so the workbench can render one causal delegation timeline.
        String childTraceId = parentRequest.executionKey().traceId();
        String childSession = delegatedSession(parentRequest.sessionId(), key);
        AgentRunRequest childRequest;
        try {
            childRequest = runtimeSnapshotFactory.buildDelegated(
                principal, parentRequest, childSession, childTurnId, childTraceId,
                target.getAgentVersionId(), query, List.of()
            );
            Map<String, Object> attributes = new LinkedHashMap<>(childRequest.attributes());
            attributes.put("delegationId", key);
            attributes.put("parentExecutionId", parentExecutionId);
            attributes.put("delegatedByAgentVersionId", parentRequest.agentVersionId());
            attributes.put("delegationDepth", parentDepth + 1);
            attributes.put(
                "delegationAgentChain",
                childAgentChain(parentAgentChain, parentRequest.agentName(), target.getAgentName())
            );
            attributes.put(
                "delegationVersionChain",
                childVersionChain(
                    delegationVersionChain(parentRequest), parentRequest.agentVersionId(),
                    target.getAgentVersionId()
                )
            );
            childRequest = new AgentRunRequest(
                childRequest.executionKey(), childRequest.userId(), childRequest.conversationId(),
                childRequest.taskId(), childRequest.runId(), childRequest.stepId(),
                childRequest.agentVersionId(), childRequest.agentName(), childRequest.sessionId(),
                childRequest.input(), childRequest.systemPrompt(), childRequest.model(),
                childRequest.workspaceKey(), childRequest.maxIterations(),
                childRequest.authorizationSnapshot(), Map.copyOf(attributes)
            );
        } catch (RuntimeException exception) {
            String error = safeSummary(exception);
            persistDelegationState(
                key, principal.id(), "failed", null, error, LocalDateTime.now()
            );
            return Map.of(
                "status", "failed", "delegation_id", key, "agent_name", target.getAgentName(),
                "error", error
            );
        }

        LocalDateTime startedAt = LocalDateTime.now();
        int running;
        String runningError = null;
        try {
            running = mapper.markDelegationRunning(
                key, principal.id(), parentRequest.taskId(), parentRequest.runId(),
                parentRequest.stepId(), childRequest.taskId(), target.getAgentId(),
                target.getAgentVersionId(), childRequest.runId(), childRequest.stepId(),
                childRequest.executionKey().traceId(), startedAt,
                startedAt.plusSeconds(delegationTimeoutSeconds())
            );
        } catch (RuntimeException exception) {
            running = 0;
            runningError = safeSummary(exception);
        }
        if (running != 1) {
            String error = runningError == null
                ? "子 Agent 委派状态无法切换为运行中"
                : "子 Agent 委派状态写入异常：" + runningError;
            persistDelegationState(
                key, principal.id(), "failed", null, error, LocalDateTime.now()
            );
            return Map.of(
                "status", "failed", "delegation_id", key,
                "agent_name", target.getAgentName(), "error", error
            );
        }
        try {
            AgentRuntimeExecutionService runtime = runtimeProvider.getIfAvailable();
            if (runtime == null) {
                throw new IllegalStateException("AgentScope运行时未启用");
            }
            List<ExecutionEventView> events = runtime.run(childRequest)
                .takeUntil(this::delegationStopEvent)
                .collectList()
                .block(Duration.ofSeconds(delegationTimeoutSeconds()));
            DelegationOutput output = delegationOutput(events);
            boolean persisted = persistDelegationState(
                key, principal.id(), output.status(), output.text(), output.error(), LocalDateTime.now()
            );
            if (!persisted) {
                return delegationResponse(
                    "failed", key, target.getAgentName(), output.text(),
                    "子 Agent 结果持久化失败", childRequest.executionKey().traceId(), null
                );
            }
            return delegationResponse(
                output.status(), key, target.getAgentName(), output.text(), output.error(),
                childRequest.executionKey().traceId(), output.pendingType()
            );
        } catch (RuntimeException exception) {
            String error = safeSummary(exception);
            String status = isTimeout(exception) ? "timed_out" : "failed";
            boolean persisted = persistDelegationState(
                key, principal.id(), status, null, error, LocalDateTime.now()
            );
            return delegationResponse(
                persisted ? status : "failed", key, target.getAgentName(), null,
                persisted ? error : error + "；委派状态持久化失败",
                childRequest.executionKey().traceId(), null
            );
        }
    }

    /**
     * 处理{@code jira}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param operation 操作参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    public Map<String, Object> jira(
        CurrentPrincipal principal,
        String operation,
        Map<String, Object> arguments
    ) {
        requireHuman(principal, operation);
        String baseUrl = jiraUrl();
        if (baseUrl.isBlank()) {
            throw unavailable(operation, "Jira URL 未配置");
        }
        return switch (operation) {
            case "jira_search" -> jiraSearch(baseUrl, arguments);
            case "jira_get_projects" -> jiraGetProjects(baseUrl);
            case "jira_create_issue" -> jiraCreateIssue(baseUrl, arguments);
            default -> throw badRequest("不支持的 Jira 工具");
        };
    }

    /**
     * 处理{@code excelRead}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    public Map<String, Object> excelRead(CurrentPrincipal principal, Map<String, Object> arguments) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        requireHuman(principal, "excel_document_read");
        String action = defaultText(arguments.get("action"), "inspect", 32);
        Path source = workspaceService.resolveRuntimeFile(principal, requiredText(arguments.get("path"), "path", 512), false);
        try (Workbook workbook = WorkbookFactory.create(source.toFile())) {
            if ("inspect".equals(action)) {
                List<Map<String, Object>> sheets = new ArrayList<>();
                DataFormatter formatter = new DataFormatter();
                for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                    Sheet sheet = workbook.getSheetAt(index);
                    sheets.add(Map.of(
                        "name", sheet.getSheetName(),
                        "rows", Math.max(sheet.getLastRowNum() + 1, 0),
                        "columns", maxColumns(sheet),
                        "preview", previewSheet(sheet, formatter)
                    ));
                }
                return Map.of("status", "ok", "data", Map.of("sheets", sheets));
            }
            if (!"read_range".equals(action)) {
                throw badRequest("excel_document_read 仅支持 inspect 或 read_range");
            }
            String sheetName = requiredText(arguments.get("sheet_name"), "sheet_name", 255);
            String range = requiredText(arguments.get("cell_range"), "cell_range", 64);
            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                throw notFound("工作表不存在");
            }
            return Map.of("status", "ok", "data", Map.of("values", readRange(sheet, range)));
        } catch (IOException | RuntimeException exception) {
            throw new ServiceException("Excel 文件读取失败：" + exception.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 处理{@code excelWrite}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    public Map<String, Object> excelWrite(CurrentPrincipal principal, Map<String, Object> arguments) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        requireHuman(principal, "excel_document_write");
        String action = defaultText(arguments.get("action"), "create", 32);
        Path temporary = null;
        try {
            Workbook workbook;
            if ("create".equals(action)) {
                workbook = new XSSFWorkbook();
                String sheetName = optionalText(arguments.get("sheet_name"), 255);
                if (sheetName != null) {
                    workbook.setSheetName(0, sheetName);
                }
            } else {
                Path source = workspaceService.resolveRuntimeFile(principal, requiredText(arguments.get("path"), "path", 512), true);
                workbook = WorkbookFactory.create(source.toFile());
            }
            try (workbook) {
                String sheetName = defaultText(arguments.get("sheet_name"), workbook.getSheetAt(0).getSheetName(), 255);
                if ("create_sheet".equals(action)) {
                    if (workbook.getSheet(sheetName) != null) {
                        throw conflict("工作表已存在");
                    }
                    workbook.createSheet(sheetName);
                } else {
                    Sheet sheet = workbook.getSheet(sheetName);
                    if (sheet == null) {
                        throw notFound("工作表不存在");
                    }
                    if ("write_cells".equals(action)) {
                        writeCells(sheet, arguments.get("cells"));
                    } else if ("append_rows".equals(action)) {
                        appendRows(sheet, arguments.get("rows"));
                    } else if (!"create".equals(action)) {
                        throw badRequest("excel_document_write 不支持该操作");
                    }
                }
                temporary = Files.createTempFile("agent-excel-", ".xlsx");
                try (OutputStream output = Files.newOutputStream(temporary)) {
                    workbook.write(output);
                }
            }
            return publishedArtifact(temporary, defaultText(arguments.get("output_filename"), "agent-output.xlsx", 255));
        } catch (IOException | RuntimeException exception) {
            throw new ServiceException("Excel 文件写入失败：" + exception.getMessage(), HttpStatus.BAD_REQUEST);
        } finally {
            deleteQuietly(temporary);
        }
    }

    /**
     * 处理{@code wordRead}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    public Map<String, Object> wordRead(CurrentPrincipal principal, Map<String, Object> arguments) {
        requireHuman(principal, "word_document_read");
        Path source = workspaceService.resolveRuntimeFile(principal, requiredText(arguments.get("path"), "path", 512), false);
        try (InputStream input = Files.newInputStream(source); XWPFDocument document = new XWPFDocument(input)) {
            List<String> paragraphs = document.getParagraphs().stream().map(XWPFParagraph::getText).toList();
            String action = defaultText(arguments.get("action"), "inspect", 32);
            if ("inspect".equals(action)) {
                return Map.of("status", "ok", "data", Map.of(
                    "paragraph_count", paragraphs.size(), "table_count", document.getTables().size(),
                    "preview", paragraphs.stream().limit(20).toList()
                ));
            }
            int start = integer(arguments.get("start"), 0, 0, 100_000);
            int limit = integer(arguments.get("limit"), 20, 1, 50);
            return Map.of("status", "ok", "data", Map.of(
                "paragraphs", paragraphs.subList(Math.min(start, paragraphs.size()), Math.min(start + limit, paragraphs.size())),
                "start", start
            ));
        } catch (IOException | RuntimeException exception) {
            throw new ServiceException("Word 文件读取失败：" + exception.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 处理{@code wordWrite}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    public Map<String, Object> wordWrite(CurrentPrincipal principal, Map<String, Object> arguments) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        requireHuman(principal, "word_document_write");
        String action = defaultText(arguments.get("action"), "create", 32);
        Path temporary = null;
        try {
            XWPFDocument document;
            if ("create".equals(action)) {
                document = new XWPFDocument();
                String title = optionalText(arguments.get("title"), 255);
                if (title != null) {
                    document.createParagraph().createRun().setText(title);
                }
            } else {
                Path source = workspaceService.resolveRuntimeFile(principal, requiredText(arguments.get("path"), "path", 512), true);
                document = new XWPFDocument(Files.newInputStream(source));
            }
            try (document) {
                if ("replace_text".equals(action)) {
                    replaceParagraphText(document, arguments.get("replacements"));
                } else if ("append_paragraphs".equals(action) || "create".equals(action)) {
                    appendParagraphs(document, arguments.get("paragraphs"));
                } else if ("append_table".equals(action)) {
                    appendTable(document, arguments.get("headers"), arguments.get("rows"));
                } else {
                    throw badRequest("word_document_write 不支持该操作");
                }
                temporary = Files.createTempFile("agent-word-", ".docx");
                try (OutputStream output = Files.newOutputStream(temporary)) {
                    document.write(output);
                }
            }
            return publishedArtifact(temporary, defaultText(arguments.get("output_filename"), "agent-output.docx", 255));
        } catch (IOException | RuntimeException exception) {
            throw new ServiceException("Word 文件写入失败：" + exception.getMessage(), HttpStatus.BAD_REQUEST);
        } finally {
            deleteQuietly(temporary);
        }
    }

    /**
     * 处理{@code jiraSearch}并返回对应结果。
     *
     * @param baseUrl {@code baseUrl}参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Map<String, Object> jiraSearch(String baseUrl, Map<String, Object> arguments) {
        String jql = requiredText(arguments.get("jql"), "jql", 4000);
        Map<String, Object> query = Map.of("jql", jql, "maxResults", 10, "fields", "summary,status,assignee,created,updated,reporter,description,comment");
        return jiraJson("GET", baseUrl + "/rest/api/2/search?" + queryString(query), null);
    }

    /**
     * 处理{@code jiraGetProjects}并返回对应结果。
     *
     * @param baseUrl {@code baseUrl}参数
     * @return 处理结果
     */
    private Map<String, Object> jiraGetProjects(String baseUrl) {
        return jiraJson("GET", baseUrl + "/rest/api/2/project", null);
    }

    /**
     * 处理{@code jiraCreateIssue}并返回对应结果。
     *
     * @param baseUrl {@code baseUrl}参数
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    private Map<String, Object> jiraCreateIssue(String baseUrl, Map<String, Object> arguments) {
        String project = requiredText(arguments.get("project_key"), "project_key", 32).toUpperCase(Locale.ROOT);
        if (!project.matches("[A-Z][A-Z0-9_-]{1,19}")) {
            throw badRequest("project_key 无效");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("project", Map.of("key", project));
        fields.put("summary", requiredText(arguments.get("summary"), "summary", 255));
        fields.put("description", requiredText(arguments.get("description"), "description", 32_000));
        fields.put("issuetype", Map.of("name", defaultText(arguments.get("issue_type"), "Task", 64)));
        return jiraJson("POST", baseUrl + "/rest/api/2/issue", Map.of("fields", fields));
    }

    /**
     * 处理{@code jiraJson}并返回对应结果。
     *
     * @param method {@code method}参数
     * @param url {@code url}参数
     * @param body {@code body}参数
     * @return 处理结果
     */
    private Map<String, Object> jiraJson(String method, String url, Object body) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/json");
            authHeader().ifPresent(value -> builder.header("Authorization", value));
            if ("GET".equals(method)) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)));
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ServiceException("Jira Provider 返回 HTTP " + response.statusCode(), response.statusCode());
            }
            String content = response.body() == null ? "" : response.body();
            if (content.isBlank()) {
                return Map.of("status", "ok");
            }
            Object parsed = jsonMapper.readValue(content, Object.class);
            return parsed instanceof Map<?, ?> map ? copyMap(map) : Map.of("status", "ok", "data", parsed);
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable("jira", "Jira Provider 请求失败");
        }
    }

    /**
     * 处理认证Header并返回对应结果。
     *
     * @return 处理结果
     */
    private java.util.Optional<String> authHeader() {
        String token = env("JIRA_API_TOKEN");
        String email = env("JIRA_EMAIL");
        if (!token.isBlank() && !email.isBlank()) {
            return java.util.Optional.of("Basic " + Base64.getEncoder().encodeToString((email + ":" + token).getBytes(StandardCharsets.UTF_8)));
        }
        if (!token.isBlank()) {
            return java.util.Optional.of("Bearer " + token);
        }
        String username = env("JIRA_USERNAME");
        String password = env("JIRA_PASSWORD");
        if (!username.isBlank() && !password.isBlank()) {
            return java.util.Optional.of("Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8)));
        }
        return java.util.Optional.empty();
    }

    /**
     * 处理{@code jiraUrl}并返回对应结果。
     *
     * @return 处理结果
     */
    private String jiraUrl() {
        String value = configuredJiraUrl.isBlank() ? env("JIRA_URL") : configuredJiraUrl;
        return value.strip().replaceAll("/+$", "");
    }

    /**
     * 获取{@code Sql}。
     *
     * @param connection {@code connection}参数
     * @param sql {@code sql}参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Map<String, Object> querySql(Connection connection, String sql) throws SQLException {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(10);
            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                int columns = Math.min(metadata.getColumnCount(), MAX_COLUMNS);
                List<String> names = new ArrayList<>();
                for (int index = 1; index <= columns; index++) {
                    names.add(metadata.getColumnLabel(index));
                }
                List<Map<String, Object>> rows = new ArrayList<>();
                while (resultSet.next() && rows.size() < MAX_ROWS) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int index = 1; index <= columns; index++) {
                        row.put(names.get(index - 1), resultSet.getObject(index));
                    }
                    rows.add(row);
                }
                return Map.of("status", "ok", "columns", names, "rows", rows, "truncated", rows.size() >= MAX_ROWS);
            }
        }
    }

    /**
     * 处理导入Rows相关逻辑。
     *
     * @param connection {@code connection}参数
     * @param raw {@code raw}参数
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private void importRows(Connection connection, Object raw) throws SQLException {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (raw == null) {
            return;
        }
        Object value = raw instanceof String text ? jsonMapper.readValue(text, OBJECT) : raw;
        if (!(value instanceof Map<?, ?> tables)) {
            throw badRequest("import_data 必须是 JSON 对象");
        }
        for (Map.Entry<?, ?> tableEntry : tables.entrySet()) {
            String table = safeIdentifier(String.valueOf(tableEntry.getKey()), "表名");
            if (!(tableEntry.getValue() instanceof List<?> rows) || rows.isEmpty()) {
                continue;
            }
            Object first = rows.getFirst();
            if (!(first instanceof Map<?, ?> firstMap) || firstMap.isEmpty()) {
                continue;
            }
            List<String> columns = firstMap.keySet().stream().map(key -> safeIdentifier(String.valueOf(key), "列名")).toList();
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DROP TABLE IF EXISTS \"" + table + "\"");
                statement.executeUpdate("CREATE TABLE \"" + table + "\" (" + columns.stream().map(column -> "\"" + column + "\" TEXT").reduce((a, b) -> a + "," + b).orElse("") + ")");
            }
            String placeholders = columns.stream().map(column -> "?").reduce((a, b) -> a + "," + b).orElse("");
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO \"" + table + "\" (" + columns.stream().map(column -> "\"" + column + "\"").reduce((a, b) -> a + "," + b).orElse("") + ") VALUES (" + placeholders + ")")) {
                for (Object row : rows) {
                    if (row instanceof Map<?, ?> values) {
                        for (int index = 0; index < columns.size(); index++) {
                            statement.setObject(index + 1, values.get(columns.get(index)));
                        }
                        statement.addBatch();
                    }
                }
                statement.executeBatch();
            }
        }
        connection.commit();
    }

    /**
     * 处理{@code previewSheet}并返回对应结果。
     *
     * @param sheet {@code sheet}参数
     * @param formatter {@code formatter}参数
     * @return 符合条件的数据集合
     */
    private List<List<String>> previewSheet(Sheet sheet, DataFormatter formatter) {
        List<List<String>> rows = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < Math.min(sheet.getLastRowNum() + 1, 20); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            List<String> values = new ArrayList<>();
            for (int column = 0; column < Math.min(maxColumns(sheet), 20); column++) {
                Cell cell = row == null ? null : row.getCell(column);
                values.add(cell == null ? "" : formatter.formatCellValue(cell));
            }
            rows.add(values);
        }
        return rows;
    }

    /**
     * 处理{@code readRange}并返回对应结果。
     *
     * @param sheet {@code sheet}参数
     * @param rawRange {@code rawRange}参数
     * @return 符合条件的数据集合
     */
    private List<List<String>> readRange(Sheet sheet, String rawRange) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Matcher matcher = CELL_RANGE.matcher(rawRange.strip());
        if (!matcher.matches()) {
            throw badRequest("cell_range 无效");
        }
        CellReference start = new CellReference(matcher.group(1) + matcher.group(2));
        CellReference end = new CellReference(
            matcher.group(3) == null ? matcher.group(1) + matcher.group(2) : matcher.group(3) + matcher.group(4)
        );
        int rowCount = end.getRow() - start.getRow() + 1;
        int columnCount = end.getCol() - start.getCol() + 1;
        if (rowCount <= 0 || columnCount <= 0 || rowCount > 200 || columnCount > 50) {
            throw badRequest("读取范围超过200行或50列限制");
        }
        DataFormatter formatter = new DataFormatter();
        List<List<String>> rows = new ArrayList<>();
        for (int rowIndex = start.getRow(); rowIndex <= end.getRow(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            List<String> values = new ArrayList<>();
            for (int column = start.getCol(); column <= end.getCol(); column++) {
                Cell cell = row == null ? null : row.getCell(column);
                values.add(cell == null ? "" : formatter.formatCellValue(cell));
            }
            rows.add(values);
        }
        return rows;
    }

    /**
     * 处理{@code maxColumns}并返回对应结果。
     *
     * @param sheet {@code sheet}参数
     * @return 处理结果
     */
    private int maxColumns(Sheet sheet) {
        int max = 0;
        for (Row row : sheet) {
            if (row != null && row.getLastCellNum() > max) {
                max = row.getLastCellNum();
            }
        }
        return max;
    }

    /**
     * 处理{@code writeCells}相关逻辑。
     *
     * @param sheet {@code sheet}参数
     * @param raw {@code raw}参数
     */
    private void writeCells(Sheet sheet, Object raw) {
        if (!(raw instanceof List<?> cells) || cells.isEmpty() || cells.size() > 1000) {
            throw badRequest("write_cells 需要1-1000个 cells");
        }
        for (Object rawCell : cells) {
            if (!(rawCell instanceof Map<?, ?> cell)) {
                throw badRequest("cell 必须是对象");
            }
            String address = requiredText(cell.get("address"), "cell.address", 32);
            CellReference reference = new CellReference(address);
            Cell target = sheet.getRow(reference.getRow()) == null
                ? sheet.createRow(reference.getRow()).createCell(reference.getCol())
                : sheet.getRow(reference.getRow()).createCell(reference.getCol());
            setCellValue(target, cell.get("value"));
        }
    }

    /**
     * 处理{@code appendRows}相关逻辑。
     *
     * @param sheet {@code sheet}参数
     * @param raw {@code raw}参数
     */
    private void appendRows(Sheet sheet, Object raw) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (!(raw instanceof List<?> rows) || rows.isEmpty() || rows.size() > 1000) {
            throw badRequest("append_rows 需要1-1000行");
        }
        for (Object rawRow : rows) {
            if (!(rawRow instanceof List<?> values) || values.size() > 50) {
                throw badRequest("每行最多50列");
            }
            Row row = sheet.createRow(sheet.getLastRowNum() + 1);
            for (int index = 0; index < values.size(); index++) {
                setCellValue(row.createCell(index), values.get(index));
            }
        }
    }

    /**
     * 设置{@code CellValue}。
     *
     * @param cell {@code cell}参数
     * @param value {@code value}参数
     */
    private void setCellValue(Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }

    /**
     * 处理{@code replaceParagraphText}相关逻辑。
     *
     * @param document 文档参数
     * @param raw {@code raw}参数
     */
    private void replaceParagraphText(XWPFDocument document, Object raw) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (!(raw instanceof List<?> replacements) || replacements.size() > 100) {
            throw badRequest("replace_text 需要不超过100个替换项");
        }
        for (Object rawReplacement : replacements) {
            if (!(rawReplacement instanceof Map<?, ?> replacement)) {
                throw badRequest("replacement 必须是对象");
            }
            String find = requiredText(replacement.get("find"), "replacement.find", 1000);
            String replace = String.valueOf(
                replacement.containsKey("replace") ? replacement.get("replace") : ""
            );
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                for (var run : paragraph.getRuns()) {
                    run.setText(run.getText(0) == null ? "" : run.getText(0).replace(find, replace), 0);
                }
            }
        }
    }

    /**
     * 处理{@code appendParagraphs}相关逻辑。
     *
     * @param document 文档参数
     * @param raw {@code raw}参数
     */
    private void appendParagraphs(XWPFDocument document, Object raw) {
        if (!(raw instanceof List<?> paragraphs) || paragraphs.size() > 1000) {
            throw badRequest("paragraphs 不得超过1000项");
        }
        for (Object paragraph : paragraphs) {
            document.createParagraph().createRun().setText(String.valueOf(paragraph));
        }
    }

    /**
     * 处理{@code appendTable}相关逻辑。
     *
     * @param document 文档参数
     * @param rawHeaders {@code rawHeaders}参数
     * @param rawRows {@code rawRows}参数
     */
    private void appendTable(XWPFDocument document, Object rawHeaders, Object rawRows) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (!(rawHeaders instanceof List<?> headers) || headers.isEmpty() || headers.size() > 50) {
            throw badRequest("append_table 需要1-50个 headers");
        }
        XWPFTable table = document.createTable(1, headers.size());
        XWPFTableRow header = table.getRow(0);
        for (int index = 0; index < headers.size(); index++) {
            header.getCell(index).setText(String.valueOf(headers.get(index)));
        }
        if (rawRows instanceof List<?> rows) {
            for (Object rawRow : rows) {
                if (!(rawRow instanceof List<?> values) || values.size() != headers.size()) {
                    throw badRequest("表格行列数必须与 headers 一致");
                }
                XWPFTableRow row = table.createRow();
                for (int index = 0; index < values.size(); index++) {
                    row.getCell(index).setText(String.valueOf(values.get(index)));
                }
            }
        }
    }

    /**
     * 处理published制品并返回对应结果。
     *
     * @param path {@code path}参数
     * @param fileName 名称
     * @return 处理结果
     */
    private Map<String, Object> publishedArtifact(Path path, String fileName) {
        try {
            return Map.of("status", "ok", "artifact", generatedFileService.publish(path, fileName).toolPayload());
        } catch (RuntimeException exception) {
            throw new ServiceException("生成文件发布失败", HttpStatus.ERROR);
        }
    }

    /**
     * 处理delegated会话并返回对应结果。
     *
     * @param parentSession parent会话参数
     * @param delegationKey {@code delegationKey}参数
     * @return 处理结果
     */
    private String delegatedSession(String parentSession, String delegationKey) {
        String base = parentSession == null || parentSession.isBlank()
            ? "delegated" : parentSession.strip();
        String value = base + ":" + delegationKey;
        return value.length() <= 128 ? value : value.substring(value.length() - 128);
    }

    /**
     * 处理{@code delegationDepth}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private int delegationDepth(AgentRunRequest request) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (request == null) return 0;
        Object raw = request.attributes().get("delegationDepth");
        if (raw instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (raw instanceof String text) {
            try {
                return Math.max(0, Integer.parseInt(text.strip()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * 处理{@code delegationMaxDepth}并返回对应结果。
     *
     * @return 处理结果
     */
    private int delegationMaxDepth() {
        String configured = System.getenv("NHS_SUB_AGENT_MAX_DEPTH");
        if (configured == null || configured.isBlank()) {
            return DEFAULT_DELEGATION_MAX_DEPTH;
        }
        try {
            return Math.max(1, Math.min(8, Integer.parseInt(configured.strip())));
        } catch (NumberFormatException ignored) {
            return DEFAULT_DELEGATION_MAX_DEPTH;
        }
    }

    /**
     * 处理delegation智能体Chain并返回对应结果。
     *
     * @param request 请求参数
     * @return 符合条件的数据集合
     */
    private List<String> delegationAgentChain(AgentRunRequest request) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (request == null) return List.of();
        Object raw = request.attributes().get("delegationAgentChain");
        if (!(raw instanceof List<?> values)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (result.size() >= 16 || !(value instanceof String text) || text.isBlank()) continue;
            String normalized = text.strip();
            if (!containsAgentName(result, normalized)) result.add(normalized);
        }
        return List.copyOf(result);
    }

    /**
     * 处理delegation版本Chain并返回对应结果。
     *
     * @param request 请求参数
     * @return 符合条件的数据集合
     */
    private List<Long> delegationVersionChain(AgentRunRequest request) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (request == null) return List.of();
        Object raw = request.attributes().get("delegationVersionChain");
        if (!(raw instanceof List<?> values)) return List.of();
        List<Long> result = new ArrayList<>();
        for (Object value : values) {
            if (result.size() >= 16 || !(value instanceof Number number)) continue;
            long id = number.longValue();
            if (id > 0 && !result.contains(id)) result.add(id);
        }
        return List.copyOf(result);
    }

    /**
     * 处理child智能体Chain并返回对应结果。
     *
     * @param inherited {@code inherited}参数
     * @param parentAgent parent智能体参数
     * @param targetAgent target智能体参数
     * @return 符合条件的数据集合
     */
    private List<String> childAgentChain(
        List<String> inherited,
        String parentAgent,
        String targetAgent
    ) {
        List<String> result = new ArrayList<>(inherited);
        if (parentAgent != null && !containsAgentName(result, parentAgent)) result.add(parentAgent);
        if (targetAgent != null && !containsAgentName(result, targetAgent)) result.add(targetAgent);
        return List.copyOf(result);
    }

    /**
     * 处理child版本Chain并返回对应结果。
     *
     * @param inherited {@code inherited}参数
     * @param parentVersionId 资源标识
     * @param targetVersionId 资源标识
     * @return 符合条件的数据集合
     */
    private List<Long> childVersionChain(
        List<Long> inherited,
        Long parentVersionId,
        Long targetVersionId
    ) {
        List<Long> result = new ArrayList<>(inherited);
        if (parentVersionId != null && !result.contains(parentVersionId)) result.add(parentVersionId);
        if (targetVersionId != null && !result.contains(targetVersionId)) result.add(targetVersionId);
        return List.copyOf(result);
    }

    /**
     * 处理contains智能体Name并返回对应结果。
     *
     * @param values {@code values}参数
     * @param expected {@code expected}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean containsAgentName(List<String> values, String expected) {
        if (expected == null) return false;
        return values.stream().anyMatch(value -> sameAgentName(value, expected));
    }

    /**
     * 处理same智能体Name并返回对应结果。
     *
     * @param left {@code left}参数
     * @param right {@code right}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean sameAgentName(String left, String right) {
        if (left == null || right == null) return false;
        return normalizeAgentName(left).equals(normalizeAgentName(right));
    }

    /**
     * 处理normalize智能体Name并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeAgentName(String value) {
        return value.strip().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    /**
     * 处理{@code delegationTimeoutSeconds}并返回对应结果。
     *
     * @return 处理结果
     */
    private int delegationTimeoutSeconds() {
        String configured = System.getenv("NHS_SUB_AGENT_TIMEOUT_SECONDS");
        if (configured == null || configured.isBlank()) {
            return 120;
        }
        try {
            return Math.max(5, Math.min(600, Integer.parseInt(configured.strip())));
        } catch (NumberFormatException ignored) {
            return 120;
        }
    }

    /**
     * 处理delegation结果MaxChars并返回对应结果。
     *
     * @return 处理结果
     */
    private int delegationResultMaxChars() {
        String configured = System.getenv("NHS_SUB_AGENT_RESULT_MAX_CHARS");
        if (configured == null || configured.isBlank()) {
            return DEFAULT_DELEGATION_RESULT_MAX_CHARS;
        }
        try {
            return Math.max(500, Math.min(65_536, Integer.parseInt(configured.strip())));
        } catch (NumberFormatException ignored) {
            return DEFAULT_DELEGATION_RESULT_MAX_CHARS;
        }
    }

    /**
     * 处理delegationStop事件并返回对应结果。
     *
     * @param event 事件参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean delegationStopEvent(ExecutionEventView event) {
        if (event == null || event.eventType() == null) return false;
        return switch (event.eventType()) {
            case "run_finished", "approval_required", "external_execution_required",
                 "failed", "permission_denied", "iteration_limit_reached", "cancelled" -> true;
            default -> false;
        };
    }

    /**
     * 处理{@code delegationOutput}并返回对应结果。
     *
     * @param events {@code events}参数
     * @return 处理结果
     */
    private DelegationOutput delegationOutput(List<ExecutionEventView> events) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (events == null || events.isEmpty()) {
            return new DelegationOutput("failed", "", "子 Agent 未返回执行事件", null);
        }
        StringBuilder content = new StringBuilder();
        String terminalStatus = null;
        String pendingType = null;
        String error = null;
        for (ExecutionEventView event : events) {
            if (event == null) continue;
            String type = event.eventType();
            if ("text_delta".equals(type) && event.summary() != null) {
                appendBounded(content, event.summary(), MAX_DELEGATION_CAPTURE_CHARS);
            } else if ("failed".equals(type) || "permission_denied".equals(type)
                || "iteration_limit_reached".equals(type)) {
                terminalStatus = "failed";
                error = event.summary();
            } else if ("cancelled".equals(type)) {
                terminalStatus = "cancelled";
                error = event.summary();
            } else if ("approval_required".equals(type)) {
                pendingType = "approval_required";
            } else if ("external_execution_required".equals(type)) {
                pendingType = "external_execution_required";
            } else if ("run_finished".equals(type)) {
                terminalStatus = "failed".equals(event.eventStatus()) ? "failed" : "succeeded";
                if ("failed".equals(terminalStatus)) error = event.summary();
            }
        }
        String output = finalizeDelegationOutput(content.toString());
        if (pendingType != null) {
            String reason = "approval_required".equals(pendingType)
                ? "子 Agent 需要用户确认工具权限，委派已暂停"
                : "子 Agent 需要外部执行结果，委派已暂停";
            return new DelegationOutput(
                "approval_required", output, reason, pendingType
            );
        }
        if (terminalStatus == null) {
            return new DelegationOutput(
                "failed", output, "子 Agent 事件流在终态事件前结束", null
            );
        }
        if (!"succeeded".equals(terminalStatus)) {
            return new DelegationOutput(terminalStatus, output, safeSummary(error), null);
        }
        return new DelegationOutput(
            "succeeded", output.isBlank() ? EMPTY_DELEGATION_RESULT_MESSAGE : output, null, null
        );
    }

    /**
     * 处理{@code finalizeDelegationOutput}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @return 处理结果
     */
    private String finalizeDelegationOutput(String raw) {
        String cleaned = SQL_PLAN_BLOCK.matcher(raw == null ? "" : raw).replaceAll("");
        cleaned = SQL_PLAN_REMAINDER.matcher(cleaned).replaceAll("").strip();
        if (cleaned.isBlank()) return "";
        int limit = delegationResultMaxChars();
        if (cleaned.length() <= limit) return cleaned;
        String suffix = "\n\n...[因数据量过大，子 Agent 回复已被系统自动截断]";
        int end = Math.max(0, limit - suffix.length());
        if (end > 0 && Character.isHighSurrogate(cleaned.charAt(end - 1))
            && Character.isLowSurrogate(cleaned.charAt(end))) {
            end--;
        }
        return cleaned.substring(0, end) + suffix;
    }

    /**
     * 处理{@code persistDelegationState}并返回对应结果。
     *
     * @param delegationKey {@code delegationKey}参数
     * @param ownerId 资源标识
     * @param status 目标状态
     * @param resultText 待处理内容
     * @param errorSummary {@code errorSummary}参数
     * @param now {@code now}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean persistDelegationState(
        String delegationKey,
        Long ownerId,
        String status,
        String resultText,
        String errorSummary,
        LocalDateTime now
    ) {
        if (!DELEGATION_FINAL_STATUSES.contains(status)) return false;
        try {
            return mapper.updateDelegation(
                delegationKey, ownerId, status, resultText,
                errorSummary == null ? null : safeSummary(errorSummary), now
            ) == 1;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * 处理{@code delegationResponse}并返回对应结果。
     *
     * @param status 目标状态
     * @param delegationKey {@code delegationKey}参数
     * @param agentName 名称
     * @param result 结果参数
     * @param error {@code error}参数
     * @param traceId 资源标识
     * @param pendingType 业务类型
     * @return 处理结果
     */
    private Map<String, Object> delegationResponse(
        String status,
        String delegationKey,
        String agentName,
        String result,
        String error,
        String traceId,
        String pendingType
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status);
        response.put("delegation_id", delegationKey);
        response.put("agent_name", agentName);
        if (result != null) response.put("result", result);
        if (error != null) response.put("error", error);
        if (traceId != null) response.put("trace_id", traceId);
        if (pendingType != null) response.put("pending_type", pendingType);
        return Map.copyOf(response);
    }

    /**
     * 处理{@code appendBounded}相关逻辑。
     *
     * @param target {@code target}参数
     * @param value {@code value}参数
     * @param maxChars {@code maxChars}参数
     */
    private void appendBounded(StringBuilder target, String value, int maxChars) {
        if (value == null || target.length() >= maxChars) return;
        int remaining = maxChars - target.length();
        int end = Math.min(value.length(), remaining);
        if (end < value.length() && end > 0
            && Character.isHighSurrogate(value.charAt(end - 1))
            && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        if (end > 0) target.append(value, 0, end);
    }

    /**
     * 处理{@code safeSummary}并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @return 处理结果
     */
    private String safeSummary(Throwable exception) {
        return safeSummary(exception == null ? null : exception.getMessage());
    }

    /**
     * 处理{@code safeSummary}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String safeSummary(String value) {
        String normalized = value == null || value.isBlank() ? "子 Agent 执行失败" : value.strip();
        normalized = normalized.replace('\0', ' ');
        return normalized.length() <= 2000 ? normalized : normalized.substring(0, 2000);
    }

    /**
     * 判断{@code Timeout}是否满足要求。
     *
     * @param exception {@code exception}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isTimeout(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof java.util.concurrent.TimeoutException
                || current.getClass().getName().contains("Timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 封装{@code DelegationOutput}相关的不可变数据。
     */
    private record DelegationOutput(
        String status,
        String text,
        String error,
        String pendingType
    ) {
    }

    /**
     * 处理{@code safeIdentifier}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String safeIdentifier(String value, String label) {
        if (!IDENTIFIER.matcher(value).matches()) {
            throw badRequest(label + "无效");
        }
        return value;
    }

    /**
     * 处理frozen资源Counts并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    private Map<String, Object> frozenResourceCounts(AgentRunRequest request) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        Object rawSnapshot = request.attributes().get("taskResourceSnapshot");
        if (rawSnapshot instanceof Map<?, ?> snapshot && snapshot.get("resources") instanceof List<?> resources) {
            for (Object raw : resources) {
                if (raw instanceof Map<?, ?> item && item.get("resourceType") != null) {
                    String type = String.valueOf(item.get("resourceType"));
                    counts.merge(type, 1, Integer::sum);
                }
            }
        }
        return Map.copyOf(counts);
    }

    /**
     * 处理{@code extension}并返回对应结果。
     *
     * @param file 文件参数
     * @return 处理结果
     */
    private String extension(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    /**
     * 处理{@code imageMime}并返回对应结果。
     *
     * @param extension {@code extension}参数
     * @return 处理结果
     */
    private String imageMime(String extension) {
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            default -> "image/png";
        };
    }

    /**
     * 处理{@code putIfPresent}相关逻辑。
     *
     * @param target {@code target}参数
     * @param key {@code key}参数
     * @param value {@code value}参数
     */
    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /**
     * 处理{@code copyMap}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    private Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    /**
     * 获取{@code String}。
     *
     * @param values {@code values}参数
     * @return 处理结果
     */
    private String queryString(Map<String, Object> values) {
        return values.entrySet().stream()
            .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8))
            .reduce((left, right) -> left + "&" + right).orElse("");
    }

    /**
     * 处理{@code env}并返回对应结果。
     *
     * @param key {@code key}参数
     * @return 处理结果
     */
    private String env(String key) {
        String value = System.getenv(key);
        return value == null ? "" : value.strip();
    }

    /**
     * 校验{@code Human}，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param tool 工具参数
     */
    private void requireHuman(CurrentPrincipal principal, String tool) {
        if (principal == null || !principal.isHuman()) {
            throw new ServiceException(tool + " 仅允许人类运行主体调用", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 校验{@code dText}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param name 名称
     * @param max {@code max}参数
     * @return 处理结果
     */
    private String requiredText(Object value, String name, int max) {
        String text = optionalText(value, max);
        if (text == null) {
            throw badRequest(name + " 不能为空");
        }
        return text;
    }

    /**
     * 处理{@code parseObject}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> parseObject(String value) {
        if (value == null || value.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = jsonMapper.readValue(value, OBJECT);
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (RuntimeException exception) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 处理{@code parseList}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> parseList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> parsed = jsonMapper.readValue(value, OBJECT_LIST);
            return parsed == null ? List.of() : List.copyOf(parsed);
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    /**
     * 处理{@code optionalText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param max {@code max}参数
     * @return 处理结果
     */
    private String optionalText(Object value, int max) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        if (text.isBlank()) {
            return null;
        }
        if (text.length() > max || text.indexOf('\0') >= 0) {
            throw badRequest("参数超过长度限制");
        }
        return text;
    }

    /**
     * 处理{@code defaultText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param fallback {@code fallback}参数
     * @param max {@code max}参数
     * @return 处理结果
     */
    private String defaultText(Object value, String fallback, int max) {
        String text = optionalText(value, max);
        return text == null ? fallback : text;
    }

    /**
     * 处理{@code integer}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param fallback {@code fallback}参数
     * @param min {@code min}参数
     * @param max {@code max}参数
     * @return 处理结果
     */
    private int integer(Object value, int fallback, int min, int max) {
        if (value == null) {
            return fallback;
        }
        try {
            int result = value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
            if (result < min || result > max) {
                throw badRequest("整数参数超出范围");
            }
            return result;
        } catch (NumberFormatException exception) {
            throw badRequest("整数参数无效");
        }
    }

    /**
     * 处理safeSql消息并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @return 处理结果
     */
    private String safeSqlMessage(SQLException exception) {
        String value = exception.getMessage() == null ? "SQL 错误" : exception.getMessage();
        return value.length() <= 500 ? value : value.substring(0, 500);
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
     * 处理{@code forbidden}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException forbidden(String message) {
        return new ServiceException(message, HttpStatus.FORBIDDEN);
    }

    /**
     * 处理{@code notFound}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException notFound(String message) {
        return new ServiceException(message, HttpStatus.NOT_FOUND);
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
     * 处理{@code unavailable}并返回对应结果。
     *
     * @param tool 工具参数
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException unavailable(String tool, String message) {
        return new ServiceException("tool_unavailable: " + tool + " (" + message + ")", 503);
    }

    /**
     * 删除{@code Quietly}。
     *
     * @param path {@code path}参数
     */
    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Temporary files are cleaned by the normal generated-file maintenance job.
        }
    }
}
