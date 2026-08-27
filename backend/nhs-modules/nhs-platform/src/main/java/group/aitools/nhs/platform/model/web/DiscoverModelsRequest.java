package group.aitools.nhs.platform.model.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 封装{@code DiscoverModels}相关的不可变数据。
 * Discovers provider model IDs using the manually entered API key. */
public record DiscoverModelsRequest(
    @Positive Long existingModelId,
    @NotBlank @Pattern(regexp = "openai|openai-compatible") String providerType,
    @Size(max = 512) String endpointUrl,
    @Size(max = 8192) String apiKey
) {

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的模型发现字段：" + field);
    }
}
