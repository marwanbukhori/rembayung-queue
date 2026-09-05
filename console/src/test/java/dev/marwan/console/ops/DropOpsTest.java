package dev.marwan.console.ops;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DropOpsTest {

    private final RestClient.Builder booking = RestClient.builder().baseUrl("http://booking-service:8081");
    private final RestClient.Builder gate = RestClient.builder().baseUrl("http://queue-gate:8080");

    /**
     * The drop must be bound to the slot that was just seeded. If it were not,
     * the console would draw the sandbox's queue beside the canonical
     * restaurant's seats — the wrong number, rendered confidently.
     */
    @Test
    void aSandboxIsASeededSlotAndADropBoundToIt() {
        MockRestServiceServer bookingServer = MockRestServiceServer.bindTo(booking).build();
        MockRestServiceServer gateServer = MockRestServiceServer.bindTo(gate).build();
        bookingServer.expect(requestTo("http://booking-service:8081/internal/slots"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("{\"slotId\":4242}", MediaType.APPLICATION_JSON));
        gateServer.expect(requestTo("http://queue-gate:8080/internal/drops"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.slotId").value(4242))
                .andExpect(jsonPath("$.admitRate").value(8))
                .andRespond(withSuccess("{\"id\":\"d-abc12345\",\"slotId\":4242}",
                        MediaType.APPLICATION_JSON));

        DropOps.Sandbox sandbox = new DropOps(booking.build(), gate.build()).create(null);

        assertThat(sandbox.dropId()).isEqualTo("d-abc12345");
        assertThat(sandbox.slotId()).isEqualTo(4242);
        assertThat(sandbox.admitRate()).isEqualTo(8);
        bookingServer.verify();
        gateServer.verify();
    }

    @Test
    void theAskedForAdmitRateIsPassedToTheGate() {
        MockRestServiceServer.bindTo(booking).build()
                .expect(requestTo("http://booking-service:8081/internal/slots"))
                .andRespond(withSuccess("{\"slotId\":7}", MediaType.APPLICATION_JSON));
        MockRestServiceServer gateServer = MockRestServiceServer.bindTo(gate).build();
        gateServer.expect(requestTo("http://queue-gate:8080/internal/drops"))
                .andExpect(jsonPath("$.admitRate").value(40))
                .andRespond(withSuccess("{\"id\":\"d-fast\",\"slotId\":7}", MediaType.APPLICATION_JSON));

        DropOps.Sandbox sandbox = new DropOps(booking.build(), gate.build())
                .create(new DropOps.StartDrop(40));

        assertThat(sandbox.admitRate()).isEqualTo(40);
        gateServer.verify();
    }

    /**
     * A failed creation is a 503 with the reason, not the 200-with-a-reason the
     * read path uses: there is no sandbox to draw, and a button that reported
     * one anyway would send a visitor to a drop that does not exist.
     */
    @Test
    void aServiceThatCannotSeedFailsLoudlyRatherThanReturningNothing() {
        MockRestServiceServer.bindTo(booking).build()
                .expect(requestTo("http://booking-service:8081/internal/slots"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> new DropOps(booking.build(), gate.build()).create(null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("booking-service")
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void anAbsurdAdmitRateIsRefusedBeforeAnythingIsSeeded() {
        MockRestServiceServer bookingServer = MockRestServiceServer.bindTo(booking).build();

        assertThatThrownBy(() -> new DropOps(booking.build(), gate.build())
                .create(new DropOps.StartDrop(100_000)))
                .isInstanceOf(ResponseStatusException.class);

        // Nothing was expected of booking-service, and nothing was asked of it.
        bookingServer.verify();
    }
}
