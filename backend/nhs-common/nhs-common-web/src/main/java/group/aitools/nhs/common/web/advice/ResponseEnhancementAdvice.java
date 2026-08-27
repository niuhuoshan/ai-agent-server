package group.aitools.nhs.common.web.advice;

import lombok.RequiredArgsConstructor;
import group.aitools.nhs.common.json.enhance.JsonValueEnhancer;
import org.jspecify.annotations.NonNull;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 响应体统一增强拦截器。
 */
@RestControllerAdvice
@RequiredArgsConstructor
public class ResponseEnhancementAdvice implements ResponseBodyAdvice<Object> {

    private final JsonValueEnhancer jsonValueEnhancer;

    @Override
    public boolean supports(@NonNull MethodParameter returnType,
                            @NonNull Class<? extends HttpMessageConverter<?>> converterType) {
        return jsonValueEnhancer.supports(converterType);
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
    public Object beforeBodyWrite(Object body,
                                  @NonNull MethodParameter returnType,
                                  @NonNull MediaType selectedContentType,
                                  @NonNull Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  @NonNull ServerHttpRequest request,
                                  @NonNull ServerHttpResponse response) {
        if (!selectedContentType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
            return body;
        }
        return jsonValueEnhancer.enhance(body);
    }

}
