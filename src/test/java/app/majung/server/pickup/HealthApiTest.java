package app.majung.server.pickup;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class HealthApiTest {
    @Test void databaseAvailabilityAndReleaseRevisionAreReported() {
        JdbcTemplate database = mock(JdbcTemplate.class);
        when(database.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        HealthApi.Health health = new HealthApi(database).health();
        assertEquals("UP", health.status());
        assertTrue(health.revision().matches("local|[0-9a-f]{40}"));
    }
    @Test void databaseFailureIsUnavailableWithoutConnectionDetails() {
        JdbcTemplate database = mock(JdbcTemplate.class);
        when(database.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("fixture-private-database-url"));
        ApiException failure = assertThrows(ApiException.class, () -> new HealthApi(database).health());
        assertEquals(503, failure.status);
        assertFalse(failure.getMessage().contains("fixture-private"));
    }
}
