package group.aitools.nhs.sandbox.runner.execution;

import group.aitools.nhs.sandbox.runner.client.SandboxProtocol.ClaimedJob;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 表示对话Code策略相关的领域对象。
 */
final class ChatCodePolicy {

    static final String TASK_TOOL = "task_tool";
    static final String CHAT_CODE = "chat_code";
    private static final int MAX_SCRIPT_BYTES = 1024 * 1024;

    /**
     * 创建 {@code ChatCodePolicy} 实例并初始化所需依赖。
     */
    private ChatCodePolicy() {
    }

    /**
     * 处理数据源Type并返回对应结果。
     *
     * @param job 作业参数
     * @return 处理结果
     */
    static String sourceType(ClaimedJob job) {
        String value = job.sourceType();
        if (value == null || value.isBlank()) {
            return TASK_TOOL;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!TASK_TOOL.equals(normalized) && !CHAT_CODE.equals(normalized)) {
            throw new ChatCodePolicyException(
                "SOURCE_TYPE_INVALID", "Sandbox job source type is invalid"
            );
        }
        return normalized;
    }

    /**
     * 判断对话Code是否满足要求。
     *
     * @param job 作业参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    static boolean isChatCode(ClaimedJob job) {
        return CHAT_CODE.equals(sourceType(job));
    }

    /**
     * 处理{@code scriptPlan}并返回对应结果。
     *
     * @param job 作业参数
     * @return 处理结果
     */
    static ScriptPlan scriptPlan(ClaimedJob job) {
        // 以下流程根据当前类型或状态选择处理分支，并保证每种分支返回明确结果。
        if (!isChatCode(job)) {
            throw new ChatCodePolicyException(
                "SOURCE_TYPE_INVALID", "Task tool jobs do not carry inline scripts"
            );
        }
        String language = job.scriptLanguage() == null
            ? "" : job.scriptLanguage().strip().toLowerCase(Locale.ROOT);
        String fileName;
        List<String> argv;
        switch (language) {
            case "python" -> {
                fileName = fileName(job, ".py");
                argv = List.of("python", "/workspace/" + fileName);
            }
            case "shell", "sh" -> {
                fileName = fileName(job, ".sh");
                argv = List.of("/bin/sh", "/workspace/" + fileName);
            }
            case "bash" -> {
                fileName = fileName(job, ".sh");
                argv = List.of("/bin/bash", "/workspace/" + fileName);
            }
            default -> throw new ChatCodePolicyException(
                "SCRIPT_LANGUAGE_INVALID",
                "Chat code language must be python, shell, bash, or sh"
            );
        }
        String script = job.scriptText();
        if (script == null || script.indexOf('\0') >= 0
            || script.getBytes(StandardCharsets.UTF_8).length > MAX_SCRIPT_BYTES) {
            throw new ChatCodePolicyException(
                "SCRIPT_INVALID", "Chat code script is invalid or exceeds the 1 MiB limit"
            );
        }
        return new ScriptPlan(fileName, argv, script);
    }

    /**
     * 处理文件Name并返回对应结果。
     *
     * @param job 作业参数
     * @param extension {@code extension}参数
     * @return 处理结果
     */
    private static String fileName(ClaimedJob job, String extension) {
        if (job.workspaceKey() == null || job.workspaceKey().isBlank()) {
            return ".agent-chat-code" + extension;
        }
        if (job.jobId() == null || job.jobId() <= 0
            || job.attemptNo() == null || job.attemptNo() <= 0) {
            throw new ChatCodePolicyException(
                "WORKSPACE_ID_INVALID", "Shared chat code workspace identity is invalid"
            );
        }
        return ".agent-chat-code-" + job.jobId() + "-" + job.attemptNo() + extension;
    }

    /**
     * 封装{@code ScriptPlan}相关的不可变数据。
     */
    record ScriptPlan(String fileName, List<String> argv, String script) {
    }

    /**
     * 表示对话Code策略处理过程中发生的业务异常。
     */
    static final class ChatCodePolicyException extends RuntimeException {
        private final String code;

        /**
         * 创建 {@code ChatCodePolicyException} 实例并初始化所需依赖。
         *
         * @param code {@code code}参数
         * @param message 待处理内容
         */
        ChatCodePolicyException(String code, String message) {
            super(message);
            this.code = code;
        }

        /**
         * 处理{@code code}并返回对应结果。
         *
         * @return 处理结果
         */
        String code() {
            return code;
        }
    }
}
