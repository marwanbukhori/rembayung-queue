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
    private static final String DROP_JSON = """
            {"dropId":"default","slotId":null,"ticketsIssued":40,"admitted":10,"waiting":30,"ticketCap":250}""";

    private final MovingClock clock = new MovingClock(Instant.parse("2026-09-05T09:00:00Z"));

    // The console is what people open when the system is unhappy. If a
    // dependency blinking made it throw, it would be the second casualty of
    // every incident rather than the thing explaining the first.
    @Test
    void aServiceThatFailsBecomesAReasonRatherThanAnException() {
        RestClient.Builder booking = RestClient.builder().baseUrl("http://booking-service:8081");
        RestClient.Builder gate = RestClient.builder().baseUrl("http://queue-gate:8080");
        MockRestServiceServer.bindTo(gate).build()
                .expect(requestTo("http://queue-gate:8080/internal/drops/default/state"))
                .andRespond(withSuccess(DROP_JSON, MediaType.APPLICATION_JSON));
        MockRestServiceServer.bindTo(booking).build()
                .expect(requestTo("http://booking-service:8081/internal/slots/1"))
                .andRespond(withServerError());

        DemoState state = new DemoStateProvider(booking.build(), gate.build(), properties(), clock)
                .currentFor("default");

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
                .andRespond(withSuccess(DROP_JSON, MediaType.APPLICATION_JSON));

        DemoStateProvider provider =
                new DemoStateProvider(booking.build(), gate.build(), properties(), clock);

        DemoState first = provider.currentFor("default");
        clock.advance(Duration.ofMillis(500));
        DemoState second = provider.currentFor("default");

        assertThat(first.seatsTaken()).isEqualTo(202);
        assertThat(first.waiting()).isEqualTo(30);
        assertThat(second).isEqualTo(first);
        // One expectation each, and MockRestServiceServer fails a second call.
        bookingServer.verify();
        gateServer.verify();
    }

    /**
     * The gap this closes: a sandbox drop sells its own slot, and the console
     * learns which one from the gate rather than being told by the browser.
     * Before this, the slot was a request parameter defaulting to the canonical
     * slot 1, so every visitor's sandbox was drawn with the real restaurant's
     * seats — 202 taken out of 250 on a drop where nobody had booked anything.
     */
    @Test
    void aSandboxIsDrawnWithItsOwnSlotRatherThanTheCanonicalOne() {
        RestClient.Builder booking = RestClient.builder().baseUrl("http://booking-service:8081");
        RestClient.Builder gate = RestClient.builder().baseUrl("http://queue-gate:8080");
        MockRestServiceServer bookingServer = MockRestServiceServer.bindTo(booking).build();
        MockRestServiceServer gateServer = MockRestServiceServer.bindTo(gate).build();
        gateServer.expect(requestTo("http://queue-gate:8080/internal/drops/d-abc12345/state"))
                .andRespond(withSuccess("""
                        {"dropId":"d-abc12345","slotId":4242,"ticketsIssued":9,\
                        "admitted":4,"waiting":5,"ticketCap":250}""",
                        MediaType.APPLICATION_JSON));
        // Slot 4242, not slot 1. MockRestServiceServer fails the test if the
        // console asks for any other slot.
        bookingServer.expect(requestTo("http://booking-service:8081/internal/slots/4242"))
                .andRespond(withSuccess("""
                        {"slotId":4242,"capacity":250,"seatsTaken":3,"remaining":247,"oversold":0}""",
                        MediaType.APPLICATION_JSON));

        DemoState state = new DemoStateProvider(booking.build(), gate.build(), properties(), clock)
                .currentFor("d-abc12345");

        assertThat(state.available()).isTrue();
        assertThat(state.slotId()).isEqualTo(4242);
        assertThat(state.seatsTaken()).isEqualTo(3);
        assertThat(state.waiting()).isEqualTo(5);
        bookingServer.verify();
        gateServer.verify();
    }

    /** Two sandboxes must never be served each other's numbers. */
    @Test
    void eachDropIsCachedUnderItsOwnKey() {
        RestClient.Builder booking = RestClient.builder().baseUrl("http://booking-service:8081");
        RestClient.Builder gate = RestClient.builder().baseUrl("http://queue-gate:8080");
        MockRestServiceServer bookingServer = MockRestServiceServer.bindTo(booking).build();
        MockRestServiceServer gateServer = MockRestServiceServer.bindTo(gate).build();
        gateServer.expect(requestTo("http://queue-gate:8080/internal/drops/default/state"))
                .andRespond(withSuccess(DROP_JSON, MediaType.APPLICATION_JSON));
        bookingServer.expect(requestTo("http://booking-service:8081/internal/slots/1"))
                .andRespond(withSuccess(SLOT_JSON, MediaType.APPLICATION_JSON));
        gateServer.expect(requestTo("http://queue-gate:8080/internal/drops/d-other/state"))
                .andRespond(withSuccess("""
                        {"dropId":"d-other","slotId":99,"ticketsIssued":1,\
                        "admitted":0,"waiting":1,"ticketCap":250}""",
                        MediaType.APPLICATION_JSON));
        bookingServer.expect(requestTo("http://booking-service:8081/internal/slots/99"))
                .andRespond(withSuccess("""
                        {"slotId":99,"capacity":250,"seatsTaken":0,"remaining":250,"oversold":0}""",
                        MediaType.APPLICATION_JSON));

        DemoStateProvider provider =
                new DemoStateProvider(booking.build(), gate.build(), properties(), clock);

        assertThat(provider.currentFor("default").waiting()).isEqualTo(30);
        assertThat(provider.currentFor("d-other").waiting()).isEqualTo(1);
        assertThat(provider.currentFor("d-other").slotId()).isEqualTo(99);
        bookingServer.verify();
        gateServer.verify();
    }

    private ConsoleProperties properties() {
        return new ConsoleProperties("http://booking-service:8081", "http://queue-gate:8080",
                "default", 1, Duration.ofSeconds(2), Duration.ofSeconds(1), "rembayung",
                "s3cret-demo-key");
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
