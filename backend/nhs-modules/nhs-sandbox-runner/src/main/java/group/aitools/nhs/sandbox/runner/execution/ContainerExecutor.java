package group.aitools.nhs.sandbox.runner.execution;

import group.aitools.nhs.sandbox.runner.client.SandboxProtocol.ClaimedJob;
import group.aitools.nhs.sandbox.runner.execution.ChatCodePolicy.ChatCodePolicyException;
import group.aitools.nhs.sandbox.runner.execution.ContainerCommandBuilder.ContainerInvocation;
import group.aitools.nhs.sandbox.runner.execution.ContainerCommandBuilder.SandboxExecutionPolicyException;
import group.aitools.nhs.sandbox.runner.execution.WorkspaceResolver.WorkspacePolicyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 表示{@code Container}相关的领域对象。
 */
@Component
public class ContainerExecutor {

    private final ContainerCommandBuilder commandBuilder;
    private final WorkspaceResolver workspaceResolver;
    private final SkillSandboxBridge skillBridge;

    /**
     * 创建 {@code ContainerExecutor} 实例并初始化所需依赖。
     *
     * @param commandBuilder 命令Builder参数
     * @param workspaceResolver 工作空间Resolver参数
     * @param skillBridge 技能Bridge参数
     */
    @Autowired
    public ContainerExecutor(
        ContainerCommandBuilder commandBuilder,
        WorkspaceResolver workspaceResolver,
        SkillSandboxBridge skillBridge
    ) {
        this.commandBuilder = commandBuilder;
        this.workspaceResolver = workspaceResolver;
        this.skillBridge = skillBridge;
    }

    /**
 * 创建 {@code ContainerExecutor} 实例并初始化所需依赖。
 * Compatibility constructor for focused tests and non-Spring embedders. */
    public ContainerExecutor(
        ContainerCommandBuilder commandBuilder,
        WorkspaceResolver workspaceResolver
    ) {
        this(commandBuilder, workspaceResolver, null);
    }

    /**
     * 执行{@code execute}相关的处理流程。
     *
     * @param job 作业参数
     * @param leaseValid 资源标识
     * @return 处理结果
     */
    public ExecutionResult execute(ClaimedJob job, BooleanSupplier leaseValid) {
        return execute(job, leaseValid, (stream, content) -> true);
    }

