package group.aitools.nhs.platform.compat.nhs;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import group.aitools.nhs.platform.data.service.DataSourceCatalogService;
import group.aitools.nhs.platform.data.web.DataColumnView;
import group.aitools.nhs.platform.data.web.DataTableView;
import group.aitools.nhs.platform.data.web.DatasetView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Small Nhs V1 compatibility surface backed by the platform control plane.
 *
 * <p>This adapter deliberately does not reimplement Nhs's permission or
 * metadata services. It delegates dataset visibility to the existing catalog
 * service and marks capabilities that have no Java equivalent explicitly.</p>
 */
/**
 * 提供{@code NhsV1Compatibility}相关的 HTTP 接口，并负责请求校验与结果返回。
 *
 * Legacy schema-only adapter retained for unit-test compatibility. The
 * production mappings live in {@code group.aitools.nhs.platform.nhs.web}
 * and this class is intentionally not a Spring Web component to avoid
 * registering duplicate /api/v1 routes.
 */
@Validated
public class NhsV1CompatibilityController {

    private static final int MAX_DATASETS = 200;
    private static final List<String> PROFILE_UNSUPPORTED = List.of("api_key", "permissions");
    private static final List<String> SCHEMA_UNSUPPORTED = List.of(
        "ragflow", "vector_search", "metrics", "relationships"
    );

    private final CurrentPrincipalProvider principalProvider;
    private final DataSourceCatalogService catalogService;
    private final YAMLMapper yamlMapper;

    /**
     * 创建 {@code NhsV1CompatibilityController} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param catalogService 目录Service参数
     */
    public NhsV1CompatibilityController(
        CurrentPrincipalProvider principalProvider,
        DataSourceCatalogService catalogService
    ) {
        this(principalProvider, catalogService, new YAMLMapper());
    }

    /**
     * 创建 {@code NhsV1CompatibilityController} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param catalogService 目录Service参数
     * @param yamlMapper {@code yamlMapper}参数
     */
    NhsV1CompatibilityController(
        CurrentPrincipalProvider principalProvider,
        DataSourceCatalogService catalogService,
        YAMLMapper yamlMapper
    ) {
        this.principalProvider = Objects.requireNonNull(principalProvider, "principalProvider");
        this.catalogService = Objects.requireNonNull(catalogService, "catalogService");
        this.yamlMapper = Objects.requireNonNull(yamlMapper, "yamlMapper");
    }

