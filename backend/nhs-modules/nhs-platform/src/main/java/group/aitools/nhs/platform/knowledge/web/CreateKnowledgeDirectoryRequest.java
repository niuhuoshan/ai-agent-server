package group.aitools.nhs.platform.knowledge.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 封装Create知识库目录相关的不可变数据。
 * Creates a logical directory; a null parent means the knowledge-base root. */
public record CreateKnowledgeDirectoryRequest(
    @jakarta.validation.constraints.NotBlank @Size(max = 255) String name,
    @Positive Long parentId
) {
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
