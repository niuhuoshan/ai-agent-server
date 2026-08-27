package group.aitools.nhs.platform.data.service;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.domain.AgentDataSource;
import group.aitools.nhs.platform.data.domain.AgentDataTable;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.platform.data.web.DataQueryRequest;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.task.service.TaskQueryService;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("postgres")
@Tag("dev")
@EnabledIfEnvironmentVariable(named = "NHS_TEST_JDBC_URL", matches = ".+")
class PostgresDataControlIntegrationTest {

    private static final CurrentPrincipal ADMIN = new CurrentPrincipal(
        1L, "admin", PrincipalType.HUMAN, Set.of(PlatformRole.PLATFORM_ADMIN)
    );
    private static final AuthorizationDecision ALLOW = new AuthorizationDecision(
        PermissionEffect.ALLOW, "TEST_ALLOW", "test", List.of()
    );

    private static DataSource dataSource;
    private static SqlSessionFactory sessionFactory;
    private static String databaseUser;
    private static String databasePassword;

    private SqlSession session;
    private DataCatalogMapper mapper;
    private AgentDataSource source;
    private AgentDataDataset dataset;
    private AtomicLong ids;
    private JsonMapper jsonMapper;

    @BeforeAll
    static void initializeDatabase() {
        String url = System.getenv("NHS_TEST_JDBC_URL");
        databaseUser = environmentOrDefault("NHS_TEST_DB_USER", "agent_server");
        databasePassword = environmentOrDefault("NHS_TEST_DB_PASSWORD", "agent_server");
        Flyway.configure()
            .dataSource(url, databaseUser, databasePassword)
            .locations("classpath:db/migration/agent")
            .baselineOnMigrate(true)
            .validateOnMigrate(true)
            .executeInTransaction(false)
            .load()
            .migrate();

        dataSource = new UnpooledDataSource("org.postgresql.Driver", url, databaseUser, databasePassword);
        Environment environment = new Environment("data-control-test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(DataCatalogMapper.class);
        sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void setUp() throws SQLException {
        executeSql("""
            TRUNCATE TABLE
                agent_data_query_result,
                agent_data_query,
                agent_data_column,
                agent_data_table,
                agent_data_dataset,
                agent_data_source
            """);
        session = sessionFactory.openSession(true);
        mapper = session.getMapper(DataCatalogMapper.class);
        ids = new AtomicLong(100000L);
        jsonMapper = JsonMapper.builder().build();
        source = source(9100L, "query-source");
        dataset = dataset(9200L, source.getId());
        mapper.insertSource(source);
        mapper.insertSource(source(9101L, "second-source"));
        mapper.insertDataset(dataset);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void executesBoundedReadOnlySelectAndPersistsOnlyQueryFacts() throws Exception {
        insertSourceTableMetadata(false);
        DataQueryExecutionService service = queryService(alwaysAllow());

        var result = service.execute(new DataQueryRequest(
            dataset.getId(), null, null, null, "列出数据源",
            "SELECT s.id, s.name FROM public.agent_data_source s ORDER BY s.id"
        ));

        assertEquals(1, result.rows().size());
        assertTrue(result.truncated());
        assertTrue(result.resultBytes() > 0);
        assertEquals("succeeded", queryStatus(result.queryId()));
        assertFalse(queryFact(result.queryId()).contains("agent_server"));
        var stored = mapper.selectQueryResult(result.queryId());
        assertNotNull(stored);
        assertEquals((long) result.rowCount(), stored.getRowCount().longValue());
        assertEquals(64, stored.getContentHash().length());

        ReadOnlyJdbcConnectionFactory connectionFactory = connectionFactory();
        try (Connection connection = connectionFactory.open(source);
             Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE public.agent_data_source SET name = 'forbidden' WHERE id = 9100"
            ));
            connection.rollback();
        }
        assertEquals("Query source", mapper.selectSource(9100L).getName());
    }

    @Test
    void rejectsSensitiveColumnsAndWritesBeforeJdbcExecution() {
        insertSourceTableMetadata(true);
        DataQueryExecutionService service = queryService(alwaysAllow());

        assertThrows(ServiceException.class, () -> service.execute(new DataQueryRequest(
            dataset.getId(), null, null, null, "读取凭证引用",
            "SELECT s.credential_ref FROM public.agent_data_source s"
        )));
        assertThrows(ServiceException.class, () -> service.execute(new DataQueryRequest(
            dataset.getId(), null, null, null, "修改数据源",
            "UPDATE public.agent_data_source SET name = 'bad'"
        )));

        assertEquals(2, countQueriesWithStatus("rejected"));
        assertEquals("Query source", mapper.selectSource(9100L).getName());
    }

    @Test
    void permissionIsRecheckedImmediatelyBeforeExecution() {
        insertSourceTableMetadata(false);
        AuthorizationEnforcer enforcer = mock(AuthorizationEnforcer.class);
        when(enforcer.requireAllowed(any(), any()))
            .thenReturn(ALLOW)
            .thenThrow(new ServiceException("permission revoked", 403));
        DataQueryExecutionService service = queryService(enforcer);

        assertThrows(ServiceException.class, () -> service.execute(new DataQueryRequest(
            dataset.getId(), null, null, null, "列出数据源",
            "SELECT s.id FROM public.agent_data_source s"
        )));

        assertEquals(1, countQueriesWithStatus("failed"));
    }

    @Test
    void synchronizesInformationSchemaAndPreservesSensitiveClassification() {
        AuthorizationEnforcer enforcer = alwaysAllow();
        DataSourceCatalogService catalog = mock(DataSourceCatalogService.class);
        when(catalog.requireDataset(dataset.getId())).thenReturn(dataset);
        when(catalog.requireSource(source.getId())).thenReturn(source);
        when(catalog.datasetContext(dataset, "sync")).thenReturn(
            new group.aitools.nhs.platform.iam.domain.PermissionContext(
                "dataset", dataset.getId(), dataset.getDatasetKey(), "sync",
                group.aitools.nhs.platform.iam.domain.ResourceState.ACTIVE,
                true, Set.of(), null
            )
        );
        DataMetadataPersistenceService persistence = new DataMetadataPersistenceService(
            idGenerator(), mapper, jsonMapper
        );
        DataMetadataSyncService syncService = new DataMetadataSyncService(
            () -> ADMIN, enforcer, catalog, mapper, connectionFactory(),
            new JdbcMetadataDiscovery(), persistence, jsonMapper
        );

        var first = syncService.synchronize(dataset.getId());
        AgentDataTable sourceTable = mapper.selectTables(dataset.getId()).stream()
            .filter(table -> "agent_data_source".equals(table.getPhysicalName()))
            .findFirst().orElseThrow();
        AgentDataColumn credentialColumn = mapper.selectColumns(dataset.getId()).stream()
            .filter(column -> column.getTableId().equals(sourceTable.getId()))
            .filter(column -> "credential_ref".equals(column.getPhysicalName()))
            .findFirst().orElseThrow();
        mapper.updateColumnGovernance(
            dataset.getId(), credentialColumn.getId(), "Credential", null,
            true, "active", LocalDateTime.now()
        );

        var second = syncService.synchronize(dataset.getId());
        AgentDataColumn refreshed = mapper.selectColumn(credentialColumn.getId());

        assertTrue(first.tableCount() > 0);
        assertTrue(first.columnCount() > first.tableCount());
        assertEquals(first.tableCount(), second.tableCount());
        assertTrue(refreshed.getIsSensitive());
        assertTrue(refreshed.getMetadataPresent());
    }

    private DataQueryExecutionService queryService(AuthorizationEnforcer enforcer) {
        CurrentPrincipalProvider principalProvider = () -> ADMIN;
        TaskQueryService taskQueryService = mock(TaskQueryService.class);
        DataSourceCatalogService catalog = mock(DataSourceCatalogService.class);
        when(catalog.requireDataset(dataset.getId())).thenReturn(dataset);
        when(catalog.requireSource(source.getId())).thenReturn(source);
        return new DataQueryExecutionService(
            principalProvider, enforcer, taskQueryService, idGenerator(), mapper, catalog,
            new ReadOnlySqlValidator(), connectionFactory(), jsonMapper
        );
    }

    private AuthorizationEnforcer alwaysAllow() {
        AuthorizationEnforcer enforcer = mock(AuthorizationEnforcer.class);
        when(enforcer.requireAllowed(any(), any())).thenReturn(ALLOW);
        return enforcer;
    }

    private ReadOnlyJdbcConnectionFactory connectionFactory() {
        DataCredentialResolver credentials = ignored -> new DataCredential(databaseUser, databasePassword);
        return new ReadOnlyJdbcConnectionFactory(
            credentials, new PostgresDataEndpointPolicy(true, true), jsonMapper
        );
    }

    private PlatformIdGenerator idGenerator() {
        return new PlatformIdGenerator() {
            @Override
            public Long nextId() {
                return ids.incrementAndGet();
            }

            @Override
            public String nextUuid() {
                return UUID.randomUUID().toString();
            }
        };
    }

    private void insertSourceTableMetadata(boolean sensitiveCredential) {
        AgentDataTable table = new AgentDataTable();
        table.setId(9300L);
        table.setDatasetId(dataset.getId());
        table.setTableKey("source-table");
        table.setPhysicalSchema("public");
        table.setPhysicalName("agent_data_source");
        table.setDisplayName("Data source");
        table.setTableType("table");
        table.setStatus("active");
        table.setMetadataPresent(true);
        table.setMetadataJson("{}");
        table.setCreateBy(ADMIN.id());
        table.setCreateTime(LocalDateTime.now());
        table.setDelFlag("0");
        mapper.insertTable(table);
        mapper.insertColumn(column(9301L, table.getId(), "id", false));
        mapper.insertColumn(column(9302L, table.getId(), "name", false));
        mapper.insertColumn(column(9303L, table.getId(), "credential_ref", sensitiveCredential));
    }

    private AgentDataColumn column(Long id, Long tableId, String name, boolean sensitive) {
        AgentDataColumn column = new AgentDataColumn();
        column.setId(id);
        column.setTableId(tableId);
        column.setColumnKey(name + "-key");
        column.setPhysicalName(name);
        column.setDisplayName(name);
        column.setDataType("text");
        column.setIsPrimary("id".equals(name));
        column.setIsSensitive(sensitive);
        column.setStatus("active");
        column.setMetadataPresent(true);
        column.setCreatedAt(LocalDateTime.now());
        return column;
    }

    private AgentDataSource source(Long id, String sourceKey) {
        URI uri = URI.create(System.getenv("NHS_TEST_JDBC_URL").substring("jdbc:".length()));
        AgentDataSource value = new AgentDataSource();
        value.setId(id);
        value.setSourceKey(sourceKey);
        value.setName(id.equals(9100L) ? "Query source" : "Second source");
        value.setDbType("postgresql");
        value.setEndpointUrl("postgresql://" + uri.getHost() + ':' + uri.getPort());
        value.setDatabaseName(uri.getPath().substring(1));
        value.setCredentialRef("env:TEST_DATABASE");
        value.setReadonly(true);
        value.setStatus("active");
        value.setConfigJson("{\"sslMode\":\"disable\"}");
        value.setRevisionNo(1);
        value.setConnectionTimeoutMs(5000);
        value.setStatementTimeoutMs(5000);
        value.setMaxRows(1);
        value.setMaxResultBytes(4096);
        value.setCreateBy(ADMIN.id());
        value.setCreateTime(LocalDateTime.now());
        value.setDelFlag("0");
        return value;
    }

    private AgentDataDataset dataset(Long id, Long sourceId) {
        AgentDataDataset value = new AgentDataDataset();
        value.setId(id);
        value.setDataSourceId(sourceId);
        value.setDatasetKey("platform-catalog");
        value.setName("Platform catalog");
        value.setStatus("active");
        value.setEnableRowPolicy(false);
        value.setRowPolicyJson("{}");
        value.setSchemaNamesJson("[\"public\"]");
        value.setRevisionNo(1);
        value.setOwnerId(ADMIN.id());
        value.setCreateBy(ADMIN.id());
        value.setCreateTime(LocalDateTime.now());
        value.setDelFlag("0");
        return value;
    }

    private String queryStatus(Long queryId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT status FROM agent_data_query WHERE id = ?")) {
            statement.setLong(1, queryId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private String queryFact(Long queryId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT row_to_json(q)::text FROM agent_data_query q WHERE id = ?")) {
            statement.setLong(1, queryId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private int countQueriesWithStatus(String status) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT COUNT(*) FROM agent_data_query WHERE status = ?")) {
            statement.setString(1, status);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void executeSql(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
