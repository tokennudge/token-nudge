package io.github.tokennudge;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class SimulationIdTest {

    @Test
    void wrapsGivenUuid() {
        UUID uuid = UUID.randomUUID();
        assertThat(new SimulationId(uuid).value()).isEqualTo(uuid);
    }

    @Test
    void nullValueIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> new SimulationId(null));
    }

    @Test
    void newIdGeneratesDistinctIds() {
        assertThat(SimulationId.newId()).isNotEqualTo(SimulationId.newId());
    }

    @Test
    void equalsAndHashCodeAreBasedOnWrappedValue() {
        UUID uuid = UUID.randomUUID();
        assertThat(new SimulationId(uuid)).isEqualTo(new SimulationId(uuid)).hasSameHashCodeAs(new SimulationId(uuid));
    }
}
