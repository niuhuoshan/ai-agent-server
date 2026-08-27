package group.aitools.nhs.platform.compat.nhs;

/**
 * 封装{@code NhsSchema}相关的不可变数据。
 * Request fields accepted by Nhs's V1 schema gateway. */
public record NhsSchemaRequest(
    String query,
    String metadata_provider,
    Integer ragflow_metadata_top_k,
    Double ragflow_similarity_threshold,
    Double ragflow_vector_weight
) {
}
