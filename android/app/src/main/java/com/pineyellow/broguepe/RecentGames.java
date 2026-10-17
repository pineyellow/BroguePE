package com.pineyellow.broguepe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The Recent Games list: finished runs, newest first, kept per mode like the
 *  Personal Stats buckets. Each game is the engine's run summary (see
 *  runSummaryJson in Items.c) plus "endedAt" and "playtimeMillis", added when
 *  it was stored, and "recording" when its recording was kept. No Android
 *  calls here, so it can be unit tested. */
final class RecentGames {

    /** Games kept for each variant and difficulty. */
    static final int MAX_PER_MODE = 10;

    static final String OUTCOME_DIED = "died";
    static final String OUTCOME_QUIT = "quit";
    static final String OUTCOME_ESCAPED = "escaped";
    static final String OUTCOME_MASTERED = "mastered";

    /** The game's key for its recording: a file name in the recordings folder. */
    static final String RECORDING = "recording";
    static final String RECORDING_SUFFIX = ".broguerec";
    /** Where the engine leaves a finished or abandoned run's recording until
     *  that run's game takes it (see Recordings.c). */
    static final String FINISHED_RECORDING = "finished" + RECORDING_SUFFIX;
    static final String ABANDONED_RECORDING = "abandoned" + RECORDING_SUFFIX;

    private static final int VERSION = 1;

    private RecentGames() { }

    /** Parses an engine run summary and adds when it ended and how long it
     *  was played (-1 if untimed). Returns null if it isn't a JSON object. */
    static JSONObject stamped(String summaryJson, long endedAt, long playtimeMillis) {
        try {
            Object parsed = new JSONTokener(summaryJson == null ? "" : summaryJson).nextValue();
            if (!(parsed instanceof JSONObject)) return null;
            JSONObject game = (JSONObject) parsed;
            game.put("endedAt", endedAt);
            game.put("playtimeMillis", playtimeMillis);
            return game;
        } catch (JSONException e) {
            return null;
        }
    }

    /** Adds the game at the front, then drops the oldest games of its mode
     *  beyond MAX_PER_MODE. Other modes are left alone. */
    static List<JSONObject> withGame(List<JSONObject> games, JSONObject game) {
        int variant = variant(game);
        int difficulty = difficulty(game);
        List<JSONObject> next = new ArrayList<>(games.size() + 1);
        next.add(game);
        int sameMode = 1;
        for (JSONObject existing : games) {
            if (variant(existing) == variant && difficulty(existing) == difficulty) {
                if (sameMode >= MAX_PER_MODE) continue;
                sameMode++;
            }
            next.add(existing);
        }
        return next;
    }

    /** One mode's games, newest first. */
    static List<JSONObject> forMode(List<JSONObject> games, int variant, int difficulty) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject game : games) {
            if (variant(game) == variant && difficulty(game) == difficulty) {
                out.add(game);
            }
        }
        return out;
    }

    /** A run abandoned before its first turn is left out of the list. */
    static boolean isWorthKeeping(JSONObject game) {
        return !(OUTCOME_QUIT.equals(outcome(game)) && game.optLong("turns", 0) <= 0);
    }

    /** The details modal's title. A death says "DIED" rather than the death
     *  screen's "YOU DIED", to match the others. */
    static int titleRes(JSONObject game) {
        switch (outcome(game)) {
            case OUTCOME_QUIT:     return R.string.abandoned_title;
            case OUTCOME_ESCAPED:  return R.string.victory_title;
            case OUTCOME_MASTERED: return R.string.mastery_title;
            default:               return R.string.died_title;
        }
    }

    /** The run's seed, or null when it is missing or not a valid seed. Seeds
     *  are unsigned 64-bit, so a large one comes back as a negative long. */
    static Long seed(JSONObject game) {
        try {
            long seed = Long.parseUnsignedLong(game.optString("seed", ""));
            return seed == 0 ? null : seed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Summaries from before this list existed have no outcome; they were
     *  deaths. */
    static String outcome(JSONObject game) {
        return game.optString("outcome", OUTCOME_DIED);
    }

    /** The file name of the game's recording, or null if it has none. Only a
     *  plain name inside the recordings folder is accepted. */
    static String recordingName(JSONObject game) {
        String name = game.optString(RECORDING, "");
        if (!name.endsWith(RECORDING_SUFFIX) || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0) {
            return null;
        }
        return name;
    }

    /** The recordings among fileNames that no game names, which can go. The
     *  engine's waiting files are never included, nor anything that isn't a
     *  recording, such as a copy still being written. */
    static List<String> recordingsToDelete(List<JSONObject> games, String[] fileNames) {
        Set<String> kept = new HashSet<>();
        for (JSONObject game : games) {
            String name = recordingName(game);
            if (name != null) kept.add(name);
        }
        List<String> unused = new ArrayList<>();
        if (fileNames == null) return unused;
        for (String name : fileNames) {
            if (name.endsWith(RECORDING_SUFFIX) && !kept.contains(name)
                    && !name.equals(FINISHED_RECORDING) && !name.equals(ABANDONED_RECORDING)) {
                unused.add(name);
            }
        }
        return unused;
    }

    static boolean isVictory(JSONObject game) {
        String outcome = outcome(game);
        return OUTCOME_ESCAPED.equals(outcome) || OUTCOME_MASTERED.equals(outcome);
    }

    static int variant(JSONObject game) {
        return PlayerStatsArchive.normalizeVariant(
            game.optInt("variant", StartMenu.VARIANT_BROGUE));
    }

    static int difficulty(JSONObject game) {
        return PlayerStatsArchive.normalizeDifficulty(
            game.optInt("difficulty", StartMenu.DIFFICULTY_DEFAULT));
    }

    /** Reads the stored list; anything unexpected is skipped. */
    static List<JSONObject> fromJson(JSONObject root) {
        List<JSONObject> games = new ArrayList<>();
        JSONArray entries = root == null ? null : root.optJSONArray("games");
        if (entries == null) return games;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject game = entries.optJSONObject(i);
            if (game != null) games.add(game);
        }
        return games;
    }

    static JSONObject toJson(List<JSONObject> games) throws JSONException {
        JSONArray entries = new JSONArray();
        for (JSONObject game : games) {
            entries.put(game);
        }
        JSONObject root = new JSONObject();
        root.put("version", VERSION);
        root.put("games", entries);
        return root;
    }
}