    /**
     * 执行{@code execute}相关的处理流程。
     *
     * @param job 作业参数
     * @param leaseValid 资源标识
     * @param outputConsumer {@code outputConsumer}参数
     * @return 处理结果
     */
    public ExecutionResult execute(
        ClaimedJob job,
        BooleanSupplier leaseValid,
        OutputChunkConsumer outputConsumer
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Instant started = Instant.now();
        ContainerInvocation invocation = null;
        SkillSandboxBridge.PreparedMount skillMount = null;
        Process process = null;
        try {
            Path workspace = workspaceResolver.resolve(job);
            workspaceResolver.materializeChatScript(job, workspace);
            if (!isBlank(job.skillManifestJson()) || !isBlank(job.skillManifestHash())) {
                if (skillBridge == null) {
                    return failure(
                        "SKILL_BRIDGE_UNAVAILABLE",
                        "Sandbox Skill bridge is not configured", null, null, null, started
                    );
                }
                skillMount = skillBridge.prepare(job);
            }
            invocation = commandBuilder.build(job, workspace, skillMount);
            process = new ProcessBuilder(invocation.command()).start();
            AtomicInteger remaining = new AtomicInteger(job.maxOutputBytes());
            AtomicBoolean exceeded = new AtomicBoolean();
            AtomicBoolean outputDeliveryValid = new AtomicBoolean(true);
            BoundedOutputCollector stdout = new BoundedOutputCollector(
                process.getInputStream(), remaining, exceeded,
                chunk -> deliver(
                    outputConsumer, "stdout", chunk, leaseValid, outputDeliveryValid
                )
            );
            BoundedOutputCollector stderr = new BoundedOutputCollector(
                process.getErrorStream(), remaining, exceeded,
                chunk -> deliver(
                    outputConsumer, "stderr", chunk, leaseValid, outputDeliveryValid
                )
            );
            CountDownLatch drained = new CountDownLatch(2);
            Thread stdoutDrain = Thread.ofVirtual().start(() -> {
                try {
                    stdout.run();
                } finally {
                    drained.countDown();
                }
            });
            Thread stderrDrain = Thread.ofVirtual().start(() -> {
                try {
                    stderr.run();
                } finally {
                    drained.countDown();
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(job.timeoutSeconds());
            String forcedFailure = null;
            while (process.isAlive()) {
                if (!leaseValid.getAsBoolean()) {
                    forcedFailure = "LEASE_LOST";
                    destroy(process);
                    break;
                }
                if (!outputDeliveryValid.get()) {
                    forcedFailure = "OUTPUT_DELIVERY_FAILED";
                    destroy(process);
                    break;
                }
                if (System.nanoTime() >= deadline) {
                    forcedFailure = "EXECUTION_TIMEOUT";
                    destroy(process);
                    break;
                }
                process.waitFor(200, TimeUnit.MILLISECONDS);
            }
            int exitCode = process.isAlive() ? -1 : process.exitValue();
            if (!drained.await(35, TimeUnit.SECONDS)) {
                outputDeliveryValid.set(false);
                stdoutDrain.interrupt();
                stderrDrain.interrupt();
            }
            if (forcedFailure == null && !leaseValid.getAsBoolean()) {
                forcedFailure = "LEASE_LOST";
            }
            if (forcedFailure == null && !outputDeliveryValid.get()) {
                forcedFailure = "OUTPUT_DELIVERY_FAILED";
            }
            if (forcedFailure != null) {
                return failure(
                    forcedFailure, forcedFailureMessage(forcedFailure),
                    exitCode, stdout.text(), stderr.text(), started
                );
            }
            if (exceeded.get()) {
                return failure(
                    "OUTPUT_LIMIT_EXCEEDED", "Sandbox output exceeded configured byte limit",
                    exitCode, stdout.text(), stderr.text(), started
                );
            }
            boolean succeeded = exitCode == 0;
            return new ExecutionResult(
                succeeded, exitCode, stdout.text(), stderr.text(),
                succeeded ? null : "NON_ZERO_EXIT",
                succeeded ? null : "Sandbox process returned a non-zero exit code",
                Map.of("durationMs", Duration.between(started, Instant.now()).toMillis())
            );
        } catch (SandboxExecutionPolicyException exception) {
            return failure(exception.code(), exception.getMessage(), null, null, null, started);
        } catch (WorkspacePolicyException exception) {
            return failure(exception.code(), exception.getMessage(), null, null, null, started);
        } catch (ChatCodePolicyException exception) {
            return failure(exception.code(), exception.getMessage(), null, null, null, started);
        } catch (SkillSandboxBridge.SkillBridgeException exception) {
            return failure(exception.code(), exception.getMessage(), null, null, null, started);
        } catch (IOException exception) {
            return failure("ENGINE_START_FAILED", "Container engine could not start", null, null, null, started);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                destroy(process);
            }
            return failure("RUNNER_INTERRUPTED", "Sandbox Runner was interrupted", null, null, null, started);
        } finally {
            if (process != null && process.isAlive()) {
                destroy(process);
            }
            if (invocation != null) {
                cleanup(invocation.containerName());
            }
            if (skillBridge != null) {
                skillBridge.cleanup(skillMount);
            }
        }
    }

    /**
     * 判断{@code Blank}是否满足要求。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 处理{@code deliver}相关逻辑。
     *
     * @param consumer {@code consumer}参数
     * @param stream {@code stream}参数
     * @param content 待处理内容
     * @param leaseValid 资源标识
     * @param outputDeliveryValid 资源标识
     */
    private void deliver(
        OutputChunkConsumer consumer,
        String stream,
        String content,
        BooleanSupplier leaseValid,
        AtomicBoolean outputDeliveryValid
    ) {
        if (!leaseValid.getAsBoolean() || !outputDeliveryValid.get()) {
            return;
        }
        try {
            if (!consumer.accept(stream, content)) {
                outputDeliveryValid.set(false);
            }
        } catch (RuntimeException exception) {
            outputDeliveryValid.set(false);
        }
    }

    /**
     * 处理forcedFailure消息并返回对应结果。
     *
     * @param code {@code code}参数
     * @return 处理结果
     */
    private String forcedFailureMessage(String code) {
        return switch (code) {
            case "LEASE_LOST" -> "Sandbox lease renewal failed or the job was cancelled";
            case "OUTPUT_DELIVERY_FAILED" -> "Sandbox output could not be persisted";
            case "EXECUTION_TIMEOUT" -> "Sandbox execution timed out";
            default -> "Sandbox execution was stopped";
        };
    }

    /**
     * 处理{@code healthy}并返回对应结果。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean healthy() {
        try {
            Process process = new ProcessBuilder(commandBuilder.healthCheck())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 处理{@code failure}并返回对应结果。
     *
     * @param code {@code code}参数
     * @param message 待处理内容
     * @param exitCode {@code exitCode}参数
     * @param stdout {@code stdout}参数
     * @param stderr {@code stderr}参数
     * @param started {@code started}参数
     * @return 处理结果
     */
    private ExecutionResult failure(
        String code,
        String message,
        Integer exitCode,
        String stdout,
        String stderr,
        Instant started
    ) {
        return new ExecutionResult(
            false, exitCode, stdout, stderr, code, message,
            Map.of("durationMs", Duration.between(started, Instant.now()).toMillis())
        );
    }

    /**
     * 处理{@code destroy}相关逻辑。
     *
     * @param process {@code process}参数
     */
    private void destroy(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /**
     * 处理{@code cleanup}相关逻辑。
     *
     * @param containerName 名称
     */
    private void cleanup(String containerName) {
        try {
            Process cleanup = new ProcessBuilder(commandBuilder.cleanup(containerName))
                .redirectErrorStream(true)
                .start();
            if (!cleanup.waitFor(10, TimeUnit.SECONDS)) {
                cleanup.destroyForcibly();
            }
        } catch (IOException ignored) {
            // The engine may be unavailable; the next host cleanup/health check will surface it.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 封装执行相关的不可变数据。
     */
    public record ExecutionResult(
        boolean succeeded,
        Integer exitCode,
        String stdout,
        String stderr,
        String failureCode,
        String failureMessage,
        Map<String, Object> resourceUsage
    ) {
    }

    /**
     * 定义{@code OutputChunkConsumer}相关能力的服务契约。
     */
    @FunctionalInterface
    public interface OutputChunkConsumer {
        /**
         * 处理{@code accept}并返回对应结果。
         *
         * @param stream {@code stream}参数
         * @param content 待处理内容
         * @return 判断结果，{@code true} 表示条件成立
         */
        boolean accept(String stream, String content);
    }
}
