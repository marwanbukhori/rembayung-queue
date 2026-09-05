package dev.marwan.booking;

import dev.marwan.booking.api.ApiError;
import dev.marwan.booking.web.RestExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that connection pool exhaustion is reported as 503 with Retry-After
 * rather than as a 500.
 *
 * There are two exceptions to cover, not one, and believing there was one is
 * what caused the original bug: the first load run inside the cluster answered
 * 188 of 200 bookings with HTTP 500 while the notes and the k6 script both said
 * overload produces 503. Spring throws CannotCreateTransactionException when
 * @Transactional cannot get a connection to OPEN the transaction - the common
 * case here - and a DataAccessResourceFailureException when code already inside
 * one asks for a connection. They share no ancestor below RuntimeException, so
 * catching either alone misses the other. The first test below pins that
 * hierarchy, because the comment that got this wrong was convincing.
 */
class PoolExhaustionTest {

    private final RestExceptionHandler handler = new RestExceptionHandler();

    /**
     * The assertion the original comment should have been. It claimed
     * DataAccessResourceFailureException was "the superclass of several more
     * specific types like CannotCreateTransactionException", and catching only
     * that type is precisely what returned 500 under load.
     */
    @Test
    void theTwoPoolTimeoutExceptionsAreUnrelated() {
        assertThat(DataAccessResourceFailureException.class
                .isAssignableFrom(CannotCreateTransactionException.class)).isFalse();
        assertThat(CannotCreateTransactionException.class
                .isAssignableFrom(DataAccessResourceFailureException.class)).isFalse();
    }

    @Test
    void aPoolTimeoutOpeningATransactionIsServiceUnavailable() {
        // What @Transactional throws when it cannot get a connection to start
        // the transaction. This is the common case under exhaustion, and the one
        // the handler used to miss.
        assertServiceUnavailable(new CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction",
                new java.sql.SQLTransientConnectionException(
                        "HikariPool-1 - Connection is not available, request timed out after 2001ms")));
    }

    @Test
    void aPoolTimeoutInsideATransactionIsServiceUnavailable() {
        assertServiceUnavailable(new DataAccessResourceFailureException(
                "Could not obtain connection",
                new java.sql.SQLTransientConnectionException(
                        "HikariPool-1 - Connection is not available, request timed out after 2001ms")));
    }

    /**
     * A failure that is not a pool timeout must not be dressed up as one: a 503
     * with Retry-After tells the caller to come back, and a caller that retries
     * a genuine fault forever is worse off than one that sees it.
     */
    @Test
    void aFailureThatIsNotAPoolTimeoutIsNotReportedAsBusy() {
        var notAPoolTimeout = new DataAccessResourceFailureException("network is down");

        // Rethrown rather than answered, so it becomes a 500 and stays visible.
        // Dressing it as 503 with Retry-After would tell the caller to come back
        // from a fault that retrying cannot fix.
        assertThatThrownBy(() -> handler.overloaded(notAPoolTimeout))
                .isSameAs(notAPoolTimeout);
    }

    private void assertServiceUnavailable(RuntimeException poolTimeout) {
        ResponseEntity<ApiError> response = handler.overloaded(poolTimeout);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().reason()).isEqualTo("BOOKING_SERVICE_BUSY");
    }
}
