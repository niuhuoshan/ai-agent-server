package group.aitools.nhs.platform.portal.mapper;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import group.aitools.nhs.platform.portal.dashboard.persistence.PortalDashboardMapper;
import group.aitools.nhs.platform.portal.quota.mapper.AgentQuotaPolicyMapper;
import group.aitools.nhs.platform.notification.mapper.AgentNotificationMapper;
import group.aitools.nhs.platform.memory.mapper.MemoryCatalogMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("dev")
class PortalMapperSqlContractTest {

    @Test
    void nullableSystemScopeDoesNotBindAnUntypedPostgresParameter() {
        Configuration configuration = new Configuration();
        configuration.addMapper(AgentQuotaPolicyMapper.class);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("scopeType", "system");
        parameters.put("scopeId", null);

        BoundSql boundSql = mappedStatement(configuration, AgentQuotaPolicyMapper.class, "selectPolicy")
            .getBoundSql(parameters);

        assertThat(boundSql.getSql()).contains("scope_id IS NULL");
        assertThat(boundSql.getParameterMappings())
            .extracting(mapping -> mapping.getProperty())
            .containsExactly("scopeType");
    }

    @Test
    void dashboardApiSummaryQualifiesJoinedCallColumns() {
        Configuration configuration = new Configuration();
        configuration.addMapper(PortalDashboardMapper.class);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("fromTime", LocalDateTime.of(2026, 1, 1, 0, 0));
        parameters.put("toTime", LocalDateTime.of(2026, 1, 2, 0, 0));
        parameters.put("userId", null);

        BoundSql boundSql = mappedStatement(configuration, PortalDashboardMapper.class, "selectApiSummary")
            .getBoundSql(parameters);
        String sql = boundSql.getSql();

        assertThat(sql)
            .contains("c.outcome")
            .contains("c.status_code")
            .contains("c.duration_ms")
            .contains("MAX(c.created_at)");
    }

    @Test
    void inboxPageOmitsNullableCategoryParameterWhenCategoryIsAbsent() {
        Configuration configuration = new Configuration();
        configuration.addMapper(AgentNotificationMapper.class);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("userId", 101L);
        parameters.put("category", null);
        parameters.put("unreadOnly", false);
        parameters.put("offset", 0);
        parameters.put("limit", 20);

        BoundSql boundSql = mappedStatement(configuration, AgentNotificationMapper.class, "selectInboxPage")
            .getBoundSql(parameters);

        assertThat(boundSql.getSql())
            .doesNotContain("category =")
            .doesNotContain("IS NULL OR")
            .doesNotContain("read_at IS NULL");
        assertThat(boundSql.getParameterMappings())
            .extracting(mapping -> mapping.getProperty())
            .containsExactly("userId", "limit", "offset");
    }

    @Test
    void inboxPageRetainsCategoryAndUnreadFiltersWhenRequested() {
        Configuration configuration = new Configuration();
        configuration.addMapper(AgentNotificationMapper.class);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("userId", 101L);
        parameters.put("category", "approval");
        parameters.put("unreadOnly", true);
        parameters.put("offset", 0);
        parameters.put("limit", 20);

        BoundSql boundSql = mappedStatement(configuration, AgentNotificationMapper.class, "selectInboxPage")
            .getBoundSql(parameters);

        assertThat(boundSql.getSql())
            .contains("category = ?")
            .contains("read_at IS NULL");
        assertThat(boundSql.getParameterMappings())
            .extracting(mapping -> mapping.getProperty())
            .containsExactly("userId", "category", "limit", "offset");
    }

    @Test
    void memoryDocumentCountUsesPostgresOperatorWithoutXmlEscaping() {
        Configuration configuration = new Configuration();
        configuration.addMapper(MemoryCatalogMapper.class);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("scopeType", "user");
        parameters.put("scopeId", 1L);

        BoundSql boundSql = mappedStatement(configuration, MemoryCatalogMapper.class, "countSearchDocuments")
            .getBoundSql(parameters);

        assertThat(boundSql.getSql())
            .contains("COALESCE(metadata_json ->> 'kind', '') <> 'memory_config'")
            .doesNotContain("&lt;&gt;");
    }

    private MappedStatement mappedStatement(
        Configuration configuration,
        Class<?> mapperType,
        String methodName
    ) {
        return configuration.getMappedStatement(mapperType.getName() + "." + methodName);
    }
}
