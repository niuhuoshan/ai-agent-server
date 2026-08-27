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
import group.aitools.nhs.platform.iam.domain.BusinessRelation;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.task.service.TaskQueryService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Produces bounded CSV/XLSX files from immutable result snapshots after current permission rechecks. */
@Service
public class DataQueryExportService {

    private static final int MAX_EXPORT_ROWS = 10_000;
    private static final int MAX_EXPORT_BYTES = 10 * 1024 * 1024;
    private static final int MAX_XLSX_BYTES = 20 * 1024 * 1024;
    private static final TypeReference<List<String>> COLUMNS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<List<Object>>> ROWS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final TaskQueryService taskQueryService;
    private final DataSourceCatalogService catalogService;
    private final DataCatalogMapper mapper;
    private final ConversationTurnMapper conversationTurnMapper;
    private final JsonMapper jsonMapper;

    public DataQueryExportService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        TaskQueryService taskQueryService,
        DataSourceCatalogService catalogService,
        DataCatalogMapper mapper,
        ConversationTurnMapper conversationTurnMapper,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.taskQueryService = taskQueryService;
        this.catalogService = catalogService;
        this.mapper = mapper;
        this.conversationTurnMapper = conversationTurnMapper;
        this.jsonMapper = jsonMapper;
    }

    public ExportedCsv export(Long queryId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentDataQuery query = mapper.selectQuery(queryId);
        Snapshot snapshot = snapshot(query, principal, true);
        byte[] body = csvBytes(snapshot.columns(), snapshot.rows());
        return new ExportedCsv(body, "chatbi-query-" + queryId + ".csv", snapshot.rows().size());
    }

    public ExportedFile exportFile(Long queryId, String requestedFormat) {
        String format = normalizeExportFormat(requestedFormat);
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentDataQuery query = mapper.selectQuery(queryId);
        Snapshot snapshot = snapshot(query, principal, true);
        if ("csv".equals(format)) {
            return new ExportedFile(
                csvBytes(snapshot.columns(), snapshot.rows()),
                "chatbi-query-" + queryId + ".csv",
                "text/csv;charset=UTF-8",
                snapshot.rows().size()
            );
        }
        return new ExportedFile(
            xlsx(snapshot.columns(), snapshot.rows()),
            "chatbi-query-" + queryId + ".xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            snapshot.rows().size()
        );
    }

    public ExportedFile exportTrace(String traceId, String requestedFormat) {
        String normalizedTraceId = normalizeTraceId(traceId);
        String format = requestedFormat != null && "xlsx".equalsIgnoreCase(requestedFormat.strip())
            ? "xlsx" : "csv";
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentDataQuery query = mapper.selectLatestSucceededQueryByTrace(
            normalizedTraceId, principal.id()
        );
        Snapshot snapshot = snapshot(query, principal, false);
        TraceMetadata metadata = traceMetadata(query);
        if ("csv".equals(format)) {
            return new ExportedFile(
                csvBytes(snapshot.columns(), snapshot.rows(), metadata),
                "export_" + normalizedTraceId + ".csv",
                "text/csv;charset=UTF-8",
                snapshot.rows().size()
            );
        }
        return new ExportedFile(
            xlsx(snapshot.columns(), snapshot.rows(), metadata),
            "export_" + normalizedTraceId + ".xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            snapshot.rows().size()
        );
    }

    private Snapshot snapshot(
        AgentDataQuery query,
        CurrentPrincipal principal,
        boolean allowAdministrator
    ) {
        if (query == null || !"succeeded".equals(query.getStatus())
            || (!principal.id().equals(query.getCreatedBy())
                && !(allowAdministrator && principal.hasRole(PlatformRole.PLATFORM_ADMIN)))) {
            throw new ServiceException("查询结果不存在", HttpStatus.NOT_FOUND);
        }
        List<AgentDataQuery> federatedSources = mapper.selectFederatedSourceQueries(query.getId());
        if (federatedSources.isEmpty()) {
            requireExportAccess(query, principal);
        } else {
            for (AgentDataQuery sourceQuery : federatedSources) {
                if (!query.getCreatedBy().equals(sourceQuery.getCreatedBy())) {
                    throw conflict("联邦查询来源所有者不一致");
                }
                requireExportAccess(sourceQuery, principal);
            }
        }

        DataQueryStoredResultRow stored = mapper.selectQueryResult(query.getId());
        if (stored == null || !query.getCreatedBy().equals(stored.getCreatedBy())) {
            throw conflict("查询结果快照缺失或所有者不一致");
        }
        List<String> columns = jsonMapper.readValue(stored.getColumnsJson(), COLUMNS_TYPE);
        List<List<Object>> rows = jsonMapper.readValue(stored.getRowsJson(), ROWS_TYPE);
        String expectedHash = ContentHashing.sha256(
            jsonMapper.writeValueAsString(columns) + "\0" + jsonMapper.writeValueAsString(rows)
        );
        if (!expectedHash.equals(stored.getContentHash())) {
            throw conflict("查询结果快照哈希不一致");
        }
        if (columns == null || columns.isEmpty() || rows == null
            || rows.size() > MAX_EXPORT_ROWS || rows.size() != stored.getRowCount()
            || query.getRowCount() == null || query.getRowCount() != rows.size()) {
            throw conflict("查询结果快照行列元数据无效");
        }
        for (List<Object> row : rows) {
            if (row == null || row.size() != columns.size()) {
                throw conflict("查询结果快照不是规则表格");
            }
        }
        return new Snapshot(columns, rows);
    }

    private void requireExportAccess(AgentDataQuery query, CurrentPrincipal principal) {
        AgentDataDataset dataset = catalogService.requireDataset(query.getDatasetId());
        AgentDataSource source = catalogService.requireSource(query.getDataSourceId());
        if (!"active".equals(dataset.getStatus()) || !"active".equals(source.getStatus())) {
            throw conflict("数据源或数据集当前不可用");
        }
        if (query.getTaskId() != null) {
            taskQueryService.get(query.getTaskId());
            if (mapper.countTaskDatasetQueryBinding(query.getTaskId(), dataset.getId()) < 1) {
                throw new ServiceException("任务当前没有数据集查询权限", HttpStatus.FORBIDDEN);
            }
        }
        Set<BusinessRelation> relations = principal.id().equals(dataset.getOwnerId())
            ? Set.of(BusinessRelation.OWNER) : Set.of();
        require(principal, dataset, "export", relations, query.getTaskId());
        if (referencesSensitiveColumn(query, mapper.selectColumns(dataset.getId()))) {
            require(principal, dataset, "export_sensitive", relations, query.getTaskId());
        }
    }

    private byte[] csvBytes(List<String> columns, List<List<Object>> rows) {
        return csvBytes(columns, rows, null);
    }

    private byte[] csvBytes(List<String> columns, List<List<Object>> rows, TraceMetadata metadata) {
        StringBuilder content = new StringBuilder();
        if (metadata != null) {
            appendRow(content, List.of("问题", metadata.question()));
            appendRow(content, List.of("AI摘要", metadata.summary()));
            appendRow(content, List.of());
        }
        content.append(csv(columns, rows));
        byte[] body = ("\ufeff" + content).getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_EXPORT_BYTES) {
            throw new ServiceException("CSV导出超过10MB限制", 413);
        }
        return body;
    }

    private String csv(List<String> columns, List<List<Object>> rows) {
        StringBuilder csv = new StringBuilder();
        appendRow(csv, new ArrayList<>(columns));
        for (List<Object> row : rows) {
            if (row == null || row.size() != columns.size()) {
                throw conflict("查询结果快照不是规则表格");
            }
            appendRow(csv, row);
        }
        return csv.toString();
    }

    private void appendRow(StringBuilder target, List<?> row) {
        for (int index = 0; index < row.size(); index++) {
            if (index > 0) {
                target.append(',');
            }
            target.append('"').append(cell(row.get(index)).replace("\"", "\"\""))
                .append('"');
        }
        target.append("\r\n");
    }

    private String cell(Object value) {
        if (value == null) {
            return "";
        }
        boolean stringCell = value instanceof String;
        String text = value instanceof String string
            ? string : value instanceof Number || value instanceof Boolean
                ? String.valueOf(value) : jsonMapper.writeValueAsString(value);
        String leading = text.stripLeading();
        if (stringCell && !leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0) {
            return "'" + text;
        }
        if (stringCell && !text.isEmpty()
            && (text.charAt(0) == '\t' || text.charAt(0) == '\r')) {
            return "'" + text;
        }
        return text;
    }

    private byte[] xlsx(List<String> columns, List<List<Object>> rows) {
        return xlsx(columns, rows, null);
    }

    private byte[] xlsx(List<String> columns, List<List<Object>> rows, TraceMetadata metadata) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (metadata != null) {
                Sheet overview = workbook.createSheet("分析概览");
                overview.createRow(0).createCell(0).setCellValue("项目");
                overview.getRow(0).createCell(1).setCellValue("内容");
                writeOverviewRow(overview, 1, "Trace ID", metadata.traceId());
                writeOverviewRow(overview, 2, "问题", metadata.question());
                writeOverviewRow(overview, 3, "AI摘要", metadata.summary());
                writeOverviewRow(overview, 4, "生成时间", metadata.generatedAt());
                overview.setColumnWidth(0, 20 * 256);
                overview.setColumnWidth(1, 100 * 256);
                overview.createFreezePane(0, 1);
            }
            Sheet sheet = workbook.createSheet("数据详情");
            sheet.createFreezePane(0, 1);
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            Row header = sheet.createRow(0);
            for (int index = 0; index < columns.size(); index++) {
                Cell target = header.createCell(index);
                target.setCellValue(columns.get(index));
                target.setCellStyle(headerStyle);
                sheet.setColumnWidth(index, Math.min(60, Math.max(12, columns.get(index).length() + 2)) * 256);
            }
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Row target = sheet.createRow(rowIndex + 1);
                List<Object> source = rows.get(rowIndex);
                for (int columnIndex = 0; columnIndex < source.size(); columnIndex++) {
                    writeCell(target.createCell(columnIndex), source.get(columnIndex));
                }
            }
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                0, rows.size(), 0, columns.size() - 1
            ));
            workbook.write(output);
            byte[] body = output.toByteArray();
            if (body.length > MAX_XLSX_BYTES) {
                throw new ServiceException("Excel导出超过20MB限制", 413);
            }
            return body;
        } catch (IOException exception) {
            throw new ServiceException("生成Excel文件失败", 500);
        }
    }

    private void writeCell(Cell target, Object value) {
        if (value == null) {
            target.setBlank();
        } else if (value instanceof Boolean bool) {
            target.setCellValue(bool);
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            target.setCellValue(((Number) value).doubleValue());
        } else if (value instanceof Long number
            && number >= -9_007_199_254_740_991L && number <= 9_007_199_254_740_991L) {
            target.setCellValue(number.doubleValue());
        } else if (value instanceof Float number && Float.isFinite(number)) {
            target.setCellValue(number.doubleValue());
        } else if (value instanceof Double number && Double.isFinite(number)) {
            target.setCellValue(number);
        } else if (value instanceof BigInteger number && number.abs().toString().length() <= 15) {
            target.setCellValue(number.doubleValue());
        } else if (value instanceof BigDecimal number && number.precision() <= 15) {
            target.setCellValue(number.doubleValue());
        } else {
            target.setCellValue(cell(value));
        }
    }

    private String normalizeTraceId(String traceId) {
        String normalized = traceId == null ? "" : traceId.strip();
        if (normalized.isEmpty() || normalized.length() > 64
            || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) {
            throw new ServiceException("Trace ID无效", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    private String normalizeExportFormat(String requestedFormat) {
        String format = requestedFormat == null ? "csv" : requestedFormat.strip().toLowerCase(Locale.ROOT);
        if (!"csv".equals(format) && !"xlsx".equals(format)) {
            throw new ServiceException("仅支持CSV或Excel导出", HttpStatus.BAD_REQUEST);
        }
        return format;
    }

    private TraceMetadata traceMetadata(AgentDataQuery query) {
        String question = query.getUserQuery() == null ? "" : query.getUserQuery();
        String summary = "";
        if (query.getConversationId() != null && query.getTraceId() != null) {
            AgentConversationTurn turn = conversationTurnMapper.selectOwnedTurnByTrace(
                query.getTraceId(), query.getCreatedBy()
            );
            if (turn != null && query.getConversationId().equals(turn.getConversationId())) {
                List<ConversationMessageRow> messages = conversationTurnMapper.selectTraceMessages(
                    query.getConversationId(), query.getTraceId()
                );
                summary = messages.stream()
                    .filter(message -> "assistant".equals(message.getRole()))
                    .map(ConversationMessageRow::getContent)
                    .filter(value -> value != null && !value.isBlank())
                    .reduce((first, second) -> second)
                    .orElse("");
            }
        }
        return new TraceMetadata(query.getTraceId(), question, summary,
            query.getFinishedAt() == null ? "" : query.getFinishedAt().toString());
    }

    private void writeOverviewRow(Sheet sheet, int rowIndex, String key, String value) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(key);
        writeCell(row.createCell(1), value == null ? "" : value);
    }

    private boolean referencesSensitiveColumn(
        AgentDataQuery query,
        List<AgentDataColumn> columns
    ) {
        Map<String, Object> plan = jsonMapper.readValue(query.getSqlPlanJson(), MAP_TYPE);
        Set<String> references = new LinkedHashSet<>();
        Object raw = plan.get("columns");
        if (raw instanceof List<?> values) {
            for (Object value : values) {
                references.add(String.valueOf(value).toLowerCase(Locale.ROOT));
            }
        }
        boolean wildcard = references.stream().anyMatch(value -> value.equals("*") || value.endsWith(".*"));
        return columns.stream().filter(column -> Boolean.TRUE.equals(column.getIsSensitive()))
            .anyMatch(column -> wildcard || references.stream().anyMatch(reference ->
                reference.equals(column.getPhysicalName().toLowerCase(Locale.ROOT))
                    || reference.endsWith("." + column.getPhysicalName().toLowerCase(Locale.ROOT))
            ));
    }

    private void require(
        CurrentPrincipal principal,
        AgentDataDataset dataset,
        String action,
        Set<BusinessRelation> relations,
        Long taskId
    ) {
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "dataset", dataset.getId(), dataset.getDatasetKey(), action,
            ResourceState.ACTIVE, true, relations, taskId
        ));
    }

    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }

    public record ExportedCsv(byte[] content, String fileName, int rowCount) {
    }

    public record ExportedFile(
        byte[] content,
        String fileName,
        String mediaType,
        int rowCount
    ) {
    }

    private record Snapshot(List<String> columns, List<List<Object>> rows) {
    }

    private record TraceMetadata(String traceId, String question, String summary, String generatedAt) {
    }
}
