package group.aitools.nhs.sandbox.runner.client;

import group.aitools.nhs.sandbox.runner.config.SandboxRunnerProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * 表示Runner凭据Store相关的领域对象。
 */
@Component
public class RunnerCredentialStore {

    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE
    );

    private final Path credentialFile;

    /**
     * 创建 {@code RunnerCredentialStore} 实例并初始化所需依赖。
     *
     * @param properties {@code properties}参数
     */
    public RunnerCredentialStore(SandboxRunnerProperties properties) {
        this.credentialFile = properties.getCredentialFile().toAbsolutePath().normalize();
    }

    /**
     * 处理{@code read}并返回对应结果。
     *
     * @return 可能为空的处理结果
     */
    public Optional<String> read() {
        if (!Files.exists(credentialFile)) {
            return Optional.empty();
        }
        try {
            verifyOwnerOnly(credentialFile);
            String secret = Files.readString(credentialFile, StandardCharsets.UTF_8).strip();
            return secret.isEmpty() ? Optional.empty() : Optional.of(secret);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read Runner credential file", exception);
        }
    }

    /**
     * 处理{@code write}相关逻辑。
     *
     * @param secret {@code secret}参数
     */
    public void write(String secret) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Runner secret must not be empty");
        }
        try {
            Path parent = credentialFile.getParent();
            if (parent == null) {
                throw new IllegalStateException("Runner credential file needs a parent directory");
            }
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, ".runner-credential-", ".tmp");
            try {
                setOwnerOnly(temporary);
                Files.writeString(temporary, secret + System.lineSeparator(), StandardCharsets.UTF_8);
                try {
                    Files.move(
                        temporary, credentialFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    );
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, credentialFile, StandardCopyOption.REPLACE_EXISTING);
                }
                setOwnerOnly(credentialFile);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot persist Runner credential file", exception);
        }
    }

    /**
     * 校验{@code OwnerOnly}，并在条件不满足时终止处理。
     *
     * @param path {@code path}参数
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private void verifyOwnerOnly(Path path) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            if (!OWNER_ONLY.containsAll(permissions)) {
                throw new IllegalStateException("Runner credential file must be owner-only (0600)");
            }
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems rely on their native ACLs.
        }
    }

    /**
     * 设置{@code OwnerOnly}。
     *
     * @param path {@code path}参数
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private void setOwnerOnly(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems rely on their native ACLs.
        }
    }
}
