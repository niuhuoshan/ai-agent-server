package group.aitools.nhs.platform.knowledge.service;

import java.io.InputStream;

/**
 * 定义知识库文件存储相关能力的服务契约。
 */
public interface KnowledgeFileStorage {

    /**
     * 处理{@code put}并返回对应结果。
     *
     * @param documentId 资源标识
     * @param input {@code input}参数
     * @param expectedSize 数量上限
     * @return 处理结果
     */
    StoredFile put(Long documentId, InputStream input, long expectedSize);

    /**
     * 处理{@code open}并返回对应结果。
     *
     * @param storageRef 存储Ref参数
     * @return 处理结果
     */
    InputStream open(String storageRef);

    /**
     * 删除{@code delete}。
     *
     * @param storageRef 存储Ref参数
     */
    void delete(String storageRef);

    /**
     * 封装Stored文件相关的不可变数据。
     */
    record StoredFile(String storageRef, long sizeBytes, String sha256) {
    }
}
