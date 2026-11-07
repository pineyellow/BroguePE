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
    /** Bytes at the start of a recording before its moves, as in Recordings.c.
     *  The first 15 name the version it was recorded in, and the next one its
     *  mode. */
    static final int RECORDING_HEADER_LENGTH = 36;
    private static final int VERSION_LENGTH = 15;
    private static final int MODE_OFFSET = 15;
    /** Brogue CE's own easy mode, the same in both games: GAME_MODE_EASY in
     *  Rogue.h. */
    private static final int MODE_CE_EASY = 2;
    /** The mode of a run on PE's Easy difficulty: GAME_MODE_BALANCED_EASY in
     *  Rogue.h. Brogue CE has no such mode. */
    private static final int MODE_PE_EASY = 3;
    /** Put before the version of an exported Easy replay. Brogue CE would play
     *  it by its own rules and show a different game, so this makes it refuse
     *  the file instead. */
    static final String PE_ONLY_PREFIX = "PE ";

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

    /** The file name an exported recording starts with, the way Brogue CE
     *  names its own: "CE 1.15.1 #12345 Died at depth 4.broguerec" (see
     *  getDefaultFilePath in Recordings.c). The version is the recording's
     *  own, from its header, so the name says which version plays it. A run
     *  only Brogue PE can play says so at the end. */
    static String exportName(JSONObject game, byte[] header, int length) {
        StringBuilder name = new StringBuilder(nameVersion(recordingVersion(header, length)));
        Long seed = seed(game);
        if (seed != null) name.append(" #").append(Long.toUnsignedString(seed));
        int depth = game.optInt("depth");
        switch (outcome(game)) {
            case OUTCOME_MASTERED: name.append(" Mastered the dungeons"); break;
            case OUTCOME_ESCAPED:  name.append(" Escaped the dungeons"); break;
            case OUTCOME_QUIT:     name.append(" Quit at depth ").append(depth); break;
            default:               name.append(" Died at depth ").append(depth); break;
        }
        int mode = length >= RECORDING_HEADER_LENGTH ? header[MODE_OFFSET] & 0xff : -1;
        if (mode == MODE_PE_EASY) {
            name.append(" (PE easy)");
        } else if (mode == MODE_CE_EASY) {
            name.append(" (easy)");
        }
        return name.append(RECORDING_SUFFIX).toString();
    }

    /** A recording version as names show it: "CE 1.15.1". Recordings made
     *  before PE matched Brogue CE's version say "CE 1.15.1.9", which plays as
     *  1.15.1, so only the first three numbers are kept. */
    static String nameVersion(String version) {
        if (version == null) return "Brogue";
        int space = version.indexOf(' ');
        String[] numbers = version.substring(space + 1).split("\\.", -1);
        if (numbers.length <= 3) return version;
        return version.substring(0, space + 1) + numbers[0] + "." + numbers[1] + "." + numbers[2];
    }

    /** Marks the header of an exported replay as one only Brogue PE can play,
     *  if its run was on Easy: "CE 1.15.1" becomes "PE CE 1.15.1". Returns
     *  whether it changed. */
    static boolean tagPeOnly(byte[] header, int length) {
        if (header == null || length < RECORDING_HEADER_LENGTH
                || (header[MODE_OFFSET] & 0xff) != MODE_PE_EASY) {
            return false;
        }
        String version = recordingVersion(header, length);
        if (version == null || PE_ONLY_PREFIX.length() + version.length() > VERSION_LENGTH) {
            return false;
        }
        byte[] tagged = (PE_ONLY_PREFIX + version).getBytes(
            java.nio.charset.StandardCharsets.US_ASCII);
        java.util.Arrays.fill(header, 0, VERSION_LENGTH, (byte) 0);
        System.arraycopy(tagged, 0, header, 0, tagged.length);
        return true;
    }

    /** Undoes tagPeOnly on an imported replay. Returns whether it changed. */
    static boolean untagPeOnly(byte[] header, int length) {
        if (header == null || length < RECORDING_HEADER_LENGTH) return false;
        for (int i = 0; i < PE_ONLY_PREFIX.length(); i++) {
            if (header[i] != PE_ONLY_PREFIX.charAt(i)) return false;
        }
        int prefix = PE_ONLY_PREFIX.length();
        System.arraycopy(header, prefix, header, 0, VERSION_LENGTH - prefix);
        java.util.Arrays.fill(header, VERSION_LENGTH - prefix, VERSION_LENGTH, (byte) 0);
        return true;
    }

    /** The version a recording's header names, such as "CE 1.15.1", or null
     *  if the bytes don't start like a Brogue recording. */
    static String recordingVersion(byte[] header, int length) {
        if (header == null || length < RECORDING_HEADER_LENGTH) return null;
        int end = 0;
        while (end < VERSION_LENGTH && header[end] != 0) {
            // Shown to the player, so only plain printable ASCII.
            if (header[end] < 0x20 || header[end] > 0x7e) return null;
            end++;
        }
        String version = new String(header, 0, end, java.nio.charset.StandardCharsets.US_ASCII);
        boolean known = version.startsWith("CE ") || version.startsWith("RB ")
            || version.startsWith("BB ");
        return known && version.length() > 3 ? version : null;
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
