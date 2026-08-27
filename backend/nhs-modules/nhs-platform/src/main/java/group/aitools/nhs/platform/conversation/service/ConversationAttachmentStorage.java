package group.aitools.nhs.platform.conversation.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 表示会话附件存储相关的领域对象。
 * Owner-opaque local storage for bounded conversation attachments. */
@Component
public class ConversationAttachmentStorage {

    private final Path root;

    public ConversationAttachmentStorage(
        @Value("${agent.platform.conversation.storage-root:./data/agent-conversations}") Path root
    ) {
        try {
            Files.createDirectories(root);
            this.root = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new IllegalStateException("无法初始化会话附件存储目录", exception);
        }
        if (Files.isSymbolicLink(this.root)) {
            throw new IllegalStateException("会话附件存储根目录不能是符号链接");
        }
    }

    /**
     * 处理{@code put}并返回对应结果。
     *
     * @param attachmentId 资源标识
     * @param input {@code input}参数
     * @param expectedSize 数量上限
     * @return 处理结果
     */
    public StoredAttachment put(Long attachmentId, InputStream input, long expectedSize) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (attachmentId == null || attachmentId <= 0 || input == null || expectedSize <= 0) {
            throw new IllegalArgumentException("会话附件存储参数无效");
        }
        Path directory = resolve(String.valueOf(attachmentId));
        Path target = directory.resolve("source.bin");
        Path temporary = directory.resolve("source.uploading");
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)) {
                throw new SecurityException("会话附件存储目录不能是符号链接");
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
                throw new IllegalStateException("会话附件上传大小与请求不一致");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target);
            }
            return new StoredAttachment(
                attachmentId + "/source.bin", copied, HexFormat.of().formatHex(digest.digest())
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Preserve the original storage failure.
            }
            throw new IllegalStateException("会话附件写入失败", exception);
        }
    }

    /**
     * 处理{@code open}并返回对应结果。
     *
     * @param storageRef 存储Ref参数
     * @return 处理结果
     */
    public InputStream open(String storageRef) {
        Path path = resolve(storageRef);
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                throw new IllegalStateException("会话附件不存在");
            }
            return Files.newInputStream(path, StandardOpenOption.READ);
        } catch (IOException exception) {
            throw new IllegalStateException("会话附件读取失败", exception);
        }
    }

    /**
     * 删除{@code delete}。
     *
     * @param storageRef 存储Ref参数
     */
    public void delete(String storageRef) {
        Path path = resolve(storageRef);
        try {
            Files.deleteIfExists(path);
            Path parent = path.getParent();
            if (parent != null && !parent.equals(root)) {
                Files.deleteIfExists(parent);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("会话附件删除失败", exception);
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
            throw new SecurityException("会话附件存储引用无效");
        }
        Path resolved = root.resolve(reference).normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("会话附件存储引用越界");
        }
        return resolved;
    }

    /**
     * 封装Stored附件相关的不可变数据。
     */
    public record StoredAttachment(String storageRef, long sizeBytes, String sha256) {
    }
}
