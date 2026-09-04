package dev.marwan.booking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
public abstract class OracleTestBase {

    private static final int ORA_TABLE_OR_VIEW_DOES_NOT_EXIST = 942;

    @ServiceConnection
    static final OracleContainer ORACLE = new OracleContainer(
            DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"))
            .withDatabaseName("bookingdb")
            .withUsername("booking")
            .withPassword("booking")
            .withStartupTimeout(Duration.ofMinutes(5))
            .withReuse(true);

    static {
        ORACLE.start();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        deleteAllRowsIfTableExists("bookings");
        deleteAllRowsIfTableExists("slots");
    }

    private void deleteAllRowsIfTableExists(String table) {
        try {
            jdbcTemplate.update("DELETE FROM " + table);
        } catch (BadSqlGrammarException e) {
            if (e.getSQLException().getErrorCode() != ORA_TABLE_OR_VIEW_DOES_NOT_EXIST) {
                throw e;
            }
        }
    }

    @Test
    void oracleContainerIsReachable() {
        Integer one = jdbcTemplate.queryForObject("SELECT 1 FROM DUAL", Integer.class);
        assertThat(one).isEqualTo(1);
    }
}
