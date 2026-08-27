package group.aitools.nhs.platform.openapi.web;

/**
 * 封装Open接口相关的不可变数据。
 */
public record OpenApiResponse<T>(String requestId, T data) {
}
