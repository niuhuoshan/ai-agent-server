package group.aitools.nhs.platform.data.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Resolves an env:NAME reference containing a bounded username/password JSON object. */
@Component
public class EnvironmentDataCredentialResolver implements DataCredentialResolver {

    private static final Pattern REFERENCE = Pattern.compile("env:([A-Z][A-Z0-9_]{0,127})");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final int MAX_VALUE_LENGTH = 8192;

    private final Map<String, String> environment;
    private final JsonMapper jsonMapper;

    @Autowired
    public EnvironmentDataCredentialResolver(JsonMapper jsonMapper) {
        this(System.getenv(), jsonMapper);
    }

    EnvironmentDataCredentialResolver(Map<String, String> environment, JsonMapper jsonMapper) {
        this.environment = Map.copyOf(environment);
        this.jsonMapper = jsonMapper;
    }

    @Override
    public DataCredential resolve(String credentialRef) {
        String reference = credentialRef == null ? "" : credentialRef.strip();
        var matcher = REFERENCE.matcher(reference);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("数据源凭证必须使用 env:NAME 引用");
        }
        String value = environment.get(matcher.group(1));
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("数据源凭证引用未配置");
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalStateException("数据源凭证配置无效");
        }
        try {
            Map<String, Object> document = jsonMapper.readValue(value, MAP_TYPE);
            if (!document.keySet().equals(Set.of("username", "password"))) {
                throw new IllegalArgumentException("invalid credential fields");
            }
            String username = string(document.get("username"));
            String password = string(document.get("password"));
            if (username.isBlank() || username.length() > 128 || password.length() > 4096
                || username.indexOf('\r') >= 0 || username.indexOf('\n') >= 0
                || password.indexOf('\r') >= 0 || password.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("invalid credential values");
            }
            return new DataCredential(username, password);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("数据源凭证配置无效");
        }
    }

    private String string(Object value) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("credential value must be a string");
        }
        return text;
    }
}
