package group.aitools.nhs.platform.memory.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 封装Create记忆相关的不可变数据。
 */
public record CreateMemoryRequest(
    @NotBlank @Size(max = 128) String memoryKey,
    @NotBlank @Size(max = 24) String memoryType,
    @NotBlank @Size(max = 4000) String content,
    @NotBlank @Size(max = 24) String sourceType,
    Long sourceId,
    @DecimalMin("0.0") @DecimalMax("1.0") Double confidence,
    @Size(max = 12) String sensitiveLevel,
    LocalDateTime expiresAt,
    Map<String, Object> metadata
) {
}
