package group.aitools.nhs.platform.sandbox.web;

/**
 * 封装对话Code执行相关的不可变数据。
 */
public record ChatCodeExecutionRequest(
    String language,
    String code,
    String conversation_id,
    String execution_id,
    String workspace_key,
    String skill_manifest_json
) {

    /**
 * 创建 {@code ChatCodeExecutionRequest} 实例并初始化所需依赖。
 * Backwards-compatible request shape used by the public Nhs endpoint and old callers. */
    public ChatCodeExecutionRequest(
        String language,
        String code,
        String conversation_id,
        String execution_id
    ) {
        this(language, code, conversation_id, execution_id, null, "[]");
    }
}
