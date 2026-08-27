package group.aitools.nhs.platform.knowledge.service;

import group.aitools.nhs.platform.common.ContentHashing;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 处理{@code split}并返回对应结果。
 *
 * 表示知识库Chunker相关的领域对象。
 * Deterministic overlapping text chunker; chunk hashes make reprocessing auditable. */
@Component
public class KnowledgeChunker {

    public List<Chunk> split(String content, int chunkSize, int overlap) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (content == null || content.isBlank() || chunkSize < 1 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("知识分块参数无效");
        }
        List<Chunk> result = new ArrayList<>();
        int start = 0;
        int number = 1;
        while (start < content.length()) {
            int desiredEnd = Math.min(content.length(), start + chunkSize);
            int end = boundary(content, start, desiredEnd);
            if (end <= start) {
                end = desiredEnd;
            }
            String text = content.substring(start, end).strip();
            if (!text.isBlank()) {
                result.add(new Chunk(
                    number++, text, start, end, estimateTokens(text), ContentHashing.sha256(text)
                ));
            }
            if (end >= content.length()) {
                break;
            }
            start = Math.max(start + 1, end - overlap);
        }
        return List.copyOf(result);
    }

    /**
     * 处理{@code boundary}并返回对应结果。
     *
     * @param content 待处理内容
     * @param start {@code start}参数
     * @param desiredEnd {@code desiredEnd}参数
     * @return 处理结果
     */
    private int boundary(String content, int start, int desiredEnd) {
        if (desiredEnd >= content.length()) {
            return content.length();
        }
        int minimum = start + Math.max(1, (desiredEnd - start) / 2);
        for (int index = desiredEnd; index >= minimum; index--) {
            char previous = content.charAt(index - 1);
            if (previous == '\n' || previous == '。' || previous == '！' || previous == '？'
                || previous == '.' || previous == '!' || previous == '?') {
                return index;
            }
        }
        return desiredEnd;
    }

    /**
     * 处理{@code estimateTokens}并返回对应结果。
     *
     * @param text 待处理内容
     * @return 处理结果
     */
    private int estimateTokens(String text) {
        return Math.max(1, (int) Math.ceil(text.codePointCount(0, text.length()) / 3.0));
    }

    /**
     * 封装{@code Chunk}相关的不可变数据。
     */
    public record Chunk(
        int number,
        String content,
        int startOffset,
        int endOffset,
        int tokenCount,
        String contentHash
    ) {
    }
}
