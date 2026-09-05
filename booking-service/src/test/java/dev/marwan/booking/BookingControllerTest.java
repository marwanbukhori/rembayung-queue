package dev.marwan.booking;

import dev.marwan.booking.domain.Slot;
import dev.marwan.booking.repository.SlotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class BookingControllerTest extends OracleTestBase {

    @Autowired private MockMvc mvc;
    @Autowired private SlotRepository slotRepository;

    private Long seedSlot(int capacity) {
        return slotRepository.save(
                new Slot(LocalDate.of(2027, 3, 1),
                         String.valueOf(System.nanoTime() % 100000), capacity)).getId();
    }

    @Test
    void bookingReturnsCreatedWithPendingDeposit() throws Exception {
        Long slotId = seedSlot(250);

        mvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"slotId":%d,"phone":"+60123456789","partySize":2,
                             "idempotencyKey":"http-key-1"}
                            """.formatted(slotId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_DEPOSIT"))
                .andExpect(jsonPath("$.idempotentReplay").value(false));
    }

    @Test
    void soldOutSlotReturnsConflictWithStructuredDetails() throws Exception {
        Long slotId = seedSlot(2);

        mvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"slotId":%d,"phone":"+60111111111","partySize":2,
                             "idempotencyKey":"http-key-2"}
                            """.formatted(slotId)))
                .andExpect(status().isCreated());

        mvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"slotId":%d,"phone":"+60122222222","partySize":1,
                             "idempotencyKey":"http-key-3"}
                            """.formatted(slotId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("SLOT_SOLD_OUT"))
                .andExpect(jsonPath("$.details.slotId").value(slotId))
                .andExpect(jsonPath("$.details.requested").value(1))
                .andExpect(jsonPath("$.details.remaining").value(0));
    }

    @Test
    void unknownSlotReturnsNotFound() throws Exception {
        mvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"slotId":999999,"phone":"+60123456789","partySize":2,
                             "idempotencyKey":"http-key-4"}
                            """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("SLOT_NOT_FOUND"))
                .andExpect(jsonPath("$.details.slotId").value(999999));
    }

    @Test
    void depositConfirmsThenRejectsASecondAttempt() throws Exception {
        Long slotId = seedSlot(250);

        String body = mvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"slotId":%d,"phone":"+60123456789","partySize":2,
                             "idempotencyKey":"http-key-5"}
                            """.formatted(slotId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long bookingId = com.jayway.jsonpath.JsonPath.parse(body).read("$.bookingId", Integer.class).longValue();

        mvc.perform(post("/bookings/" + bookingId + "/deposit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        mvc.perform(post("/bookings/" + bookingId + "/deposit"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("BOOKING_NOT_PENDING"));
    }

    @Test
    void depositOnUnknownBookingReturnsNotFound() throws Exception {
        mvc.perform(post("/bookings/999999/deposit"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("BOOKING_NOT_FOUND"))
                .andExpect(jsonPath("$.details.bookingId").value(999999));
    }

    /**
     * The payloads a hand-written client sends by accident. None of these can
     * oversell - Slot.takeSeats refuses a non-positive party and the NOT NULL
     * columns refuse a missing phone - but refusing them as HTTP 500 tells the
     * caller the server broke when the caller's request was malformed, and puts
     * a server error on the dashboard for something no server fixed.
     */
    @Test
    void malformedRequestsAreRejectedAsBadRequest() throws Exception {
        Long slotId = seedSlot(250);

        record Case(String name, String body) { }
        var cases = java.util.List.of(
                new Case("party of zero",
                        """
                        {"slotId":%d,"phone":"+60122222222","partySize":0,
                         "idempotencyKey":"bad-zero"}
                        """.formatted(slotId)),
                new Case("negative party",
                        """
                        {"slotId":%d,"phone":"+60122222223","partySize":-4,
                         "idempotencyKey":"bad-negative"}
                        """.formatted(slotId)),
                new Case("no phone",
                        """
                        {"slotId":%d,"partySize":2,"idempotencyKey":"bad-nophone"}
                        """.formatted(slotId)),
                new Case("blank phone",
                        """
                        {"slotId":%d,"phone":"   ","partySize":2,
                         "idempotencyKey":"bad-blankphone"}
                        """.formatted(slotId)),
                new Case("no idempotency key",
                        """
                        {"slotId":%d,"phone":"+60122222224","partySize":2}
                        """.formatted(slotId)),
                new Case("no slot",
                        """
                        {"phone":"+60122222225","partySize":2,
                         "idempotencyKey":"bad-noslot"}
                        """),
                new Case("party larger than any sitting",
                        """
                        {"slotId":%d,"phone":"+60122222226","partySize":5000,
                         "idempotencyKey":"bad-huge"}
                        """.formatted(slotId)));

        for (Case bad : cases) {
            mvc.perform(post("/bookings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bad.body()))
                    .andExpect(status().isBadRequest())
                    // Same envelope as every other error this API returns, so a
                    // caller parses one shape rather than two.
                    .andExpect(jsonPath("$.reason").value("INVALID_REQUEST"));
        }
    }

    @Test
    void aRejectionNamesTheFieldThatWasWrong() throws Exception {
        Long slotId = seedSlot(250);

        mvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"slotId":%d,"phone":"","partySize":0,
                             "idempotencyKey":"bad-two-fields"}
                            """.formatted(slotId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.phone").value("phone is required"))
                .andExpect(jsonPath("$.details.partySize").value("partySize must be at least 1"));
    }

    @Test
    void aRejectedRequestTakesNoSeats() throws Exception {
        Long slotId = seedSlot(10);

        mvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"slotId":%d,"phone":"+60123333333","partySize":0,
                             "idempotencyKey":"bad-takes-nothing"}
                            """.formatted(slotId)))
                .andExpect(status().isBadRequest());

        org.assertj.core.api.Assertions
                .assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken())
                .isZero();
    }
}
