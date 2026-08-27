package group.aitools.nhs.runtime.agentscope.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 承载智能体范围运行时相关的外部化配置属性。
 */
@ConfigurationProperties(prefix = "agent.runtime.agentscope")
public class AgentScopeRuntimeProperties {

    private boolean enabled;
    private Path workspaceRoot = Path.of("./data/agent-workspaces");
    private int maxWorkspaceFileSizeMb = 10;
    private boolean allowInsecureModelEndpoints;
    private String stateSchema = "agentscope";
    private String stateTable = "agentscope_sessions";

    /**
     * 判断{@code Enabled}是否满足要求。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置{@code Enabled}。
     *
     * @param enabled {@code enabled}参数
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 获取工作空间Root。
     *
     * @return 处理结果
     */
    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    /**
     * 设置工作空间Root。
     *
     * @param workspaceRoot 工作空间Root参数
     */
    public void setWorkspaceRoot(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    /**
     * 获取Max工作空间文件SizeMb。
     *
     * @return 处理结果
     */
    public int getMaxWorkspaceFileSizeMb() {
        return maxWorkspaceFileSizeMb;
    }

    /**
     * 设置Max工作空间文件SizeMb。
     *
     * @param maxWorkspaceFileSizeMb 数量上限
     */
    public void setMaxWorkspaceFileSizeMb(int maxWorkspaceFileSizeMb) {
        this.maxWorkspaceFileSizeMb = maxWorkspaceFileSizeMb;
    }

    /**
     * 判断AllowInsecure模型Endpoints是否满足要求。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    public boolean isAllowInsecureModelEndpoints() {
        return allowInsecureModelEndpoints;
    }

    /**
     * 设置AllowInsecure模型Endpoints。
     *
     * @param allowInsecureModelEndpoints allowInsecure模型Endpoints参数
     */
    public void setAllowInsecureModelEndpoints(boolean allowInsecureModelEndpoints) {
        this.allowInsecureModelEndpoints = allowInsecureModelEndpoints;
    }

    /**
     * 获取{@code StateSchema}。
     *
     * @return 处理结果
     */
    public String getStateSchema() {
        return stateSchema;
    }

    /**
     * 设置{@code StateSchema}。
     *
     * @param stateSchema {@code stateSchema}参数
     */
    public void setStateSchema(String stateSchema) {
        this.stateSchema = stateSchema;
    }

    /**
     * 获取{@code StateTable}。
     *
     * @return 处理结果
     */
    public String getStateTable() {
        return stateTable;
    }

    /**
     * 设置{@code StateTable}。
     *
     * @param stateTable {@code stateTable}参数
     */
    public void setStateTable(String stateTable) {
        this.stateTable = stateTable;
    }
}
