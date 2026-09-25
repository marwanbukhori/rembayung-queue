package dev.marwan.console.objects;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogLinesTest {

    private static final String EVENT = "2026-09-25T10:46:40.206610188Z {\"@timestamp\":\"2026-09-25T10:46:40.2Z\","
            + "\"message\":\"Claimed seats for a booking\",\"logger_name\":\"dev.marwan.booking.service.BookingService\","
            + "\"level\":\"INFO\",\"service\":\"booking-service\",\"event\":\"booking.claimed\",\"bookingId\":42,"
            + "\"seatsLeft\":60,\"lockWaitMs\":600}";
    private static final String WARN = "2026-09-25T10:46:41.000000001Z {\"message\":\"HikariPool-1 - Connection is not available\","
            + "\"level\":\"WARN\",\"logger_name\":\"com.zaxxer.hikari.pool.HikariPool\",\"service\":\"booking-service\"}";
    private static final String PLAIN = "2026-09-25T04:17:21.494000000Z 1:M 25 Sep 2026 04:17:21.494 * Ready to accept connections tcp";

    @Test
    void aStructuredEventKeepsItsNameAndBusinessFields() {
        LogLine line = LogLines.parse(EVENT).getFirst();

        assertThat(line.at()).isEqualTo("2026-09-25T10:46:40.206610188Z");
        assertThat(line.level()).isEqualTo("INFO");
        assertThat(line.message()).isEqualTo("Claimed seats for a booking");
        assertThat(line.event()).isEqualTo("booking.claimed");
        assertThat(line.fields()).containsEntry("bookingId", "42").containsEntry("seatsLeft", "60")
                .doesNotContainKeys("logger_name", "service", "level", "@timestamp", "message", "event");
    }

    @Test
    void aPlainLineIsKeptVerbatimWithItsKubeletTime() {
        LogLine line = LogLines.parse(PLAIN).getFirst();

        assertThat(line.at()).isEqualTo("2026-09-25T04:17:21.494000000Z");
        assertThat(line.level()).isNull();
        assertThat(line.message()).isEqualTo("1:M 25 Sep 2026 04:17:21.494 * Ready to accept connections tcp");
    }

    @Test
    void eachFilterKeepsWhatItSays() {
        List<LogLine> all = LogLines.parse(String.join("\n", EVENT, WARN, PLAIN));

        assertThat(LogLines.filter(all, LogFilter.ALL)).hasSize(3);
        assertThat(LogLines.filter(all, LogFilter.WARN)).extracting(LogLine::level).containsExactly("WARN");
        assertThat(LogLines.filter(all, LogFilter.EVENTS)).extracting(LogLine::event).containsExactly("booking.claimed");
    }

    /** Review focus 2: only numbers shaped like Malaysian phones are masked. */
    @Test
    void phonesAreMaskedAndOtherNumbersAreNot() {
        assertThat(LogLines.mask("booked for +60121")).isEqualTo("booked for +6012••••");
        assertThat(LogLines.mask("call +60123456789 now")).isEqualTo("call +6012•••• now");
        assertThat(LogLines.mask("or 60123456789")).isEqualTo("or 60123••••");
        assertThat(LogLines.mask("lockWaitMs=600 ticket 6012 seatsLeft=60"))
                .isEqualTo("lockWaitMs=600 ticket 6012 seatsLeft=60");
    }

    @Test
    void maskingReachesTheFieldsToo() {
        String line = "2026-09-25T10:00:00.000000000Z {\"message\":\"x\",\"level\":\"INFO\",\"event\":\"e\",\"phone\":\"+60123456789\"}";

        assertThat(LogLines.parse(line).getFirst().fields()).containsEntry("phone", "+6012••••");
    }

    /** Review focus 5. */
    @Test
    void aHugeMessageIsCut() {
        String line = "2026-09-25T10:00:00.000000000Z {\"message\":\"" + "x".repeat(5000) + "\",\"level\":\"ERROR\"}";

        assertThat(LogLines.parse(line).getFirst().message()).hasSize(LogLines.MAX_MESSAGE + 1).endsWith("…");
    }

    @Test
    void onlyTheLastFiveHundredLinesAreKept() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 700; i++) {
            raw.append(String.format("2026-09-25T10:%02d:%02d.000000000Z line %d%n", i / 60, i % 60, i));
        }

        List<LogLine> lines = LogLines.parse(raw.toString());

        assertThat(lines).hasSize(LogLines.MAX_LINES);
        assertThat(lines.getLast().message()).isEqualTo("line 699");
    }

    @Test
    void filtersParseFromTheirParamNames() {
        assertThat(LogFilter.parse("warn")).contains(LogFilter.WARN);
        assertThat(LogFilter.parse("nonsense")).isEmpty();
        assertThat(LogFilter.parse(null)).isEmpty();
    }
}
