package group.aitools.nhs.platform.data;

import group.aitools.nhs.platform.data.service.PostgresDataEndpointPolicy;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class PostgresDataEndpointPolicyTest {

    @Test
    void normalizesPostgresEndpointWithoutCredentialsOrParameters() {
        PostgresDataEndpointPolicy policy = new PostgresDataEndpointPolicy(true, true);

        var target = policy.normalize("postgresql://DB.EXAMPLE.COM:5433", "reporting");

        assertEquals("postgresql://db.example.com:5433", target.normalizedEndpoint());
        assertEquals("jdbc:postgresql://db.example.com:5433/reporting", target.jdbcUrl());
    }

    @Test
    void rejectsEmbeddedCredentialsPathsQueriesAndLocalTargetsByDefault() {
        PostgresDataEndpointPolicy policy = new PostgresDataEndpointPolicy(true, false);

        assertThrows(ServiceException.class, () -> policy.normalize("postgresql://u:p@db.example.com", "reporting"));
        assertThrows(ServiceException.class, () -> policy.normalize("postgresql://db.example.com/other", "reporting"));
        assertThrows(ServiceException.class, () -> policy.normalize("postgresql://db.example.com?ssl=false", "reporting"));
        assertThrows(ServiceException.class, () -> policy.normalize("postgresql://localhost:5432", "reporting"));
        assertThrows(ServiceException.class, () -> policy.normalize("jdbc:postgresql://db.example.com:5432", "reporting"));
    }

    @Test
    void validatesResolvedLocalAndPrivateNetworkTargetsAgainstExplicitFlags() {
        PostgresDataEndpointPolicy defaultPolicy = new PostgresDataEndpointPolicy(true, false);
        PostgresDataEndpointPolicy publicOnly = new PostgresDataEndpointPolicy(false, true);
        PostgresDataEndpointPolicy testPolicy = new PostgresDataEndpointPolicy(true, true);

        assertThrows(ServiceException.class, () -> defaultPolicy.validateNetworkTarget(
            defaultPolicy.normalize("postgresql://127.0.0.1:5432", "reporting")
        ));
        assertThrows(ServiceException.class, () -> publicOnly.validateNetworkTarget(
            publicOnly.normalize("postgresql://10.0.0.10:5432", "reporting")
        ));
        testPolicy.validateNetworkTarget(
            testPolicy.normalize("postgresql://127.0.0.1:5432", "reporting")
        );
    }
}
