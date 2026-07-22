package com.pineyellow.broguepe;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Local-only, per-install player stats. All mutation and disk I/O runs on
 *  a single background HandlerThread so the JNI callbacks from the engine
 *  return in microseconds. Reads are lock-free via a volatile reference to
 *  the immutable PlayerStats snapshot.
 *
 *  Writes are atomic (tmp + rename) so a crash mid-write can't leave the
 *  file corrupt. If the file is unreadable at load time, we start fresh
 *  rather than crash — loss of stats is preferable to a crashed app. */
final class StatsStore {

    private static final String TAG = "BrogueStats";
    private static final String FILENAME = "stats.json";

    private static volatile StatsStore instance;

    static StatsStore get(Context ctx) {
        if (instance == null) {
            synchronized (StatsStore.class) {
                if (instance == null) {
                    instance = new StatsStore(ctx.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final File statsFile;
    private final Handler handler;
    private volatile PlayerStatsArchive current;
    // Accessed only on handler's looper. onGameStart is queued before any
    // events from that run, so every callback uses the correct bucket.
    private int activeVariant = StartMenu.VARIANT_BROGUE;
    private int activeDifficulty = StartMenu.DIFFICULTY_DEFAULT;

    private StatsStore(Context appContext) {
        this.statsFile = new File(appContext.getFilesDir(), FILENAME);
        HandlerThread thread = new HandlerThread("BrogueStatsIO");
        thread.start();
        this.handler = new Handler(thread.getLooper());
        this.current = loadFromDisk();
    }

    /** UI-thread safe. Returns the most recent immutable snapshot. */
    PlayerStats snapshot(int variant, int difficulty) {
        return current.bucket(variant, difficulty);
    }

    /** Functional shape of every PlayerStats mutator — takes the current
     *  snapshot, returns the next. Lets every record* method funnel through
     *  the same publish-and-persist pipeline. */
    private interface Mutator {
        PlayerStats apply(PlayerStats s);
    }

    private void post(final String tag, final Mutator mutator) {
        handler.post(new Runnable() {
            @Override public void run() {
                try {
                    PlayerStats nextBucket = mutator.apply(
                        current.bucket(activeVariant, activeDifficulty));
                    PlayerStatsArchive next = current.withBucket(
                        activeVariant, activeDifficulty, nextBucket);
                    current = next;
                    // Flush every event: if the app is OS-killed mid-run,
                    // brogue's save will replay the run under rogue.playbackMode
                    // and the replay guard correctly skips those events — but
                    // that guarantee only holds if the events were already
                    // persisted. Deferring the flush would silently lose them.
                    writeToDisk(next);
                } catch (Throwable t) {
                    Log.w(TAG, tag + " failed", t);
                }
            }
        });
    }

    void recordGameStart(final long seed, final int variant,
                         final int difficulty, final boolean isResume) {
        handler.post(new Runnable() {
            @Override public void run() {
                try {
                    activeVariant = PlayerStatsArchive.normalizeVariant(variant);
                    activeDifficulty = PlayerStatsArchive.normalizeDifficulty(difficulty);
                    PlayerStatsArchive next = current.withRunStarted(
                        seed, activeVariant, activeDifficulty, isResume);
                    if (isResume) return;
                    current = next;
                    writeToDisk(next);
                } catch (Throwable t) {
                    Log.w(TAG, "recordGameStart failed", t);
                }
            }
        });
    }

    void recordAllyFreed(final String name) {
        if (name == null || name.isEmpty()) return;
        post("recordAllyFreed", s -> s.withAllyFreed(name));
    }

    void recordAllyDied(final String name) {
        if (name == null || name.isEmpty()) return;
        post("recordAllyDied", s -> s.withAllyDied(name));
    }

    void recordMonsterKilled(final String name) {
        if (name == null || name.isEmpty()) return;
        post("recordMonsterKilled", s -> s.withMonsterKilled(name));
    }

    void recordPlayerDied(final String killedBy, final int depth, final int turns,
                          final long gold) {
        final String safeKilledBy = killedBy == null ? "" : killedBy;
        post("recordPlayerDied",
            s -> s.withPlayerDied(safeKilledBy, depth, turns, gold));
    }

    void recordPlayerWon(final boolean superVictory, final int depth, final int turns,
                         final long gold) {
        post("recordPlayerWon",
            s -> s.withPlayerWon(superVictory, depth, turns, gold));
    }

    void recordPlayerQuit(final int depth, final int turns, final long gold) {
        post("recordPlayerQuit", s -> s.withPlayerQuit(depth, turns, gold));
    }

    private PlayerStatsArchive loadFromDisk() {
        try {
            if (!statsFile.exists()) return PlayerStatsArchive.empty();
            byte[] bytes = new byte[(int) statsFile.length()];
            try (FileInputStream in = new FileInputStream(statsFile)) {
                int read = 0;
                while (read < bytes.length) {
                    int n = in.read(bytes, read, bytes.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            String s = new String(bytes, StandardCharsets.UTF_8);
            Object parsed = new JSONTokener(s).nextValue();
            if (!(parsed instanceof JSONObject)) return PlayerStatsArchive.empty();
            return PlayerStatsArchive.fromJson((JSONObject) parsed);
        } catch (Throwable t) {
            Log.w(TAG, "failed to load " + FILENAME + "; starting fresh", t);
            return PlayerStatsArchive.empty();
        }
    }

    private void writeToDisk(PlayerStatsArchive stats) {
        File tmp = new File(statsFile.getParentFile(), FILENAME + ".tmp");
        try {
            String json = stats.toJson().toString();
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                // fsync the tmp file before renaming — otherwise an OS crash
                // between rename and flush could leave us with a zero-byte file.
                out.getFD().sync();
            }
            if (!tmp.renameTo(statsFile)) {
                Log.w(TAG, FILENAME + " rename failed");
                tmp.delete();
            }
        } catch (Throwable t) {
            Log.w(TAG, "writeToDisk failed", t);
            tmp.delete();
        }
    }
}
