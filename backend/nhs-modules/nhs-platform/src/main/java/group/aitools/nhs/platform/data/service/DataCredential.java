package group.aitools.nhs.platform.data.service;

/** Resolved only at the JDBC boundary and never persisted or returned. */
public record DataCredential(String username, String password) {
}
