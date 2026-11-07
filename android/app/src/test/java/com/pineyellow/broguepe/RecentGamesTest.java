package com.pineyellow.broguepe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class RecentGamesTest {

    private static JSONObject game(String outcome, int variant, int difficulty, int turns)
            throws Exception {
        JSONObject game = new JSONObject();
        game.put("outcome", outcome);
        game.put("cause", "");
        game.put("depth", 3);
        game.put("turns", turns);
        game.put("seed", "12345");
        game.put("variant", variant);
        game.put("difficulty", difficulty);
        return game;
    }

    @Test
    public void newestFirstAndCappedPerModeOnly() throws Exception {
        List<JSONObject> games = new ArrayList<>();
        JSONObject easy = game("died", StartMenu.VARIANT_BROGUE, StartMenu.DIFFICULTY_EASY, 10);
        games = RecentGames.withGame(games, easy);
        for (int i = 0; i < RecentGames.MAX_PER_MODE + 2; i++) {
            JSONObject normal = game("died", StartMenu.VARIANT_BROGUE,
                StartMenu.DIFFICULTY_DEFAULT, 10);
            normal.put("index", i);
            games = RecentGames.withGame(games, normal);
        }

        List<JSONObject> normal = RecentGames.forMode(games,
            StartMenu.VARIANT_BROGUE, StartMenu.DIFFICULTY_DEFAULT);
        assertEquals(RecentGames.MAX_PER_MODE, normal.size());
        assertEquals(RecentGames.MAX_PER_MODE + 1, normal.get(0).getInt("index"));
        assertEquals(2, normal.get(normal.size() - 1).getInt("index"));

        List<JSONObject> easyGames = RecentGames.forMode(games,
            StartMenu.VARIANT_BROGUE, StartMenu.DIFFICULTY_EASY);
        assertEquals(1, easyGames.size());
        assertTrue(easyGames.get(0) == easy);
    }

    @Test
    public void onlyAQuitWithoutTurnsIsDropped() throws Exception {
        assertFalse(RecentGames.isWorthKeeping(game("quit", 0, 0, 0)));
        assertTrue(RecentGames.isWorthKeeping(game("quit", 0, 0, 1)));
        assertTrue(RecentGames.isWorthKeeping(game("died", 0, 0, 0)));
    }

    @Test
    public void titlesFollowTheOutcome() throws Exception {
        assertEquals(R.string.died_title, RecentGames.titleRes(game("died", 0, 0, 50)));
        assertEquals(R.string.abandoned_title, RecentGames.titleRes(game("quit", 0, 0, 50)));
        assertEquals(R.string.victory_title, RecentGames.titleRes(game("escaped", 0, 0, 50)));
        assertEquals(R.string.mastery_title, RecentGames.titleRes(game("mastered", 0, 0, 50)));
        assertEquals(R.string.died_title, RecentGames.titleRes(new JSONObject()));
    }

    @Test
    public void seedsParseUnsignedAndRejectBadValues() throws Exception {
        JSONObject game = game("died", 0, 0, 1);
        assertEquals(Long.valueOf(12345L), RecentGames.seed(game));
        game.put("seed", "18446744073709551615");
        assertEquals(Long.valueOf(-1L), RecentGames.seed(game));
        game.put("seed", "not a seed");
        assertNull(RecentGames.seed(game));
        game.put("seed", "0");
        assertNull(RecentGames.seed(game));
        game.remove("seed");
        assertNull(RecentGames.seed(game));
    }

    @Test
    public void stampedAddsTimesAndRejectsNonObjects() throws Exception {
        JSONObject game = RecentGames.stamped("{\"outcome\":\"died\"}", 1000L, -1L);
        assertEquals(1000L, game.getLong("endedAt"));
        assertEquals(-1L, game.getLong("playtimeMillis"));
        assertNull(RecentGames.stamped("[]", 1000L, 0L));
        assertNull(RecentGames.stamped("not json", 1000L, 0L));
        assertNull(RecentGames.stamped(null, 1000L, 0L));
    }

    @Test
    public void recordingNamesMustBePlainRecordingFiles() throws Exception {
        JSONObject game = game("died", 0, 0, 1);
        assertNull(RecentGames.recordingName(game));
        game.put("recording", "1760000000000.broguerec");
        assertEquals("1760000000000.broguerec", RecentGames.recordingName(game));
        game.put("recording", "../BroguePE.broguerec");
        assertNull(RecentGames.recordingName(game));
        game.put("recording", "BroguePE.broguesave");
        assertNull(RecentGames.recordingName(game));
    }

    @Test
    public void exportNamesFollowBrogueCe() throws Exception {
        byte[] ce = header("CE 1.15.1", 0);
        assertEquals("CE 1.15.1 #12345 Died at depth 3.broguerec",
            RecentGames.exportName(game("died", 0, 0, 10), ce, ce.length));
        assertEquals("CE 1.15.1 #12345 Quit at depth 3.broguerec",
            RecentGames.exportName(game("quit", 0, 0, 10), ce, ce.length));
        assertEquals("CE 1.15.1 #12345 Escaped the dungeons.broguerec",
            RecentGames.exportName(game("escaped", 0, 0, 10), ce, ce.length));
        assertEquals("CE 1.15.1 #12345 Mastered the dungeons.broguerec",
            RecentGames.exportName(game("mastered", 0, 0, 10), ce, ce.length));

        JSONObject game = game("died", StartMenu.VARIANT_RAPID, 1, 10);
        game.put("seed", "18446744073709551615");
        byte[] easy = header("RB 1.7.1", 3);
        assertEquals("RB 1.7.1 #18446744073709551615 Died at depth 3 (PE easy).broguerec",
            RecentGames.exportName(game, easy, easy.length));
        byte[] ceEasy = header("CE 1.15.1", 2);
        assertEquals("CE 1.15.1 #18446744073709551615 Died at depth 3 (easy).broguerec",
            RecentGames.exportName(game, ceEasy, ceEasy.length));

        // Made before the version lost its ".9", which plays as 1.15.1.
        byte[] old = header("CE 1.15.1.9", 0);
        assertEquals("CE 1.15.1 #12345 Died at depth 3.broguerec",
            RecentGames.exportName(game("died", 0, 0, 10), old, old.length));
        assertEquals("Brogue Died at depth 0.broguerec",
            RecentGames.exportName(new JSONObject(), new byte[0], 0));
    }

    @Test
    public void recordingVersionsComeFromBrogueHeadersOnly() throws Exception {
        byte[] header = new byte[RecentGames.RECORDING_HEADER_LENGTH];
        byte[] version = "RB 1.5.0".getBytes("US-ASCII");
        System.arraycopy(version, 0, header, 0, version.length);
        assertEquals("RB 1.5.0", RecentGames.recordingVersion(header, header.length));
        assertNull(RecentGames.recordingVersion(header, header.length - 1));

        byte[] text = "Just some notes, not a replay at all".getBytes("US-ASCII");
        assertNull(RecentGames.recordingVersion(text, text.length));
        header[4] = (byte) 0xC3;
        assertNull(RecentGames.recordingVersion(header, header.length));
        assertNull(RecentGames.recordingVersion(new byte[40], 40));
    }

    private static byte[] header(String version, int mode) throws Exception {
        byte[] header = new byte[RecentGames.RECORDING_HEADER_LENGTH];
        byte[] text = version.getBytes("US-ASCII");
        System.arraycopy(text, 0, header, 0, text.length);
        header[15] = (byte) mode;
        header[20] = 42; // the seed, which must be left alone
        return header;
    }

    @Test
    public void onlyEasyExportsAreTaggedAndTagsComeOff() throws Exception {
        byte[] easy = header("CE 1.15.1", 3);
        assertTrue(RecentGames.tagPeOnly(easy, easy.length));
        assertNull(RecentGames.recordingVersion(easy, easy.length));
        assertEquals("PE CE 1.15.1", new String(easy, 0, 12, "US-ASCII"));
        assertEquals(0, easy[12]);
        assertEquals(3, easy[15]);
        assertEquals(42, easy[20]);

        assertTrue(RecentGames.untagPeOnly(easy, easy.length));
        assertEquals("CE 1.15.1", RecentGames.recordingVersion(easy, easy.length));
        assertEquals(3, easy[15]);
        assertEquals(42, easy[20]);

        // Recordings from before the version lost its ".9" still fit.
        byte[] old = header("CE 1.15.1.9", 3);
        assertTrue(RecentGames.tagPeOnly(old, old.length));
        assertTrue(RecentGames.untagPeOnly(old, old.length));
        assertEquals("CE 1.15.1.9", RecentGames.recordingVersion(old, old.length));

        byte[] normal = header("RB 1.7.1", 0);
        assertFalse(RecentGames.tagPeOnly(normal, normal.length));
        assertFalse(RecentGames.untagPeOnly(normal, normal.length));
        assertEquals("RB 1.7.1", RecentGames.recordingVersion(normal, normal.length));
        // Brogue CE's own easy mode plays the same there.
        byte[] ceEasy = header("CE 1.15.1", 2);
        assertFalse(RecentGames.tagPeOnly(ceEasy, ceEasy.length));
        assertFalse(RecentGames.tagPeOnly(easy, easy.length - 1));
    }

    @Test
    public void onlyUnusedRecordingsAreDeleted() throws Exception {
        List<JSONObject> games = new ArrayList<>();
        JSONObject kept = game("died", 0, 0, 5);
        kept.put("recording", "1.broguerec");
        games.add(kept);
        games.add(game("quit", 0, 0, 5));

        List<String> unused = RecentGames.recordingsToDelete(games, new String[] {
            "1.broguerec", "2.broguerec", "finished.broguerec", "abandoned.broguerec",
            "finished.broguerec.tmp", "notes.txt" });
        assertEquals(1, unused.size());
        assertEquals("2.broguerec", unused.get(0));
        assertTrue(RecentGames.recordingsToDelete(games, null).isEmpty());
    }

    @Test
    public void storedListRoundTripsAndSkipsBadEntries() throws Exception {
        List<JSONObject> games = new ArrayList<>();
        games = RecentGames.withGame(games, game("died", 1, 0, 5));
        games = RecentGames.withGame(games, game("quit", 2, 1, 5));

        List<JSONObject> loaded = RecentGames.fromJson(RecentGames.toJson(games));
        assertEquals(2, loaded.size());
        assertEquals("quit", RecentGames.outcome(loaded.get(0)));

        JSONObject odd = new JSONObject("{\"games\":[1,\"x\",{\"outcome\":\"died\"}]}");
        assertEquals(1, RecentGames.fromJson(odd).size());
        assertTrue(RecentGames.fromJson(new JSONObject()).isEmpty());
        assertTrue(RecentGames.fromJson(null).isEmpty());
    }
}
