package group.aitools.nhs.platform.sandbox.web;

/**
 * 封装Append沙箱作业Output相关的不可变数据。
 */
public record AppendSandboxJobOutputRequest(
    Long sequenceNo,
    String stream,
    String content
) {
}
