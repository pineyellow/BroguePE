package com.pineyellow.broguepe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.junit.Test;

import java.util.List;

public class RunSummaryPanelTest {

    @Test
    public void runTalliesMergeNamesAndSortByCount() throws Exception {
        JSONArray entries = new JSONArray(
            "[{\"name\":\"rat\",\"count\":2},"
            + "{\"name\":\"goblin\",\"count\":3},"
            + "{\"name\":\"rat\",\"count\":4}]");

        List<PlayerStats.Tally> tallies = RunSummaryPanel.runTallies(entries);

        assertEquals(2, tallies.size());
        assertEquals("rat", tallies.get(0).label);
        assertEquals(6, tallies.get(0).count);
        assertEquals("goblin", tallies.get(1).label);
        assertEquals(3, tallies.get(1).count);
    }

    @Test
    public void runTalliesSkipBadEntries() throws Exception {
        JSONArray entries = new JSONArray(
            "[{\"name\":\"\",\"count\":2},{\"name\":\"rat\",\"count\":0},"
            + "{\"count\":5},\"rat\",{\"name\":\"jackal\",\"count\":1}]");

        List<PlayerStats.Tally> tallies = RunSummaryPanel.runTallies(entries);

        assertEquals(1, tallies.size());
        assertEquals("jackal", tallies.get(0).label);
    }

    @Test
    public void runTalliesAreEmptyWithoutAList() {
        assertTrue(RunSummaryPanel.runTallies(null).isEmpty());
    }

    @Test
    public void runPlaytimeRoundsUpToWholeMinutes() {
        assertEquals("0h 0m", RunSummaryPanel.formatRunPlaytime(0L));
        assertEquals("0h 1m", RunSummaryPanel.formatRunPlaytime(10_000L));
        assertEquals("0h 1m", RunSummaryPanel.formatRunPlaytime(60_000L));
        assertEquals("0h 2m", RunSummaryPanel.formatRunPlaytime(65_000L));
        assertEquals("1h 0m", RunSummaryPanel.formatRunPlaytime(3_540_001L));
        assertEquals("0h 0m", RunSummaryPanel.formatRunPlaytime(-1L));
    }
}
