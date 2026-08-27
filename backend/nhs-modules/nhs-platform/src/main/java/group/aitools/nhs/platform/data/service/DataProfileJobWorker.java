package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.data.domain.AgentDataDataset;
import group.aitools.nhs.platform.data.domain.AgentDataProfileJob;
import group.aitools.nhs.platform.data.domain.AgentDataProfileJobTable;
import group.aitools.nhs.platform.data.domain.AgentDataSource;
import group.aitools.nhs.platform.data.domain.AgentDataTable;
import group.aitools.nhs.platform.data.mapper.DataCatalogMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Objects;

/** Claims and executes durable metadata profile jobs through bounded read-only JDBC work. */
@Component
@ConditionalOnProperty(
    prefix = "agent.platform.data",
    name = "profile-worker-enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class DataProfileJobWorker {

    private final String workerId = "metadata-profile-" + ManagementFactory.getRuntimeMXBean().getName();
    private final DataProfilePersistenceService persistence;
    private final DataCatalogMapper catalogMapper;
    private final DataTableProfiler profiler;
    private final DataProfileRecommendationService recommendationService;

    public DataProfileJobWorker(
        DataProfilePersistenceService persistence,
        DataCatalogMapper catalogMapper,
        DataTableProfiler profiler,
        DataProfileRecommendationService recommendationService
    ) {
        this.persistence = persistence;
        this.catalogMapper = catalogMapper;
        this.profiler = profiler;
        this.recommendationService = recommendationService;
    }

    @Scheduled(
        fixedDelayString = "${agent.platform.data.profile-worker-delay-ms:2000}",
        initialDelayString = "${agent.platform.data.profile-worker-initial-delay-ms:5000}"
    )
    public void poll() {
        for (int count = 0; count < 2; count++) {
            AgentDataProfileJob job = persistence.claim(workerId);
            if (job == null) {
                return;
            }
            process(job);
        }
    }

    void process(AgentDataProfileJob job) {
        AgentDataProfileJobTable running = null;
        try {
            AgentDataSource source = snapshot(job);
            while (true) {
                AgentDataProfileJob current = persistence.current(job.getId());
                if (current == null || !"running".equals(current.getStatus())
                    || !workerId.equals(current.getWorkerId())) {
                    return;
                }
                if (Boolean.TRUE.equals(current.getCancelRequested())) {
                    persistence.resetAndFinishCancelled(job, running, workerId);
                    return;
                }
                validateSnapshot(job);
                running = persistence.claimTable(job.getId(), workerId);
                if (running == null) {
                    break;
                }
                current = persistence.current(job.getId());
                if (current == null || Boolean.TRUE.equals(current.getCancelRequested())) {
                    persistence.resetAndFinishCancelled(job, running, workerId);
                    return;
                }
                Long tableId = running.getTableId();
                AgentDataTable table = catalogMapper.selectTable(tableId);
                List<AgentDataColumn> columns = catalogMapper.selectColumns(job.getDatasetId()).stream()
                    .filter(column -> tableId.equals(column.getTableId()))
                    .toList();
                if (table == null || !"active".equals(table.getStatus())
                    || !Boolean.TRUE.equals(table.getMetadataPresent())) {
                    throw new IllegalStateException("画像任务引用的数据表已失效");
                }
                try {
                    persistence.renew(job.getId(), workerId);
                    var result = profiler.profile(
                        source, table, columns, running.getSourceHash()
                    );
                    persistence.renew(job.getId(), workerId);
                    persistence.completeTable(job, running, result, workerId);
                } catch (Exception tableFailure) {
                    persistence.failTable(job, running, workerId, safeError(tableFailure));
                }
                running = null;
            }
            AgentDataProfileJob current = persistence.current(job.getId());
            if (current != null && Boolean.TRUE.equals(current.getCancelRequested())) {
                persistence.resetAndFinishCancelled(job, null, workerId);
                return;
            }
            persistence.renew(job.getId(), workerId);
            recommendationService.generate(job.getDatasetId(), job.getId());
            persistence.finish(job, workerId);
        } catch (Exception exception) {
            persistence.failJob(job, workerId, safeError(exception));
        }
    }

    private AgentDataSource snapshot(AgentDataProfileJob job) {
        validateSnapshot(job);
        AgentDataSource source = catalogMapper.selectSource(job.getDataSourceId());
        return source;
    }

    private void validateSnapshot(AgentDataProfileJob job) {
        AgentDataDataset dataset = catalogMapper.selectDataset(job.getDatasetId());
        AgentDataSource source = catalogMapper.selectSource(job.getDataSourceId());
        if (dataset == null || source == null
            || !job.getDataSourceId().equals(dataset.getDataSourceId())
            || !Objects.equals(job.getDatasetRevision(), dataset.getRevisionNo())
            || !Objects.equals(job.getDataSourceRevision(), source.getRevisionNo())
            || !"active".equals(dataset.getStatus()) || !"active".equals(source.getStatus())) {
            throw new IllegalStateException("数据源或数据集配置已变化，画像任务必须重新创建");
        }
    }

    private String safeError(Throwable exception) {
        String message = exception == null ? null : exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception == null ? "元数据画像失败" : exception.getClass().getSimpleName();
        }
        String safe = message
            .replaceAll("(?i)bearer\\s+[^\\s,;]+", "Bearer [REDACTED]")
            .replaceAll("(?i)(token|secret|password|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]")
            .replaceAll("[\\r\\n]+", " ")
            .strip();
        return safe.length() <= 1000 ? safe : safe.substring(0, 1000);
    }

}
