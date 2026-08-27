package group.aitools.nhs.sandbox.runner.execution;

import group.aitools.nhs.sandbox.runner.client.SandboxProtocol.ClaimedJob;
import group.aitools.nhs.sandbox.runner.config.SandboxRunnerProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 表示Container命令Builder相关的领域对象。
 */
@Component
public class ContainerCommandBuilder {

    private static final Pattern IMMUTABLE_IMAGE = Pattern.compile(
        "[A-Za-z0-9._/:@-]{1,400}@sha256:[0-9a-f]{64}"
    );
    private static final Pattern TEMPLATE_KEY = Pattern.compile("[a-z][a-z0-9._-]{1,63}");

    private final String engine;
    private final Map<String, String> templates;

    /**
     * 创建 {@code ContainerCommandBuilder} 实例并初始化所需依赖。
     *
     * @param properties {@code properties}参数
     */
    public ContainerCommandBuilder(SandboxRunnerProperties properties) {
        String configuredEngine = properties.getEngine() == null
            ? "" : properties.getEngine().strip().toLowerCase(Locale.ROOT);
        if (!List.of("podman", "docker").contains(configuredEngine)) {
            throw new IllegalArgumentException("Sandbox engine must be podman or docker");
        }
        this.engine = configuredEngine;
        this.templates = Map.copyOf(properties.getTemplates());
        if (templates.isEmpty() || templates.size() > 32) {
            throw new IllegalArgumentException("Configure 1 to 32 sandbox templates");
        }
        templates.forEach((key, image) -> {
            if (!TEMPLATE_KEY.matcher(key).matches()
                || image == null
                || !IMMUTABLE_IMAGE.matcher(image).matches()) {
                throw new IllegalArgumentException(
                    "Sandbox templates require valid keys and immutable @sha256 image references"
                );
            }
        });
    }

    /**
     * 构建{@code build}。
     *
     * @param job 作业参数
     * @param workspace 工作空间参数
     * @return 处理结果
     */
    public ContainerInvocation build(ClaimedJob job, Path workspace) {
        return build(job, workspace, null);
    }

    /**
     * 构建{@code build}。
     *
     * @param job 作业参数
     * @param workspace 工作空间参数
     * @param skillMount 技能Mount参数
     * @return 处理结果
     */
    public ContainerInvocation build(
        ClaimedJob job,
        Path workspace,
        SkillSandboxBridge.PreparedMount skillMount
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        String image = templates.get(job.templateKey());
        if (image == null) {
            throw new SandboxExecutionPolicyException("UNKNOWN_TEMPLATE", "Unknown sandbox template");
        }
        if (!"none".equals(job.networkPolicy())) {
            throw new SandboxExecutionPolicyException(
                "NETWORK_POLICY_UNAVAILABLE",
                "Allowlist networking requires a dedicated egress proxy and is disabled"
            );
        }
        if (job.allowedHosts() != null && !job.allowedHosts().isEmpty()) {
            throw new SandboxExecutionPolicyException(
                "NETWORK_POLICY_INVALID", "Network-disabled job cannot carry allowed hosts"
            );
        }
        List<String> executionArgv = ChatCodePolicy.isChatCode(job)
            ? ChatCodePolicy.scriptPlan(job).argv()
            : job.argv();
        if (executionArgv == null || executionArgv.isEmpty() || executionArgv.size() > 128) {
            throw new SandboxExecutionPolicyException("ARGV_INVALID", "Sandbox argv is invalid");
        }
        String containerName = "agent-sbx-" + job.jobId() + "-" + job.attemptNo();
        String mountMode = "read_only".equals(job.workspaceAccess()) ? "ro" : "rw";
        List<String> command = new ArrayList<>();
        command.add(engine);
        command.add("run");
        command.add("--rm");
        command.add("--pull=never");
        command.add("--name=" + containerName);
        command.add("--network=none");
        command.add("--read-only");
        command.add("--cap-drop=ALL");
        command.add("--security-opt=no-new-privileges");
        command.add("--memory=" + job.memoryMb() + "m");
        command.add("--cpus=" + String.format(Locale.ROOT, "%.3f", job.cpuMillis() / 1000.0));
        command.add("--pids-limit=" + job.pidsLimit());
        command.add("--user=65532:65532");
        command.add("--tmpfs=/tmp:rw,noexec,nosuid,nodev,size=64m");
        command.add("--volume=" + workspace + ":/workspace:" + mountMode + ",Z");
        if (skillMount != null) {
            command.add("--volume=" + skillMount.skillsRoot() + ":/workspace/skills:ro,Z");
            command.add("--env=PYTHONPATH=" + interpreterPaths(skillMount, ".agent-dependencies/python"));
            command.add("--env=NODE_PATH=" + interpreterPaths(skillMount, ".agent-dependencies/node/node_modules"));
        }
        command.add("--workdir=/workspace");
        command.add("--stop-timeout=5");
        command.add(image);
        command.addAll(executionArgv);
        return new ContainerInvocation(containerName, List.copyOf(command));
    }

    /**
     * 处理{@code interpreterPaths}并返回对应结果。
     *
     * @param mount {@code mount}参数
     * @param suffix {@code suffix}参数
     * @return 处理结果
     */
    private String interpreterPaths(SkillSandboxBridge.PreparedMount mount, String suffix) {
        return mount.skillKeys().stream()
            .map(key -> "/workspace/skills/" + key + "/" + suffix)
            .reduce((left, right) -> left + ":" + right)
            .orElse("");
    }

    /**
     * 处理{@code cleanup}并返回对应结果。
     *
     * @param containerName 名称
     * @return 符合条件的数据集合
     */
    public List<String> cleanup(String containerName) {
        if (containerName == null || !containerName.matches("agent-sbx-[0-9]+-[0-9]+")) {
            throw new IllegalArgumentException("Container name is invalid");
        }
        return List.of(engine, "rm", "--force", containerName);
    }

    /**
     * 处理健康状态Check并返回对应结果。
     *
     * @return 符合条件的数据集合
     */
    public List<String> healthCheck() {
        return List.of(engine, "info");
    }

    /**
     * 封装Container调用相关的不可变数据。
     */
    public record ContainerInvocation(String containerName, List<String> command) {
    }

    /**
     * 表示沙箱执行策略处理过程中发生的业务异常。
     */
    public static final class SandboxExecutionPolicyException extends RuntimeException {
        private final String code;

        /**
         * 创建 {@code SandboxExecutionPolicyException} 实例并初始化所需依赖。
         *
         * @param code {@code code}参数
         * @param message 待处理内容
         */
        public SandboxExecutionPolicyException(String code, String message) {
            super(message);
            this.code = code;
        }

        /**
         * 处理{@code code}并返回对应结果。
         *
         * @return 处理结果
         */
        public String code() {
            return code;
        }
    }
}
