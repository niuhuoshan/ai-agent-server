package group.aitools.nhs.runtime.agentscope;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class DatabaseModelCredentialResolverTest {

    @Test
    void resolvesActiveModelApiKeyByDatabaseReference() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getString("status")).thenReturn("active");
        when(result.getString("credential_ref")).thenReturn("  stored-secret  ");

        DatabaseModelCredentialResolver resolver = new DatabaseModelCredentialResolver(dataSource);

        assertEquals("stored-secret", resolver.resolve("db:model:42"));
    }

    @Test
    void rejectsInvalidAndLegacyReferences() {
        DatabaseModelCredentialResolver resolver = new DatabaseModelCredentialResolver(mock(DataSource.class));

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("env:MODEL_KEY"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("db:model:not-a-number"));
    }
}
