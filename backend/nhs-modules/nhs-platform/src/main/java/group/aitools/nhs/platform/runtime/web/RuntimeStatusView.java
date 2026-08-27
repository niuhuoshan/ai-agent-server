package group.aitools.nhs.platform.runtime.web;

/**
 * 封装运行时Status相关的不可变数据。
 * Runtime readiness exposed without leaking model credentials or configuration values. */
public record RuntimeStatusView(
    String runtimeType,
    boolean available,
    String state,
    String message
) {
}
