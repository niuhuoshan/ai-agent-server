package group.aitools.nhs.platform.operations.web;

/**
 * 封装系统运行时Metrics相关的不可变数据。
 * Safe JVM metrics intended for the private-deployment operator console. */
public record SystemRuntimeMetricsView(
    String javaVersion,
    String vmName,
    int availableProcessors,
    long uptimeSeconds,
    long heapUsedBytes,
    long heapCommittedBytes,
    long heapMaxBytes,
    int liveThreads,
    Double systemLoadAverage
) {
}
