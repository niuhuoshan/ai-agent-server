package group.aitools.nhs.sandbox.runner.execution;

import group.aitools.nhs.sandbox.runner.client.SandboxProtocol.ClaimedJob;
import group.aitools.nhs.sandbox.runner.config.SandboxRunnerProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 负责工作空间相关的转换、解析或处理逻辑。
 */
@Component
public class WorkspaceResolver {

    private static final Pattern WORKSPACE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final Path root;

    /**
     * 创建 {@code WorkspaceResolver} 实例并初始化所需依赖。
     *
     * @param properties {@code properties}参数
     */
    public WorkspaceResolver(SandboxRunnerProperties properties) {
        try {
            root = properties.getWorkspaceRoot().toAbsolutePath().normalize();
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) {
                throw new IllegalArgumentException("Sandbox workspace root cannot be a symbolic link");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot initialize sandbox workspace root", exception);
        }
    }

    /**
     * 获取{@code resolve}。
     *
     * @param job 作业参数
     * @return 处理结果
     */
    public Path resolve(ClaimedJob job) {
        if (job == null) {
            throw new WorkspacePolicyException("WORKSPACE_ID_INVALID", "Sandbox job is missing");
        }
        if (job.workspaceKey() != null && !job.workspaceKey().isBlank()) {
            return resolveBoundWorkspace(job);
        }
        if (ChatCodePolicy.isChatCode(job)) {
            return resolveChatCode(job);
        }
        return resolveTaskTool(job);
    }

    /**
     * 处理materialize对话Script相关逻辑。
     *
     * @param job 作业参数
     * @param workspace 工作空间参数
     */
    public void materializeChatScript(ClaimedJob job, Path workspace) {
        if (!ChatCodePolicy.isChatCode(job)) {
            return;
        }
        ChatCodePolicy.ScriptPlan plan = ChatCodePolicy.scriptPlan(job);
        Path script = workspace.resolve(plan.fileName()).normalize();
        if (!script.getParent().equals(workspace) || Files.exists(script, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspacePolicyException(
                "SCRIPT_PATH_INVALID", "Chat code script path cannot be prepared safely"
            );
        }
        try {
            Files.writeString(
                script, plan.script(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE
            );
            makeReadableBySandbox(script);
        } catch (IOException exception) {
            throw new WorkspacePolicyException(
                "WORKSPACE_IO_ERROR", "Chat code script cannot be prepared"
            );
        }
    }

    /**
     * 获取任务工具。
     *
     * @param job 作业参数
     * @return 处理结果
     */
    private Path resolveTaskTool(ClaimedJob job) {
        if (job.taskId() == null || job.taskId() <= 0 || job.runId() == null || job.runId() <= 0) {
            throw new WorkspacePolicyException("WORKSPACE_ID_INVALID", "Task or run ID is invalid");
        }
        try {
            Path runRoot = createChecked(root, "task-" + job.taskId());
            runRoot = createChecked(runRoot, "run-" + job.runId());
            return resolveRelative(runRoot, job);
        } catch (IOException exception) {
            throw new WorkspacePolicyException("WORKSPACE_IO_ERROR", "Workspace cannot be prepared");
        }
    }

    /**
     * 获取Bound工作空间。
     *
     * @param job 作业参数
     * @return 处理结果
     */
    private Path resolveBoundWorkspace(ClaimedJob job) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        String workspaceKey = job.workspaceKey().strip();
        if (!WORKSPACE_KEY.matcher(workspaceKey).matches()
            || ".".equals(workspaceKey) || "..".equals(workspaceKey)) {
            throw new WorkspacePolicyException("WORKSPACE_KEY_INVALID", "Workspace key is invalid");
        }
        try {
            Path workspace = createChecked(root, workspaceKey);
            if (ChatCodePolicy.isChatCode(job)) {
                Path rootReal = root.toRealPath();
                Path result = workspace.toRealPath();
                if (!result.startsWith(rootReal)) {
                    throw new WorkspacePolicyException(
                        "WORKSPACE_SYMLINK_ESCAPE", "Workspace escaped its root"
                    );
                }
                if ("read_write".equals(job.workspaceAccess())) {
                    makeWritableBySandbox(result);
                }
                return result;
            }
            return resolveRelative(workspace, job);
        } catch (IOException exception) {
            throw new WorkspacePolicyException("WORKSPACE_IO_ERROR", "Workspace cannot be prepared");
        }
    }

    /**
     * 获取{@code Relative}。
     *
     * @param workspace 工作空间参数
     * @param job 作业参数
     * @return 处理结果
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private Path resolveRelative(Path workspace, ClaimedJob job) throws IOException {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        String relativeValue = job.workspacePath() == null ? "." : job.workspacePath();
        if (relativeValue.length() > 512 || relativeValue.indexOf('\\') >= 0
            || relativeValue.indexOf(':') >= 0) {
            throw new WorkspacePolicyException("WORKSPACE_PATH_INVALID", "Workspace path is invalid");
        }
        try {
            Path relative = Path.of(relativeValue);
            if (relative.isAbsolute() || relative.normalize().startsWith("..")) {
                throw new WorkspacePolicyException(
                    "WORKSPACE_PATH_ESCAPE", "Workspace path escapes its bound workspace"
                );
            }
            Path current = workspace;
            for (Path part : relative.normalize()) {
                current = createChecked(current, part.toString());
            }
            Path rootReal = root.toRealPath();
            Path result = current.toRealPath();
            if (!result.startsWith(rootReal)) {
                throw new WorkspacePolicyException("WORKSPACE_SYMLINK_ESCAPE", "Workspace escaped its root");
            }
            if ("read_write".equals(job.workspaceAccess())) {
                makeWritableBySandbox(result);
            }
            return result;
        } catch (InvalidPathException exception) {
            throw new WorkspacePolicyException("WORKSPACE_PATH_INVALID", "Workspace path is invalid");
        }
    }

    /**
     * 获取对话Code。
     *
     * @param job 作业参数
     * @return 处理结果
     */
    private Path resolveChatCode(ClaimedJob job) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (job.jobId() == null || job.jobId() <= 0
            || job.ownerUserId() == null || job.ownerUserId() <= 0
            || job.conversationId() == null || job.conversationId() <= 0
            || job.attemptNo() == null || job.attemptNo() <= 0) {
            throw new WorkspacePolicyException(
                "WORKSPACE_ID_INVALID", "Chat code workspace identity is invalid"
            );
        }
        try {
            Path workspace = createChecked(root, "chat-code");
            workspace = createChecked(workspace, "user-" + job.ownerUserId());
            workspace = createChecked(workspace, "conversation-" + job.conversationId());
            workspace = createChecked(workspace, "job-" + job.jobId());
            workspace = createChecked(workspace, "attempt-" + job.attemptNo());
            Path rootReal = root.toRealPath();
            Path result = workspace.toRealPath();
            if (!result.startsWith(rootReal)) {
                throw new WorkspacePolicyException(
                    "WORKSPACE_SYMLINK_ESCAPE", "Workspace escaped its root"
                );
            }
            if ("read_write".equals(job.workspaceAccess())) {
                makeWritableBySandbox(result);
            }
            return result;
        } catch (IOException exception) {
            throw new WorkspacePolicyException("WORKSPACE_IO_ERROR", "Workspace cannot be prepared");
        }
    }

