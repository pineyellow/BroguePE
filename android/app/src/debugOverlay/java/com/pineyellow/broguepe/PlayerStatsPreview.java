package com.pineyellow.broguepe;

import org.json.JSONException;
import org.json.JSONObject;

/** Display-only stress data. Included only in debug builds with -PdebugOverlay=true.
 * Never publishes to StatsStore or writes files. Names match the monster catalog. */
final class PlayerStatsPreview {
    // Every monster in Brogue's monsterCatalog (Globals.c), in catalog order.
    private static final String[] MONSTERS = {
        "rat", "kobold", "jackal", "eel", "monkey", "bloat", "pit bloat",
        "goblin", "goblin conjurer", "goblin mystic", "goblin totem", "pink jelly",
        "toad", "vampire bat", "arrow turret", "acid mound", "centipede", "ogre",
        "bog monster", "ogre totem", "spider", "spark turret", "wisp", "wraith",
        "zombie", "troll", "ogre shaman", "naga", "salamander", "explosive bloat",
        "dar blademaster", "dar priestess", "dar battlemage", "acidic jelly",
        "centaur", "underworm", "sentinel", "dart turret", "kraken", "lich",
        "phylactery", "pixie", "phantom", "flame turret", "imp", "fury",
        "revenant", "tentacle horror", "golem", "dragon", "goblin warlord",
        "black jelly", "vampire", "flamedancer", "spectral blade", "spectral sword",
        "stone guardian", "winged guardian", "guardian spirit", "Warden of Yendor",
        "eldritch totem", "mirrored totem", "unicorn", "ifrit", "phoenix",
        "phoenix egg", "mangrove dryad"
    };
    // Deaths the game words as a sentence, exactly as gameOver() gets them. Both lava and both
    // reflected bolts are here to show their pills merging. The last one isn't in the table in
    // android-stats.c, to show a sentence from a newer version.
    private static final String[] DEATH_SENTENCES = {
        "Killed by a cloud of caustic gas", "Killed by a cloud of scalding steam",
        "Killed by lava", "Killed by cooling lava", "Killed by a sacrificial pit",
        "Killed by a violent explosion", "Killed by thorned vines", "Burned to death",
        "Died from poison", "Starved to death", "Drained by a cursed ring",
        "Killed by a reflected lightning", "Killed by a reflected flame", "Killed by a fall",
        "Killed by a falling boulder"
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
                if (SECTIONS[section].equals("deathCauses")) {
                    for (int i = 0; i < DEATH_SENTENCES.length; i++) {
                        tallies.put(DEATH_SENTENCES[i], 1 + (i * 97 + 41) % 500);
                    }
                }
            }
            return PlayerStats.fromJson(preview);
        } catch (JSONException e) {
            throw new IllegalStateException("Invalid debug stats fixture", e);
        }
    }
}
