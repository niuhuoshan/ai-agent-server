package group.aitools.nhs.platform.operations;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import group.aitools.nhs.platform.operations.domain.PlatformConfiguration;
import group.aitools.nhs.platform.operations.domain.PlatformConfigurationHistory;
import group.aitools.nhs.platform.operations.mapper.PlatformConfigurationMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("postgres")
@Tag("dev")
@EnabledIfEnvironmentVariable(named = "NHS_TEST_JDBC_URL", matches = ".+")
class PlatformConfigurationMapperIntegrationTest {

    private static SqlSessionFactory sessions;

    @BeforeAll
    static void configureMyBatis() {
        var source = new UnpooledDataSource(
            "org.postgresql.Driver",
            System.getenv("NHS_TEST_JDBC_URL"),
            environmentOrDefault("NHS_TEST_DB_USER", "agent_server"),
            environmentOrDefault("NHS_TEST_DB_PASSWORD", "agent_server")
        );
        Configuration configuration = new Configuration(new Environment(
            "platform-configuration-test", new JdbcTransactionFactory(), source
        ));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(PlatformConfigurationMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void persistsOptimisticConfigurationAndImmutableHistorySnapshot() {
        try (SqlSession session = sessions.openSession(false)) {
            PlatformConfigurationMapper mapper = session.getMapper(PlatformConfigurationMapper.class);
            PlatformConfiguration current = mapper.selectCurrent();
            assertNotNull(current);

            LocalDateTime now = LocalDateTime.now();
            PlatformConfiguration next = copy(current);
            next.setProductName("集成测试平台");
            next.setPrimaryColor("#1570EF");
            next.setUpdateBy(99L);
            next.setUpdateTime(now);
            assertEquals(1, mapper.updateCurrent(next, current.getRevisionNo()));
            assertEquals(0, mapper.updateCurrent(next, current.getRevisionNo()));

            PlatformConfiguration updated = mapper.selectCurrent();
            assertEquals(current.getRevisionNo() + 1, updated.getRevisionNo());
            PlatformConfigurationHistory history = history(updated, now);
            assertEquals(1, mapper.insertHistory(history));
            assertEquals(history.getRevisionNo(), mapper.selectHistory(1).getFirst().getRevisionNo());
            session.rollback();
        }
    }

    private PlatformConfiguration copy(PlatformConfiguration source) {
        PlatformConfiguration value = new PlatformConfiguration();
        value.setId(source.getId());
        value.setProductName(source.getProductName());
        value.setProductShortName(source.getProductShortName());
        value.setLogoUrl(source.getLogoUrl());
        value.setFaviconUrl(source.getFaviconUrl());
        value.setPrimaryColor(source.getPrimaryColor());
        value.setPlatformTimezone(source.getPlatformTimezone());
        value.setDefaultLocale(source.getDefaultLocale());
        value.setWatermarkEnabled(source.getWatermarkEnabled());
        return value;
    }

    private PlatformConfigurationHistory history(PlatformConfiguration value, LocalDateTime now) {
        PlatformConfigurationHistory history = new PlatformConfigurationHistory();
        history.setId(9_620_000_000_000_001L);
        history.setConfigurationId(1L);
        history.setProductName(value.getProductName());
        history.setProductShortName(value.getProductShortName());
        history.setLogoUrl(value.getLogoUrl());
        history.setFaviconUrl(value.getFaviconUrl());
        history.setPrimaryColor(value.getPrimaryColor());
        history.setPlatformTimezone(value.getPlatformTimezone());
        history.setDefaultLocale(value.getDefaultLocale());
        history.setWatermarkEnabled(value.getWatermarkEnabled());
        history.setRevisionNo(value.getRevisionNo());
        history.setChangeReason("集成测试");
        history.setChangedBy(99L);
        history.setCreatedAt(now);
        return history;
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
