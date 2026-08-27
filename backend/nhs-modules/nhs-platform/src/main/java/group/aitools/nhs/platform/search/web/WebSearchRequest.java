package group.aitools.nhs.platform.search.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 封装{@code WebSearch}相关的不可变数据。
 */
public record WebSearchRequest(
    @Positive Long connectorId,
    @NotBlank @Size(max = 2000) String query,
    @Min(1) @Max(20) Integer maxResults
) {
}
