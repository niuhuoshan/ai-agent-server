package group.aitools.nhs.platform.compat.nhs;

/**
 * 封装{@code NhsSchemaHit}相关的不可变数据。
 * Dataset hit in the Nhs V1 schema response. */
public record NhsSchemaHit(
    Long id,
    String name,
    String display_name
) {
}
