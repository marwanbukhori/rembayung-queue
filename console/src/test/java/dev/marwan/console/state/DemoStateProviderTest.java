package dev.marwan.console.state;

import dev.marwan.console.ConsoleProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DemoStateProviderTest {

    private static final String SLOT_JSON = """
            {"slotId":1,"capacity":250,"seatsTaken":202,"remaining":48,"oversold":0}""";
    private static final String QUEUE_JSON = """
            {"ticketsIssued":40,"admitted":10,"waiting":30,"ticketCap":250}""";

    private final MovingClock clock = new MovingClock(Instant.parse("2026-09-05T09:00:00Z"));

    // The console is what people open when the system is unhappy. If a
    // dependency blinking made it throw, it would be the second casualty of
    // every incident rather than the thing explaining the first.
    @Test
    void aServiceThatFailsBecomesAReasonRatherThanAnException() {
        RestClient.Builder booking = RestClient.builder().baseUrl("http://booking-service:8081");
        RestClient.Builder gate = RestClient.builder().baseUrl("http://queue-gate:8080");
        MockRestServiceServer.bindTo(booking).build()
                .expect(requestTo("http://booking-service:8081/internal/slots/1"))
                .andRespond(withServerError());

        DemoState state = new DemoStateProvider(booking.build(), gate.build(), properties(), clock)
                .currentFor("default", 1);

        assertThat(state.available()).isFalse();
        assertThat(state.detail()).contains("booking-service");
    }

    // Any number of people may have the page open, each polling every 2s. The
    // cache is what stops viewer count multiplying into load on the very
    // services the console exists to watch.
    @Test
    void repeatedPollsWithinTheCacheWindowReadTheServicesOnce() {
        RestClient.Builder booking = RestClient.builder().baseUrl("http://booking-service:8081");
        RestClient.Builder gate = RestClient.builder().baseUrl("http://queue-gate:8080");
        MockRestServiceServer bookingServer = MockRestServiceServer.bindTo(booking).build();
        MockRestServiceServer gateServer = MockRestServiceServer.bindTo(gate).build();
        bookingServer.expect(requestTo("http://booking-service:8081/internal/slots/1"))
                .andRespond(withSuccess(SLOT_JSON, MediaType.APPLICATION_JSON));
        gateServer.expect(requestTo("http://queue-gate:8080/internal/drops/default/state"))
                .andRespond(withSuccess(QUEUE_JSON, MediaType.APPLICATION_JSON));

        DemoStateProvider provider =
                new DemoStateProvider(booking.build(), gate.build(), properties(), clock);

        DemoState first = provider.currentFor("default", 1);
        clock.advance(Duration.ofMillis(500));
        DemoState second = provider.currentFor("default", 1);

        assertThat(first.seatsTaken()).isEqualTo(202);
        assertThat(first.waiting()).isEqualTo(30);
        assertThat(second).isEqualTo(first);
        // One expectation each, and MockRestServiceServer fails a second call.
        bookingServer.verify();
        gateServer.verify();
    }

    private ConsoleProperties properties() {
        return new ConsoleProperties("http://booking-service:8081", "http://queue-gate:8080",
                "default", 1, Duration.ofSeconds(2), Duration.ofSeconds(1), "rembayung");
    }

    /** A clock the test moves, so cache expiry is asserted rather than slept through. */
    private static final class MovingClock extends Clock {
        private Instant now;

        private MovingClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
