package com.pineyellow.broguepe;

import org.json.JSONException;
import org.json.JSONObject;

/** Display-only stress data. Included only in debug builds with -PdebugOverlay=true.
 * Never publishes to StatsStore or writes files. Names match the monster catalog. */
final class PlayerStatsPreview {
    private static final String[] MONSTERS = {
        "rat", "kobold", "jackal", "eel", "monkey", "bloat", "pit bloat",
        "goblin", "goblin conjurer", "goblin mystic", "goblin totem", "pink jelly",
        "toad", "vampire bat", "arrow turret", "acid mound", "centipede", "ogre",
        "bog monster", "ogre totem", "spider", "spark turret", "wisp", "wraith",
        "zombie", "troll", "ogre shaman", "naga", "salamander", "explosive bloat",
        "dar blademaster", "dar priestess", "dar battlemage", "acidic jelly",
        "centaur", "underworm", "sentinel", "dart turret", "kraken", "lich",
        "phylactery", "pixie", "phantom"
    };
    private static final String[] SECTIONS = {
        "deathCauses", "kills", "alliesFreed", "alliesLost"
    };

    private PlayerStatsPreview() { }

    static PlayerStats apply(PlayerStats stats, int variant, int difficulty) {
        if (variant != StartMenu.VARIANT_BROGUE
                || difficulty != StartMenu.DIFFICULTY_EASY) return stats;
        try {
            JSONObject preview = stats.toJson();
            for (int section = 0; section < SECTIONS.length; section++) {
                JSONObject tallies = preview.getJSONObject(SECTIONS[section]);
                for (int i = 0; i < MONSTERS.length; i++) {
                    // Deterministic counts/order make repeated timing comparable.
                    int count = i == 0 ? 1 : i == MONSTERS.length - 1 ? 500
                        : 1 + (i * 73 + section * 131) % 500;
                    tallies.put(MONSTERS[i], count);
                }
            }
            return PlayerStats.fromJson(preview);
        } catch (JSONException e) {
            throw new IllegalStateException("Invalid debug stats fixture", e);
        }
    }
}
