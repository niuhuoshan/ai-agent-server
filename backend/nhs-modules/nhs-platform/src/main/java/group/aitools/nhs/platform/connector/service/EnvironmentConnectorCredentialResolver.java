package group.aitools.nhs.platform.connector.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 负责Environment连接器凭据相关的转换、解析或处理逻辑。
 * Resolves strict env:NAME references without exposing environment enumeration. */
@Component
public class EnvironmentConnectorCredentialResolver implements ConnectorCredentialResolver {

    private static final Pattern REFERENCE = Pattern.compile("env:([A-Z][A-Z0-9_]{0,127})");
    private static final int MAX_SECRET_LENGTH = 8192;
    private final Map<String, String> environment;

    @Autowired
    public EnvironmentConnectorCredentialResolver() {
        this(System.getenv());
    }

    /**
     * 创建 {@code EnvironmentConnectorCredentialResolver} 实例并初始化所需依赖。
     *
     * @param environment {@code environment}参数
     */
    EnvironmentConnectorCredentialResolver(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
    }

    /**
     * 获取{@code resolve}。
     *
     * @param credentialRef 凭据Ref参数
     * @return 处理结果
     */
    @Override
    public String resolve(String credentialRef) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (credentialRef == null || credentialRef.isBlank()) {
            return null;
        }
        var matcher = REFERENCE.matcher(credentialRef.strip());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("连接器凭证必须使用 env:NAME 引用");
        }
        String secret = environment.get(matcher.group(1));
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("连接器凭证引用未配置");
        }
        if (secret.length() > MAX_SECRET_LENGTH || secret.chars().anyMatch(ch -> ch == '\r' || ch == '\n')) {
            throw new IllegalStateException("连接器凭证值无效");
        }
        return secret;
    }
}
