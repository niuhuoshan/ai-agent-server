package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Strict engine options, env credential references and schema allowlists. */
@Component
public class DataSourceConfigurationValidator {

    private static final Pattern CREDENTIAL_REFERENCE = Pattern.compile("env:[A-Z][A-Z0-9_]{0,127}");

    public Map<String, Object> config(Map<String, Object> value) {
        return config("postgresql", value);
    }

    public Map<String, Object> config(String dbType, Map<String, Object> value) {
        return DataSourceType.require(dbType).normalizeConfig(value);
    }

    public String dbType(String value) {
        return DataSourceType.require(value).id();
    }

    public String credentialReference(String value) {
        String reference = value == null ? "" : value.strip();
        if (!CREDENTIAL_REFERENCE.matcher(reference).matches()) {
            throw badRequest("数据源凭证必须使用 env:NAME 引用");
        }
        return reference;
    }

    public List<String> schemas(List<String> values) {
        return schemas("postgresql", null, values);
    }

    public List<String> schemas(String dbType, String databaseName, List<String> values) {
        DataSourceType type = DataSourceType.require(dbType);
        if (values == null || values.isEmpty() || values.size() > 16) {
            throw badRequest("数据集必须配置 1 至 16 个 Schema");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String schema = value == null ? "" : value.strip();
            String normalized = schema.toLowerCase(Locale.ROOT);
            if (!schema.matches("[A-Za-z_][A-Za-z0-9_$]{0,62}")
                || isSystemSchema(type, normalized)) {
                throw badRequest("数据集 Schema 无效或属于系统 Schema：" + schema);
            }
            if (type.restrictToDatabase()
                && (databaseName == null || !schema.equalsIgnoreCase(databaseName.strip()))) {
                throw badRequest(type.label() + " 数据集只能使用数据源已配置的数据库：" + databaseName);
            }
            if (type == DataSourceType.ORACLE) {
                schema = schema.toUpperCase(Locale.ROOT);
            } else if (type.restrictToDatabase() && databaseName != null) {
                schema = databaseName.strip();
            }
            result.add(schema);
        }
        return List.copyOf(result);
    }

    private boolean isSystemSchema(DataSourceType type, String schema) {
        return switch (type) {
            case POSTGRESQL -> schema.equals("information_schema")
                || schema.equals("pg_catalog") || schema.equals("pg_toast")
                || schema.startsWith("pg_");
            case MYSQL -> List.of("information_schema", "mysql", "performance_schema", "sys")
                .contains(schema);
            case ORACLE -> List.of(
                "sys", "system", "sysaux", "xdb", "outln", "dbsnmp", "ctxsys", "mdsys", "ordsys"
            ).contains(schema);
            case SQLSERVER -> List.of("information_schema", "sys").contains(schema);
            case CLICKHOUSE -> List.of("information_schema", "system").contains(schema);
        };
    }

    private ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }
}
