package group.aitools.nhs.platform.compat.nhs;

/**
 * 处理{@code success}并返回对应结果。
 *
 * 封装{@code Nhs}相关的不可变数据。
 *
 * Nhs V1 success envelope. Error responses continue to use the platform's
 * global exception contract, while successful compatibility responses keep the
 * field names expected by Nhs clients.
 */
public record NhsResponse<T>(int code, String message, T data) {

    public static <T> NhsResponse<T> success(T data) {
        return new NhsResponse<>(200, "success", data);
    }
}
