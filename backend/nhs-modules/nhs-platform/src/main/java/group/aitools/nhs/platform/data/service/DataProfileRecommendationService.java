package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataProfileRelationRecommendation;
import group.aitools.nhs.platform.data.domain.AgentDataTable;
import group.aitools.nhs.platform.data.domain.AgentDataTableProfile;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import group.aitools.nhs.platform.data.mapper.DataGovernanceMapper;
import group.aitools.nhs.platform.data.mapper.DataProfileMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Derives bounded, explainable relationship candidates from completed table profiles. */
@Service
public class DataProfileRecommendationService {

    private static final int MAX_RECOMMENDATIONS = 1000;

    private final PlatformIdGenerator idGenerator;
    private final DataCatalogMapper catalogMapper;
    private final DataGovernanceMapper governanceMapper;
    private final DataProfileMapper profileMapper;

    public DataProfileRecommendationService(
        PlatformIdGenerator idGenerator,
        DataCatalogMapper catalogMapper,
        DataGovernanceMapper governanceMapper,
        DataProfileMapper profileMapper
    ) {
        this.idGenerator = idGenerator;
        this.catalogMapper = catalogMapper;
        this.governanceMapper = governanceMapper;
        this.profileMapper = profileMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public int generate(Long datasetId, Long jobId) {
        Map<Long, AgentDataTable> tables = catalogMapper.selectTables(datasetId).stream()
            .filter(table -> "active".equals(table.getStatus()))
            .filter(table -> Boolean.TRUE.equals(table.getMetadataPresent()))
            .collect(Collectors.toMap(AgentDataTable::getId, Function.identity()));
        Map<Long, AgentDataTableProfile> profiles = profileMapper.selectJobProfiles(datasetId, jobId).stream()
            .collect(Collectors.toMap(AgentDataTableProfile::getTableId, Function.identity()));
        Map<Long, List<AgentDataColumn>> columns = new LinkedHashMap<>();
        for (AgentDataColumn column : catalogMapper.selectColumns(datasetId)) {
            if ("active".equals(column.getStatus()) && Boolean.TRUE.equals(column.getMetadataPresent())) {
                columns.computeIfAbsent(column.getTableId(), ignored -> new ArrayList<>()).add(column);
            }
        }
        Set<TablePair> existing = governanceMapper.selectRelationships(datasetId).stream()
            .filter(relation -> "active".equals(relation.getStatus()))
            .map(relation -> new TablePair(relation.getSourceTableId(), relation.getTargetTableId()))
            .collect(Collectors.toSet());
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<Long, List<AgentDataColumn>> entry : columns.entrySet()) {
            AgentDataTable source = tables.get(entry.getKey());
            AgentDataTableProfile sourceProfile = profiles.get(entry.getKey());
            if (source == null || ignored(sourceProfile)) {
                continue;
            }
            for (AgentDataColumn sourceColumn : entry.getValue()) {
                String hint = linkHint(sourceColumn.getPhysicalName());
                if (hint == null) {
                    continue;
                }
                candidates.addAll(candidates(
                    source, sourceColumn, hint, tables, columns, profiles, existing
                ));
            }
        }
        candidates.sort(Comparator.comparing(Candidate::confidence).reversed());
        Set<String> deduplicated = new HashSet<>();
        int inserted = 0;
        LocalDateTime now = LocalDateTime.now();
        for (Candidate candidate : candidates) {
            if (inserted >= MAX_RECOMMENDATIONS) {
                break;
            }
            String key = candidate.sourceColumn().getId() + ":" + candidate.target().getId()
                + ":" + candidate.targetColumn().getId();
            if (!deduplicated.add(key)) {
                continue;
            }
            AgentDataProfileRelationRecommendation row = new AgentDataProfileRelationRecommendation();
            row.setId(idGenerator.nextId());
            row.setDatasetId(datasetId);
            row.setProfileJobId(jobId);
            row.setSourceTableId(candidate.source().getId());
            row.setSourceColumnId(candidate.sourceColumn().getId());
            row.setTargetTableId(candidate.target().getId());
            row.setTargetColumnId(candidate.targetColumn().getId());
            row.setConfidenceScore(candidate.confidence());
            row.setJoinType("left");
            row.setJoinCondition(joinCondition(candidate));
            row.setReason(candidate.reason());
            row.setStatus("pending");
            row.setCreatedAt(now);
            inserted += profileMapper.insertRecommendation(row);
        }
        return inserted;
    }

