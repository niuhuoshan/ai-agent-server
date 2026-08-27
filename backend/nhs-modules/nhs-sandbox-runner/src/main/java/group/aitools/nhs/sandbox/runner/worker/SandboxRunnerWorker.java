package group.aitools.nhs.sandbox.runner.worker;

import group.aitools.nhs.sandbox.runner.client.SandboxPlatformClient;
import group.aitools.nhs.sandbox.runner.client.SandboxProtocol.ClaimedJob;
import group.aitools.nhs.sandbox.runner.client.SandboxProtocol.Completion;
import group.aitools.nhs.sandbox.runner.client.SandboxProtocol.OutputChunk;
import group.aitools.nhs.sandbox.runner.config.SandboxRunnerProperties;
import group.aitools.nhs.sandbox.runner.execution.ContainerExecutor;
import group.aitools.nhs.sandbox.runner.execution.ContainerExecutor.ExecutionResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 表示沙箱Runner工作进程相关的领域对象。
 */
@Component
public class SandboxRunnerWorker implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxRunnerWorker.class);

    private final SandboxRunnerProperties properties;
    private final SandboxPlatformClient client;
    private final ContainerExecutor executor;
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(
        Thread.ofPlatform().name("sandbox-runner-coordinator").daemon(false).factory()
    );
    private final ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService renewals = Executors.newScheduledThreadPool(1);
    private final AtomicInteger activeJobs = new AtomicInteger();
    private final AtomicBoolean running = new AtomicBoolean(true);

    /**
     * 创建 {@code SandboxRunnerWorker} 实例并初始化所需依赖。
     *
     * @param properties {@code properties}参数
     * @param client 客户端参数
     * @param executor {@code executor}参数
     */
    public SandboxRunnerWorker(
        SandboxRunnerProperties properties,
        SandboxPlatformClient client,
        ContainerExecutor executor
    ) {
        this.properties = properties;
        this.client = client;
        this.executor = executor;
        if (properties.getMaxConcurrency() < 1 || properties.getMaxConcurrency() > 128) {
            throw new IllegalArgumentException("Runner max concurrency must be between 1 and 128");
        }
    }

    /**
     * 执行{@code run}相关的处理流程。
     *
     * @param args {@code args}参数
     */
    @Override
    public void run(ApplicationArguments args) {
        coordinator.submit(this::loop);
    }

    /**
     * 处理{@code loop}相关逻辑。
     */
    private void loop() {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Instant nextHeartbeat = Instant.EPOCH;
        long backoffMs = 1000;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                client.ensureRegistered();
                Instant now = Instant.now();
                if (!now.isBefore(nextHeartbeat)) {
                    if (!executor.healthy()) {
                        LOGGER.error("Container engine health check failed; Runner will not claim work");
                        sleep(Math.max(1000, properties.getHeartbeatIntervalSeconds() * 1000L));
                        continue;
                    }
                    client.heartbeat(activeJobs.get());
                    nextHeartbeat = now.plusSeconds(
                        Math.max(5, properties.getHeartbeatIntervalSeconds())
                    );
                }
                if (activeJobs.get() < properties.getMaxConcurrency()) {
                    ClaimedJob job = client.claim();
                    if (job != null) {
                        activeJobs.incrementAndGet();
                        jobs.submit(() -> execute(job));
                        backoffMs = 1000;
                        continue;
                    }
                }
                backoffMs = 1000;
                sleep(Math.max(100, properties.getPollIntervalMs()));
            } catch (RuntimeException exception) {
                LOGGER.error("Sandbox Runner control-plane call failed: {}", safeMessage(exception));
                sleep(backoffMs);
                backoffMs = Math.min(30000, backoffMs * 2);
            }
        }
    }

    /**
     * 执行{@code execute}相关的处理流程。
     *
     * @param job 作业参数
     */
    private void execute(ClaimedJob job) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        AtomicBoolean leaseValid = new AtomicBoolean(true);
        ScheduledFuture<?> renewal = null;
        try {
            client.start(job);
            long renewEverySeconds = renewalInterval(job);
            renewal = renewals.scheduleAtFixedRate(() -> {
                if (!leaseValid.get()) {
                    return;
                }
                try {
                    client.renew(job);
                } catch (RuntimeException exception) {
                    leaseValid.set(false);
                    LOGGER.error("Sandbox job {} lost its lease", job.jobId());
                }
            }, renewEverySeconds, renewEverySeconds, TimeUnit.SECONDS);
            AtomicLong outputSequence = new AtomicLong();
            Object outputLock = new Object();
            ExecutionResult result = executor.execute(
                job, leaseValid::get,
                (stream, content) -> {
                    synchronized (outputLock) {
                        return appendOutput(
                            job, leaseValid, outputSequence.incrementAndGet(), stream, content
                        );
                    }
                }
            );
            Completion completion = new Completion(
                result.succeeded(), result.exitCode(), result.stdout(), result.stderr(),
                List.of(), result.resourceUsage(), result.failureCode(), result.failureMessage()
            );
            client.complete(job, completion);
            LOGGER.info(
                "Sandbox job {} completed with outcome {}",
                job.jobId(), result.succeeded() ? "succeeded" : result.failureCode()
            );
        } catch (RuntimeException exception) {
            LOGGER.error("Sandbox job {} could not be finalized: {}", job.jobId(), safeMessage(exception));
        } finally {
            leaseValid.set(false);
            if (renewal != null) {
                renewal.cancel(false);
            }
            activeJobs.decrementAndGet();
        }
    }

    /**
     * 处理{@code appendOutput}并返回对应结果。
     *
     * @param job 作业参数
     * @param leaseValid 资源标识
     * @param sequenceNo 起始位置或序号
     * @param stream {@code stream}参数
     * @param content 待处理内容
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean appendOutput(
        ClaimedJob job,
        AtomicBoolean leaseValid,
        long sequenceNo,
        String stream,
        String content
    ) {
        if (!leaseValid.get()) {
            return false;
        }
        try {
            client.appendOutput(job, new OutputChunk(sequenceNo, stream, content));
            return true;
        } catch (RuntimeException exception) {
            if (leaseValid.compareAndSet(true, false)) {
                LOGGER.error(
                    "Sandbox job {} output was rejected; execution will stop: {}",
                    job.jobId(), safeMessage(exception)
                );
            }
            return false;
        }
    }

    /**
     * 处理{@code renewalInterval}并返回对应结果。
     *
     * @param job 作业参数
     * @return 处理结果
     */
    private long renewalInterval(ClaimedJob job) {
        LocalDateTime leaseUntil = job.leaseUntil();
        if (leaseUntil == null) {
            return 20;
        }
        long remaining = Duration.between(
            LocalDateTime.now(ZoneOffset.UTC), leaseUntil
        ).toSeconds();
        return Math.max(10, Math.min(30, remaining / 3));
    }

    /**
     * 处理safe消息并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @return 处理结果
     */
    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        String redacted = message.replaceAll(
            "(?:asr|asj|agk)_[A-Za-z0-9_-]{12}\\.[A-Za-z0-9_-]{20,}",
            "[REDACTED_SECRET]"
        );
        return redacted.substring(0, Math.min(redacted.length(), 500));
    }

    /**
     * 处理{@code sleep}相关逻辑。
     *
     * @param milliseconds {@code milliseconds}参数
     */
    private void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 处理{@code stop}相关逻辑。
     */
    @PreDestroy
    public void stop() {
        running.set(false);
        coordinator.shutdownNow();
        jobs.shutdownNow();
        renewals.shutdownNow();
    }
}
