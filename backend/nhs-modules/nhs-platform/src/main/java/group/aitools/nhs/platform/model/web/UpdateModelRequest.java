package group.aitools.nhs.platform.model.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 封装Update模型相关的不可变数据。
 * Full model update; a blank API key keeps the currently saved key. */
public record UpdateModelRequest(
    @NotBlank @Size(max = 128) String displayName,
    @NotBlank @Pattern(regexp = "openai|openai-compatible") String providerType,
    @NotBlank @Size(max = 255) String modelName,
    @NotBlank @Pattern(regexp = "chat|embedding|multimodal|rerank") String modelType,
    @Size(max = 512) String endpointUrl,
    @Size(max = 8192) String apiKey,
    @Min(1) @Max(10_000_000) Integer contextSize,
    @Min(1) @Max(1_000_000) Integer maxOutputTokens,
    Map<String, Object> reasoningConfig,
    Map<String, Object> capabilities,
    @NotBlank @Pattern(regexp = "active|disabled") String status
) {

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的模型配置字段：" + field);
    }
}
