package com.pineyellow.broguepe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class PlayerStatsModalTest {

    @Test
    public void formatPlaytimeShowsOnlyAccumulatedHoursAndMinutes() {
        assertEquals("0h 0m", PlayerStatsModal.formatPlaytime(0L));
        assertEquals("0h 0m", PlayerStatsModal.formatPlaytime(59_999L));
        assertEquals("5h 21m", PlayerStatsModal.formatPlaytime(19_260_000L));
        assertEquals("49h 2m", PlayerStatsModal.formatPlaytime(176_520_000L));
    }

    @Test
    public void formatPlaytimeClampsInvalidNegativeValues() {
        assertEquals("0h 0m", PlayerStatsModal.formatPlaytime(-1L));
    }
}
