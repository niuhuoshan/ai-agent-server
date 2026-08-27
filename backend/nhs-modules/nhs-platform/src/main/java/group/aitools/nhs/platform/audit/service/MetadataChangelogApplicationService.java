package group.aitools.nhs.platform.audit.service;

import group.aitools.nhs.runtime.spi.RuntimeSecretScrubber;
import group.aitools.nhs.platform.audit.mapper.MetadataChangelogQueryMapper;
import group.aitools.nhs.platform.audit.mapper.MetadataChangelogStatisticRow;
import group.aitools.nhs.platform.audit.web.MetadataChangeDiffView;
import group.aitools.nhs.platform.audit.web.MetadataChangelogPageView;
import group.aitools.nhs.platform.audit.web.MetadataChangelogStatsView;
import group.aitools.nhs.platform.data.persistence.row.MetadataChangeRow;
import group.aitools.nhs.platform.data.web.MetadataGovernanceContracts.MetadataChangeView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 负责元数据Changelog相关的业务编排与领域规则处理。
 * Administrator-wide metadata changelog, diff and aggregate operations. */
@Service
public class MetadataChangelogApplicationService {

    private static final Duration MAX_RANGE = Duration.ofDays(365);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final MetadataChangelogQueryMapper mapper;
    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code MetadataChangelogApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param mapper {@code mapper}参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public MetadataChangelogApplicationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        MetadataChangelogQueryMapper mapper,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.mapper = mapper;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 查询{@code search}列表。
     *
     * @param datasetId 资源标识
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param action {@code action}参数
     * @param actorId 资源标识
     * @param createdFrom {@code createdFrom}参数
     * @param createdTo {@code createdTo}参数
     * @param page {@code page}参数
     * @param size 数量上限
     * @return 处理结果
     */
    public MetadataChangelogPageView search(
        Long datasetId,
        String resourceType,
        Long resourceId,
        String action,
        Long actorId,
        LocalDateTime createdFrom,
        LocalDateTime createdTo,
        int page,
        int size
    ) {
        authorize("list");
        int boundedPage = Math.max(1, page);
        int boundedSize = Math.max(1, Math.min(size, 200));
        Range range = range(createdFrom, createdTo);
        String type = token(resourceType, 64, "资源类型");
        String operation = token(action, 32, "变更动作");
        int offset = boundedPage > 10_000 / boundedSize
            ? 10_000 : (boundedPage - 1) * boundedSize;
        List<MetadataChangeView> rows = offset >= 10_000 ? List.of() : mapper.search(
            datasetId, type, resourceId, operation, actorId, range.from(), range.to(), offset, boundedSize
        ).stream().map(this::view).toList();
        long total = mapper.count(datasetId, type, resourceId, operation, actorId, range.from(), range.to());
        return new MetadataChangelogPageView(total, boundedPage, boundedSize, rows);
    }

    /**
     * 处理统计并返回对应结果。
     *
     * @param days {@code days}参数
     * @return 处理结果
     */
    public MetadataChangelogStatsView statistics(int days) {
        authorize("list");
        int boundedDays = Math.max(1, Math.min(days, 365));
        LocalDateTime to = LocalDateTime.now().plusSeconds(1);
        LocalDateTime from = to.minusDays(boundedDays);
        List<MetadataChangelogStatsView.MetadataChangelogBreakdownView> breakdown = mapper.statistics(from, to).stream()
            .map(row -> new MetadataChangelogStatsView.MetadataChangelogBreakdownView(
                row.getResourceType(), row.getAction(), row.getChangeCount()
            )).toList();
        long total = breakdown.stream().mapToLong(MetadataChangelogStatsView.MetadataChangelogBreakdownView::count).sum();
        return new MetadataChangelogStatsView(total, from, to, breakdown);
    }

