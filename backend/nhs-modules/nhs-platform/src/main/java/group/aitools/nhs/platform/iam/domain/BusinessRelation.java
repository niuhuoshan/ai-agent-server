package group.aitools.nhs.platform.iam.domain;

/**
 * 定义{@code BusinessRelation}相关的可选值。
 * Object-level relations; these are not global platform roles. */
public enum BusinessRelation {
    OWNER,
    PROJECT_ADMIN,
    ASSIGNEE,
    COLLABORATOR,
    ACCEPTOR,
    WATCHER
}
