package com.pineyellow.broguepe;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RunPlaytimeTrackerTest {

    @Test
    public void resumeContinuesOnlyItsOwnRecord() {
        assertTrue(RunPlaytimeTracker.continuesRecord(true, true, 42L, 42L));
        assertFalse(RunPlaytimeTracker.continuesRecord(true, true, 41L, 42L));
        assertFalse(RunPlaytimeTracker.continuesRecord(true, false, 0L, 0L));
    }

    @Test
    public void newGameNeverContinuesARecord() {
        assertFalse(RunPlaytimeTracker.continuesRecord(false, true, 42L, 42L));
    }
}