    /**
     * 处理{@code diff}并返回对应结果。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    public MetadataChangeDiffView diff(Long id) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        authorize("view");
        if (id == null || id <= 0) throw new ServiceException("变更记录 ID 无效", HttpStatus.BAD_REQUEST);
        MetadataChangeRow row = mapper.selectById(id);
        if (row == null) throw new ServiceException("变更记录不存在", HttpStatus.NOT_FOUND);
        Map<String, Object> before = json(row.getBeforeJson());
        Map<String, Object> after = json(row.getAfterJson());
        Set<String> fields = new LinkedHashSet<>();
        fields.addAll(before.keySet());
        fields.addAll(after.keySet());
        List<MetadataChangeDiffView.FieldChangeView> changes = new ArrayList<>();
        for (String field : fields) {
            Object oldValue = before.get(field);
            Object newValue = after.get(field);
            if (!java.util.Objects.equals(oldValue, newValue)) {
                changes.add(new MetadataChangeDiffView.FieldChangeView(field, oldValue, newValue));
            }
        }
        if (changes.isEmpty() && ("create".equals(row.getAction()) || "archive".equals(row.getAction()))) {
            changes = List.of(new MetadataChangeDiffView.FieldChangeView(
                "全部数据", "create".equals(row.getAction()) ? null : before, "create".equals(row.getAction()) ? after : null
            ));
        }
        String operation = row.getAction() == null ? "unknown" : row.getAction();
        String summary = operation + " " + row.getResourceType() + " #" + row.getResourceId();
        return new MetadataChangeDiffView(
            row.getId(), row.getDatasetId(), row.getResourceType(), row.getResourceId(), operation,
            summary, List.copyOf(changes)
        );
    }

    /**
     * 处理资源并返回对应结果。
     *
     * @param resourceType 业务类型
     * @param resourceId 资源标识
     * @param page {@code page}参数
     * @param size 数量上限
     * @return 处理结果
     */
    public MetadataChangelogPageView resource(String resourceType, Long resourceId, int page, int size) {
        return search(null, resourceType, resourceId, null, null, null, null, page, size);
    }

    /**
     * 处理{@code view}并返回对应结果。
     *
     * @param row {@code row}参数
     * @return 处理结果
     */
    private MetadataChangeView view(MetadataChangeRow row) {
        return new MetadataChangeView(
            row.getId(), row.getDatasetId(), row.getResourceType(), row.getResourceId(), row.getAction(),
            row.getBeforeJson(), row.getAfterJson(), row.getBeforeHash(), row.getAfterHash(),
            row.getActorId(), row.getCreatedAt()
        );
    }

    /**
     * 处理{@code json}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> json(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = jsonMapper.readValue(value, MAP_TYPE);
            if (parsed == null) return Map.of();
            return Collections.unmodifiableMap(RuntimeSecretScrubber.sanitizeMap(parsed));
        } catch (RuntimeException exception) {
            return Map.of("redacted", true, "invalidJson", true);
        }
    }

    /**
     * 处理{@code range}并返回对应结果。
     *
     * @param from {@code from}参数
     * @param to {@code to}参数
     * @return 处理结果
     */
    private Range range(LocalDateTime from, LocalDateTime to) {
        LocalDateTime effectiveTo = to == null ? LocalDateTime.now().plusSeconds(1) : to;
        LocalDateTime effectiveFrom = from == null ? effectiveTo.minusDays(30) : from;
        if (!effectiveFrom.isBefore(effectiveTo)) throw new ServiceException("变更开始时间必须早于结束时间", HttpStatus.BAD_REQUEST);
        if (Duration.between(effectiveFrom, effectiveTo).compareTo(MAX_RANGE) > 0) {
            throw new ServiceException("单次变更查询范围不能超过365天", HttpStatus.BAD_REQUEST);
        }
        return new Range(effectiveFrom, effectiveTo);
    }

    /**
     * 将输入数据转换为{@code ken}。
     *
     * @param value {@code value}参数
     * @param maximum {@code maximum}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String token(String value, int maximum, String label) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > maximum || normalized.indexOf('\0') >= 0
            || !normalized.matches("[a-z0-9_.:-]+")) {
            throw new ServiceException(label + "无效", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 处理{@code authorize}相关逻辑。
     *
     * @param action {@code action}参数
     */
    private void authorize(String action) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "audit", null, "metadata-changelog", action, ResourceState.ACTIVE, true, Set.of()
        ));
    }

    /**
     * 封装{@code Range}相关的不可变数据。
     */
    private record Range(LocalDateTime from, LocalDateTime to) {
    }
}
