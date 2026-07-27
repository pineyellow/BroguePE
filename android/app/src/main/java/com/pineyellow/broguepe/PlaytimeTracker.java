package com.pineyellow.broguepe;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Persists time spent in the resumed activity while its window has focus. */
final class PlaytimeTracker {

    private static final String PREFS = "brogue_playtime";
    private static final String KEY_TOTAL_MILLIS = "total_millis";
    private static final long MINUTE_MILLIS = 60_000L;

    private final SharedPreferences preferences;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable minuteCheckpoint = this::checkpointMinute;
    private long accumulatedMillis;
    private long activeSinceMillis = -1;
    private boolean resumed;
    private boolean windowFocused;

    PlaytimeTracker(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        accumulatedMillis = Math.max(0L, preferences.getLong(KEY_TOTAL_MILLIS, 0L));
    }

    void onResume() {
        resumed = true;
        updateTrackingState();
    }

    void onPause() {
        resumed = false;
        updateTrackingState();
    }

    void onWindowFocusChanged(boolean focused) {
        windowFocused = focused;
        updateTrackingState();
    }

    long totalMillis() {
        if (activeSinceMillis < 0) return accumulatedMillis;
        return safeAdd(accumulatedMillis,
            Math.max(0L, SystemClock.elapsedRealtime() - activeSinceMillis));
    }

    private void updateTrackingState() {
        long now = SystemClock.elapsedRealtime();
        boolean shouldTrack = resumed && windowFocused;
        if (shouldTrack && activeSinceMillis < 0) {
            activeSinceMillis = now;
            scheduleMinuteCheckpoint();
        } else if (!shouldTrack && activeSinceMillis >= 0) {
            handler.removeCallbacks(minuteCheckpoint);
            accumulateThrough(now);
            activeSinceMillis = -1;
            preferences.edit().putLong(KEY_TOTAL_MILLIS, accumulatedMillis).apply();
        }
    }

    /** Checkpoint at each accumulated whole-minute boundary. A synchronous
     *  write here is intentional: once the UI can display a minute, that
     *  minute must survive even an immediate hard process stop. */
    @SuppressLint("ApplySharedPref")
    private void checkpointMinute() {
        if (activeSinceMillis < 0) return;
        accumulateThrough(SystemClock.elapsedRealtime());
        preferences.edit().putLong(KEY_TOTAL_MILLIS, accumulatedMillis).commit();
        scheduleMinuteCheckpoint();
    }

    private void scheduleMinuteCheckpoint() {
        handler.removeCallbacks(minuteCheckpoint);
        handler.postDelayed(minuteCheckpoint, millisUntilNextMinute(totalMillis()));
    }

    static long millisUntilNextMinute(long totalMillis) {
        long remainder = Math.max(0L, totalMillis) % MINUTE_MILLIS;
        return remainder == 0L ? MINUTE_MILLIS : MINUTE_MILLIS - remainder;
    }

    private void accumulateThrough(long now) {
        accumulatedMillis = safeAdd(accumulatedMillis,
            Math.max(0L, now - activeSinceMillis));
        activeSinceMillis = now;
    }

    private static long safeAdd(long a, long b) {
        return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b;
    }
}
