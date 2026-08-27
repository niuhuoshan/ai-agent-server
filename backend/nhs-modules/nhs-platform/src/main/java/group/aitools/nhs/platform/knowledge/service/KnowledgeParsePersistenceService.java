package group.aitools.nhs.platform.knowledge.service;

import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeChunk;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeDocument;
import group.aitools.nhs.platform.knowledge.mapper.KnowledgeCatalogMapper;
import group.aitools.nhs.platform.knowledge.persistence.row.KnowledgeParseJobRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 负责知识库ParsePersistence相关的业务编排与领域规则处理。
 * Short database transactions around durable knowledge parsing work. */
@Service
public class KnowledgeParsePersistenceService {

    private final KnowledgeCatalogMapper mapper;

    public KnowledgeParsePersistenceService(KnowledgeCatalogMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 处理{@code claim}并返回对应结果。
     *
     * @param workerId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeParseJobRow claim(String workerId) {
        return mapper.claimParseJob(workerId);
    }

    /**
     * 处理{@code start}并返回对应结果。
     *
     * @param documentId 资源标识
     * @param revision {@code revision}参数
     * @param allowResume {@code allowResume}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean start(Long documentId, Long revision, boolean allowResume) {
        return mapper.markDocumentProcessing(documentId, revision, allowResume, LocalDateTime.now()) == 1;
    }

    /**
     * 处理{@code renew}相关逻辑。
     *
     * @param job 作业参数
     * @param workerId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void renew(KnowledgeParseJobRow job, String workerId) {
        if (mapper.renewParseJob(job.getId(), workerId) != 1) {
            throw new IllegalStateException("知识文档解析作业租约已失效");
        }
    }

    /**
     * 处理{@code complete}相关逻辑。
     *
     * @param job 作业参数
     * @param workerId 资源标识
     * @param expected {@code expected}参数
     * @param parserType 业务类型
     * @param metadataJson 元数据Json参数
     * @param chunks {@code chunks}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void complete(
        KnowledgeParseJobRow job,
        String workerId,
        AgentKnowledgeDocument expected,
        String parserType,
        String metadataJson,
        List<AgentKnowledgeChunk> chunks
    ) {
        mapper.lockDocument(expected.getId());
        AgentKnowledgeDocument current = mapper.selectDocumentById(expected.getId());
        if (current == null || !expected.getRevisionNo().equals(current.getRevisionNo())
            || !"processing".equals(current.getStatus())) {
            throw new IllegalStateException("知识文档在解析期间发生变化");
        }
        mapper.deleteDocumentChunks(current.getId());
        chunks.forEach(mapper::insertChunk);
        LocalDateTime now = LocalDateTime.now();
        if (mapper.completeDocument(
            current.getId(), current.getRevisionNo(), parserType, chunks.size(), metadataJson, now
        ) != 1) {
            throw new IllegalStateException("知识文档解析结果保存冲突");
        }
        if (mapper.completeParseJob(job.getId(), workerId) != 1) {
            throw new IllegalStateException("知识文档解析作业租约已失效");
        }
    }

    /**
     * 处理{@code fail}相关逻辑。
     *
     * @param job 作业参数
     * @param workerId 资源标识
     * @param documentId 资源标识
     * @param revision {@code revision}参数
     * @param error {@code error}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void fail(
        KnowledgeParseJobRow job,
        String workerId,
        Long documentId,
        Long revision,
        String error
    ) {
        if (mapper.failParseJob(job.getId(), workerId, error) != 1) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean retry = job.getAttemptNo() < job.getMaxAttempts();
        if (retry) {
            mapper.retryDocument(documentId, revision, error, now);
        } else {
            mapper.failDocument(documentId, revision, error, now);
        }
    }
}
