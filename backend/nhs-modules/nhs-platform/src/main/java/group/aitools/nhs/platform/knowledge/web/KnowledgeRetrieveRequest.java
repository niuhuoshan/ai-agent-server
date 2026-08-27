package group.aitools.nhs.platform.knowledge.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 封装知识库Retrieve相关的不可变数据。
 */
public record KnowledgeRetrieveRequest(
    @NotEmpty @Size(max = 10) List<@Positive Long> knowledgeBaseIds,
    @NotBlank @Size(max = 4000) String query,
    @Positive Integer topK,
    @DecimalMin("0.0") @DecimalMax("1.0") Double similarityThreshold,
    @DecimalMin("0.0") @DecimalMax("1.0") Double vectorWeight
) {
}
