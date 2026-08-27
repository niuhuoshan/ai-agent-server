package group.aitools.nhs.runtime.agentscope;

import group.aitools.nhs.runtime.spi.AgentRunRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 负责智能体范围工作空间相关的转换、解析或处理逻辑。
 * Resolves opaque workspace keys below one configured root and rejects path-shaped identifiers. */
public final class AgentScopeWorkspaceResolver {

    private static final Pattern WORKSPACE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final Path root;

    public AgentScopeWorkspaceResolver(Path root) {
        Objects.requireNonNull(root, "workspace root must not be null");
        try {
            Files.createDirectories(root);
            this.root = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new IllegalStateException("unable to initialize AgentScope workspace root", exception);
        }
        if (Files.isSymbolicLink(this.root)) {
            throw new IllegalStateException("AgentScope workspace root must not be a symbolic link");
        }
    }

    /**
     * 获取{@code resolve}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    public Path resolve(AgentRunRequest request) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Objects.requireNonNull(request, "request must not be null");
        String workspaceKey = request.workspaceKey();
        if (workspaceKey == null) {
            workspaceKey = request.runId() != null
                ? "run-" + request.runId()
                : "conversation-" + request.conversationId();
        }
        if (!WORKSPACE_KEY.matcher(workspaceKey).matches()
            || ".".equals(workspaceKey)
            || "..".equals(workspaceKey)) {
            throw new IllegalArgumentException("workspaceKey must be an opaque identifier");
        }
        Path workspace = root.resolve(workspaceKey).normalize();
        if (!workspace.startsWith(root)) {
            throw new SecurityException("workspace resolves outside the configured root");
        }
        try {
            if (Files.isSymbolicLink(workspace)) {
                throw new SecurityException("workspace must not be a symbolic link");
            }
            Files.createDirectories(workspace);
            return workspace.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new IllegalStateException("unable to initialize execution workspace", exception);
        }
    }

    /**
     * 处理{@code root}并返回对应结果。
     *
     * @return 处理结果
     */
    Path root() {
        return root;
    }
}
