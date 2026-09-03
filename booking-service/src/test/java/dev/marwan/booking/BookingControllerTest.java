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
}
