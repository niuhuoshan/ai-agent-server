package group.aitools.nhs.platform.task.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 封装Put任务Participant相关的不可变数据。
 * Creates one non-owner task operation relation. */
public record PutTaskParticipantRequest(
    @NotBlank @Pattern(regexp = "assignee|collaborator|acceptor|watcher") String type
) {

    /**
     * 处理{@code rejectUnknownField}相关逻辑。
     *
     * @param field {@code field}参数
     * @param ignored {@code ignored}参数
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("不支持的任务参与人字段：" + field);
    }
}
