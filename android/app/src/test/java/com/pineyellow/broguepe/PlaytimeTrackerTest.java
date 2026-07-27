package com.pineyellow.broguepe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class PlaytimeTrackerTest {

    @Test
    public void checkpointDelayTargetsNextAccumulatedMinute() {
        assertEquals(60_000L, PlaytimeTracker.millisUntilNextMinute(0L));
        assertEquals(1L, PlaytimeTracker.millisUntilNextMinute(59_999L));
        assertEquals(60_000L, PlaytimeTracker.millisUntilNextMinute(60_000L));
        assertEquals(59_999L, PlaytimeTracker.millisUntilNextMinute(60_001L));
    }
}
