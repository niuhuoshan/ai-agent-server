package group.aitools.nhs.sandbox.runner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 承载沙箱Runner相关的外部化配置属性。
 */
@ConfigurationProperties(prefix = "agent.sandbox.runner")
public class SandboxRunnerProperties {

    private String platformBaseUrl = "http://127.0.0.1:8080";
    private String runnerKey = "local-runner";
    private String runnerName = "Local Sandbox Runner";
    private String runnerVersion = "1.0.0";
    private String bootstrapToken = "";
    private Path credentialFile = Path.of("./data/sandbox-runner/credential");
    private String engine = "podman";
    private Path workspaceRoot = Path.of("./data/agent-workspaces");
    /** Retained for configuration compatibility; Skills are fetched through the platform API. */
    private Path skillSourceRoot = Path.of("./data/agent-workspaces");
    /**
 * 获取平台BaseUrl。
 * Job-scoped staging stays below workspaceRoot so nested Docker sees the same host path. */
    private Path skillStagingRoot;
    private int maxConcurrency = 1;
    private long pollIntervalMs = 1000;
    private int heartbeatIntervalSeconds = 15;
    private Map<String, String> templates = new LinkedHashMap<>();

    public String getPlatformBaseUrl() {
        return platformBaseUrl;
    }

    /**
     * 设置平台BaseUrl。
     *
     * @param platformBaseUrl 平台BaseUrl参数
     */
    public void setPlatformBaseUrl(String platformBaseUrl) {
        this.platformBaseUrl = platformBaseUrl;
    }

    /**
     * 获取{@code RunnerKey}。
     *
     * @return 处理结果
     */
    public String getRunnerKey() {
        return runnerKey;
    }

    /**
     * 设置{@code RunnerKey}。
     *
     * @param runnerKey {@code runnerKey}参数
     */
    public void setRunnerKey(String runnerKey) {
        this.runnerKey = runnerKey;
    }

    /**
     * 获取{@code RunnerName}。
     *
     * @return 处理结果
     */
    public String getRunnerName() {
        return runnerName;
    }

    /**
     * 设置{@code RunnerName}。
     *
     * @param runnerName 名称
     */
    public void setRunnerName(String runnerName) {
        this.runnerName = runnerName;
    }

    /**
     * 获取Runner版本。
     *
     * @return 处理结果
     */
    public String getRunnerVersion() {
        return runnerVersion;
    }

    /**
     * 设置Runner版本。
     *
     * @param runnerVersion runner版本参数
     */
    public void setRunnerVersion(String runnerVersion) {
        this.runnerVersion = runnerVersion;
    }

    /**
     * 获取Bootstrap令牌。
     *
     * @return 处理结果
     */
    public String getBootstrapToken() {
        return bootstrapToken;
    }

    /**
     * 设置Bootstrap令牌。
     *
     * @param bootstrapToken bootstrap令牌参数
     */
    public void setBootstrapToken(String bootstrapToken) {
        this.bootstrapToken = bootstrapToken;
    }

    /**
     * 获取凭据文件。
     *
     * @return 处理结果
     */
    public Path getCredentialFile() {
        return credentialFile;
    }

    /**
     * 设置凭据文件。
     *
     * @param credentialFile 凭据文件参数
     */
    public void setCredentialFile(Path credentialFile) {
        this.credentialFile = credentialFile;
    }

    /**
     * 获取{@code Engine}。
     *
     * @return 处理结果
     */
    public String getEngine() {
        return engine;
    }

    /**
     * 设置{@code Engine}。
     *
     * @param engine {@code engine}参数
     */
    public void setEngine(String engine) {
        this.engine = engine;
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
     * 获取技能数据源Root。
     *
     * @return 处理结果
     */
    public Path getSkillSourceRoot() {
        return skillSourceRoot;
    }

    /**
     * 设置技能数据源Root。
     *
     * @param skillSourceRoot 技能数据源Root参数
     */
    public void setSkillSourceRoot(Path skillSourceRoot) {
        this.skillSourceRoot = skillSourceRoot;
    }

    /**
     * 获取技能StagingRoot。
     *
     * @return 处理结果
     */
    public Path getSkillStagingRoot() {
        return skillStagingRoot == null
            ? workspaceRoot.resolve(".skill-staging") : skillStagingRoot;
    }

    /**
     * 设置技能StagingRoot。
     *
     * @param skillStagingRoot 技能StagingRoot参数
     */
    public void setSkillStagingRoot(Path skillStagingRoot) {
        this.skillStagingRoot = skillStagingRoot;
    }

    /**
     * 获取{@code MaxConcurrency}。
     *
     * @return 处理结果
     */
    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    /**
     * 设置{@code MaxConcurrency}。
     *
     * @param maxConcurrency {@code maxConcurrency}参数
     */
    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    /**
     * 获取{@code PollIntervalMs}。
     *
     * @return 处理结果
     */
    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    /**
     * 设置{@code PollIntervalMs}。
     *
     * @param pollIntervalMs {@code pollIntervalMs}参数
     */
    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * 获取{@code HeartbeatIntervalSeconds}。
     *
     * @return 处理结果
     */
    public int getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    /**
     * 设置{@code HeartbeatIntervalSeconds}。
     *
     * @param heartbeatIntervalSeconds {@code heartbeatIntervalSeconds}参数
     */
    public void setHeartbeatIntervalSeconds(int heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
    }

    /**
     * 获取{@code Templates}。
     *
     * @return 处理结果
     */
    public Map<String, String> getTemplates() {
        return templates;
    }

    /**
     * 设置{@code Templates}。
     *
     * @param templates {@code templates}参数
     */
    public void setTemplates(Map<String, String> templates) {
        this.templates = templates;
    }
}
