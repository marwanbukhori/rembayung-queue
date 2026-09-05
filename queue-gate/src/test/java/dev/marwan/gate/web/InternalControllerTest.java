package dev.marwan.gate.web;

import dev.marwan.gate.RedisTestBase;
import dev.marwan.gate.queue.DropRecord;
import dev.marwan.gate.queue.DropRegistry;
import dev.marwan.gate.queue.QueueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class InternalControllerTest extends RedisTestBase {

    @Autowired private MockMvc mvc;
    @Autowired private DropRegistry drops;
    @Autowired private QueueService queueService;

    @Test
    void reportsTheStateOfOneNamedDrop() throws Exception {
        DropRecord drop = drops.create(1, 100L);
        queueService.join(drop.id());
        queueService.join(drop.id());

        mvc.perform(get("/internal/drops/" + drop.id() + "/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketsIssued").value(2))
                .andExpect(jsonPath("$.admitted").value(0))
                .andExpect(jsonPath("$.waiting").value(2));
    }

    // Two sandboxes read back to back inside the memo window must not be shown
    // each other's numbers.
    @Test
    void twoDropsReportTheirOwnDepths() throws Exception {
        DropRecord a = drops.create(1, 100L);
        DropRecord b = drops.create(1, 200L);
        queueService.join(a.id());
        queueService.join(a.id());
        queueService.join(a.id());
        queueService.join(b.id());

        mvc.perform(get("/internal/drops/" + a.id() + "/state"))
                .andExpect(jsonPath("$.ticketsIssued").value(3));
        mvc.perform(get("/internal/drops/" + b.id() + "/state"))
                .andExpect(jsonPath("$.ticketsIssued").value(1));
    }

    @Test
    void theDefaultDropIsReadableByName() throws Exception {
        mvc.perform(get("/internal/drops/" + DropRegistry.DEFAULT_ID + "/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketCap").value(250));
    }

    @Test
    void anExpiredDropIsNotFound() throws Exception {
        mvc.perform(get("/internal/drops/d-gone/state"))
                .andExpect(status().isNotFound());
    }
}
