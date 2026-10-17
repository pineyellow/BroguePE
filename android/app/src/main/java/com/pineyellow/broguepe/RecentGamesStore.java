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
 *  last save. A file that can't be read starts empty rather than crashing. */
final class RecentGamesStore {

    private static final String TAG = "BrogueRecentGames";
    private static final String GAMES_FILE = "recent_games.json";
    private static final String UNFINISHED_FILE = "unfinished_run.json";

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
    private final Handler handler;
    // Written only on handler's thread; never changed once published.
    private volatile List<JSONObject> games = Collections.emptyList();

    private RecentGamesStore(Context appContext) {
        File dir = appContext.getFilesDir();
        gamesFile = new AtomicFile(new File(dir, GAMES_FILE));
        unfinishedFile = new AtomicFile(new File(dir, UNFINISHED_FILE));
        HandlerThread thread = new HandlerThread("BrogueRecentGamesIO");
        thread.start();
        handler = new Handler(thread.getLooper());
        // Queued first, so every later change sees the loaded list.
        handler.post(() -> games = Collections.unmodifiableList(
            RecentGames.fromJson(read(gamesFile))));
    }

    /** UI-thread safe. One mode's games, newest first. */
    List<JSONObject> games(int variant, int difficulty) {
        return RecentGames.forMode(games, variant, difficulty);
    }

    /** A run summary from the engine. A finished run joins the list; an
     *  unfinished one replaces the snapshot of the run in progress. */
    void record(final String summaryJson, final boolean finished,
                final long playtimeMillis, final long nowMillis) {
        handler.post(() -> {
            try {
                JSONObject game = RecentGames.stamped(summaryJson, nowMillis, playtimeMillis);
                if (finished) {
                    if (game != null) add(game);
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
     *  abandoned. It joins the list as it was at its last save. */
    void onNewRunStarted() {
        handler.post(() -> {
            try {
                JSONObject abandoned = read(unfinishedFile);
                if (abandoned != null) add(abandoned);
                unfinishedFile.delete();
            } catch (Throwable t) {
                Log.w(TAG, "onNewRunStarted failed", t);
            }
        });
    }

    private void add(JSONObject game) throws JSONException {
        if (!RecentGames.isWorthKeeping(game)) return;
        List<JSONObject> next = Collections.unmodifiableList(
            RecentGames.withGame(games, game));
        games = next;
        write(gamesFile, RecentGames.toJson(next));
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
