package group.aitools.nhs.platform.sandbox.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.sandbox.mapper.SandboxRunnerMapper;
import group.aitools.nhs.platform.sandbox.persistence.row.SandboxJobRow;
import group.aitools.nhs.platform.sandbox.persistence.row.SandboxRunnerRow;
import group.aitools.nhs.platform.sandbox.service.SandboxRequestAuthenticator.RunnerAuthentication;
import group.aitools.nhs.platform.sandbox.web.CompleteSandboxJobRequest;
import group.aitools.nhs.platform.sandbox.web.AppendSandboxJobOutputRequest;
import group.aitools.nhs.platform.sandbox.web.RegisterSandboxRunnerRequest;
import group.aitools.nhs.platform.sandbox.web.SandboxJobClaimView;
import group.aitools.nhs.platform.sandbox.web.SandboxRunnerHeartbeatRequest;
import group.aitools.nhs.platform.sandbox.web.SandboxRunnerRegistrationView;
import group.aitools.nhs.platform.skill.service.SkillSandboxBundleService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.io.OutputStream;

/**
 * 负责沙箱Runner相关的业务编排与领域规则处理。
 */
@Service
public class SandboxRunnerApplicationService {

    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9._-]{2,63}");
    private static final Pattern CAPABILITY = Pattern.compile("[a-z][a-z0-9._-]{1,63}");
    private static final Pattern JOB_TOKEN = Pattern.compile(
        "asj_[A-Za-z0-9_-]{12}\\.[A-Za-z0-9_-]{43}"
    );
    private static final Pattern FAILURE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    private static final Pattern SECRET = Pattern.compile(
        "(?:asr|asj|agk)_[A-Za-z0-9_-]{12}\\.[A-Za-z0-9_-]{20,}"
    );
    private static final Pattern BEARER_SECRET = Pattern.compile(
        "(?i)(authorization\\s*[:=]\\s*bearer\\s+)[A-Za-z0-9._~+/=-]{8,}"
    );
    private static final Pattern PROVIDER_SECRET = Pattern.compile(
        "(?i)\\b(sk-[A-Za-z0-9_-]{16,})\\b"
    );
    private static final Pattern NAMED_SECRET = Pattern.compile(
        "(?i)((?:api[_-]?key|password|secret|token)\\s*[:=]\\s*)[^\\s,;]{8,}"
    );
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final SandboxRunnerMapper mapper;
    private final PlatformIdGenerator idGenerator;
    private final SandboxSecretGenerator secretGenerator;
    private final JsonMapper jsonMapper;
    private final int heartbeatTtlSeconds;
    private final int leaseSeconds;
    private final ApplicationEventPublisher eventPublisher;
    private SkillSandboxBundleService skillBundleService;

    /**
     * 创建 {@code SandboxRunnerApplicationService} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param idGenerator {@code idGenerator}参数
     * @param secretGenerator {@code secretGenerator}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param heartbeatTtlSeconds {@code heartbeatTtlSeconds}参数
     * @param leaseSeconds {@code leaseSeconds}参数
     * @param eventPublisher 事件Publisher参数
     */
    @Autowired
    public SandboxRunnerApplicationService(
        SandboxRunnerMapper mapper,
        PlatformIdGenerator idGenerator,
        SandboxSecretGenerator secretGenerator,
        JsonMapper jsonMapper,
        @Value("${agent.platform.sandbox.heartbeat-ttl-seconds:45}") int heartbeatTtlSeconds,
        @Value("${agent.platform.sandbox.lease-seconds:90}") int leaseSeconds,
        ApplicationEventPublisher eventPublisher
    ) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.secretGenerator = secretGenerator;
        this.jsonMapper = jsonMapper;
        this.heartbeatTtlSeconds = Math.max(15, Math.min(heartbeatTtlSeconds, 300));
        this.leaseSeconds = Math.max(30, Math.min(leaseSeconds, 300));
        this.eventPublisher = eventPublisher;
    }

    /**
     * 设置技能沙箱BundleService。
     *
     * @param skillBundleService 技能BundleService参数
     */
    @Autowired(required = false)
    public void setSkillSandboxBundleService(SkillSandboxBundleService skillBundleService) {
        this.skillBundleService = skillBundleService;
    }

    /**
     * 创建 {@code SandboxRunnerApplicationService} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param idGenerator {@code idGenerator}参数
     * @param secretGenerator {@code secretGenerator}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param heartbeatTtlSeconds {@code heartbeatTtlSeconds}参数
     * @param leaseSeconds {@code leaseSeconds}参数
     */
    public SandboxRunnerApplicationService(
        SandboxRunnerMapper mapper,
        PlatformIdGenerator idGenerator,
        SandboxSecretGenerator secretGenerator,
        JsonMapper jsonMapper,
        int heartbeatTtlSeconds,
        int leaseSeconds
    ) {
        this(
            mapper, idGenerator, secretGenerator, jsonMapper, heartbeatTtlSeconds,
            leaseSeconds, event -> { }
        );
    }

    /**
     * 创建并保存{@code register}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public SandboxRunnerRegistrationView register(RegisterSandboxRunnerRequest request) {
        String runnerKey = required(request.runnerKey(), 64, "Runner键");
        if (!KEY.matcher(runnerKey).matches()) {
            throw badRequest("Runner键无效");
        }
        String name = required(request.name(), 128, "Runner名称");
        List<String> capabilities = capabilities(request.capabilities());
        int maxConcurrency = valueOrDefault(request.maxConcurrency(), 1);
        range(maxConcurrency, 1, 128, "并发容量");
        String version = optional(request.version(), 64, "Runner版本");
        SandboxSecretGenerator.GeneratedSecret secret = secretGenerator.runnerSecret();
        LocalDateTime now = utcNow();
        mapper.upsertRunner(
            idGenerator.nextId(), runnerKey, name, secret.secretHash(),
            jsonMapper.writeValueAsString(capabilities), maxConcurrency, version, now
        );
        SandboxRunnerRow row = mapper.selectRunnerByKey(runnerKey);
        if (row == null) {
            throw new IllegalStateException("Runner注册事实未能读取");
        }
        return new SandboxRunnerRegistrationView(row.getId(), runnerKey, secret.rawSecret());
    }

    /**
     * 处理{@code heartbeat}相关逻辑。
     *
     * @param runner {@code runner}参数
     * @param request 请求参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void heartbeat(RunnerAuthentication runner, SandboxRunnerHeartbeatRequest request) {
        List<String> capabilities = capabilities(request.capabilities());
        int maxConcurrency = valueOrDefault(request.maxConcurrency(), 1);
        int activeJobCount = valueOrDefault(request.activeJobCount(), 0);
        range(maxConcurrency, 1, 128, "并发容量");
        range(activeJobCount, 0, maxConcurrency, "活动作业数");
        String version = optional(request.version(), 64, "Runner版本");
        LocalDateTime now = utcNow();
        if (mapper.heartbeat(
            runner.runnerId(), jsonMapper.writeValueAsString(capabilities), maxConcurrency,
            activeJobCount, version, now, now.plusSeconds(heartbeatTtlSeconds)
        ) != 1) {
            throw forbidden("Runner已被禁用");
        }
    }

    /**
     * 处理{@code claim}并返回对应结果。
     *
     * @param runner {@code runner}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public SandboxJobClaimView claim(RunnerAuthentication runner) {
        LocalDateTime now = utcNow();
        mapper.markStaleRunners(now);
        mapper.expireExhaustedJobs(now);
        SandboxSecretGenerator.GeneratedSecret token = secretGenerator.jobToken();
        SandboxJobRow job = mapper.claimJob(
            runner.runnerId(), token.secretHash(), now, now.plusSeconds(leaseSeconds)
        );
        if (job == null) {
            return null;
        }
        return new SandboxJobClaimView(
            job.getId(), job.getSourceType(), job.getOwnerUserId(), job.getConversationId(),
            job.getTaskId(), job.getRunId(), job.getStepId(), job.getToolId(),
            job.getTraceId(), token.rawSecret(), job.getTemplateKey(),
            job.getScriptLanguage(), job.getScriptText(),
            jsonMapper.readValue(job.getArgvJson(), STRING_LIST), job.getWorkspacePath(),
            job.getWorkspaceKey(), job.getWorkspaceAccess(), job.getNetworkPolicy(),
            jsonMapper.readValue(job.getAllowedHostsJson(), STRING_LIST),
            job.getSkillManifestJson(), job.getSkillManifestHash(),
            job.getTimeoutSeconds(), job.getMemoryMb(), job.getCpuMillis(), job.getPidsLimit(),
            job.getMaxOutputBytes(), job.getLeaseUntil(), job.getAttemptNo()
        );
    }

    /**
     * 处理{@code start}相关逻辑。
     *
     * @param runner {@code runner}参数
     * @param jobId 资源标识
     * @param rawJobToken raw作业令牌参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void start(RunnerAuthentication runner, Long jobId, String rawJobToken) {
        requireJobId(jobId);
        String tokenHash = tokenHash(rawJobToken);
        if (mapper.startJob(jobId, runner.runnerId(), tokenHash, utcNow()) != 1) {
            throw staleLease();
        }
    }

    /**
     * 处理{@code renew}相关逻辑。
     *
     * @param runner {@code runner}参数
     * @param jobId 资源标识
     * @param rawJobToken raw作业令牌参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void renew(RunnerAuthentication runner, Long jobId, String rawJobToken) {
        requireJobId(jobId);
        String tokenHash = tokenHash(rawJobToken);
        LocalDateTime now = utcNow();
        if (mapper.renewJob(
            jobId, runner.runnerId(), tokenHash, now, now.plusSeconds(leaseSeconds)
        ) != 1) {
            throw staleLease();
        }
    }

    /**
 * 处理技能BundleHash并返回对应结果。
 * Returns the immutable manifest hash after rechecking the current lease and job token. */
    public String skillBundleHash(
        RunnerAuthentication runner,
        Long jobId,
        String rawJobToken
    ) {
        SandboxJobRow job = requireLeasedJob(runner, jobId, rawJobToken);
        if (job.getSkillManifestJson() == null || job.getSkillManifestHash() == null) {
            throw new ServiceException("沙箱作业没有 Skill manifest", HttpStatus.NOT_FOUND);
        }
        SandboxSkillManifest.Normalized manifest = SandboxSkillManifest.fromJson(
            job.getSkillManifestJson(), jsonMapper
        );
        if (manifest.empty()) {
            throw new ServiceException("沙箱作业没有冻结 Skill", HttpStatus.NOT_FOUND);
        }
        if (!manifestHashMatches(job, manifest)) {
            throw new ServiceException("沙箱 Skill manifest 哈希不一致", HttpStatus.CONFLICT);
        }
        return job.getSkillManifestHash();
    }

    /**
 * 处理write技能Bundle相关逻辑。
 * Streams a validated Skill archive only while the one-time lease remains valid. */
    public void writeSkillBundle(
        RunnerAuthentication runner,
        Long jobId,
        String rawJobToken,
        OutputStream output
    ) {
        SandboxJobRow job = requireLeasedJob(runner, jobId, rawJobToken);
        if (skillBundleService == null) {
            throw new ServiceException("Sandbox Skill bridge 未配置", 503);
        }
        skillBundleService.writeBundle(job, output);
    }

    /**
     * 校验Leased作业，并在条件不满足时终止处理。
     *
     * @param runner {@code runner}参数
     * @param jobId 资源标识
     * @param rawJobToken raw作业令牌参数
     * @return 处理结果
     */
    private SandboxJobRow requireLeasedJob(
        RunnerAuthentication runner,
        Long jobId,
        String rawJobToken
    ) {
        requireJobId(jobId);
        SandboxJobRow job = mapper.selectJob(jobId);
        String tokenHash = tokenHash(rawJobToken);
        LocalDateTime now = utcNow();
        if (job == null || !runner.runnerId().equals(job.getAssignedRunnerId())
            || job.getJobTokenHash() == null
            || !job.getJobTokenHash().equals(tokenHash)
            || job.getLeaseUntil() == null || job.getLeaseUntil().isBefore(now)
            || !Set.of("leased", "running").contains(job.getStatus())) {
            throw staleLease();
        }
        return job;
    }

    /**
 * 处理{@code manifestHashMatches}并返回对应结果。
 *
     * New queue rows use the compact recursively sorted JSON hash.  The raw fallback keeps jobs
     * created by the early JSONB migration draft readable, whose hash was based on PostgreSQL's
     * canonical text representation (including its formatting).
     */
    private boolean manifestHashMatches(
        SandboxJobRow job,
        SandboxSkillManifest.Normalized manifest
    ) {
        String stored = job.getSkillManifestHash();
        if (stored == null) {
            return false;
        }
        if (stored.equalsIgnoreCase(manifest.hash())) {
            return true;
        }
        return job.getSkillManifestJson() != null
            && stored.equalsIgnoreCase(ContentHashing.sha256(job.getSkillManifestJson()));
    }

    /**
     * 处理{@code appendOutput}相关逻辑。
     *
     * @param runner {@code runner}参数
     * @param jobId 资源标识
     * @param rawJobToken raw作业令牌参数
     * @param request 请求参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void appendOutput(
        RunnerAuthentication runner,
        Long jobId,
        String rawJobToken,
        AppendSandboxJobOutputRequest request
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        requireJobId(jobId);
        if (request == null || request.sequenceNo() == null
            || request.sequenceNo() < 0 || request.sequenceNo() > 1_000_000_000L) {
            throw badRequest("输出序号无效");
        }
        if (!"stdout".equals(request.stream()) && !"stderr".equals(request.stream())) {
            throw badRequest("输出流无效");
        }
        String tokenHash = tokenHash(rawJobToken);
        SandboxJobRow job = mapper.selectJob(jobId);
        if (job == null || !runner.runnerId().equals(job.getAssignedRunnerId())
            || job.getAttemptNo() == null) {
            throw staleLease();
        }
        String content = redact(request.content());
        int contentBytes = utf8Length(content);
        if (contentBytes < 1 || contentBytes > 16384) {
            throw badRequest("单个输出片段必须为1至16384字节");
        }
        LocalDateTime now = utcNow();
        Long sequence = mapper.reserveOutputSequence(
            jobId, runner.runnerId(), tokenHash, request.sequenceNo(), contentBytes, now
        );
        if (sequence == null) {
            var existing = mapper.selectOutputByRunnerSequence(
                jobId, job.getAttemptNo(), request.sequenceNo()
            );
            if (existing != null && request.stream().equals(existing.getStream())
                && content.equals(existing.getContent())) {
                return;
            }
            if (job.getOutputBytes() != null && job.getMaxOutputBytes() != null
                && job.getOutputBytes() + contentBytes > job.getMaxOutputBytes()) {
                mapper.markOutputTruncated(jobId, runner.runnerId(), tokenHash, now);
                throw new ServiceException("沙箱输出超过字节上限", 413);
            }
            throw staleLease();
        }
        if (mapper.insertOutput(
            idGenerator.nextId(), jobId, job.getAttemptNo(), sequence, request.sequenceNo(),
            request.stream(), content, contentBytes, now
        ) != 1) {
            throw new ServiceException("沙箱输出保存冲突", HttpStatus.CONFLICT);
        }
    }

    /**
     * 处理{@code complete}相关逻辑。
     *
     * @param runner {@code runner}参数
     * @param jobId 资源标识
     * @param rawJobToken raw作业令牌参数
     * @param request 请求参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void complete(
        RunnerAuthentication runner,
        Long jobId,
        String rawJobToken,
        CompleteSandboxJobRequest request
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        requireJobId(jobId);
        String tokenHash = tokenHash(rawJobToken);
        SandboxJobRow job = mapper.selectJob(jobId);
        if (job == null || !runner.runnerId().equals(job.getAssignedRunnerId())) {
            throw staleLease();
        }
        if (request.succeeded() == null) {
            throw badRequest("作业结果状态不能为空");
        }
        String stdout = redact(request.stdout());
        String stderr = redact(request.stderr());
        int maxBytes = job.getMaxOutputBytes();
        String boundedStdout = boundedUtf8(stdout, maxBytes);
        int remaining = Math.max(0, maxBytes - utf8Length(boundedStdout));
        String boundedStderr = boundedUtf8(stderr, remaining);
        boolean outputExceeded = utf8Length(stdout) + utf8Length(stderr) > maxBytes;
        boolean succeeded = request.succeeded() && !outputExceeded;
        String status = succeeded ? "succeeded" : "failed";
        String failureCode = outputExceeded
            ? "OUTPUT_LIMIT_EXCEEDED" : normalizeFailureCode(request.failureCode(), succeeded);
        String failureMessage = outputExceeded
            ? "sandbox output exceeded configured byte limit"
            : optional(redact(request.failureMessage()), 1000, "失败说明");
        String manifestJson = boundedJson(
            request.outputManifest() == null ? List.of() : request.outputManifest(), 65536, "输出清单"
        );
        String usageJson = boundedJson(
            request.resourceUsage() == null ? Map.of() : request.resourceUsage(), 16384, "资源统计"
        );
        LocalDateTime now = utcNow();
        if (mapper.completeJob(
            jobId, runner.runnerId(), tokenHash, status, request.exitCode(), boundedStdout,
            boundedStderr, manifestJson, usageJson, failureCode, failureMessage, now
        ) != 1) {
            throw staleLease();
        }
        eventPublisher.publishEvent(new SandboxJobCompletedEvent(jobId));
    }

    /**
     * 处理{@code capabilities}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @return 符合条件的数据集合
     */
    private List<String> capabilities(Set<String> raw) {
        if (raw == null || raw.isEmpty() || raw.size() > 32) {
            throw badRequest("Runner能力必须包含1到32个模板键");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        raw.stream().sorted().forEach(value -> {
            String normalized = required(value, 64, "Runner能力");
            if (!CAPABILITY.matcher(normalized).matches()) {
                throw badRequest("Runner能力模板键无效");
            }
            result.add(normalized);
        });
        return List.copyOf(result);
    }

    /**
     * 将输入数据转换为{@code kenHash}。
     *
     * @param rawJobToken raw作业令牌参数
     * @return 处理结果
     */
    private String tokenHash(String rawJobToken) {
        if (rawJobToken == null || !JOB_TOKEN.matcher(rawJobToken).matches()) {
            throw staleLease();
        }
        return ContentHashing.sha256(rawJobToken);
    }

    /**
     * 处理{@code normalizeFailureCode}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param succeeded {@code succeeded}参数
     * @return 处理结果
     */
    private String normalizeFailureCode(String value, boolean succeeded) {
        if (succeeded) {
            return null;
        }
        String normalized = value == null || value.isBlank() ? "SANDBOX_EXECUTION_FAILED" : value.strip();
        if (!FAILURE_CODE.matcher(normalized).matches()) {
            throw badRequest("失败代码无效");
        }
        return normalized;
    }

    /**
     * 处理{@code boundedJson}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maxBytes {@code maxBytes}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String boundedJson(Object value, int maxBytes, String label) {
        String json = jsonMapper.writeValueAsString(value);
        if (utf8Length(json) > maxBytes) {
            throw badRequest(label + "过大");
        }
        return json;
    }

    /**
     * 处理{@code redact}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String redact(String value) {
        if (value == null) {
            return null;
        }
        String redacted = SECRET.matcher(value).replaceAll("[REDACTED_SECRET]");
        redacted = BEARER_SECRET.matcher(redacted).replaceAll("$1[REDACTED_SECRET]");
        redacted = PROVIDER_SECRET.matcher(redacted).replaceAll("[REDACTED_SECRET]");
        return NAMED_SECRET.matcher(redacted).replaceAll("$1[REDACTED_SECRET]");
    }

    /**
     * 处理{@code boundedUtf8}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maxBytes {@code maxBytes}参数
     * @return 处理结果
     */
    private String boundedUtf8(String value, int maxBytes) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (value == null || maxBytes <= 0) {
            return value == null ? null : "";
        }
        if (utf8Length(value) <= maxBytes) {
            return value;
        }
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String text = new String(Character.toChars(codePoint));
            int length = utf8Length(text);
            if (bytes + length > maxBytes) {
                break;
            }
            result.append(text);
            bytes += length;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    /**
     * 处理{@code utf8Length}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 校验{@code d}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String required(String value, int maxLength, String label) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength || hasControl(normalized)) {
            throw badRequest(label + "不能为空、过长或包含控制字符");
        }
        return normalized;
    }

    /**
     * 处理{@code optional}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String optional(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return required(value, maxLength, label);
    }

    /**
     * 判断{@code Control}是否满足要求。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean hasControl(String value) {
        return value.chars().anyMatch(character -> character < 32 || character == 127);
    }

    /**
     * 处理{@code valueOrDefault}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param defaultValue {@code defaultValue}参数
     * @return 处理结果
     */
    private int valueOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    /**
     * 处理{@code range}相关逻辑。
     *
     * @param value {@code value}参数
     * @param min {@code min}参数
     * @param max {@code max}参数
     * @param label {@code label}参数
     */
    private void range(int value, int min, int max, String label) {
        if (value < min || value > max) {
            throw badRequest(label + "超出允许范围");
        }
    }

    /**
     * 校验作业Id，并在条件不满足时终止处理。
     *
     * @param jobId 资源标识
     */
    private void requireJobId(Long jobId) {
        if (jobId == null || jobId <= 0) {
            throw badRequest("作业ID无效");
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
     * 处理{@code forbidden}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException forbidden(String message) {
        return new ServiceException(message, HttpStatus.FORBIDDEN);
    }

    /**
     * 处理{@code staleLease}并返回对应结果。
     *
     * @return 处理结果
     */
    private ServiceException staleLease() {
        return new ServiceException("沙箱作业租约或一次性令牌已失效", HttpStatus.CONFLICT);
    }
}