    /**
 * 处理配置档案并返回对应结果。
 *
     * Returns the currently authenticated human or service principal.
     * API keys are intentionally not exposed by this compatibility endpoint.
     */
    @SaCheckLogin
    @GetMapping("/users/profile")
    public NhsResponse<NhsUserProfile> profile(
        @RequestParam(required = false) String username
    ) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (username != null && !username.isBlank()
            && !principal.username().equalsIgnoreCase(username.strip())) {
            throw new ServiceException(
                "Nhs profile lookup only supports the current principal",
                HttpStatus.NOT_IMPLEMENTED
            );
        }
        List<String> roles = principal.roles().stream()
            .map(PlatformRole::key)
            .sorted()
            .toList();
        return NhsResponse.success(new NhsUserProfile(
            principal.id(),
            principal.username(),
            principal.username(),
            legacyRole(principal),
            1,
            null,
            roles,
            List.of(),
            PROFILE_UNSUPPORTED
        ));
    }

    /**
 * 处理{@code schema}并返回对应结果。
 *
     * Searches the authorized local metadata catalog. The returned YAML is a
     * JSON-compatible YAML document so existing Nhs consumers can parse it
     * without losing the richer table/column projections available in Java.
     */
    @SaCheckLogin
    @PostMapping("/schema")
    public NhsResponse<NhsSchemaResponse> schema(
        @Valid @RequestBody(required = false) NhsSchemaRequest request
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        NhsSchemaRequest input = request == null
            ? new NhsSchemaRequest(null, null, null, null, null) : request;
        String provider = normalizeProvider(input.metadata_provider());
        if (!"local".equals(provider)) {
            throw new ServiceException(
                "Nhs metadata provider is not configured: " + provider,
                HttpStatus.NOT_IMPLEMENTED
            );
        }

        String query = input.query() == null ? "" : input.query().strip();
        int requestedTopK = input.ragflow_metadata_top_k() == null
            ? MAX_DATASETS : input.ragflow_metadata_top_k();
        if (requestedTopK <= 0) {
            throw new ServiceException("ragflow_metadata_top_k must be positive", HttpStatus.BAD_REQUEST);
        }
        int topK = Math.min(requestedTopK, MAX_DATASETS);
        List<DatasetView> datasets = catalogService.listDatasets(MAX_DATASETS);
        List<NhsSchemaHit> hits = new ArrayList<>();
        List<Map<String, Object>> schemaDatasets = new ArrayList<>();
        List<String> logs = new ArrayList<>();
        logs.add("[Nhs Compatibility] provider=LOCAL");
        logs.add("[Nhs Compatibility] authorized datasets=" + datasets.size());
        if (input.ragflow_similarity_threshold() != null || input.ragflow_vector_weight() != null) {
            logs.add("[Nhs Compatibility] vector parameters ignored by local metadata provider");
        }

        for (DatasetView dataset : datasets) {
            List<DataTableView> tables = catalogService.metadata(dataset.id());
            if (!matches(dataset, tables, query)) {
                continue;
            }
            hits.add(new NhsSchemaHit(dataset.id(), dataset.name(), dataset.name()));
            schemaDatasets.add(datasetDocument(dataset, tables));
            if (hits.size() >= topK) {
                break;
            }
        }

        String context;
        if (schemaDatasets.isEmpty()) {
            context = datasets.isEmpty()
                ? "[System] No authorized metadata found."
                : "[System] No relevant metadata found. Please refine your query.";
            logs.add("[Nhs Compatibility] no matching datasets");
        } else {
            context = yamlMapper.writeValueAsString(Map.of("datasets", schemaDatasets));
            logs.add("[Nhs Compatibility] matched datasets=" + schemaDatasets.size());
        }
        return NhsResponse.success(new NhsSchemaResponse(
            context,
            List.copyOf(hits),
            "local",
            List.copyOf(logs),
            SCHEMA_UNSUPPORTED
        ));
    }

    /**
     * 处理normalize提供方并返回对应结果。
     *
     * @param provider 提供方参数
     * @return 处理结果
     */
    private String normalizeProvider(String provider) {
        String normalized = provider == null || provider.isBlank()
            ? "local" : provider.strip().toLowerCase(Locale.ROOT);
        if (!Set.of("local", "ragflow").contains(normalized)) {
            throw new ServiceException("Unsupported Nhs metadata provider: " + normalized, HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 处理legacy角色并返回对应结果。
     *
     * @param principal 当前操作主体
     * @return 处理结果
     */
    private String legacyRole(CurrentPrincipal principal) {
        if (principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
            return "admin";
        }
        if (principal.hasRole(PlatformRole.MEMBER)) {
            return "user";
        }
        return principal.roles().stream().map(PlatformRole::key).sorted().findFirst().orElse("user");
    }

    /**
     * 判断{@code matches}是否满足要求。
     *
     * @param dataset 数据集参数
     * @param tables {@code tables}参数
     * @param query 查询参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean matches(DatasetView dataset, List<DataTableView> tables, String query) {
        if (query.isBlank()) {
            return true;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        List<String> datasetValues = new ArrayList<>();
        datasetValues.add(dataset.datasetKey());
        datasetValues.add(dataset.name());
        datasetValues.add(dataset.description());
        if (contains(needle, datasetValues)) {
            return true;
        }
        return tables.stream().anyMatch(table -> {
            List<String> values = new ArrayList<>();
            values.add(table.tableKey());
            values.add(table.physicalSchema());
            values.add(table.physicalName());
            values.add(table.displayName());
            values.add(table.description());
            table.columns().forEach(column -> {
                values.add(column.physicalName());
                values.add(column.displayName());
            });
            return contains(needle, values);
        });
    }

    /**
     * 处理{@code contains}并返回对应结果。
     *
     * @param needle {@code needle}参数
     * @param values {@code values}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean contains(String needle, List<String> values) {
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 处理数据集文档并返回对应结果。
     *
     * @param dataset 数据集参数
     * @param tables {@code tables}参数
     * @return 处理结果
     */
    private Map<String, Object> datasetDocument(DatasetView dataset, List<DataTableView> tables) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("dataset_id", dataset.id());
        document.put("dataset_key", dataset.datasetKey());
        document.put("name", dataset.name());
        document.put("description", dataset.description());
        document.put("status", dataset.status());
        document.put("schemas", dataset.schemaNames());
        document.put("tables", tables.stream().map(this::tableDocument).toList());
        return document;
    }

    /**
     * 处理table文档并返回对应结果。
     *
     * @param table {@code table}参数
     * @return 处理结果
     */
    private Map<String, Object> tableDocument(DataTableView table) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("table_id", table.id());
        document.put("table_key", table.tableKey());
        document.put("schema", table.physicalSchema());
        document.put("name", table.physicalName());
        document.put("display_name", table.displayName());
        document.put("description", table.description());
        document.put("table_type", table.tableType());
        document.put("columns", table.columns().stream().map(this::columnDocument).toList());
        return document;
    }

    /**
     * 处理column文档并返回对应结果。
     *
     * @param column {@code column}参数
     * @return 处理结果
     */
    private Map<String, Object> columnDocument(DataColumnView column) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("column_id", column.id());
        document.put("column_key", column.columnKey());
        document.put("name", column.physicalName());
        document.put("display_name", column.displayName());
        document.put("data_type", column.dataType());
        document.put("description", column.description());
        document.put("primary", column.primary());
        document.put("sensitive", column.sensitive());
        return document;
    }
}
