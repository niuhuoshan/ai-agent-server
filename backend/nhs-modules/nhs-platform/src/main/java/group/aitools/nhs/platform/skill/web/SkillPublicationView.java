package group.aitools.nhs.platform.skill.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 封装技能Publication相关的不可变数据。
 * Nhs-compatible personal Skill publication summary and review detail. */
public record SkillPublicationView(
    @JsonProperty("publication_id") Long publicationId,
    @JsonProperty("version_id") Long versionId,
    @JsonProperty("skill_id") Long skillId,
    @JsonProperty("platform_skill_id") Long platformSkillId,
    String name,
    String description,
    @JsonProperty("publication_status") String publicationStatus,
    @JsonProperty("version_number") Integer versionNumber,
    @JsonProperty("version_status") String versionStatus,
    @JsonProperty("current_public_version") Integer currentPublicVersion,
    @JsonProperty("pending_version") Integer pendingVersion,
    @JsonProperty("last_review_comment") String lastReviewComment,
    @JsonProperty("content_sha256") String contentSha256,
    @JsonProperty("file_count") Integer fileCount,
    @JsonProperty("total_size") Long totalSize,
    @JsonProperty("submitted_by") Long submittedBy,
    @JsonProperty("submitted_at") LocalDateTime submittedAt,
    @JsonProperty("reviewed_by") Long reviewedBy,
    @JsonProperty("reviewed_at") LocalDateTime reviewedAt,
    @JsonProperty("review_comment") String reviewComment,
    @JsonProperty("withdrawn_by") Long withdrawnBy,
    @JsonProperty("withdrawn_at") LocalDateTime withdrawnAt,
    @JsonProperty("skill_md_content") String skillMarkdownContent,
    @JsonProperty("file_tree") List<SkillPublicationFileNode> fileTree
) {
}
