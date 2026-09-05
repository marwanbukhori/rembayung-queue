package dev.marwan.console.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// The documentation is behind the console key like everything else under
// /api, so every request here carries one. KeyFilterTest asserts what happens
// without it.
@SpringBootTest(properties = "console.access-key=s3cret-demo-key")
@AutoConfigureMockMvc
class DocsControllerTest {

    private static final String KEY_HEADER = "X-Console-Key";
    private static final String KEY = "s3cret-demo-key";

    @Autowired private MockMvc mvc;

    @Test
    void theNotesAreListed() throws Exception {
        mvc.perform(get("/api/docs").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("observability")));
    }

    // The notes are written in GFM and lean on pipe tables. Without the tables
    // extension commonmark emits them as a paragraph of literal pipes, which is
    // what a reader saw first on the busiest page of the record.
    @Test
    void pipeTablesRenderAsTables() throws Exception {
        mvc.perform(get("/api/docs/03-how-this-runs").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<table>")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("|---|---|---|"))));
    }

    // Path traversal: the id is used to open a file, so it is the one place a
    // string from the browser touches the filesystem.
    @Test
    void aTraversingPathIsRefused() throws Exception {
        mvc.perform(get("/api/docs/..%2F..%2F..%2Fetc%2Fpasswd").header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }
}
