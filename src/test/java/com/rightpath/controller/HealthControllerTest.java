package com.rightpath.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class HealthControllerTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final HealthController controller = new HealthController(dataSource);

    @Test
    void healthyWhenConnectionIsValid() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.isValid(anyInt())).thenReturn(true);
        when(dataSource.getConnection()).thenReturn(connection);

        assertResponse(HttpStatus.OK, "healthy");
    }

    @Test
    void unhealthyWhenConnectionIsNotValid() throws SQLException {
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);

        assertResponse(HttpStatus.SERVICE_UNAVAILABLE, "unhealthy");
    }

    @Test
    void unhealthyWhenNoConnectionCanBeObtained() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("database is down"));

        assertResponse(HttpStatus.SERVICE_UNAVAILABLE, "unhealthy");
    }

    private void assertResponse(HttpStatus status, String body) {
        ResponseEntity<Map<String, String>> response = controller.healthDb();

        assertEquals(status, response.getStatusCode());
        assertEquals(Map.of("status", body), response.getBody());
    }
}
