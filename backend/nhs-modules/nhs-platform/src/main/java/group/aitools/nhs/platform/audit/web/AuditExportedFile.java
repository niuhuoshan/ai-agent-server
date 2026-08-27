package group.aitools.nhs.platform.audit.web;

/**
 * 封装审计Exported文件相关的不可变数据。
 */
public record AuditExportedFile(String fileName, String mediaType, byte[] content) {
}
