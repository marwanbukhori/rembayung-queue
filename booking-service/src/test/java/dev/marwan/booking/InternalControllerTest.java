package dev.marwan.booking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class InternalControllerTest extends OracleTestBase {

    @Autowired private MockMvc mvc;

    private long seed() throws Exception {
        String body = mvc.perform(post("/internal/slots"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.slotId", Integer.class);
    }

    @Test
    void seedingReturnsANewSlotWithItsOwnCapacity() throws Exception {
        mvc.perform(post("/internal/slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotId").isNumber());
    }

    @Test
    void slotStateReportsSeatsAndOversold() throws Exception {
        long slotId = seed();

        mvc.perform(get("/internal/slots/" + slotId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(250))
                .andExpect(jsonPath("$.seatsTaken").value(0))
                .andExpect(jsonPath("$.oversold").value(0));
    }

    @Test
    void anUnknownSlotIsNotFound() throws Exception {
        mvc.perform(get("/internal/slots/999999")).andExpect(status().isNotFound());
    }

    // slots has a UNIQUE (service_date, service_time), so a seed fixed at one
    // date and time works exactly once. Two visitors opening the console on the
    // same afternoon is the ordinary case, not the edge case.
    @Test
    void everySeedIsASeparateSandbox() throws Exception {
        long first = seed();
        long second = seed();

        assertThat(second).isNotEqualTo(first);
        mvc.perform(get("/internal/slots/" + first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotId").value(first));
        mvc.perform(get("/internal/slots/" + second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotId").value(second));
    }
}
