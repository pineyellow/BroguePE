package com.pineyellow.broguepe;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/** Local storage for the Recent Games list. As in StatsStore, every change
 *  and all disk I/O run on one background thread, and the UI reads an
 *  immutable snapshot.
 *
 *  Two files: the list itself, and a snapshot of the run in progress that is
 *  rewritten at every save. When a new run starts while that snapshot exists,
 *  the old run was abandoned and joins the list as a quit, as it was at its
 *  last save. A file that can't be read starts empty rather than crashing.
 *
 *  Each game's recording is kept in the recordings folder. The engine leaves
 *  it under a fixed name (RecentGames.FINISHED_RECORDING or
 *  ABANDONED_RECORDING) just before the game reaches this store, which then
 *  renames it for the game. Recordings no game names are deleted. */
final class RecentGamesStore {

    private static final String TAG = "BrogueRecentGames";
    private static final String GAMES_FILE = "recent_games.json";
    private static final String UNFINISHED_FILE = "unfinished_run.json";
    // In the engine's working directory, which is the app's files folder.
    private static final String RECORDINGS_DIR = "recordings";

    private static volatile RecentGamesStore instance;

    static RecentGamesStore get(Context ctx) {
        if (instance == null) {
            synchronized (RecentGamesStore.class) {
                if (instance == null) {
                    instance = new RecentGamesStore(ctx.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final AtomicFile gamesFile;
    private final AtomicFile unfinishedFile;
    private final File recordingsDir;
    private final Handler handler;
    // Written only on handler's thread; never changed once published.
    private volatile List<JSONObject> games = Collections.emptyList();

    private RecentGamesStore(Context appContext) {
        File dir = appContext.getFilesDir();
        gamesFile = new AtomicFile(new File(dir, GAMES_FILE));
        unfinishedFile = new AtomicFile(new File(dir, UNFINISHED_FILE));
        recordingsDir = new File(dir, RECORDINGS_DIR);
        HandlerThread thread = new HandlerThread("BrogueRecentGamesIO");
        thread.start();
        handler = new Handler(thread.getLooper());
        // Queued first, so every later change sees the loaded list.
        handler.post(() -> {
            games = Collections.unmodifiableList(RecentGames.fromJson(read(gamesFile)));
            sweepRecordings();
        });
    }

    /** UI-thread safe. One mode's games, newest first. */
    List<JSONObject> games(int variant, int difficulty) {
        return RecentGames.forMode(games, variant, difficulty);
    }

    /** The game's recording file, or null if it has none. It may since have
     *  been deleted, so check that it exists. */
    static File recordingFile(Context ctx, JSONObject game) {
        String name = RecentGames.recordingName(game);
        return name == null ? null
            : new File(new File(ctx.getFilesDir(), RECORDINGS_DIR), name);
    }

    /** A run summary from the engine. A finished run joins the list; an
     *  unfinished one replaces the snapshot of the run in progress. */
    void record(final String summaryJson, final boolean finished,
                final long playtimeMillis, final long nowMillis) {
        handler.post(() -> {
            try {
                JSONObject game = RecentGames.stamped(summaryJson, nowMillis, playtimeMillis);
                if (finished) {
                    if (game != null) {
                        claimRecording(game, RecentGames.FINISHED_RECORDING);
                        add(game);
                    }
                    unfinishedFile.delete();
                } else if (game != null) {
                    write(unfinishedFile, game);
                }
            } catch (Throwable t) {
                Log.w(TAG, "record failed", t);
            }
        });
    }

    /** A new run (not a resume) has begun, so any run still in progress was
     *  abandoned. It joins the list as it was at its last save, with its save
     *  as its recording. */
    void onNewRunStarted() {
        handler.post(() -> {
            try {
                JSONObject abandoned = read(unfinishedFile);
                if (abandoned != null) {
                    claimRecording(abandoned, RecentGames.ABANDONED_RECORDING);
                    add(abandoned);
                } else {
                    // Without its game, a waiting recording must not be given
                    // to a later one.
                    new File(recordingsDir, RecentGames.ABANDONED_RECORDING).delete();
                }
                unfinishedFile.delete();
            } catch (Throwable t) {
                Log.w(TAG, "onNewRunStarted failed", t);
            }
        });
    }

    private void add(JSONObject game) throws JSONException {
        if (RecentGames.isWorthKeeping(game)) {
            List<JSONObject> next = Collections.unmodifiableList(
                RecentGames.withGame(games, game));
            games = next;
            write(gamesFile, RecentGames.toJson(next));
        }
        // Also drops the recordings of games pushed off the list, and of a
        // game that wasn't kept.
        sweepRecordings();
    }

    /** Renames the recording the engine left under stagingName for this game,
     *  after when it ended, and notes the name in the game. */
    private void claimRecording(JSONObject game, String stagingName) throws JSONException {
        File staged = new File(recordingsDir, stagingName);
        if (!staged.isFile()) return;
        String base = String.valueOf(game.optLong("endedAt", System.currentTimeMillis()));
        File target = new File(recordingsDir, base + RecentGames.RECORDING_SUFFIX);
        for (int i = 2; target.exists(); i++) {
            target = new File(recordingsDir, base + "-" + i + RecentGames.RECORDING_SUFFIX);
        }
        if (staged.renameTo(target)) {
            game.put(RecentGames.RECORDING, target.getName());
        } else {
            Log.w(TAG, "failed to keep the recording " + stagingName);
        }
    }

    private void sweepRecordings() {
        for (String name : RecentGames.recordingsToDelete(games, recordingsDir.list())) {
            if (!new File(recordingsDir, name).delete()) {
                Log.w(TAG, "failed to delete the recording " + name);
            }
        }
    }

    private static JSONObject read(AtomicFile file) {
        try {
            String text = new String(file.readFully(), StandardCharsets.UTF_8);
            Object parsed = new JSONTokener(text).nextValue();
            return parsed instanceof JSONObject ? (JSONObject) parsed : null;
        } catch (FileNotFoundException e) {
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "failed to read " + file.getBaseFile().getName() + "; starting fresh", t);
            return null;
        }
    }

    /** AtomicFile keeps the old file until the new one is complete and
     *  synced, so a crash mid-write can't leave it corrupt. */
    private static void write(AtomicFile file, JSONObject json) {
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (Throwable t) {
            Log.w(TAG, "failed to write " + file.getBaseFile().getName(), t);
            if (out != null) file.failWrite(out);
        }
    }
}
