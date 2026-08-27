package group.aitools.nhs.platform.model;

import group.aitools.nhs.platform.model.service.StoredModelCredentialResolver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class StoredModelCredentialResolverTest {

    private final StoredModelCredentialResolver resolver = new StoredModelCredentialResolver();

    @Test
    void resolvesApiKeyStoredWithModelRecord() {
        assertEquals("secret", resolver.resolve("  secret  "));
    }

    @Test
    void rejectsLegacyReferencesAndInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(" "));
        assertThrows(IllegalStateException.class, () -> resolver.resolve("v1s.legacy"));
        assertThrows(IllegalStateException.class, () -> resolver.resolve("env:MODEL_KEY"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("x".repeat(8193)));
    }
}
