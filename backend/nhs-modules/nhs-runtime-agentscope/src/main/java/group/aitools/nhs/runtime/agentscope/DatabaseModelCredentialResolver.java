package group.aitools.nhs.runtime.agentscope;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * 负责Database模型凭据相关的转换、解析或处理逻辑。
 * Resolves a model API key from the model registry without copying it into run snapshots. */
public final class DatabaseModelCredentialResolver implements RuntimeCredentialResolver {

    private static final String REFERENCE_PREFIX = "db:model:";
    private static final int MAX_API_KEY_LENGTH = 8192;

    private final DataSource dataSource;

    public DatabaseModelCredentialResolver(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /**
     * 获取{@code resolve}。
     *
     * @param credentialRef 凭据Ref参数
     * @return 处理结果
     */
    @Override
    public String resolve(String credentialRef) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        long modelId = modelId(credentialRef);
        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement("""
                SELECT credential_ref, status
                FROM agent_model
                WHERE id = ? AND del_flag = '0'
                """)
        ) {
            statement.setLong(1, modelId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("模型不存在或已被删除");
                }
                if (!"active".equals(result.getString("status"))) {
                    throw new IllegalStateException("模型已停用");
                }
                return apiKey(result.getString("credential_ref"));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("模型 API Key 读取失败", exception);
        }
    }

    /**
     * 处理模型Id并返回对应结果。
     *
     * @param credentialRef 凭据Ref参数
     * @return 处理结果
     */
    private long modelId(String credentialRef) {
        if (credentialRef == null || !credentialRef.startsWith(REFERENCE_PREFIX)) {
            throw new IllegalArgumentException("模型凭证不是有效的数据库引用");
        }
        try {
            long value = Long.parseLong(credentialRef.substring(REFERENCE_PREFIX.length()));
            if (value <= 0) {
                throw new NumberFormatException("model id must be positive");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("模型凭证不是有效的数据库引用", exception);
        }
    }

    /**
     * 处理接口Key并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String apiKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("模型 API Key 未配置");
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_API_KEY_LENGTH) {
            throw new IllegalStateException("模型 API Key 超过长度限制");
        }
        if (normalized.startsWith("v1s.") || normalized.startsWith("env:")) {
            throw new IllegalStateException("模型 API Key 使用旧存储格式，请重新填写");
        }
        return normalized;
    }
}
