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
