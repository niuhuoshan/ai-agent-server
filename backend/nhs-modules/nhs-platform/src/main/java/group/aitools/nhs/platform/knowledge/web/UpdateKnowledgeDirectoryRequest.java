package group.aitools.nhs.platform.knowledge.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 封装Update知识库目录操作的请求参数。
 * Renames and/or moves a directory with optimistic locking. */
public final class UpdateKnowledgeDirectoryRequest {

    @NotNull
    @Positive
    private Long expectedRevision;
    @Size(max = 255)
    private String name;
    @Positive
    private Long parentId;
    private boolean namePresent;
    private boolean parentPresent;

    public UpdateKnowledgeDirectoryRequest() {
    }

    /**
     * 创建 {@code UpdateKnowledgeDirectoryRequest} 实例并初始化所需依赖。
     *
     * @param expectedRevision {@code expectedRevision}参数
     * @param name 名称
     * @param parentId 资源标识
     */
    public UpdateKnowledgeDirectoryRequest(Long expectedRevision, String name, Long parentId) {
        this.expectedRevision = expectedRevision;
        this.name = name;
        this.parentId = parentId;
        // The convenience constructor represents a complete patch payload, so null explicitly
        // means "move to the root" rather than "leave the parent unchanged".
        this.namePresent = true;
        this.parentPresent = true;
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
     * 处理{@code parentId}并返回对应结果。
     *
     * @return 处理结果
     */
    public Long parentId() {
        return parentId;
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
     * 设置{@code ParentId}。
     *
     * @param parentId 资源标识
     */
    @JsonProperty("parentId")
    public void setParentId(Long parentId) {
        this.parentId = parentId;
        this.parentPresent = true;
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
     * 处理{@code parentPresent}并返回对应结果。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    @JsonIgnore
    public boolean parentPresent() {
        return parentPresent;
    }

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的知识目录字段：" + field);
    }
}
