package group.aitools.nhs.platform.data.service;

/** Backward-compatible PostgreSQL facade; new code uses {@link DataSourceEndpointPolicy}. */
@Deprecated(forRemoval = false)
public class PostgresDataEndpointPolicy extends DataSourceEndpointPolicy {

    public PostgresDataEndpointPolicy(boolean allowPrivateEndpoints, boolean allowLocalEndpoints) {
        super(allowPrivateEndpoints, allowLocalEndpoints);
    }

    public DataConnectionTarget normalize(String endpointUrl, String databaseName) {
        return normalize("postgresql", endpointUrl, databaseName);
    }
}
