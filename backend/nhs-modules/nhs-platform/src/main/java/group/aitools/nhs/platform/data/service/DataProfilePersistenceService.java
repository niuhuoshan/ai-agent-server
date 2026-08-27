package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.data.domain.AgentDataProfileJob;
import group.aitools.nhs.platform.data.domain.AgentDataProfileJobTable;
import group.aitools.nhs.platform.data.domain.AgentDataTableProfile;
import group.aitools.nhs.platform.data.mapper.DataGovernanceMapper;
import group.aitools.nhs.platform.data.mapper.DataProfileMapper;
import group.aitools.nhs.platform.data.persistence.row.MetadataChangeRow;
import group.aitools.nhs.platform.data.service.DataTableProfiler.ProfileResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Short transactions around JDBC/model work performed by the profile worker. */
@Service
public class DataProfilePersistenceService {

    private final PlatformIdGenerator idGenerator;
    private final DataProfileMapper mapper;
    private final DataGovernanceMapper governanceMapper;
    private final JsonMapper jsonMapper;

    public DataProfilePersistenceService(
        PlatformIdGenerator idGenerator,
        DataProfileMapper mapper,
        DataGovernanceMapper governanceMapper,
        JsonMapper jsonMapper
    ) {
        this.idGenerator = idGenerator;
        this.mapper = mapper;
        this.governanceMapper = governanceMapper;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentDataProfileJob claim(String workerId) {
        mapper.resetExpiredCancelledJobTables();
        mapper.finishExpiredCancelledJobs();
        mapper.failExhaustedJobTables();
        mapper.failExhaustedJobs();
        AgentDataProfileJob job = mapper.claimJob(workerId);
        if (job != null && Boolean.TRUE.equals(job.getRecovered())) {
            mapper.recoverRunningJobTables(job.getId());
        }
        return job;
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentDataProfileJobTable claimTable(Long jobId, String workerId) {
        if (mapper.renewJobLease(jobId, workerId) != 1) {
            throw new IllegalStateException("元数据画像任务租约已失效");
        }
        AgentDataProfileJobTable item = mapper.claimNextJobTable(jobId, workerId);
        if (item != null && mapper.setCurrentTable(jobId, workerId, item.getTableId()) != 1) {
            throw new IllegalStateException("元数据画像任务租约已失效");
        }
        return item;
    }

    @Transactional(rollbackFor = Exception.class)
    public void renew(Long jobId, String workerId) {
        if (mapper.renewJobLease(jobId, workerId) != 1) {
            throw new IllegalStateException("元数据画像任务租约已失效");
        }
    }

    public AgentDataProfileJob current(Long jobId) {
        return mapper.selectClaimedJob(jobId);
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentDataTableProfile completeTable(
        AgentDataProfileJob job,
        AgentDataProfileJobTable item,
        ProfileResult result,
        String workerId
    ) {
        AgentDataTableProfile previous = mapper.selectLatestProfile(job.getDatasetId(), item.getTableId());
        boolean ignored = result.ignored();
        String decision = result.ignoreDecision();
        if (previous != null && previous.getIgnoreDecision() != null
            && previous.getIgnoreDecision().startsWith("manual_")) {
            ignored = Boolean.TRUE.equals(previous.getIgnored());
            decision = previous.getIgnoreDecision();
        }
        LocalDateTime now = LocalDateTime.now();
        AgentDataTableProfile profile = new AgentDataTableProfile();
        profile.setId(idGenerator.nextId());
        profile.setDatasetId(job.getDatasetId());
        profile.setTableId(item.getTableId());
        profile.setJobId(job.getId());
        profile.setSourceHash(result.sourceHash());
        profile.setTableType(result.tableType());
        profile.setTerm(result.term());
        profile.setDescription(result.description());
        profile.setDdlText(result.ddl());
        profile.setRowCountEstimate(result.rowCountEstimate());
        profile.setColumnCount(result.columns().size());
        profile.setColumnsProfileJson(jsonMapper.writeValueAsString(result.columns()));
        profile.setSampleDataJson(jsonMapper.writeValueAsString(result.samples()));
        profile.setSampleRowCount(result.samples().size());
        profile.setSampleRedacted(result.sampleRedacted());
        profile.setConfidenceScore(result.confidenceScore());
        profile.setConfidenceReason(result.confidenceReason());
        profile.setTagsJson(jsonMapper.writeValueAsString(result.tags()));
        profile.setTemporaryClassification(result.temporaryClassification());
        profile.setIgnored(ignored);
        profile.setIgnoreDecision(decision);
        profile.setProfileJson(result.profileJson());
        profile.setRevisionNo(1);
        profile.setCreatedBy(job.getRequestedBy());
        profile.setCreatedAt(now);
        if (mapper.insertProfile(profile) != 1) {
            throw new IllegalStateException("元数据表画像写入失败");
        }
        if (mapper.completeJobTable(
            job.getId(), item.getId(), profile.getId(), workerId, now
        ) != 1) {
            throw new IllegalStateException("元数据画像任务租约已失效");
        }
        if (mapper.refreshJobProgress(job.getId(), workerId) != 1) {
            throw new IllegalStateException("元数据画像任务进度更新失败");
        }
        change(
            job.getDatasetId(), "table_profile", profile.getId(), "create", null,
            profileAudit(profile), job.getRequestedBy(), now
        );
        return profile;
    }

    @Transactional(rollbackFor = Exception.class)
    public void failTable(
        AgentDataProfileJob job,
        AgentDataProfileJobTable item,
        String workerId,
        String error
    ) {
        LocalDateTime now = LocalDateTime.now();
        if (mapper.failJobTable(job.getId(), item.getId(), workerId, error, now) != 1) {
            return;
        }
        mapper.refreshJobProgress(job.getId(), workerId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void resetAndFinishCancelled(
        AgentDataProfileJob job,
        AgentDataProfileJobTable running,
        String workerId
    ) {
        if (running != null) {
            if (mapper.resetJobTable(job.getId(), running.getId(), workerId) != 1) {
                throw new IllegalStateException("元数据画像任务租约已失效");
            }
        }
        finish(job, workerId);
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentDataProfileJob finish(AgentDataProfileJob expected, String workerId) {
        AgentDataProfileJob before = mapper.selectClaimedJob(expected.getId());
        LocalDateTime now = LocalDateTime.now();
        if (mapper.finishJob(expected.getId(), workerId, now) != 1) {
            throw new IllegalStateException("元数据画像任务租约已失效");
        }
        AgentDataProfileJob after = mapper.selectClaimedJob(expected.getId());
        change(
            expected.getDatasetId(), "profile_job", expected.getId(), "update",
            jobAudit(before), jobAudit(after), expected.getRequestedBy(), now
        );
        return after;
    }

    @Transactional(rollbackFor = Exception.class)
    public void failJob(AgentDataProfileJob expected, String workerId, String error) {
        AgentDataProfileJob before = mapper.selectClaimedJob(expected.getId());
        LocalDateTime now = LocalDateTime.now();
        mapper.failRunningJobTables(expected.getId(), workerId, error, now);
        mapper.refreshJobProgress(expected.getId(), workerId);
        if (mapper.failJob(expected.getId(), workerId, error, now) != 1) {
            return;
        }
        AgentDataProfileJob after = mapper.selectClaimedJob(expected.getId());
        change(
            expected.getDatasetId(), "profile_job", expected.getId(), "update",
            jobAudit(before), jobAudit(after), expected.getRequestedBy(), now
        );
    }

    private Map<String, Object> profileAudit(AgentDataTableProfile profile) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("profileId", profile.getId());
        value.put("jobId", profile.getJobId());
        value.put("tableId", profile.getTableId());
        value.put("sourceHash", profile.getSourceHash());
        value.put("term", profile.getTerm());
        value.put("description", profile.getDescription());
        value.put("columnCount", profile.getColumnCount());
        value.put("sampleRowCount", profile.getSampleRowCount());
        value.put("sampleRedacted", profile.getSampleRedacted());
        value.put("confidenceScore", profile.getConfidenceScore());
        value.put("temporaryClassification", profile.getTemporaryClassification());
        value.put("ignored", profile.getIgnored());
        value.put("ignoreDecision", profile.getIgnoreDecision());
        return value;
    }

    private Map<String, Object> jobAudit(AgentDataProfileJob job) {
        if (job == null) {
            return Map.of();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("jobId", job.getId());
        value.put("status", DataProfileApplicationService.externalJobStatus(job.getStatus()));
        value.put("completedTables", job.getCompletedTables());
        value.put("failedTables", job.getFailedTables());
        value.put("progressPercent", job.getProgressPercent());
        value.put("errorMessage", job.getErrorMessage());
        return value;
    }

    private void change(
        Long datasetId,
        String resourceType,
        Long resourceId,
        String action,
        Object before,
        Object after,
        Long actorId,
        LocalDateTime now
    ) {
        String beforeJson = before == null ? null : jsonMapper.writeValueAsString(before);
        String afterJson = after == null ? null : jsonMapper.writeValueAsString(after);
        MetadataChangeRow row = new MetadataChangeRow();
        row.setId(idGenerator.nextId());
        row.setDatasetId(datasetId);
        row.setResourceType(resourceType);
        row.setResourceId(resourceId);
        row.setAction(action);
        row.setBeforeJson(beforeJson);
        row.setAfterJson(afterJson);
        row.setBeforeHash(beforeJson == null ? null : ContentHashing.sha256(beforeJson));
        row.setAfterHash(afterJson == null ? null : ContentHashing.sha256(afterJson));
        row.setActorId(actorId);
        row.setCreatedAt(now);
        if (governanceMapper.insertChange(row) != 1) {
            throw new IllegalStateException("元数据画像审计记录写入失败");
        }
    }
}
