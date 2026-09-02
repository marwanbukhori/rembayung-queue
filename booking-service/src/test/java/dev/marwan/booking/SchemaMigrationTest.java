package dev.marwan.booking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaMigrationTest extends OracleTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void slotsAndBookingsTablesExist() {
        Integer tables = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_tables WHERE table_name IN ('SLOTS','BOOKINGS')",
                Integer.class);
        assertThat(tables).isEqualTo(2);
    }

    @Test
    void databaseRefusesToOversellASlot() {
        jdbc.update("INSERT INTO slots (service_date, service_time, capacity, seats_taken) "
                + "VALUES (DATE '2026-10-01', '19:00', 250, 0)");

        assertThatThrownBy(() ->
                jdbc.update("UPDATE slots SET seats_taken = 251 "
                        + "WHERE service_date = DATE '2026-10-01' AND service_time = '19:00'"))
                .hasMessageContaining("CK_SLOTS_SEATS");
    }
}
