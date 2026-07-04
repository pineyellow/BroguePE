package com.pineyellow.broguepe;

import android.content.Context;

/** SharedPreferences-backed game settings. Mirrored in C (the engine reads
 *  them via JNI at startup and re-reads on relevant keystrokes like '\\' to
 *  toggle color effects). Keep keys in sync with brogue_settings usage on
 *  the C side. */
final class GameSettings {

    private static final String PREFS = "brogue_settings";

    private GameSettings() {}

    static boolean getBool(Context c, String key) {
        return getBool(c, key, false);
    }

    static boolean getBool(Context c, String key, boolean defaultValue) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, defaultValue);
    }

    static void setBool(Context c, String key, boolean value) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(key, value).apply();
    }

    static int getInt(Context c, String key, int defaultValue) {
        Object value = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll().get(key);
        return value instanceof Number ? ((Number) value).intValue() : defaultValue;
    }

    static void setInt(Context c, String key, int value) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(key, value).apply();
    }

    static float getFloat(Context c, String key, float defaultValue) {
        Object value = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll().get(key);
        return value instanceof Number ? ((Number) value).floatValue() : defaultValue;
    }

    static void setFloat(Context c, String key, float value) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(key, value).apply();
    }
}
