package group.aitools.nhs.platform.embed.service;

import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class EmbedBrowserRequestPolicyTest {

    private final EmbedBrowserRequestPolicy policy = new EmbedBrowserRequestPolicy();

    @Test
    void acceptsSameOriginIframeAndHeaderlessServerClient() {
        assertDoesNotThrow(() -> policy.requireSameOrigin("same-origin", "https://agent.example.com"));
        assertDoesNotThrow(() -> policy.requireSameOrigin(null, null));
    }

    @Test
    void rejectsCrossSiteAndOriginBearingRequestsWithoutFetchMetadata() {
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ServiceException.class, () ->
            policy.requireSameOrigin("cross-site", "https://evil.example")
        ).getCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ServiceException.class, () ->
            policy.requireSameOrigin(null, "https://evil.example")
        ).getCode());
    }
}
