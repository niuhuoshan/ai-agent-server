package group.aitools.nhs.platform.nhs.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 表示{@code NhsV1ResponseBodyAdvice}相关的领域对象。
 * Converts platform envelopes to the Nhs StandardResponse wire contract. */
@RestControllerAdvice(basePackageClasses = NhsV1CompatibilityController.class)
public class NhsV1ResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    private final Clock clock;

    @Autowired
    public NhsV1ResponseBodyAdvice() {
        this(Clock.systemDefaultZone());
    }

    /**
     * 创建 {@code NhsV1ResponseBodyAdvice} 实例并初始化所需依赖。
     *
     * @param clock {@code clock}参数
     */
    NhsV1ResponseBodyAdvice(Clock clock) {
        this.clock = clock;
    }

    /**
     * 判断{@code supports}是否满足要求。
     *
     * @param returnType 业务类型
     * @param converterType 业务类型
     * @return 判断结果，{@code true} 表示条件成立
     */
    @Override
    public boolean supports(
        MethodParameter returnType,
        Class<? extends HttpMessageConverter<?>> converterType
    ) {
        return true;
    }

    /**
     * 处理{@code beforeBodyWrite}并返回对应结果。
     *
     * @param body {@code body}参数
     * @param returnType 业务类型
     * @param selectedContentType 业务类型
     * @param selectedConverterType 业务类型
     * @param request 请求参数
     * @param response {@code response}参数
     * @return 处理结果
     */
    @Override
    public Object beforeBodyWrite(
        Object body,
        MethodParameter returnType,
        MediaType selectedContentType,
        Class<? extends HttpMessageConverter<?>> selectedConverterType,
        ServerHttpRequest request,
        ServerHttpResponse response
    ) {
        if (!(body instanceof R<?> envelope)) {
            return body;
        }
        String message = envelope.getCode() == HttpStatus.SUCCESS
            ? "success" : envelope.getMsg();
        return new StandardResponse<>(
            envelope.getCode(),
            message,
            envelope.getData(),
            LocalDateTime.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            null,
            null
        );
    }

    /**
     * 封装{@code Standard}相关的不可变数据。
     */
    record StandardResponse<T>(
        int code,
        String message,
        T data,
        String timestamp,
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("execution_mode") String executionMode
    ) {
    }
}
