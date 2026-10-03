package com.falconx.market.health;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * {@link ClickHouseHealthIndicator} 单元测试。
 */
class ClickHouseHealthIndicatorTests {

    @Test
    void shouldReportUpWhenSelectOneSucceeds() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(connection.getCatalog()).thenReturn("falconx_market_analytics");

        Health health = new ClickHouseHealthIndicator(dataSource).health();

        Assertions.assertEquals(Status.UP, health.getStatus());
        Assertions.assertEquals("falconx_market_analytics", health.getDetails().get("database"));
    }

    @Test
    void shouldReportDownWhenConnectionInvalid() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(false);

        Health health = new ClickHouseHealthIndicator(dataSource).health();

        Assertions.assertEquals(Status.DOWN, health.getStatus());
        Assertions.assertEquals("connection-invalid", health.getDetails().get("reason"));
    }

    @Test
    void shouldReportDownWhenConnectionThrows() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("clickhouse unreachable"));

        Health health = new ClickHouseHealthIndicator(dataSource).health();

        Assertions.assertEquals(Status.DOWN, health.getStatus());
    }
}
