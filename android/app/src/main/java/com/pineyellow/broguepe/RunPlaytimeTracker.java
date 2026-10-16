package com.pineyellow.broguepe;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Time played in the current run, for the death screen. Counts while a run
 *  is in progress and the activity is resumed, so the title screen, the
 *  death screen and time in the background are left out. The total is stored
 *  with the run's seed so resuming a save continues its own time. A save
 *  resumed without a matching record (for example one started before this
 *  tracker existed) stays untimed rather than showing a partial time.
 *  UI thread only. */
final class RunPlaytimeTracker {

    private static final String PREFS = "brogue_run_playtime";
    private static final String KEY_SEED = "seed";
    private static final String KEY_MILLIS = "millis";

    private final SharedPreferences preferences;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable minuteCheckpoint = this::checkpointMinute;
    private long accumulatedMillis;
    private long activeSinceMillis = -1;
    private boolean resumed;
    private boolean runActive;
    private boolean timed;

    RunPlaytimeTracker(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void onResume() {
        resumed = true;
        updateTrackingState();
    }

    void onPause() {
        resumed = false;
        updateTrackingState();
    }

    /** A new game starts from zero; a resumed save continues only its own
     *  record and is otherwise left untimed. */
    @SuppressLint("ApplySharedPref")
    void onRunStarted(long seed, boolean isResume) {
        runActive = false;
        updateTrackingState();

        if (continuesRecord(isResume, preferences.contains(KEY_SEED),
                            preferences.getLong(KEY_SEED, 0L), seed)) {
            accumulatedMillis = Math.max(0L, preferences.getLong(KEY_MILLIS, 0L));
            timed = true;
        } else if (!isResume) {
            accumulatedMillis = 0L;
            timed = true;
            preferences.edit().putLong(KEY_SEED, seed).putLong(KEY_MILLIS, 0L).commit();
        } else {
            accumulatedMillis = 0L;
            timed = false;
            preferences.edit().clear().commit();
        }

        runActive = true;
        updateTrackingState();
    }

    /** Death, victory, or back to the title screen. */
    void onRunEnded() {
        runActive = false;
        updateTrackingState();
    }

    /** Time played in the current or most recent run, or -1 when that run
     *  isn't timed. */
    long runMillis() {
        if (!timed) return -1L;
        if (activeSinceMillis < 0) return accumulatedMillis;
        return PlaytimeTracker.safeAdd(accumulatedMillis,
            Math.max(0L, SystemClock.elapsedRealtime() - activeSinceMillis));
    }

    static boolean continuesRecord(boolean isResume, boolean hasRecord,
                                   long recordSeed, long seed) {
        return isResume && hasRecord && recordSeed == seed;
    }

    private void updateTrackingState() {
        long now = SystemClock.elapsedRealtime();
        boolean shouldTrack = resumed && runActive && timed;
        if (shouldTrack && activeSinceMillis < 0) {
            activeSinceMillis = now;
            scheduleMinuteCheckpoint();
        } else if (!shouldTrack && activeSinceMillis >= 0) {
            handler.removeCallbacks(minuteCheckpoint);
            accumulateThrough(now);
            activeSinceMillis = -1;
            preferences.edit().putLong(KEY_MILLIS, accumulatedMillis).apply();
        }
    }

    /** Same synchronous minute checkpoint as PlaytimeTracker, so a hard
     *  process stop loses less than a minute of the run. */
    @SuppressLint("ApplySharedPref")
    private void checkpointMinute() {
        if (activeSinceMillis < 0) return;
        accumulateThrough(SystemClock.elapsedRealtime());
        preferences.edit().putLong(KEY_MILLIS, accumulatedMillis).commit();
        scheduleMinuteCheckpoint();
    }

    private void scheduleMinuteCheckpoint() {
        handler.removeCallbacks(minuteCheckpoint);
        handler.postDelayed(minuteCheckpoint,
            PlaytimeTracker.millisUntilNextMinute(runMillis()));
    }

    private void accumulateThrough(long now) {
        accumulatedMillis = PlaytimeTracker.safeAdd(accumulatedMillis,
            Math.max(0L, now - activeSinceMillis));
        activeSinceMillis = now;
    }
}
