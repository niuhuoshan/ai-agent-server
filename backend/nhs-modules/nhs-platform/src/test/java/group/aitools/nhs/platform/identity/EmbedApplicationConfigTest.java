package group.aitools.nhs.platform.identity;

import group.aitools.nhs.platform.identity.service.EmbedApplicationConfig;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class EmbedApplicationConfigTest {

    @Test
    void normalizesOriginsAndNarrowsAgentsAndSessionLifetime() {
        EmbedApplicationConfig config = EmbedApplicationConfig.from(Map.of(
            "allowedOrigins", List.of("HTTPS://Portal.Example.com:443/", "http://localhost:5173"),
            "agentVersionIds", List.of(20L, 10L, 20L),
            "displayName", "财务助手",
            "primaryColor", "#18A058",
            "watermark", false,
            "maxSessionMinutes", 30
        ));

        assertEquals(List.of("https://portal.example.com", "http://localhost:5173"), config.allowedOrigins());
        assertEquals(Set.of(10L, 20L), config.agentVersionIds());
        assertTrue(config.allowsOrigin("https://PORTAL.example.com/"));
        assertTrue(config.allowsAgentVersion(10L));
        assertFalse(config.allowsAgentVersion(30L));
        assertEquals(30, config.maxSessionMinutes());
        assertEquals("#18a058", config.primaryColor());
    }

    @Test
    void rejectsWildcardPathAndUnknownConfiguration() {
        for (Map<String, Object> value : List.<Map<String, Object>>of(
            Map.of("allowedOrigins", List.of("*")),
            Map.of("allowedOrigins", List.of("https://example.com/embed")),
            Map.of("accessToken", "must-not-be-stored")
        )) {
            ServiceException exception = assertThrows(
                ServiceException.class, () -> EmbedApplicationConfig.from(value)
            );
            assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());
        }
    }
}
