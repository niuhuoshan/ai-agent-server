package group.aitools.nhs.platform.operations.service;

import group.aitools.nhs.platform.audit.mapper.AgentAuditEventMapper;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.operations.domain.LogMaintenanceRun;
import group.aitools.nhs.platform.operations.domain.LogRetentionPolicy;
import group.aitools.nhs.platform.operations.mapper.LogMaintenanceMapper;
import group.aitools.nhs.platform.operations.web.LogCleanupPreviewView;
import group.aitools.nhs.platform.operations.web.LogCleanupRequest;
import group.aitools.nhs.platform.operations.web.LogCleanupResultView;
import group.aitools.nhs.platform.operations.web.LogCleanupTableResultView;
import group.aitools.nhs.platform.operations.web.LogMaintenanceRunView;
import group.aitools.nhs.platform.operations.web.LogPartitionStatusView;
import group.aitools.nhs.platform.operations.web.LogPartitionView;
import group.aitools.nhs.platform.operations.web.LogRetentionConfigView;
import group.aitools.nhs.platform.operations.web.LogTableStorageView;
import group.aitools.nhs.platform.operations.web.UpdateLogRetentionConfigRequest;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 负责{@code LogMaintenance}相关的业务编排与领域规则处理。
 * Administrator-only PostgreSQL log retention, preview and cleanup workflow. */
@Service
public class LogMaintenanceApplicationService {

    public static final int MIN_RETENTION_DAYS = 1;
    public static final int MAX_RETENTION_DAYS = 3_650;
    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(10);
    private static final TypeReference<Map<String, Object>> SUMMARY_TYPE = new TypeReference<>() {
    };

