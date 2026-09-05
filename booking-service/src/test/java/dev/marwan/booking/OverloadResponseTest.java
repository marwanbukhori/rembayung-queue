package dev.marwan.booking;

import dev.marwan.booking.web.RestExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pool exhaustion must answer 503 with Retry-After, not 500.
 *
 * This exists because it did not. The handler was written against
 * DataAccessResourceFailureException on the stated belief that
 * CannotCreateTransactionException is a subclass of it. It is not — it lives in
 * org.springframework.transaction and shares no ancestor below RuntimeException.
 *
 * So the handler caught the case where code ALREADY IN a transaction asks for a
 * connection, and missed the case where @Transactional cannot get one to open
 * the transaction at all. The second is what happens under load. The first load
 * run ever executed inside the cluster returned 500 for 188 of 200 bookings,
 * against documentation in loadtest/drop.js promising 503 + Retry-After.
 *
 * Nothing in the suite exhausts a real pool, so this asserts the mapping
 * directly. A test that cannot fail the way production failed is not a test of
 * production.
 */
class OverloadResponseTest {

    private final RestExceptionHandler handler = new RestExceptionHandler();

    private static SQLTransientConnectionException poolTimeout() {
        return new SQLTransientConnectionException(
                "HikariPool-1 - Connection is not available, request timed out after 2000ms");
    }

    @Test
    void aTransactionThatCannotStartBecausesThePoolIsEmptyAnswers503() {
        var e = new org.springframework.transaction.CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction", poolTimeout());

        ResponseEntity<?> response = handler.overloaded(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
    }

    @Test
    void aConnectionRequestInsideAnOpenTransactionAlsoAnswers503() {
        var e = new org.springframework.jdbc.CannotGetJdbcConnectionException(
                "Failed to obtain JDBC Connection", poolTimeout());

        ResponseEntity<?> response = handler.overloaded(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // A database failure that is NOT pool exhaustion must not be dressed up as
    // back-pressure. Telling a caller to retry in 2 seconds when the database is
    // genuinely broken sends them into a loop against something that will not
    // recover on its own.
    @Test
    void anUnrelatedDataAccessFailureIsNotDisguisedAsBackPressure() {
        var e = new org.springframework.dao.DataAccessResourceFailureException(
                "ORA-00942: table or view does not exist");

        assertThatThrownBy(() -> handler.overloaded(e)).isSameAs(e);
    }
}
