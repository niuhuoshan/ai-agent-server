package group.aitools.nhs.platform.knowledge.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 表示Local知识库文件存储相关的领域对象。
 * Local persistent-volume storage with opaque references and symlink escape protection. */
@Component
public class LocalKnowledgeFileStorage implements KnowledgeFileStorage {

    private final Path root;

    public LocalKnowledgeFileStorage(
        @Value("${agent.platform.knowledge.storage-root:./data/agent-knowledge}") Path root
    ) {
        try {
            Files.createDirectories(root);
            this.root = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new IllegalStateException("无法初始化知识文档存储目录", exception);
        }
        if (Files.isSymbolicLink(this.root)) {
            throw new IllegalStateException("知识文档存储根目录不能是符号链接");
        }
    }

    /**
     * 处理{@code put}并返回对应结果。
     *
     * @param documentId 资源标识
     * @param input {@code input}参数
     * @param expectedSize 数量上限
     * @return 处理结果
     */
    @Override
    public StoredFile put(Long documentId, InputStream input, long expectedSize) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (documentId == null || documentId <= 0 || input == null || expectedSize < 0) {
            throw new IllegalArgumentException("知识文档存储参数无效");
        }
        Path directory = resolve(String.valueOf(documentId));
        Path target = directory.resolve("source.bin");
        Path temporary = directory.resolve("source.uploading");
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)) {
                throw new SecurityException("知识文档存储目录不能是符号链接");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long copied;
            try (InputStream source = new DigestInputStream(new BufferedInputStream(input), digest);
                 OutputStream output = new BufferedOutputStream(Files.newOutputStream(
                     temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE
                 ))) {
                copied = source.transferTo(output);
            }
            if (copied != expectedSize) {
                Files.deleteIfExists(temporary);
                throw new IllegalStateException("知识文档上传大小与请求不一致");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target);
            }
            return new StoredFile(
                documentId + "/source.bin", copied, HexFormat.of().formatHex(digest.digest())
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Preserve the original storage failure.
            }
            throw new IllegalStateException("知识文档写入失败", exception);
        }
    }

    /**
     * 处理{@code open}并返回对应结果。
     *
     * @param storageRef 存储Ref参数
     * @return 处理结果
     */
    @Override
    public InputStream open(String storageRef) {
        Path path = resolve(storageRef);
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                throw new IllegalStateException("知识文档原始文件不存在");
            }
            return Files.newInputStream(path, StandardOpenOption.READ);
        } catch (IOException exception) {
            throw new IllegalStateException("知识文档读取失败", exception);
        }
    }

    /**
     * 删除{@code delete}。
     *
     * @param storageRef 存储Ref参数
     */
    @Override
    public void delete(String storageRef) {
        Path path = resolve(storageRef);
        try {
            Files.deleteIfExists(path);
            Path parent = path.getParent();
            if (parent != null && !parent.equals(root)) {
                Files.deleteIfExists(parent);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("知识文档删除失败", exception);
        }
    }

    /**
     * 获取{@code resolve}。
     *
     * @param reference {@code reference}参数
     * @return 处理结果
     */
    private Path resolve(String reference) {
        if (reference == null || !reference.matches("[1-9][0-9]*(?:/source\\.bin)?")) {
            throw new SecurityException("知识文档存储引用无效");
        }
        Path resolved = root.resolve(reference).normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("知识文档存储引用越界");
        }
        return resolved;
    }
}