    private List<Candidate> candidates(
        AgentDataTable source,
        AgentDataColumn sourceColumn,
        String hint,
        Map<Long, AgentDataTable> tables,
        Map<Long, List<AgentDataColumn>> columns,
        Map<Long, AgentDataTableProfile> profiles,
        Set<TablePair> existing
    ) {
        List<Candidate> result = new ArrayList<>();
        for (AgentDataTable target : tables.values()) {
            if (source.getId().equals(target.getId()) || ignored(profiles.get(target.getId()))
                || existing.contains(new TablePair(source.getId(), target.getId()))) {
                continue;
            }
            NameMatch match = tableMatch(hint, target.getPhysicalName());
            if (match == null) {
                continue;
            }
            AgentDataColumn targetColumn = targetColumn(
                hint, sourceColumn, columns.getOrDefault(target.getId(), List.of())
            );
            if (targetColumn == null || !compatible(sourceColumn.getDataType(), targetColumn.getDataType())) {
                continue;
            }
            int score = match.exact() ? 82 : 68;
            if (Boolean.TRUE.equals(targetColumn.getIsPrimary())) {
                score += 10;
            }
            if (sourceColumn.getPhysicalName().equalsIgnoreCase(
                singular(target.getPhysicalName()) + "_id"
            )) {
                score += 5;
            }
            result.add(new Candidate(
                source, sourceColumn, target, targetColumn,
                BigDecimal.valueOf(Math.min(97, score)),
                "字段 " + sourceColumn.getPhysicalName() + " 与目标表 "
                    + target.getPhysicalName() + " 的名称、类型和候选主键匹配"
            ));
        }
        return result.stream()
            .sorted(Comparator.comparing(Candidate::confidence).reversed())
            .limit(3)
            .toList();
    }

    private AgentDataColumn targetColumn(
        String hint,
        AgentDataColumn source,
        List<AgentDataColumn> candidates
    ) {
        Map<String, AgentDataColumn> byName = new HashMap<>();
        candidates.forEach(column -> byName.put(column.getPhysicalName().toLowerCase(Locale.ROOT), column));
        for (String name : List.of(
            "id", hint + "_id", source.getPhysicalName().toLowerCase(Locale.ROOT),
            hint + "_no", hint + "_code"
        )) {
            AgentDataColumn found = byName.get(name);
            if (found != null) {
                return found;
            }
        }
        return candidates.stream().filter(column -> Boolean.TRUE.equals(column.getIsPrimary()))
            .findFirst().orElse(null);
    }

    private String linkHint(String name) {
        String value = name == null ? "" : name.toLowerCase(Locale.ROOT).strip();
        for (String suffix : List.of("_id", "_no", "_code")) {
            if (value.endsWith(suffix) && value.length() > suffix.length() + 1) {
                return singular(value.substring(0, value.length() - suffix.length()));
            }
        }
        return null;
    }

    private NameMatch tableMatch(String hint, String tableName) {
        String normalized = singular(tableName == null ? "" : tableName.toLowerCase(Locale.ROOT));
        if (normalized.equals(hint) || normalized.endsWith("_" + hint)) {
            return new NameMatch(true);
        }
        if (normalized.contains("_" + hint + "_") || normalized.startsWith(hint + "_")) {
            return new NameMatch(false);
        }
        return null;
    }

    private boolean compatible(String source, String target) {
        return typeFamily(source).equals(typeFamily(target));
    }

    private String typeFamily(String value) {
        String type = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (type.contains("int") || type.contains("number") || type.contains("decimal")
            || type.contains("numeric")) {
            return "number";
        }
        if (type.contains("char") || type.contains("text") || type.contains("string")
            || type.contains("uuid")) {
            return "string";
        }
        return type.replaceAll("\\(.*", "").strip();
    }

    private boolean ignored(AgentDataTableProfile profile) {
        return profile == null || Boolean.TRUE.equals(profile.getIgnored());
    }

    private String singular(String value) {
        if (value.endsWith("ies") && value.length() > 4) {
            return value.substring(0, value.length() - 3) + "y";
        }
        if (value.endsWith("ses") && value.length() > 4) {
            return value.substring(0, value.length() - 2);
        }
        if (value.endsWith("s") && !value.endsWith("ss") && value.length() > 3) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String joinCondition(Candidate candidate) {
        return quote(candidate.source().getPhysicalName()) + "." + quote(candidate.sourceColumn().getPhysicalName())
            + " = " + quote(candidate.target().getPhysicalName()) + "."
            + quote(candidate.targetColumn().getPhysicalName());
    }

    private String quote(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private record TablePair(Long source, Long target) {
        private TablePair {
            if (source != null && target != null && source > target) {
                Long originalSource = source;
                source = target;
                target = originalSource;
            }
        }
    }

    private record NameMatch(boolean exact) {
    }

    private record Candidate(
        AgentDataTable source,
        AgentDataColumn sourceColumn,
        AgentDataTable target,
        AgentDataColumn targetColumn,
        BigDecimal confidence,
        String reason
    ) {
    }
}
