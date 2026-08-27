package group.aitools.nhs.platform.embed.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 封装Create嵌入式会话Widget会话回合相关的不可变数据。
 */
public record CreateEmbedWidgetTurnRequest(
    @NotBlank @Size(max = 128) String idempotencyKey,
    @NotBlank @Size(max = 65536) String input,
    @Size(max = 5) List<@Positive Long> attachmentIds,
    Map<String, Object> context
) {
    /**
     * 创建 {@code CreateEmbedWidgetTurnRequest} 实例并初始化所需依赖。
     *
     * @param idempotencyKey {@code idempotencyKey}参数
     * @param input {@code input}参数
     * @param attachmentIds 资源标识集合
     * @param context 待处理内容
     */
    public CreateEmbedWidgetTurnRequest {
        attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
        context = context == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(context));
    }

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的Embed消息字段：" + field);
    }
}
