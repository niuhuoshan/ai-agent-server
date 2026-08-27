package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.conversation.domain.AgentConversationTurn;
import group.aitools.nhs.platform.conversation.mapper.ConversationTurnMapper;
import group.aitools.nhs.platform.conversation.persistence.row.ConversationMessageRow;
import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.domain.AgentDataQuery;
import group.aitools.nhs.platform.data.domain.AgentDataSource;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.platform.data.persistence.row.DataQueryStoredResultRow;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.task.service.TaskQueryService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class DataQueryExportServiceTest {

    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        101L, "member", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );

    private CurrentPrincipalProvider principals;
    private AuthorizationEnforcer authorization;
    private DataSourceCatalogService catalog;
    private DataCatalogMapper mapper;
    private ConversationTurnMapper conversationTurns;
    private JsonMapper jsonMapper;
    private DataQueryExportService service;

    @BeforeEach
    void setUp() {
        principals = mock(CurrentPrincipalProvider.class);
        authorization = mock(AuthorizationEnforcer.class);
        catalog = mock(DataSourceCatalogService.class);
        mapper = mock(DataCatalogMapper.class);
        conversationTurns = mock(ConversationTurnMapper.class);
        jsonMapper = JsonMapper.builder().build();
        when(principals.currentPrincipal()).thenReturn(MEMBER);
        when(mapper.selectQuery(9L)).thenReturn(query(101L));
        when(mapper.selectQueryResult(9L)).thenReturn(stored());
        when(catalog.requireDataset(70L)).thenReturn(dataset());
        when(catalog.requireSource(60L)).thenReturn(source());
        when(mapper.selectColumns(70L)).thenReturn(List.of());
        AgentConversationTurn ownedTurn = new AgentConversationTurn();
        ownedTurn.setConversationId(7L);
        when(conversationTurns.selectOwnedTurnByTrace("trace-1", 101L)).thenReturn(ownedTurn);
        when(conversationTurns.selectTraceMessages(7L, "trace-1")).thenReturn(List.of(
            message("assistant", "销售增长")
        ));
        service = new DataQueryExportService(
            principals, authorization, mock(TaskQueryService.class), catalog, mapper,
            conversationTurns, jsonMapper
        );
    }

    @Test
    void csvNeutralizesSpreadsheetFormulasButPreservesNumericNegatives() {
        var export = service.export(9L);
        String csv = new String(export.content(), StandardCharsets.UTF_8);

        assertTrue(csv.startsWith("\ufeff"));
        assertTrue(csv.contains("\"'=SUM(A1:A2)\""));
        assertTrue(csv.contains("\"'  @cmd\""));
        assertTrue(csv.contains("\"-3\""));
        assertEquals(2, export.rowCount());
    }

    @Test
    void tamperedResultSnapshotIsRejected() {
        DataQueryStoredResultRow stored = stored();
        stored.setContentHash("0".repeat(64));
        when(mapper.selectQueryResult(9L)).thenReturn(stored);

        ServiceException exception = assertThrows(
            ServiceException.class, () -> service.export(9L)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getCode());
    }

    @Test
    void anotherUsersQueryIsHiddenBeforeDatasetAuthorization() {
        when(mapper.selectQuery(9L)).thenReturn(query(202L));

        ServiceException exception = assertThrows(
            ServiceException.class, () -> service.export(9L)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getCode());
        verify(catalog, never()).requireDataset(any());
    }

    @Test
    void sensitiveProjectionRequiresSeparateCurrentPermission() {
        AgentDataColumn sensitive = new AgentDataColumn();
        sensitive.setPhysicalName("salary");
        sensitive.setIsSensitive(true);
        when(mapper.selectColumns(70L)).thenReturn(List.of(sensitive));
        AgentDataQuery query = query(101L);
        query.setSqlPlanJson("{\"columns\":[\"employee.salary\"]}");
        when(mapper.selectQuery(9L)).thenReturn(query);

        service.export(9L);

        verify(authorization, times(2)).requireAllowed(eq(MEMBER), any());
        verify(authorization).requireAllowed(eq(MEMBER), org.mockito.ArgumentMatchers.argThat(
            context -> "export_sensitive".equals(context.action())
        ));
    }

    @Test
    void traceCsvUsesOnlyTheCurrentUsersLatestSucceededQuery() {
        AgentDataQuery query = query(101L);
        query.setTraceId("trace-1");
        when(mapper.selectLatestSucceededQueryByTrace("trace-1", 101L)).thenReturn(query);

        var export = service.exportTrace("trace-1", "csv");

        assertEquals("export_trace-1.csv", export.fileName());
        assertEquals("text/csv;charset=UTF-8", export.mediaType());
        String csv = new String(export.content(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("\"问题\",\"本月销售\""));
        assertTrue(csv.contains("\"AI摘要\",\"销售增长\""));
        assertTrue(csv.contains("'=SUM(A1:A2)"));
    }

    @Test
    void traceXlsxContainsTypedRowsWithoutCreatingFormulaCells() throws Exception {
        AgentDataQuery query = query(101L);
        query.setTraceId("trace-1");
        when(mapper.selectLatestSucceededQueryByTrace("trace-1", 101L)).thenReturn(query);

        var export = service.exportTrace("trace-1", "xlsx");

        assertEquals("export_trace-1.xlsx", export.fileName());
        assertEquals('P', export.content()[0]);
        assertEquals('K', export.content()[1]);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(export.content()))) {
            var overview = workbook.getSheet("分析概览");
            assertEquals("本月销售", overview.getRow(2).getCell(1).getStringCellValue());
            assertEquals("销售增长", overview.getRow(3).getCell(1).getStringCellValue());
            var sheet = workbook.getSheet("数据详情");
            assertEquals("name", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("'=SUM(A1:A2)", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals(1D, sheet.getRow(1).getCell(1).getNumericCellValue());
        }
    }

    @Test
    void traceBlankAndUnknownFormatsFallBackToCsv() {
        AgentDataQuery query = query(101L);
        query.setTraceId("trace-1");
        when(mapper.selectLatestSucceededQueryByTrace("trace-1", 101L)).thenReturn(query);

        assertEquals("export_trace-1.csv", service.exportTrace("trace-1", "").fileName());
        assertEquals("export_trace-1.csv", service.exportTrace("trace-1", "pdf").fileName());
    }

    @Test
    void missingOwnedTraceIsHiddenBeforeDatasetAuthorization() {
        when(mapper.selectLatestSucceededQueryByTrace("trace-missing", 101L)).thenReturn(null);

        ServiceException exception = assertThrows(
            ServiceException.class, () -> service.exportTrace("trace-missing", "csv")
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getCode());
        verify(catalog, never()).requireDataset(any());
    }

    private AgentDataQuery query(Long creator) {
        AgentDataQuery query = new AgentDataQuery();
        query.setId(9L);
        query.setConversationId(7L);
        query.setDataSourceId(60L);
        query.setDatasetId(70L);
        query.setSqlPlanJson("{\"columns\":[\"name\",\"value\"]}");
        query.setRowCount(2L);
        query.setStatus("succeeded");
        query.setUserQuery("本月销售");
        query.setCreatedBy(creator);
        return query;
    }

    private ConversationMessageRow message(String role, String content) {
        ConversationMessageRow row = new ConversationMessageRow();
        row.setRole(role);
        row.setContent(content);
        return row;
    }

    private DataQueryStoredResultRow stored() {
        String columns = "[\"name\", \"value\"]";
        String rows = "[[\"=SUM(A1:A2)\", 1], [\"  @cmd\", -3]]";
        @SuppressWarnings("unchecked")
        List<String> parsedColumns = jsonMapper.readValue(columns, List.class);
        @SuppressWarnings("unchecked")
        List<List<Object>> parsedRows = jsonMapper.readValue(rows, List.class);
        String hash = ContentHashing.sha256(
            jsonMapper.writeValueAsString(parsedColumns) + "\0" + jsonMapper.writeValueAsString(parsedRows)
        );
        DataQueryStoredResultRow stored = new DataQueryStoredResultRow();
        stored.setQueryId(9L);
        stored.setColumnsJson(columns);
        stored.setRowsJson(rows);
        stored.setContentHash(hash);
        stored.setRowCount(2);
        stored.setResultBytes(64);
        stored.setCreatedBy(101L);
        return stored;
    }

    private AgentDataDataset dataset() {
        AgentDataDataset dataset = new AgentDataDataset();
        dataset.setId(70L);
        dataset.setDataSourceId(60L);
        dataset.setDatasetKey("sales");
        dataset.setOwnerId(101L);
        dataset.setStatus("active");
        return dataset;
    }

    private AgentDataSource source() {
        AgentDataSource source = new AgentDataSource();
        source.setId(60L);
        source.setStatus("active");
        return source;
    }
}
