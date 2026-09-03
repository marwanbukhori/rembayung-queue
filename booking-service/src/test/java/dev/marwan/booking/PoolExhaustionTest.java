package dev.marwan.booking;

import dev.marwan.booking.web.RestExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that connection pool exhaustion is reported as 503 with Retry-After,
 * not as a 500 server error. When the pool times out, Spring wraps the Hikari
 * SQLTransientConnectionException in a CannotCreateTransactionException, which
 * the exception handler must map to 503 with a truthful Retry-After header.
 */
class PoolExhaustionTest {

    private final RestExceptionHandler handler = new RestExceptionHandler();

    @Test
    void connectionPoolTimeoutReturnsServiceUnavailableWithRetryAfter() {
        // When the connection pool is exhausted, Hikari throws SQLTransientConnectionException.
        // Spring wraps this in CannotCreateTransactionException before it reaches the handler.
        var poolTimeoutException = new CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction",
                new java.sql.SQLTransientConnectionException(
                        "HikariPool-1 - Connection is not available, request timed out after 2001ms"));

        ResponseEntity<?> response = handler.overloaded(poolTimeoutException);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
        assertThat(response.getBody()).isNotNull();
        // The response body is an ApiError with reason="BOOKING_SERVICE_BUSY"
    }
}
