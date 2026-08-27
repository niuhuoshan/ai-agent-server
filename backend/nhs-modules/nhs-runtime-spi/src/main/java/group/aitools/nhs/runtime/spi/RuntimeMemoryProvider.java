package group.aitools.nhs.runtime.spi;

import java.util.List;

/**
 * 获取{@code resolve}。
 *
 * 定义运行时记忆相关的处理能力契约。
 * Resolves governed frozen memory; runtime adapters must treat it as read-only context. */
@FunctionalInterface
public interface RuntimeMemoryProvider {

    List<RuntimeMemoryDefinition> resolve(AgentRunRequest request);

    /**
     * 处理{@code empty}并返回对应结果。
     *
     * @return 处理结果
     */
    static RuntimeMemoryProvider empty() {
        return request -> List.of();
    }
}
