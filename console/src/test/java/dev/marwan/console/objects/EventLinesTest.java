package dev.marwan.console.objects;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.MicroTime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventLinesTest {

    /** Newer events carry only eventTime. They must sort among the rest, not fall to the bottom. */
    @Test
    void eventsWithOnlyAnEventTimeSortByIt() {
        Event old = new EventBuilder().withType("Normal").withReason("Pulled")
                .withLastTimestamp("2026-09-25T07:00:00Z").withCount(1).build();
        Event recent = new EventBuilder().withType("Warning").withReason("Unhealthy")
                .withEventTime(new MicroTime("2026-09-25T07:30:00.000000Z")).build();

        List<ObjectDetail.EventLine> lines = EventLines.from(List.of(old, recent));

        assertThat(lines).extracting(ObjectDetail.EventLine::reason).containsExactly("Unhealthy", "Pulled");
        assertThat(lines.getFirst().at()).isEqualTo("2026-09-25T07:30:00Z");
        assertThat(lines.getFirst().count()).isEqualTo(1);
    }

    @Test
    void anEventWithNoTimeAtAllIsKeptLast() {
        Event timeless = new EventBuilder().withType("Normal").withReason("Mystery").build();
        Event timed = new EventBuilder().withType("Normal").withReason("Created")
                .withLastTimestamp("2026-09-25T07:00:00Z").build();

        assertThat(EventLines.from(List.of(timeless, timed)))
                .extracting(ObjectDetail.EventLine::reason).containsExactly("Created", "Mystery");
    }

    @Test
    void atMostTwentyAreKept() {
        List<Event> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(new EventBuilder().withReason("r" + i)
                    .withLastTimestamp(String.format("2026-09-25T07:%02d:00Z", i)).build());
        }

        List<ObjectDetail.EventLine> lines = EventLines.from(many);

        assertThat(lines).hasSize(20);
        assertThat(lines.getFirst().reason()).isEqualTo("r29");
    }
}