    private final CurrentPrincipalProvider principalProvider;
    private final LogMaintenanceMapper mapper;
    private final LogStorageMaintenanceRepository storageRepository;
    private final PlatformIdGenerator idGenerator;
    private final AgentAuditEventMapper auditMapper;
    private final JsonMapper jsonMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 创建 {@code LogMaintenanceApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param mapper {@code mapper}参数
     * @param storageRepository 存储Repository参数
     * @param idGenerator {@code idGenerator}参数
     * @param auditMapper 审计Mapper参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public LogMaintenanceApplicationService(
        CurrentPrincipalProvider principalProvider,
        LogMaintenanceMapper mapper,
        LogStorageMaintenanceRepository storageRepository,
        PlatformIdGenerator idGenerator,
        AgentAuditEventMapper auditMapper,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.mapper = mapper;
        this.storageRepository = storageRepository;
        this.idGenerator = idGenerator;
        this.auditMapper = auditMapper;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 处理配置并返回对应结果。
     *
     * @return 处理结果
     */
    public LogRetentionConfigView configuration() {
        requireAdministrator();
        return configView(requirePolicy());
    }

    /**
     * 更新配置。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public LogRetentionConfigView updateConfiguration(UpdateLogRetentionConfigRequest request) {
        CurrentPrincipal principal = requireAdministrator();
        if (request == null || request.retentionDays() < MIN_RETENTION_DAYS
            || request.retentionDays() > MAX_RETENTION_DAYS) {
            throw badRequest("日志保留天数必须在 1 到 3650 之间");
        }
        LogRetentionPolicy current = requirePolicy();
        int expectedRevision = request.expectedRevision() == null
            ? current.getRevisionNo() : request.expectedRevision();
        String reason = request.changeReason() == null || request.changeReason().isBlank()
            ? "Nhs 日志管理兼容接口更新" : bounded(request.changeReason().strip(), 500);
        if (reason.length() < 2) {
            throw badRequest("变更原因至少需要 2 个字符");
        }
        LocalDateTime now = LocalDateTime.now();
        if (mapper.updatePolicy(
            request.retentionDays(), expectedRevision, principal.id(), now, reason
        ) != 1) {
            throw new ServiceException("日志保留策略已被其他管理员更新，请刷新后重试", HttpStatus.CONFLICT);
        }
        LogRetentionPolicy updated = requirePolicy();
        auditMapper.insertEvent(
            idGenerator.nextId(), "user", principal.id(), "update", "log_retention_policy", 1L,
            null, "success", "platform_admin",
            "retentionDays=" + updated.getRetentionDays() + ", revision=" + updated.getRevisionNo()
                + ", reason=" + bounded(reason, 300), now
        );
        return configView(updated);
    }

    /**
     * 处理{@code partitions}并返回对应结果。
     *
     * @return 处理结果
     */
    public LogPartitionStatusView partitions() {
        requireAdministrator();
        LogRetentionPolicy policy = requirePolicy();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(policy.getRetentionDays());
        return partitionStatus(policy, inspect(cutoff));
    }

    /**
     * 处理{@code previewCleanup}并返回对应结果。
     *
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public LogCleanupPreviewView previewCleanup() {
        CurrentPrincipal principal = requireAdministrator();
        LogRetentionPolicy policy = requirePolicy();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusDays(policy.getRetentionDays());
        LogStorageMaintenanceRepository.StorageSnapshot snapshot = inspect(cutoff);
        String token = token();
        LocalDateTime expiresAt = now.plus(CONFIRMATION_TTL);
        LogMaintenanceRun run = new LogMaintenanceRun();
        run.setId(idGenerator.nextId());
        run.setTriggerType("manual");
        run.setStatus("previewed");
        run.setRetentionDays(policy.getRetentionDays());
        run.setPolicyRevision(policy.getRevisionNo());
        run.setCutoffAt(cutoff);
        run.setConfirmationTokenHash(hash(token));
        run.setConfirmationExpiresAt(expiresAt);
        run.setRequestedBy(principal.id());
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        mapper.insertRun(run);
        long expiredRows = snapshot.tables().stream().mapToLong(
            LogStorageMaintenanceRepository.TableStorageFact::expiredRows
        ).sum();
        long removable = snapshot.tables().stream().flatMap(value -> value.partitions().stream())
            .filter(LogStorageMaintenanceRepository.PartitionStorageFact::removableCandidate).count();
        auditMapper.insertEvent(
            idGenerator.nextId(), "user", principal.id(), "preview_cleanup", "log_maintenance", run.getId(),
            null, "success", "platform_admin",
            "retentionDays=" + policy.getRetentionDays() + ", expiredRows=" + expiredRows
                + ", removablePartitions=" + removable, now
        );
        List<String> warnings = List.of(
            "确认令牌 10 分钟后失效，且只能使用一次",
            "普通表和跨界分区每次每表最多删除 50000 行，剩余数据由后续运行继续清理",
            "完全过期的月分区会被物理删除且不可恢复"
        );
        return new LogCleanupPreviewView(
            String.valueOf(run.getId()), token, expiresAt, policy.getRetentionDays(), policy.getRevisionNo(),
            cutoff, expiredRows, removable, LogStorageMaintenanceRepository.MAX_ROWS_PER_TABLE,
            snapshot.tables().stream().anyMatch(value -> value.expiredRows()
                > LogStorageMaintenanceRepository.MAX_ROWS_PER_TABLE),
            tableViews(snapshot), warnings
        );
    }

    /**
     * 处理{@code cleanup}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    public LogCleanupResultView cleanup(LogCleanupRequest request) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CurrentPrincipal principal = requireAdministrator();
        if (request == null || !request.confirm() || request.confirmationToken() == null
            || request.confirmationToken().isBlank()) {
            throw badRequest("手动清理需要预览令牌和明确确认");
        }
        String rawToken = request.confirmationToken().strip();
        if (rawToken.length() > 128) {
            throw badRequest("清理确认令牌无效");
        }
        LogMaintenanceRun run = mapper.selectRunByTokenHash(hash(rawToken));
        if (run == null || !Objects.equals(run.getRequestedBy(), principal.id())) {
            throw new ServiceException("清理确认令牌不存在或不属于当前管理员", HttpStatus.NOT_FOUND);
        }
        if (!"previewed".equals(run.getStatus())) {
            throw new ServiceException("清理确认令牌已经使用或失效", HttpStatus.CONFLICT);
        }
        LocalDateTime now = LocalDateTime.now();
        if (run.getConfirmationExpiresAt() == null || !run.getConfirmationExpiresAt().isAfter(now)) {
            mapper.expirePreview(run.getId(), now);
            throw new ServiceException("清理确认令牌已过期，请重新预览", 410);
        }
        LogRetentionPolicy policy = requirePolicy();
        if (!Objects.equals(run.getPolicyRevision(), policy.getRevisionNo())
            || !Objects.equals(run.getRetentionDays(), policy.getRetentionDays())) {
            mapper.expirePreview(run.getId(), now);
            throw new ServiceException("日志保留策略已变化，请重新预览后确认", HttpStatus.CONFLICT);
        }
        try {
            if (mapper.claimManualRun(run.getId(), run.getPolicyRevision(), now) != 1) {
                throw new ServiceException("清理任务已被其他请求处理，请刷新运行记录", HttpStatus.CONFLICT);
            }
        } catch (DuplicateKeyException exception) {
            throw new ServiceException("已有日志维护任务正在执行，请稍后重试", HttpStatus.CONFLICT);
        }
        return executeClaimed(run, principal);
    }

    /**
     * 处理{@code recentRuns}并返回对应结果。
     *
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<LogMaintenanceRunView> recentRuns(int limit) {
        requireAdministrator();
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        return mapper.selectRecentRuns(boundedLimit).stream().map(this::runView).toList();
    }

    /**
 * 处理{@code scheduledMaintenance}并返回对应结果。
 * Scheduler entry point. It never relies on a browser principal. */
    public LogCleanupResultView scheduledMaintenance() {
        LogRetentionPolicy policy = requirePolicy();
        LocalDateTime now = LocalDateTime.now();
        LogMaintenanceRun run = scheduledRun(policy, now, "running");
        try {
            mapper.insertRun(run);
        } catch (DuplicateKeyException exception) {
            LogMaintenanceRun skipped = scheduledRun(policy, now, "skipped");
            skipped.setFinishedAt(now);
            skipped.setErrorCode("MAINTENANCE_BUSY");
            skipped.setErrorMessage("已有日志维护任务正在执行");
            mapper.insertRun(skipped);
            return skippedResult(skipped);
        }
        return executeClaimed(run, null);
    }

    /**
     * 执行{@code Claimed}相关的处理流程。
     *
     * @param run {@code run}参数
     * @param principal 当前操作主体
     * @return 处理结果
     */
    private LogCleanupResultView executeClaimed(LogMaintenanceRun run, CurrentPrincipal principal) {
        LocalDateTime started = run.getStartedAt() == null ? LocalDateTime.now() : run.getStartedAt();
        try {
            LogStorageMaintenanceRepository.MaintenanceOutcome outcome = storageRepository.maintain(run.getCutoffAt());
            String status = outcome.remainingExpiredRows() ? "partial" : "succeeded";
            LocalDateTime finished = LocalDateTime.now();
            String summary = summaryJson(outcome);
            mapper.finishRun(run.getId(), status, summary, null, null, finished);
            try {
                auditSuccess(run, principal, outcome, finished);
            } catch (RuntimeException auditFailure) {
                mapper.markCompletedRunAuditFailure(
                    run.getId(), "AUDIT_WRITE_FAILED", "日志已维护，但结果审计写入失败", LocalDateTime.now()
                );
                throw new ServiceException("日志已完成维护，但审计写入失败，请检查运行记录", HttpStatus.ERROR);
            }
            return resultView(run, status, outcome, started, finished);
        } catch (ServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LocalDateTime finished = LocalDateTime.now();
            mapper.finishRun(
                run.getId(), "failed", null, "LOG_MAINTENANCE_FAILED",
                "PostgreSQL 日志维护失败；未提交的清理操作已回滚", finished
            );
            auditFailure(run, principal, finished);
            throw new ServiceException(
                "PostgreSQL 日志维护失败，可能存在锁等待或分区边界冲突，请查看运行记录", 503
            );
        }
    }

    /**
     * 处理{@code scheduledRun}并返回对应结果。
     *
     * @param policy 策略参数
     * @param now {@code now}参数
     * @param status 目标状态
     * @return 处理结果
     */
    private LogMaintenanceRun scheduledRun(LogRetentionPolicy policy, LocalDateTime now, String status) {
        LogMaintenanceRun run = new LogMaintenanceRun();
        run.setId(idGenerator.nextId());
        run.setTriggerType("scheduled");
        run.setStatus(status);
        run.setRetentionDays(policy.getRetentionDays());
        run.setPolicyRevision(policy.getRevisionNo());
        run.setCutoffAt(now.minusDays(policy.getRetentionDays()));
        run.setStartedAt("running".equals(status) ? now : null);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        return run;
    }

    /**
     * 处理skipped结果并返回对应结果。
     *
     * @param run {@code run}参数
     * @return 处理结果
     */
    private LogCleanupResultView skippedResult(LogMaintenanceRun run) {
        return new LogCleanupResultView(
            String.valueOf(run.getId()), "skipped", "scheduled", run.getRetentionDays(), run.getCutoffAt(),
            List.of(), List.of(), 0, 0, false, null, run.getFinishedAt(),
            "已有日志维护任务正在执行，本次定时运行已跳过", List.of()
        );
    }

    /**
     * 处理审计Success相关逻辑。
     *
     * @param run {@code run}参数
     * @param principal 当前操作主体
     * @param outcome {@code outcome}参数
     * @param now {@code now}参数
     */
    private void auditSuccess(
        LogMaintenanceRun run,
        CurrentPrincipal principal,
        LogStorageMaintenanceRepository.MaintenanceOutcome outcome,
        LocalDateTime now
    ) {
        auditMapper.insertEvent(
            idGenerator.nextId(), principal == null ? "system" : "user", principal == null ? null : principal.id(),
            "cleanup", "log_maintenance", run.getId(), null, "success",
            principal == null ? "scheduled_maintenance" : "platform_admin",
            "trigger=" + run.getTriggerType() + ", deletedRows=" + outcome.deletedRows()
                + ", droppedRows=" + outcome.droppedRows() + ", droppedPartitions="
                + outcome.droppedPartitions().size() + ", remaining=" + outcome.remainingExpiredRows(), now
        );
    }

    /**
     * 处理审计Failure相关逻辑。
     *
     * @param run {@code run}参数
     * @param principal 当前操作主体
     * @param now {@code now}参数
     */
    private void auditFailure(LogMaintenanceRun run, CurrentPrincipal principal, LocalDateTime now) {
        try {
            auditMapper.insertEvent(
                idGenerator.nextId(), principal == null ? "system" : "user", principal == null ? null : principal.id(),
                "cleanup", "log_maintenance", run.getId(), null, "failure",
                "log_maintenance_failed", "trigger=" + run.getTriggerType(), now
            );
        } catch (RuntimeException ignored) {
            // The persistent maintenance run remains the failure source when audit storage is unavailable.
        }
    }

    /**
     * 处理{@code summaryJson}并返回对应结果。
     *
     * @param outcome {@code outcome}参数
     * @return 处理结果
     */
    private String summaryJson(LogStorageMaintenanceRepository.MaintenanceOutcome outcome) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("createdPartitions", outcome.createdPartitions());
        summary.put("droppedPartitions", outcome.droppedPartitions());
        summary.put("droppedRows", outcome.droppedRows());
        summary.put("deletedRows", outcome.deletedRows());
        summary.put("remainingExpiredRows", outcome.remainingExpiredRows());
        summary.put("tables", outcome.tables());
        try {
            return jsonMapper.writeValueAsString(summary);
        } catch (JacksonException exception) {
            throw new IllegalStateException("日志维护结果序列化失败", exception);
        }
    }

    /**
     * 处理结果View并返回对应结果。
     *
     * @param run {@code run}参数
     * @param status 目标状态
     * @param outcome {@code outcome}参数
     * @param started {@code started}参数
     * @param finished {@code finished}参数
     * @return 处理结果
     */
    private LogCleanupResultView resultView(
        LogMaintenanceRun run,
        String status,
        LogStorageMaintenanceRepository.MaintenanceOutcome outcome,
        LocalDateTime started,
        LocalDateTime finished
    ) {
        List<LogCleanupTableResultView> tables = outcome.tables().stream().map(value ->
            new LogCleanupTableResultView(
                value.tableName(), value.droppedPartitions(), value.droppedRows(), value.deletedRows(),
                value.remainingExpiredRows()
            )
        ).toList();
        String message = outcome.remainingExpiredRows()
            ? "已完成本轮安全上限内的日志清理，仍有过期数据等待后续运行"
            : "日志分区维护与过期数据清理已完成";
        return new LogCleanupResultView(
            String.valueOf(run.getId()), status, run.getTriggerType(), run.getRetentionDays(), run.getCutoffAt(),
            outcome.createdPartitions(), outcome.droppedPartitions(), outcome.droppedRows(), outcome.deletedRows(),
            outcome.remainingExpiredRows(), started, finished, message, tables
        );
    }

    /**
     * 处理{@code partitionStatus}并返回对应结果。
     *
     * @param policy 策略参数
     * @param snapshot 快照参数
     * @return 处理结果
     */
    private LogPartitionStatusView partitionStatus(
        LogRetentionPolicy policy,
        LogStorageMaintenanceRepository.StorageSnapshot snapshot
    ) {
        return new LogPartitionStatusView(
            "PostgreSQL", snapshot.checkedAt(), policy.getRetentionDays(), snapshot.cutoffAt(),
            LogStorageMaintenanceRepository.FUTURE_MONTHS,
            LogStorageMaintenanceRepository.BATCH_SIZE,
            LogStorageMaintenanceRepository.MAX_ROWS_PER_TABLE,
            tableViews(snapshot)
        );
    }

    /**
     * 处理{@code tableViews}并返回对应结果。
     *
     * @param snapshot 快照参数
     * @return 符合条件的数据集合
     */
    private List<LogTableStorageView> tableViews(LogStorageMaintenanceRepository.StorageSnapshot snapshot) {
        return snapshot.tables().stream().map(table -> new LogTableStorageView(
            table.tableName(), table.displayName(), table.partitioned() ? "partitioned" : "regular",
            table.partitionKey(), table.estimatedRows(), table.sizeBytes(), table.oldestAt(), table.newestAt(),
            table.expiredRows(), table.partitions().stream().map(partition -> new LogPartitionView(
                partition.partitionName(), partition.boundExpression(), partition.defaultPartition(),
                partition.estimatedRows(), partition.sizeBytes(), partition.oldestAt(), partition.newestAt(),
                partition.expiredRows(), partition.removableCandidate()
            )).toList()
        )).toList();
    }

    /**
     * 执行{@code View}相关的处理流程。
     *
     * @param run {@code run}参数
     * @return 处理结果
     */
    private LogMaintenanceRunView runView(LogMaintenanceRun run) {
        return new LogMaintenanceRunView(
            String.valueOf(run.getId()), run.getTriggerType(), run.getStatus(), run.getRetentionDays(),
            run.getPolicyRevision(), run.getCutoffAt(), run.getRequestedBy() == null
                ? null : String.valueOf(run.getRequestedBy()), run.getConfirmationExpiresAt(), run.getStartedAt(),
            run.getFinishedAt(), parseSummary(run.getSummaryJson()), run.getErrorCode(), run.getErrorMessage(),
            run.getCreatedAt()
        );
    }

    /**
     * 处理{@code parseSummary}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> parseSummary(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return jsonMapper.readValue(value, SUMMARY_TYPE);
        } catch (JacksonException exception) {
            return Map.of("unavailable", true);
        }
    }

    /**
     * 处理{@code configView}并返回对应结果。
     *
     * @param policy 策略参数
     * @return 处理结果
     */
    private LogRetentionConfigView configView(LogRetentionPolicy policy) {
        return new LogRetentionConfigView(
            policy.getRetentionDays(), MIN_RETENTION_DAYS, MAX_RETENTION_DAYS, policy.getRevisionNo(),
            policy.getUpdatedBy() == null ? null : String.valueOf(policy.getUpdatedBy()), policy.getUpdatedAt(),
            policy.getChangeReason(), "每天 02:00（Asia/Shanghai）"
        );
    }

    /**
     * 处理{@code inspect}并返回对应结果。
     *
     * @param cutoff {@code cutoff}参数
     * @return 处理结果
     */
    private LogStorageMaintenanceRepository.StorageSnapshot inspect(LocalDateTime cutoff) {
        try {
            return storageRepository.inspect(cutoff);
        } catch (DataAccessException | IllegalStateException exception) {
            throw new ServiceException("PostgreSQL 日志存储状态读取失败，请检查迁移和数据库权限", 503);
        }
    }

    /**
     * 校验策略，并在条件不满足时终止处理。
     *
     * @return 处理结果
     */
    private LogRetentionPolicy requirePolicy() {
        LogRetentionPolicy policy = mapper.selectPolicy();
        if (policy == null) {
            throw new ServiceException("日志保留策略尚未初始化，请检查 Flyway 迁移", 503);
        }
        return policy;
    }

    /**
     * 校验{@code Administrator}，并在条件不满足时终止处理。
     *
     * @return 处理结果
     */
    private CurrentPrincipal requireAdministrator() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (principal == null || !principal.isHuman() || !principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
            throw new ServiceException("仅平台管理员可以管理系统日志", HttpStatus.FORBIDDEN);
        }
        return principal;
    }

    /**
     * 将输入数据转换为{@code ken}。
     *
     * @return 处理结果
     */
    private String token() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 判断{@code h}是否满足要求。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    /**
     * 处理{@code bounded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maximum {@code maximum}参数
     * @return 处理结果
     */
    private String bounded(String value, int maximum) {
        String normalized = value == null ? "" : value.replace('\0', ' ').replace('\r', ' ').replace('\n', ' ').strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
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
}
