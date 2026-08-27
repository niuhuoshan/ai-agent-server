package group.aitools.nhs.platform.automation.service;

import group.aitools.nhs.platform.automation.domain.AutomationFire;
import group.aitools.nhs.platform.automation.domain.AutomationTrigger;
import group.aitools.nhs.platform.automation.mapper.AutomationMapper;
import group.aitools.nhs.platform.automation.persistence.row.AutomationTaskTargetRow;
import group.aitools.nhs.platform.automation.web.AutomationFireView;
import group.aitools.nhs.platform.automation.web.AutomationTriggerView;
import group.aitools.nhs.platform.automation.web.CreateAutomationTriggerRequest;
import group.aitools.nhs.platform.automation.web.ManualAutomationFireRequest;
import group.aitools.nhs.platform.automation.web.UpdateAutomationTriggerRequest;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.execution.service.TaskRunApplicationService;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.identity.service.ServiceAccountPrincipalResolver;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 负责自动化相关的业务编排与领域规则处理。
 */
@Service
public class AutomationApplicationService {

    private static final int MAX_INPUT_BYTES = 128 * 1024;
    private static final int MAX_CONFIG_BYTES = 64 * 1024;
    private static final Set<String> TYPES = Set.of("manual", "cron", "webhook");
    private static final Set<String> STATUSES = Set.of("active", "paused", "error", "archived");
    private static final Set<String> MISFIRE_POLICIES = Set.of("skip", "fire_once", "catch_up");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final PlatformIdGenerator idGenerator;
    private final AutomationMapper mapper;
    private final ServiceAccountPrincipalResolver accountResolver;
    private final TaskRunApplicationService runService;
    private final CronScheduleCalculator cronCalculator;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code AutomationApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param idGenerator {@code idGenerator}参数
     * @param mapper {@code mapper}参数
     * @param accountResolver 账户Resolver参数
     * @param runService {@code runService}参数
     * @param cronCalculator {@code cronCalculator}参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public AutomationApplicationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        AutomationMapper mapper,
        ServiceAccountPrincipalResolver accountResolver,
        @Lazy TaskRunApplicationService runService,
        CronScheduleCalculator cronCalculator,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.idGenerator = idGenerator;
        this.mapper = mapper;
        this.accountResolver = accountResolver;
        this.runService = runService;
        this.cronCalculator = cronCalculator;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param requestedStatus 目标状态
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<AutomationTriggerView> list(String requestedStatus, int limit) {
        requireManage("view", null);
        String status = requestedStatus == null || requestedStatus.isBlank()
            ? null : enumValue(requestedStatus, STATUSES, "触发器状态");
        return mapper.selectTriggers(status, limit).stream().map(this::view).toList();
    }

