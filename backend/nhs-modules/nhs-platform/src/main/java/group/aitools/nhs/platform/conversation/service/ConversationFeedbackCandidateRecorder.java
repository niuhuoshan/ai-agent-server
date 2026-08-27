package group.aitools.nhs.platform.conversation.service;

import group.aitools.nhs.platform.conversation.persistence.row.ConversationMessageRow;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;

/**
 * 处理{@code record}相关逻辑。
 *
 * 定义会话反馈CandidateRecorder相关能力的服务契约。
 * Persists a reviewable example from an owner-validated assistant-message feedback event. */
public interface ConversationFeedbackCandidateRecorder {

    void record(
        CurrentPrincipal principal,
        ConversationMessageRow assistantMessage,
        ConversationMessageRow previousUserMessage,
        String feedbackType
    );
}
