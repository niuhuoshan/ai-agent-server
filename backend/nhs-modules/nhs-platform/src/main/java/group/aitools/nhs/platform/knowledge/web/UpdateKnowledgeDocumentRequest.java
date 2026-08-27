package group.aitools.nhs.platform.knowledge.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 封装Update知识库文档操作的请求参数。
 * Partial document catalog update. Explicit null directoryId means the root. */
public final class UpdateKnowledgeDocumentRequest {

    @NotNull
    @Positive
    private Long expectedRevision;
    @Size(max = 255)
    private String name;
    @Positive
    private Long directoryId;
    private List<String> tags;
    @Size(max = 4000)
    private String remark;
    private boolean namePresent;
    private boolean directoryPresent;
    private boolean tagsPresent;
    private boolean remarkPresent;

    public UpdateKnowledgeDocumentRequest() {
    }

    /**
     * 创建 {@code UpdateKnowledgeDocumentRequest} 实例并初始化所需依赖。
     *
     * @param expectedRevision {@code expectedRevision}参数
     * @param name 名称
     * @param directoryId 资源标识
     * @param tags {@code tags}参数
     * @param remark {@code remark}参数
     */
    public UpdateKnowledgeDocumentRequest(
        Long expectedRevision,
        String name,
        Long directoryId,
        List<String> tags,
        String remark
    ) {
        this.expectedRevision = expectedRevision;
        this.name = name;
        this.directoryId = directoryId;
        this.tags = tags;
        this.remark = remark;
        // The convenience constructor represents a complete patch payload; null values are
        // therefore explicit clears (including moving a document back to the root directory).
        this.namePresent = true;
        this.directoryPresent = true;
        this.tagsPresent = true;
        this.remarkPresent = true;
    }

    /**
     * 处理{@code expectedRevision}并返回对应结果。
     *
     * @return 处理结果
     */
    public Long expectedRevision() {
        return expectedRevision;
    }

    /**
     * 处理{@code name}并返回对应结果。
     *
     * @return 处理结果
     */
    public String name() {
        return name;
    }

    /**
     * 处理目录Id并返回对应结果。
     *
     * @return 处理结果
     */
    public Long directoryId() {
        return directoryId;
    }

    /**
     * 处理{@code tags}并返回对应结果。
     *
     * @return 符合条件的数据集合
     */
    public List<String> tags() {
        return tags;
    }

    /**
     * 处理{@code remark}并返回对应结果。
     *
     * @return 处理结果
     */
    public String remark() {
        return remark;
    }

    /**
     * 设置{@code ExpectedRevision}。
     *
     * @param expectedRevision {@code expectedRevision}参数
     */
    @JsonProperty("expectedRevision")
    public void setExpectedRevision(Long expectedRevision) {
        this.expectedRevision = expectedRevision;
    }

    /**
     * 设置{@code Name}。
     *
     * @param name 名称
     */
    @JsonProperty("name")
    public void setName(String name) {
        this.name = name;
        this.namePresent = true;
    }

    /**
     * 设置目录Id。
     *
     * @param directoryId 资源标识
     */
    @JsonProperty("directoryId")
    public void setDirectoryId(Long directoryId) {
        this.directoryId = directoryId;
        this.directoryPresent = true;
    }

    /**
     * 设置{@code Tags}。
     *
     * @param tags {@code tags}参数
     */
    @JsonProperty("tags")
    public void setTags(List<String> tags) {
        this.tags = tags;
        this.tagsPresent = true;
    }

    /**
     * 设置{@code Remark}。
     *
     * @param remark {@code remark}参数
     */
    @JsonProperty("remark")
    public void setRemark(String remark) {
        this.remark = remark;
        this.remarkPresent = true;
    }

    /**
     * 处理{@code namePresent}并返回对应结果。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    @JsonIgnore
    public boolean namePresent() {
        return namePresent;
    }

    /**
     * 处理目录Present并返回对应结果。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    @JsonIgnore
    public boolean directoryPresent() {
        return directoryPresent;
    }

    /**
     * 处理{@code tagsPresent}并返回对应结果。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    @JsonIgnore
    public boolean tagsPresent() {
        return tagsPresent;
    }

    /**
     * 处理{@code remarkPresent}并返回对应结果。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    @JsonIgnore
    public boolean remarkPresent() {
        return remarkPresent;
    }

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的知识文档字段：" + field);
    }
}
