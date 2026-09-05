package dev.marwan.booking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the readiness probe reports while the connection pool is saturated.
 *
 * This matters because saturating the pool is something the demo does on
 * purpose: the console offers 200 admissions a second precisely to show
 * booking-service shedding load with 503 and Retry-After while oversold stays
 * at zero. The readiness group includes `db`, and the db indicator borrows a
 * pooled connection - so if a saturated pool makes the probe fail, Kubernetes
 * pulls every booking-service pod out of the Service during the one run the
 * whole console was built to show, and the router answers with its own error
 * page instead of the application's deliberate refusal.
 *
 * The production pool is 5, not the 40 the test profile uses, so nothing else
 * in this suite comes close to exercising it.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=1000"
})
class ReadinessUnderLoadTest extends OracleTestBase {

    @Autowired private MockMvc mvc;
    @Autowired private DataSource dataSource;

    @Test
    void readinessWhileEveryConnectionIsCheckedOut() throws Exception {
        List<Connection> held = new ArrayList<>();
        try {
            held.add(dataSource.getConnection());
            held.add(dataSource.getConnection());

            // Ready while saturated: the pod is busy, not broken, and it has a
            // correct answer for the overflow - 503 with Retry-After.
            mvc.perform(get("/actuator/health/readiness"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));

            // The database is still checked, just no longer a gate on traffic.
            mvc.perform(get("/actuator/health"))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            for (Connection c : held) {
                c.close();
            }
        }
    }
}