    /**
     * 获取{@code get}。
     *
     * @param triggerId 资源标识
     * @return 处理结果
     */
    public AutomationTriggerView get(Long triggerId) {
        requireManage("view", triggerId);
        return view(requireTrigger(triggerId));
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AutomationTriggerView create(CreateAutomationTriggerRequest request) {
        CurrentPrincipal actor = requireManage("create", null);
        return createInternal(actor, request, false);
    }

    /**
 * 创建并保存For任务Operator。
 * Creates a cron trigger through a frozen task operator instead of HTTP IAM state. */
    @Transactional(rollbackFor = Exception.class)
    public AutomationTriggerView createForTaskOperator(
        CurrentPrincipal actor,
        CreateAutomationTriggerRequest request
    ) {
        return createInternal(
            Objects.requireNonNull(actor, "actor must not be null"), request, true
        );
    }

    /**
     * 创建并保存{@code Internal}。
     *
     * @param actor {@code actor}参数
     * @param request 请求参数
     * @param taskOperator 任务Operator参数
     * @return 处理结果
     */
    private AutomationTriggerView createInternal(
        CurrentPrincipal actor,
        CreateAutomationTriggerRequest request,
        boolean taskOperator
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String type = enumValue(request.triggerType(), TYPES, "触发器类型");
        if (taskOperator && !"cron".equals(type)) {
            throw badRequest("任务工具只能创建Cron触发器");
        }
        AutomationTaskTargetRow target = requireTarget(request.taskId(), request.taskVersionId());
        if (taskOperator) {
            runService.validateAs(actor, target.getTaskId(), target.getTaskVersionId());
        }
        CurrentPrincipal servicePrincipal = taskOperator
            ? accountResolver.requireOwnedForAutomation(actor, request.serviceAccountId())
            : accountResolver.requireActive(request.serviceAccountId());
        runService.validateAs(servicePrincipal, target.getTaskId(), target.getTaskVersionId());
        LocalDateTime now = utcNow();
        AutomationTrigger trigger = new AutomationTrigger();
        trigger.setId(idGenerator.nextId());
        trigger.setTriggerKey(key(request.triggerKey()));
        trigger.setName(text(request.name(), "触发器名称", 128));
        trigger.setTriggerType(type);
        applyConfiguration(trigger, target, request.serviceAccountId(), request.cronExpression(),
            request.timezone(), request.misfirePolicy(), request.maxCatchupCount(),
            request.maxAttempts(), request.inputTemplate(), request.config(), now);
        trigger.setStatus("active");
        trigger.setRevisionNo(1L);
        trigger.setCreateBy(actor.id());
        trigger.setCreateTime(now);
        if (mapper.insertTrigger(trigger) != 1) {
            AutomationTrigger existing = mapper.selectTriggerByKey(trigger.getTriggerKey());
            if (sameConfiguration(existing, trigger)) {
                return view(existing);
            }
            throw conflict("触发器标识已存在");
        }
        return view(trigger);
    }

    /**
 * 更新{@code RecurringStatusAs}。
 * Starts, pauses or archives the cron trigger attached to one formal task. */
    @Transactional(rollbackFor = Exception.class)
    public AutomationTriggerView changeRecurringStatusAs(
        CurrentPrincipal actor,
        Long taskId,
        String requestedStatus
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal requiredActor = Objects.requireNonNull(actor, "actor must not be null");
        String targetStatus = enumValue(
            requestedStatus, Set.of("active", "paused", "archived"), "周期任务状态"
        );
        AutomationTrigger trigger = requireLockedRecurringTrigger(taskId);
        runService.validateAs(requiredActor, trigger.getTaskId(), trigger.getTaskVersionId());
        if (targetStatus.equals(trigger.getStatus())) {
            return view(trigger);
        }
        if ("archived".equals(trigger.getStatus())) {
            throw conflict("已取消的周期任务不能重新启用");
        }
        LocalDateTime now = utcNow();
        if ("active".equals(targetStatus)) {
            CurrentPrincipal servicePrincipal = accountResolver.requireActive(
                trigger.getServiceAccountId()
            );
            runService.validateAs(
                servicePrincipal, trigger.getTaskId(), trigger.getTaskVersionId()
            );
            trigger.setNextRunAt(cronCalculator.next(
                trigger.getCronExpr(), cronCalculator.zone(trigger.getTimezone()), now
            ));
        } else {
            trigger.setNextRunAt(null);
        }
        trigger.setStatus(targetStatus);
        trigger.setUpdateBy(requiredActor.id());
        trigger.setUpdateTime(now);
        if (mapper.updateTrigger(trigger) != 1) {
            throw conflict("周期任务状态已被并发修改");
        }
        trigger.setRevisionNo(trigger.getRevisionNo() + 1);
        return view(trigger);
    }

    /**
 * 处理{@code manualRunRecurringAs}并返回对应结果。
 * Queues an immediate durable fire; paused/error schedules remain manually runnable. */
    @Transactional(rollbackFor = Exception.class)
    public AutomationFireView manualRunRecurringAs(
        CurrentPrincipal actor,
        Long taskId,
        String idempotencyKey
    ) {
        CurrentPrincipal requiredActor = Objects.requireNonNull(actor, "actor must not be null");
        AutomationTrigger trigger = requireLockedRecurringTrigger(taskId);
        runService.validateAs(requiredActor, trigger.getTaskId(), trigger.getTaskVersionId());
        if ("archived".equals(trigger.getStatus())) {
            throw conflict("已取消的周期任务不能手工运行");
        }
        accountResolver.requireOwnedForAutomation(requiredActor, trigger.getServiceAccountId());
        return accept(
            trigger, "manual", idempotencyKey, trigger.getInputTemplate(), null, false
        );
    }

    /**
     * 更新{@code update}。
     *
     * @param triggerId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AutomationTriggerView update(Long triggerId, UpdateAutomationTriggerRequest request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal actor = requireManage("manage", triggerId);
        AutomationTrigger existing = requireLockedTrigger(triggerId);
        if (!existing.getRevisionNo().equals(request.revisionNo())) {
            throw conflict("触发器配置已被并发修改");
        }
        if ("archived".equals(existing.getStatus())) {
            throw conflict("已归档触发器不能修改");
        }
        AutomationTaskTargetRow target = requireTarget(request.taskId(), request.taskVersionId());
        CurrentPrincipal servicePrincipal = accountResolver.requireActive(request.serviceAccountId());
        runService.validateAs(servicePrincipal, target.getTaskId(), target.getTaskVersionId());
        LocalDateTime now = utcNow();
        existing.setName(text(request.name(), "触发器名称", 128));
        applyConfiguration(existing, target, request.serviceAccountId(), request.cronExpression(),
            request.timezone(), request.misfirePolicy(), request.maxCatchupCount(),
            request.maxAttempts(), request.inputTemplate(), request.config(), now);
        existing.setStatus(enumValue(request.status(), STATUSES, "触发器状态"));
        if (!"active".equals(existing.getStatus())) {
            existing.setNextRunAt(null);
        } else if ("cron".equals(existing.getTriggerType())) {
            existing.setNextRunAt(cronCalculator.next(
                existing.getCronExpr(), cronCalculator.zone(existing.getTimezone()), now
            ));
        }
        existing.setUpdateBy(actor.id());
        existing.setUpdateTime(now);
        if (mapper.updateTrigger(existing) != 1) {
            throw conflict("触发器配置已被并发修改");
        }
        existing.setRevisionNo(existing.getRevisionNo() + 1);
        return view(existing);
    }

    /**
     * 处理{@code manualFire}并返回对应结果。
     *
     * @param triggerId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AutomationFireView manualFire(Long triggerId, ManualAutomationFireRequest request) {
        requireManage("operate", triggerId);
        AutomationTrigger trigger = requireLockedTrigger(triggerId);
        requireSource(trigger, "manual");
        return accept(trigger, "manual", request.idempotencyKey(), request.input(), null);
    }

    /**
     * 处理回调通知Fire并返回对应结果。
     *
     * @param trigger {@code trigger}参数
     * @param idempotencyKey {@code idempotencyKey}参数
     * @param input {@code input}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AutomationFireView webhookFire(
        AutomationTrigger trigger,
        String idempotencyKey,
        String input
    ) {
        AutomationTrigger locked = requireLockedTrigger(trigger.getId());
        if (!trigger.getRevisionNo().equals(locked.getRevisionNo())) {
            throw conflict("Webhook触发器配置已变化，请重试");
        }
        requireSource(locked, "webhook");
        return accept(locked, "webhook", idempotencyKey, input, null);
    }

    /**
     * 处理{@code cronFire}并返回对应结果。
     *
     * @param trigger {@code trigger}参数
     * @param scheduledAt {@code scheduledAt}参数
     * @return 处理结果
     */
    AutomationFireView cronFire(AutomationTrigger trigger, LocalDateTime scheduledAt) {
        requireSource(trigger, "cron");
        return accept(
            trigger, "cron", scheduledAt.atOffset(ZoneOffset.UTC).toInstant().toString(),
            trigger.getInputTemplate(), scheduledAt
        );
    }

    /**
     * 处理{@code accept}并返回对应结果。
     *
     * @param trigger {@code trigger}参数
     * @param source 数据源参数
     * @param idempotencyKey {@code idempotencyKey}参数
     * @param requestedInput {@code requestedInput}参数
     * @param scheduledAt {@code scheduledAt}参数
     * @return 处理结果
     */
    private AutomationFireView accept(
        AutomationTrigger trigger,
        String source,
        String idempotencyKey,
        String requestedInput,
        LocalDateTime scheduledAt
    ) {
        return accept(trigger, source, idempotencyKey, requestedInput, scheduledAt, true);
    }

    /**
     * 处理{@code accept}并返回对应结果。
     *
     * @param trigger {@code trigger}参数
     * @param source 数据源参数
     * @param idempotencyKey {@code idempotencyKey}参数
     * @param requestedInput {@code requestedInput}参数
     * @param scheduledAt {@code scheduledAt}参数
     * @param requireActive {@code requireActive}参数
     * @return 处理结果
     */
    private AutomationFireView accept(
        AutomationTrigger trigger,
        String source,
        String idempotencyKey,
        String requestedInput,
        LocalDateTime scheduledAt,
        boolean requireActive
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if ((requireActive && !"active".equals(trigger.getStatus()))
            || "archived".equals(trigger.getStatus())) {
            throw conflict("触发器当前未启用");
        }
        String normalizedKey = text(idempotencyKey, "触发幂等键", 128);
        String input = input(requestedInput, trigger.getInputTemplate());
        CurrentPrincipal servicePrincipal = accountResolver.requireActive(trigger.getServiceAccountId());
        AutomationTaskTargetRow target = requireTarget(trigger.getTaskId(), trigger.getTaskVersionId());
        if (!target.getTaskRevisionNo().equals(trigger.getTaskRevisionNo())) {
            throw conflict("自动化触发器绑定的任务版本号已发生变化");
        }
        runService.validateAs(servicePrincipal, trigger.getTaskId(), trigger.getTaskVersionId());

        String payloadJson = jsonMapper.writeValueAsString(Map.of("input", input));
        String payloadHash = ContentHashing.sha256(payloadJson);
        String fireKey = source + ":" + ContentHashing.sha256(normalizedKey);
        LocalDateTime now = utcNow();
        AutomationFire fire = new AutomationFire();
        fire.setId(idGenerator.nextId());
        fire.setTriggerId(trigger.getId());
        fire.setTriggerRevisionNo(trigger.getRevisionNo());
        fire.setServiceAccountId(trigger.getServiceAccountId());
        fire.setSourceType(source);
        fire.setFireKey(fireKey);
        fire.setPayloadHash(payloadHash);
        fire.setPayloadJson(payloadJson);
        fire.setScheduledAt(scheduledAt);
        fire.setStatus("queued");
        fire.setAttemptNo(0);
        fire.setAcceptedAt(now);
        if (mapper.insertFire(fire) != 1) {
            AutomationFire existing = mapper.selectFireByKey(trigger.getId(), fireKey);
            if (existing == null || !payloadHash.equals(existing.getPayloadHash())
                || !source.equals(existing.getSourceType())) {
                throw conflict("同一触发幂等键不能用于不同请求");
            }
            return fireView(existing, true);
        }
        Long jobId = idGenerator.nextId();
        if (mapper.insertFireJob(
            jobId, fire.getId(), "automation-fire:" + fire.getId(), payloadJson,
            trigger.getMaxAttempts(), now
        ) != 1 || mapper.bindFireJob(fire.getId(), jobId) != 1) {
            throw conflict("自动化触发作业入队冲突");
        }
        fire.setJobId(jobId);
        return fireView(fire, false);
    }

    /**
     * 处理apply配置相关逻辑。
     *
     * @param trigger {@code trigger}参数
     * @param target {@code target}参数
     * @param serviceAccountId 资源标识
     * @param cronExpression {@code cronExpression}参数
     * @param timezone {@code timezone}参数
     * @param misfirePolicy misfire策略参数
     * @param maxCatchupCount {@code maxCatchupCount}参数
     * @param maxAttempts {@code maxAttempts}参数
     * @param inputTemplate input模板参数
     * @param config {@code config}参数
     * @param now {@code now}参数
     */
    private void applyConfiguration(
        AutomationTrigger trigger,
        AutomationTaskTargetRow target,
        Long serviceAccountId,
        String cronExpression,
        String timezone,
        String misfirePolicy,
        Integer maxCatchupCount,
        Integer maxAttempts,
        String inputTemplate,
        Map<String, Object> config,
        LocalDateTime now
    ) {
        trigger.setTaskId(target.getTaskId());
        trigger.setTaskVersionId(target.getTaskVersionId());
        trigger.setTaskRevisionNo(target.getTaskRevisionNo());
        trigger.setServiceAccountId(serviceAccountId);
        trigger.setMisfirePolicy(misfirePolicy == null || misfirePolicy.isBlank()
            ? "fire_once" : enumValue(misfirePolicy, MISFIRE_POLICIES, "错过执行策略"));
        trigger.setMaxCatchupCount(maxCatchupCount == null ? 1 : maxCatchupCount);
        trigger.setMaxAttempts(maxAttempts == null ? 3 : maxAttempts);
        trigger.setInputTemplate(optionalInput(inputTemplate));
        trigger.setConfigJson(configJson(config));
        if ("cron".equals(trigger.getTriggerType())) {
            trigger.setCronExpr(cronCalculator.normalize(cronExpression));
            ZoneId zone = cronCalculator.zone(timezone);
            trigger.setTimezone(zone.getId());
            if (trigger.getInputTemplate() == null) {
                throw badRequest("Cron触发器必须配置固定输入");
            }
            trigger.setNextRunAt(cronCalculator.next(trigger.getCronExpr(), zone, now));
        } else {
            if (cronExpression != null && !cronExpression.isBlank()) {
                throw badRequest("非Cron触发器不能配置Cron表达式");
            }
            trigger.setCronExpr(null);
            trigger.setTimezone("UTC");
            trigger.setNextRunAt(null);
        }
    }

    /**
     * 校验{@code Target}，并在条件不满足时终止处理。
     *
     * @param taskId 资源标识
     * @param taskVersionId 资源标识
     * @return 处理结果
     */
    private AutomationTaskTargetRow requireTarget(Long taskId, Long taskVersionId) {
        AutomationTaskTargetRow target = mapper.selectTaskTarget(taskId);
        if (target == null) {
            throw new ServiceException("自动化任务不存在或版本配置不完整", HttpStatus.NOT_FOUND);
        }
        if (!target.getTaskVersionId().equals(taskVersionId)) {
            throw conflict("自动化只能绑定任务当前不可变版本");
        }
        return target;
    }

    /**
     * 校验数据源，并在条件不满足时终止处理。
     *
     * @param trigger {@code trigger}参数
     * @param source 数据源参数
     */
    private void requireSource(AutomationTrigger trigger, String source) {
        if (!source.equals(trigger.getTriggerType())) {
            throw badRequest("触发入口与触发器类型不匹配");
        }
    }

    /**
     * 校验{@code Trigger}，并在条件不满足时终止处理。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    private AutomationTrigger requireTrigger(Long id) {
        AutomationTrigger trigger = mapper.selectTrigger(id);
        if (trigger == null) {
            throw new ServiceException("自动化触发器不存在", HttpStatus.NOT_FOUND);
        }
        return trigger;
    }

    /**
     * 校验{@code LockedTrigger}，并在条件不满足时终止处理。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    private AutomationTrigger requireLockedTrigger(Long id) {
        AutomationTrigger trigger = mapper.lockTrigger(id);
        if (trigger == null) {
            throw new ServiceException("自动化触发器不存在", HttpStatus.NOT_FOUND);
        }
        return trigger;
    }

    /**
     * 校验{@code LockedRecurringTrigger}，并在条件不满足时终止处理。
     *
     * @param taskId 资源标识
     * @return 处理结果
     */
    private AutomationTrigger requireLockedRecurringTrigger(Long taskId) {
        if (taskId == null || taskId <= 0) {
            throw badRequest("周期任务ID无效");
        }
        AutomationTrigger trigger = mapper.lockRecurringTriggerByTaskId(taskId);
        if (trigger == null) {
            throw new ServiceException("周期任务不存在", HttpStatus.NOT_FOUND);
        }
        return trigger;
    }

    /**
     * 处理same配置并返回对应结果。
     *
     * @param left {@code left}参数
     * @param right {@code right}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean sameConfiguration(AutomationTrigger left, AutomationTrigger right) {
        return left != null
            && Objects.equals(left.getName(), right.getName())
            && Objects.equals(left.getTriggerType(), right.getTriggerType())
            && Objects.equals(left.getTaskId(), right.getTaskId())
            && Objects.equals(left.getTaskVersionId(), right.getTaskVersionId())
            && Objects.equals(left.getTaskRevisionNo(), right.getTaskRevisionNo())
            && Objects.equals(left.getServiceAccountId(), right.getServiceAccountId())
            && Objects.equals(left.getCronExpr(), right.getCronExpr())
            && Objects.equals(left.getTimezone(), right.getTimezone())
            && Objects.equals(left.getMisfirePolicy(), right.getMisfirePolicy())
            && Objects.equals(left.getMaxCatchupCount(), right.getMaxCatchupCount())
            && Objects.equals(left.getMaxAttempts(), right.getMaxAttempts())
            && Objects.equals(left.getInputTemplate(), right.getInputTemplate())
            && Objects.equals(left.getConfigJson(), right.getConfigJson())
            && "active".equals(left.getStatus());
    }

    /**
     * 校验{@code Manage}，并在条件不满足时终止处理。
     *
     * @param action {@code action}参数
     * @param resourceId 资源标识
     * @return 处理结果
     */
    private CurrentPrincipal requireManage(String action, Long resourceId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "iam", resourceId, null, action, ResourceState.ACTIVE, true, Set.of(), null
        ));
        return principal;
    }

    /**
     * 处理{@code view}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private AutomationTriggerView view(AutomationTrigger value) {
        return new AutomationTriggerView(
            value.getId(), value.getTriggerKey(), value.getName(), value.getTriggerType(),
            value.getTaskId(), value.getTaskVersionId(), value.getTaskRevisionNo(),
            value.getServiceAccountId(), value.getCronExpr(), value.getTimezone(),
            value.getStatus(), value.getMisfirePolicy(), value.getMaxCatchupCount(),
            value.getMaxAttempts(), value.getInputTemplate(), value.getLastRunAt(),
            value.getNextRunAt(), value.getRevisionNo(), map(value.getConfigJson()),
            value.getCreateTime()
        );
    }

    /**
     * 处理{@code fireView}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param replayed {@code replayed}参数
     * @return 处理结果
     */
    AutomationFireView fireView(AutomationFire value, boolean replayed) {
        return new AutomationFireView(
            value.getId(), value.getTriggerId(), value.getSourceType(), value.getStatus(),
            value.getJobId(), value.getRunId(), value.getAttemptNo(), value.getLastError(),
            value.getScheduledAt(), value.getAcceptedAt(), value.getDispatchedAt(), replayed
        );
    }

    /**
     * 处理{@code input}并返回对应结果。
     *
     * @param requested {@code requested}参数
     * @param fallback {@code fallback}参数
     * @return 处理结果
     */
    private String input(String requested, String fallback) {
        String value = requested == null || requested.isBlank() ? fallback : requested;
        if (value == null || value.isBlank()) {
            throw badRequest("触发输入不能为空");
        }
        String normalized = value.strip();
        if (normalized.indexOf('\0') >= 0
            || normalized.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            throw badRequest("触发输入包含非法字符或超过128KB");
        }
        return normalized;
    }

    /**
     * 处理{@code optionalInput}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String optionalInput(String value) {
        return value == null || value.isBlank() ? null : input(value, null);
    }

    /**
     * 处理{@code configJson}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @return 处理结果
     */
    private String configJson(Map<String, Object> raw) {
        Map<String, Object> canonical = canonicalMap(raw == null ? Map.of() : raw, 0);
        String json = jsonMapper.writeValueAsString(canonical);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
            throw badRequest("触发器配置超过64KB");
        }
        return json;
    }

    /**
     * 判断{@code onicalMap}是否满足要求。
     *
     * @param raw {@code raw}参数
     * @param depth {@code depth}参数
     * @return 处理结果
     */
    private Map<String, Object> canonicalMap(Map<String, Object> raw, int depth) {
        if (depth > 12) {
            throw badRequest("触发器配置嵌套过深");
        }
        TreeMap<String, Object> sorted = new TreeMap<>();
        raw.forEach((key, value) -> {
            if (key == null || key.isBlank() || key.length() > 128 || sensitive(key)) {
                throw badRequest("触发器配置包含敏感或无效字段");
            }
            sorted.put(key, canonicalValue(value, depth + 1));
        });
        return new LinkedHashMap<>(sorted);
    }

    /**
     * 判断{@code onicalValue}是否满足要求。
     *
     * @param value {@code value}参数
     * @param depth {@code depth}参数
     * @return 处理结果
     */
    private Object canonicalValue(Object value, int depth) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (depth > 12) {
            throw badRequest("触发器配置嵌套过深");
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String text)) {
                    throw badRequest("触发器配置字段必须为文本");
                }
                converted.put(text, item);
            });
            return canonicalMap(converted, depth);
        }
        if (value instanceof List<?> list) {
            if (list.size() > 1000) {
                throw badRequest("触发器配置数组过大");
            }
            return list.stream().map(item -> canonicalValue(item, depth + 1)).toList();
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        throw badRequest("触发器配置包含不支持的值");
    }

    /**
     * 处理{@code sensitive}并返回对应结果。
     *
     * @param key {@code key}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean sensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return Set.of("secret", "password", "apikey", "authorization", "credential",
            "privatekey", "token", "accesstoken", "refreshtoken").stream()
            .anyMatch(normalized::contains);
    }

    /**
     * 将输入数据转换为{@code map}。
     *
     * @param json {@code json}参数
     * @return 处理结果
     */
    private Map<String, Object> map(String json) {
        return json == null || json.isBlank() ? Map.of() : jsonMapper.readValue(json, MAP_TYPE);
    }

    /**
     * 处理{@code key}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String key(String value) {
        String normalized = text(value, "触发器标识", 128);
        if (!normalized.matches("[A-Za-z0-9._:-]+")) {
            throw badRequest("触发器标识无效");
        }
        return normalized;
    }

    /**
     * 处理{@code enumValue}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param allowed {@code allowed}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String enumValue(String value, Set<String> allowed, String label) {
        String normalized = text(value, label, 32).toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw badRequest(label + "无效");
        }
        return normalized;
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    private String text(String value, String label, int limit) {
        if (value == null || value.isBlank()) {
            throw badRequest(label + "不能为空");
        }
        String normalized = value.strip();
        if (normalized.length() > limit || normalized.indexOf('\0') >= 0) {
            throw badRequest(label + "过长或包含非法字符");
        }
        return normalized;
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
     * 处理{@code conflict}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }
}
