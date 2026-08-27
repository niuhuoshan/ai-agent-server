package group.aitools.nhs.platform.identity;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.identity.service.CredentialSecretGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class CredentialSecretGeneratorTest {

    @Test
    void generatedSecretHasExpectedEntropyFormatAndOnlyHashIsDerived() {
        CredentialSecretGenerator generator = new CredentialSecretGenerator();

        var first = generator.generate();
        var second = generator.generate();

        assertTrue(first.rawSecret().matches("agk_[A-Za-z0-9_-]{12}\\.[A-Za-z0-9_-]{43}"));
        assertTrue(first.keyPrefix().matches("agk_[A-Za-z0-9_-]{12}"));
        assertEquals(ContentHashing.sha256(first.rawSecret()), first.secretHash());
        assertEquals(64, first.secretHash().length());
        assertNotEquals(first.rawSecret(), second.rawSecret());
    }
}
