package dev.marwan.gate.web;

import dev.marwan.gate.RedisTestBase;
import dev.marwan.gate.queue.DropRecord;
import dev.marwan.gate.queue.DropRegistry;
import dev.marwan.gate.queue.QueueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    /**
     * The console draws seats next to the queue depth, and the seats belong to
     * whichever slot the drop sells. Without the slot id in this response the
     * console has to be told which slot to read, and the only thing it can
     * assume is the canonical one — so every sandbox would be drawn with the
     * real restaurant's seats.
     */
    @Test
    void stateNamesTheSlotTheDropSells() throws Exception {
        DropRecord drop = drops.create(1, 4242L);

        mvc.perform(get("/internal/drops/" + drop.id() + "/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dropId").value(drop.id()))
                .andExpect(jsonPath("$.slotId").value(4242));
    }

    /** The canonical drop sells whatever slot 1 is; it is not bound to one here. */
    @Test
    void theDefaultDropHasNoSlotOfItsOwn() throws Exception {
        mvc.perform(get("/internal/drops/" + DropRegistry.DEFAULT_ID + "/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotId").doesNotExist());
    }

    @Test
    void aDropCanBeCreatedAndIsImmediatelyReadable() throws Exception {
        String body = mvc.perform(post("/internal/drops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"admitRate\": 8, \"slotId\": 4242}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.admitRate").value(8))
                .andExpect(jsonPath("$.slotId").value(4242))
                .andExpect(jsonPath("$.id").isString())
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.parse(body).read("$.id");

        mvc.perform(get("/internal/drops/" + id + "/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotId").value(4242))
                .andExpect(jsonPath("$.ticketsIssued").value(0));
    }

    /** A drop admitting nobody is a queue that never moves and never says why. */
    @Test
    void aNonPositiveAdmitRateIsRefused() throws Exception {
        mvc.perform(post("/internal/drops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"admitRate\": 0, \"slotId\": 1}"))
                .andExpect(status().isBadRequest());
    }
}
