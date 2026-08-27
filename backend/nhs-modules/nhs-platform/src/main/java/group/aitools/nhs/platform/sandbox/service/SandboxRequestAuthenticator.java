package group.aitools.nhs.platform.sandbox.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.sandbox.mapper.SandboxRunnerMapper;
import group.aitools.nhs.platform.sandbox.persistence.row.SandboxRunnerRow;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 表示沙箱RequestAuthenticator相关的领域对象。
 * Authenticates internal Runner traffic without creating a human or service-account principal. */
@Service
public class SandboxRequestAuthenticator {

    private static final Pattern RUNNER_KEY = Pattern.compile("[a-z][a-z0-9._-]{2,63}");
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{16,128}");
    private static final Pattern RUNNER_SECRET = Pattern.compile(
        "asr_[A-Za-z0-9_-]{12}\\.[A-Za-z0-9_-]{43}"
    );
    private static final int BOOTSTRAP_FAILURES_PER_MINUTE = 10;

    private final SandboxRunnerMapper mapper;
    private final PlatformIdGenerator idGenerator;
    private final String bootstrapToken;
    private final int clockSkewSeconds;
    private final AtomicLong bootstrapWindow = new AtomicLong(-1);
    private final AtomicInteger bootstrapFailures = new AtomicInteger();

    /**
     * 创建 {@code SandboxRequestAuthenticator} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param idGenerator {@code idGenerator}参数
     * @param bootstrapToken bootstrap令牌参数
     * @param clockSkewSeconds {@code clockSkewSeconds}参数
     */
    public SandboxRequestAuthenticator(
        SandboxRunnerMapper mapper,
        PlatformIdGenerator idGenerator,
        @Value("${agent.platform.sandbox.bootstrap-token:}") String bootstrapToken,
        @Value("${agent.platform.sandbox.request-clock-skew-seconds:120}") int clockSkewSeconds
    ) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.bootstrapToken = bootstrapToken == null ? "" : bootstrapToken.strip();
        this.clockSkewSeconds = Math.max(30, Math.min(clockSkewSeconds, 300));
    }

    /**
     * 处理{@code authenticateRegistration}相关逻辑。
     *
     * @param authorization 授权参数
     * @param timestamp {@code timestamp}参数
     * @param nonce {@code nonce}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void authenticateRegistration(String authorization, String timestamp, String nonce) {
        LocalDateTime requestTime = requestTime(timestamp);
        String supplied = bearer(authorization);
        if (bootstrapToken.length() < 32 || !constantTimeEquals(bootstrapToken, supplied)) {
            recordBootstrapFailure();
            throw unauthorized();
        }
        resetBootstrapFailures();
        consumeNonce(0L, nonce, requestTime);
    }

    /**
     * 处理{@code authenticateRunner}并返回对应结果。
     *
     * @param runnerKey {@code runnerKey}参数
     * @param authorization 授权参数
     * @param timestamp {@code timestamp}参数
     * @param nonce {@code nonce}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public RunnerAuthentication authenticateRunner(
        String runnerKey,
        String authorization,
        String timestamp,
        String nonce
    ) {
        LocalDateTime requestTime = requestTime(timestamp);
        String normalizedKey = runnerKey == null ? "" : runnerKey.strip();
        String secret = bearer(authorization);
        if (!RUNNER_KEY.matcher(normalizedKey).matches()
            || !RUNNER_SECRET.matcher(secret).matches()) {
            throw unauthorized();
        }
        SandboxRunnerRow row = mapper.selectRunnerByKey(normalizedKey);
        if (row == null
            || "disabled".equals(row.getStatus())
            || !constantTimeEquals(row.getSecretHash(), ContentHashing.sha256(secret))) {
            throw unauthorized();
        }
        consumeNonce(row.getId(), nonce, requestTime);
        return new RunnerAuthentication(row.getId(), row.getRunnerKey(), row.getStatus());
    }

    /**
     * 处理{@code consumeNonce}相关逻辑。
     *
     * @param runnerId 资源标识
     * @param nonce {@code nonce}参数
     * @param requestTime {@code requestTime}参数
     */
    private void consumeNonce(Long runnerId, String nonce, LocalDateTime requestTime) {
        if (nonce == null || !NONCE.matcher(nonce).matches()) {
            throw unauthorized();
        }
        LocalDateTime now = utcNow();
        mapper.deleteExpiredNonces(now.minusMinutes(1));
        if (mapper.insertNonce(
            idGenerator.nextId(), runnerId, ContentHashing.sha256(nonce), requestTime,
            now.plusSeconds(clockSkewSeconds * 2L), now
        ) != 1) {
            throw new ServiceException("Runner请求已重放", HttpStatus.UNAUTHORIZED);
        }
    }

    /**
     * 处理{@code requestTime}并返回对应结果。
     *
     * @param timestamp {@code timestamp}参数
     * @return 处理结果
     */
    private LocalDateTime requestTime(String timestamp) {
        try {
            long epochSeconds = Long.parseLong(timestamp);
            Instant instant = Instant.ofEpochSecond(epochSeconds);
            Instant now = Instant.now();
            if (Math.abs(now.getEpochSecond() - instant.getEpochSecond()) > clockSkewSeconds) {
                throw unauthorized();
            }
            return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        } catch (RuntimeException exception) {
            if (exception instanceof ServiceException serviceException) {
                throw serviceException;
            }
            throw unauthorized();
        }
    }

    /**
     * 处理{@code bearer}并返回对应结果。
     *
     * @param authorization 授权参数
     * @return 处理结果
     */
    private String bearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw unauthorized();
        }
        String value = authorization.substring(7).strip();
        if (value.isEmpty() || value.length() > 256) {
            throw unauthorized();
        }
        return value;
    }

    /**
     * 处理{@code constantTimeEquals}并返回对应结果。
     *
     * @param expected {@code expected}参数
     * @param supplied {@code supplied}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean constantTimeEquals(String expected, String supplied) {
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * 处理{@code recordBootstrapFailure}相关逻辑。
     */
    private void recordBootstrapFailure() {
        long minute = Instant.now().getEpochSecond() / 60;
        long current = bootstrapWindow.get();
        if (current != minute && bootstrapWindow.compareAndSet(current, minute)) {
            bootstrapFailures.set(0);
        }
        if (bootstrapFailures.incrementAndGet() > BOOTSTRAP_FAILURES_PER_MINUTE) {
            throw new ServiceException("Runner注册尝试过于频繁", 429);
        }
    }

    /**
     * 清理或重置{@code BootstrapFailures}。
     */
    private void resetBootstrapFailures() {
        bootstrapFailures.set(0);
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
     * 处理{@code unauthorized}并返回对应结果。
     *
     * @return 处理结果
     */
    private ServiceException unauthorized() {
        return new ServiceException("Runner凭证无效或请求已失效", HttpStatus.UNAUTHORIZED);
    }

    /**
     * 封装{@code RunnerAuthentication}相关的不可变数据。
     */
    public record RunnerAuthentication(Long runnerId, String runnerKey, String status) {
    }
}
