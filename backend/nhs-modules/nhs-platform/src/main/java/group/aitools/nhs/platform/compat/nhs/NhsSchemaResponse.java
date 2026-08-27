package group.aitools.nhs.platform.compat.nhs;

import java.util.List;

/**
 * 封装{@code NhsSchema}相关的不可变数据。
 * Nhs V1 schema response backed by the local platform metadata catalog. */
public record NhsSchemaResponse(
    String schema_context,
    List<NhsSchemaHit> hits,
    String provider,
    List<String> logs,
    List<String> unsupported
) {
}
