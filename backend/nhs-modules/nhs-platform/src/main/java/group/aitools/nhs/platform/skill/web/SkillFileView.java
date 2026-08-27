package group.aitools.nhs.platform.skill.web;

import group.aitools.nhs.platform.skill.domain.AgentSkillFile;

import java.time.LocalDateTime;
import java.util.Base64;

/**
 * 封装技能文件相关的不可变数据。
 */
public record SkillFileView(
    Long id,
    Long skillId,
    Long versionId,
    String path,
    String fileKind,
    String content,
    boolean binary,
    String contentBase64,
    String contentHash,
    Integer sizeBytes,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param file 文件参数
     * @param includeContent 待处理内容
     * @return 处理结果
     */
    public static SkillFileView from(AgentSkillFile file, boolean includeContent) {
        return new SkillFileView(
            file.getId(), file.getSkillId(), file.getVersionId(), file.getPath(), file.getFileKind(),
            includeContent ? file.getContent() : null,
            "binary".equals(file.getContentEncoding()),
            includeContent && file.getContentBytes() != null
                ? Base64.getEncoder().encodeToString(file.getContentBytes()) : null,
            file.getContentHash(), file.getSizeBytes(),
            file.getCreateTime(), file.getUpdateTime()
        );
    }
}
