package group.aitools.nhs.platform.connector;

import group.aitools.nhs.platform.connector.service.ConnectorConfigurationValidator;
import group.aitools.nhs.platform.connector.service.ConnectorEndpointPolicy;
import group.aitools.nhs.platform.connector.service.McpServersImportParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class McpServersImportParserTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final McpServersImportParser parser = new McpServersImportParser(
        new ConnectorConfigurationValidator(jsonMapper), new ConnectorEndpointPolicy(false, false)
    );

    @Test
    void extractsEnvironmentReferenceWithoutEchoingExportedEnvironmentValue() {
        var preview = parser.preview(Map.of("mcpServers", Map.of("reports", Map.of(
            "type", "streamable-http",
            "url", "https://mcp.example/rpc",
            "headers", Map.of("Authorization", "Bearer ${MCP_REPORTS_TOKEN}"),
            "env", Map.of("MCP_REPORTS_TOKEN", "actual-secret-must-not-survive")
        ))));

        var entry = preview.entries().getFirst();
        assertTrue(entry.importable());
        assertEquals("mcp-reports", entry.suggestedConnectorKey());
        assertEquals("bearer", entry.authType());
        assertEquals("env:MCP_REPORTS_TOKEN", entry.credentialRef());
        assertFalse(entry.credentialRequired());
        assertFalse(jsonMapper.writeValueAsString(preview).contains("actual-secret-must-not-survive"));
    }

    @Test
    void dropsInlineHeaderSecretAndRequiresExplicitEnvironmentReference() {
        var preview = parser.preview(Map.of("mcpServers", Map.of("search", Map.of(
            "url", "https://mcp.example/search",
            "headers", Map.of("X-API-Key", "inline-key-value")
        ))));

        var entry = preview.entries().getFirst();
        assertTrue(entry.importable());
        assertEquals("header", entry.authType());
        assertEquals("X-API-Key", entry.authHeader());
        assertNull(entry.credentialRef());
        assertTrue(entry.credentialRequired());
        assertTrue(entry.diagnostics().stream().anyMatch(value -> value.contains("env:NAME")));
        assertFalse(jsonMapper.writeValueAsString(preview).contains("inline-key-value"));
    }

    @Test
    void reportsStdioAndMultipleHeadersAsUnsupportedInsteadOfPretendingSuccess() {
        var preview = parser.preview(Map.of("mcpServers", Map.of(
            "local", Map.of("command", "node", "args", java.util.List.of("server.js")),
            "multi-header", Map.of(
                "url", "https://mcp.example/rpc",
                "headers", Map.of("Authorization", "${TOKEN}", "X-Tenant", "tenant-a")
            )
        )));

        assertEquals(2, preview.entries().size());
        assertTrue(preview.entries().stream().noneMatch(entry -> entry.importable()));
        assertTrue(preview.entries().stream()
            .flatMap(entry -> entry.diagnostics().stream())
            .anyMatch(value -> value.contains("远程 HTTP/SSE")));
        assertTrue(preview.entries().stream()
            .flatMap(entry -> entry.diagnostics().stream())
            .anyMatch(value -> value.contains("一个鉴权 Header")));
    }
}