    /**
     * 处理makeWritableBy沙箱相关逻辑。
     *
     * @param path {@code path}参数
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private void makeWritableBySandbox(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE
            ));
        } catch (UnsupportedOperationException ignored) {
            // Docker/Podman deployment is POSIX; non-POSIX unit-test filesystems use mount ACLs.
        }
    }

    /**
     * 处理makeReadableBy沙箱相关逻辑。
     *
     * @param path {@code path}参数
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private void makeReadableBySandbox(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ
            ));
        } catch (UnsupportedOperationException ignored) {
            // Container deployments are POSIX; other filesystems rely on their mount ACLs.
        }
    }

    /**
     * 创建并保存{@code Checked}。
     *
     * @param parent {@code parent}参数
     * @param child {@code child}参数
     * @return 处理结果
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private Path createChecked(Path parent, String child) throws IOException {
        Path candidate = parent.resolve(child).normalize();
        if (!candidate.startsWith(root)) {
            throw new WorkspacePolicyException("WORKSPACE_PATH_ESCAPE", "Workspace path escapes its root");
        }
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(candidate)) {
            throw new WorkspacePolicyException("WORKSPACE_SYMLINK_ESCAPE", "Workspace contains a symbolic link");
        }
        Files.createDirectories(candidate);
        if (Files.isSymbolicLink(candidate)) {
            throw new WorkspacePolicyException("WORKSPACE_SYMLINK_ESCAPE", "Workspace contains a symbolic link");
        }
        return candidate;
    }

    /**
     * 表示工作空间策略处理过程中发生的业务异常。
     */
    public static final class WorkspacePolicyException extends RuntimeException {
        private final String code;

        /**
         * 创建 {@code WorkspacePolicyException} 实例并初始化所需依赖。
         *
         * @param code {@code code}参数
         * @param message 待处理内容
         */
        public WorkspacePolicyException(String code, String message) {
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
