package dev.marwan.console.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class DocsControllerTest {

    @Autowired private MockMvc mvc;

    @Test
    void theNotesAreListed() throws Exception {
        mvc.perform(get("/api/docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("observability")));
    }

    // Path traversal: the id is used to open a file, so it is the one place a
    // string from the browser touches the filesystem.
    @Test
    void aTraversingPathIsRefused() throws Exception {
        mvc.perform(get("/api/docs/..%2F..%2F..%2Fetc%2Fpasswd"))
                .andExpect(status().isNotFound());
    }
}
