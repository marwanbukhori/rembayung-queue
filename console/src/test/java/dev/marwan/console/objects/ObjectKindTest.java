package dev.marwan.console.objects;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectKindTest {

    @Test
    void everyKindParsesFromItsOwnPathName() {
        for (ObjectKind kind : ObjectKind.values()) {
            assertThat(ObjectKind.parse(kind.path())).contains(kind);
        }
    }

    /** The path segment is user input. Anything unrecognised is absent, never an exception. */
    @Test
    void unknownOrOddlyCasedKindsAreAbsent() {
        assertThat(ObjectKind.parse("secret")).isEmpty();
        assertThat(ObjectKind.parse("POD")).isEmpty();
        assertThat(ObjectKind.parse("")).isEmpty();
        assertThat(ObjectKind.parse(null)).isEmpty();
    }
}
