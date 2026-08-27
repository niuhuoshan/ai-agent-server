package group.aitools.nhs.platform.memory.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 表示记忆Embedding工作进程相关的领域对象。
 * Incrementally embeds newly-created or changed memories without blocking chat writes. */
@Slf4j
@Component
public class MemoryEmbeddingWorker {

    private final MemoryVectorApplicationService vectorService;

    public MemoryEmbeddingWorker(MemoryVectorApplicationService vectorService) {
        this.vectorService = vectorService;
    }

    /**
     * 处理{@code indexPending}相关逻辑。
     */
    @Scheduled(
        fixedDelayString = "${agent.memory.embedding-delay-ms:60000}",
        initialDelayString = "${agent.memory.embedding-initial-delay-ms:30000}"
    )
    public void indexPending() {
        try {
            int indexed = vectorService.indexPendingBatch(32);
            if (indexed > 0) {
                log.info("Indexed {} pending memory embeddings", indexed);
            }
        } catch (RuntimeException exception) {
            log.warn("Memory embedding batch failed: {}", safeMessage(exception));
        }
    }

    /**
     * 处理safe消息并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @return 处理结果
     */
    private String safeMessage(RuntimeException exception) {
        String value = exception.getMessage();
        if (value == null || value.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        String normalized = value.replaceAll("[\\r\\n]+", " ").strip();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200);
    }
}
