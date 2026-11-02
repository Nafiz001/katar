package dev.nafiz.katar.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LegsTest {

    @Test
    void aJourneyCoversTheLegsBetweenItsStops() {
        // stops 0 -> 3 use legs 0, 1 and 2
        assertThat(Legs.mask(0, 3)).isEqualTo(0b111L);
        // stops 3 -> 9 use legs 3 to 8
        assertThat(Legs.mask(3, 9)).isEqualTo(0b1_1111_1000L);
        assertThat(Legs.count(Legs.mask(2, 7))).isEqualTo(5);
    }

    @Test
    void journeysThatMeetAtAStationDoNotOverlap() {
        // Dhaka -> Tangail ends where Tangail -> Rajshahi begins: one seat serves both
        assertThat(Legs.overlap(Legs.mask(0, 3), Legs.mask(3, 9))).isFalse();
        assertThat(Legs.overlap(Legs.mask(0, 4), Legs.mask(3, 9))).isTrue();
    }

    @Test
    void theWidestRouteFitsInAPositiveBigint() {
        assertThat(Legs.mask(0, 63)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void impossibleJourneysAreRefused() {
        assertThatThrownBy(() -> Legs.mask(3, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Legs.mask(4, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Legs.mask(0, 64)).isInstanceOf(IllegalArgumentException.class);
    }
}
