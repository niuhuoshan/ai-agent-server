package group.aitools.nhs.platform.notification.service;

import group.aitools.nhs.platform.iam.domain.PrincipalType;

import java.util.Objects;

/**
 * 封装通知Recipient相关的不可变数据。
 * Typed recipient prevents service-account IDs from entering the human inbox. */
public record NotificationRecipient(Long id, PrincipalType type) {

    public NotificationRecipient {
        Objects.requireNonNull(id, "recipient id must not be null");
        Objects.requireNonNull(type, "recipient type must not be null");
        if (id <= 0) {
            throw new IllegalArgumentException("recipient id must be positive");
        }
    }
}
