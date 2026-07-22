package com.pineyellow.broguepe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Versioned collection of one stats bucket per variant and difficulty. */
final class PlayerStatsArchive {

    static final int SCHEMA_VERSION = 2;
    private static final int VARIANT_COUNT = 3;
    private static final int DIFFICULTY_COUNT = 2;

    private final PlayerStats[][] buckets;

    private PlayerStatsArchive(PlayerStats[][] buckets) {
        this.buckets = buckets;
    }

    static PlayerStatsArchive empty() {
        PlayerStats[][] buckets = new PlayerStats[VARIANT_COUNT][DIFFICULTY_COUNT];
        for (int variant = 0; variant < VARIANT_COUNT; variant++) {
            for (int difficulty = 0; difficulty < DIFFICULTY_COUNT; difficulty++) {
                buckets[variant][difficulty] = PlayerStats.empty();
            }
        }
        return new PlayerStatsArchive(buckets);
    }

    PlayerStats bucket(int variant, int difficulty) {
        return buckets[normalizeVariant(variant)][normalizeDifficulty(difficulty)];
    }

    PlayerStatsArchive withBucket(int variant, int difficulty, PlayerStats bucket) {
        int safeVariant = normalizeVariant(variant);
        int safeDifficulty = normalizeDifficulty(difficulty);
        PlayerStats[][] next = buckets.clone();
        next[safeVariant] = buckets[safeVariant].clone();
        next[safeVariant][safeDifficulty] = bucket == null ? PlayerStats.empty() : bucket;
        return new PlayerStatsArchive(next);
    }

    PlayerStatsArchive withGameStart(int variant, int difficulty) {
        PlayerStats current = bucket(variant, difficulty);
        return withBucket(variant, difficulty, current.withGameStart());
    }

    PlayerStatsArchive withRunStarted(long seed, int variant, int difficulty,
                                      boolean isResume) {
        if (isResume) return this;
        return withGameStart(variant, difficulty)
            .withSeedPlayed(seed, variant, difficulty);
    }

    PlayerStatsArchive withSeedPlayed(long seed, int variant, int difficulty) {
        PlayerStats current = bucket(variant, difficulty);
        return withBucket(variant, difficulty,
            current.withSeedPlayed(seed, variant, difficulty));
    }

    JSONObject toJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", SCHEMA_VERSION);
        JSONArray entries = new JSONArray();
        for (int variant = 0; variant < VARIANT_COUNT; variant++) {
            for (int difficulty = 0; difficulty < DIFFICULTY_COUNT; difficulty++) {
                JSONObject entry = new JSONObject();
                entry.put("variant", variant);
                entry.put("difficulty", difficulty);
                entry.put("stats", buckets[variant][difficulty].toJson());
                entries.put(entry);
            }
        }
        root.put("buckets", entries);
        return root;
    }

    static PlayerStatsArchive fromJson(JSONObject root) {
        JSONArray entries = root.optJSONArray("buckets");
        if (entries == null) return migrateLegacy(root);

        PlayerStatsArchive archive = empty();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            JSONObject stats = entry.optJSONObject("stats");
            if (stats == null) continue;
            int variant = normalizeVariant(
                entry.optInt("variant", StartMenu.VARIANT_BROGUE));
            int difficulty = normalizeDifficulty(
                entry.optInt("difficulty", StartMenu.DIFFICULTY_DEFAULT));
            PlayerStats parsed = PlayerStats.fromJson(stats);
            PlayerStats normalized = parsed.withoutRecentSeeds();
            for (int seedIndex = parsed.recentSeeds.size() - 1;
                 seedIndex >= 0; seedIndex--) {
                PlayerStats.RecentSeed recent = parsed.recentSeeds.get(seedIndex);
                normalized = normalized.withSeedPlayed(
                    recent.seed, variant, difficulty);
            }
            archive = archive.withBucket(variant, difficulty, normalized);
        }
        return archive;
    }

    private static PlayerStatsArchive migrateLegacy(JSONObject root) {
        PlayerStats legacy = PlayerStats.fromJson(root);
        PlayerStatsArchive archive = empty().withBucket(
            StartMenu.VARIANT_BROGUE,
            StartMenu.DIFFICULTY_DEFAULT,
            legacy.withoutRecentSeeds());

        // Aggregate legacy statistics had no mode metadata, but newer recent
        // seed entries did. Preserve metadata where it actually exists.
        for (int i = legacy.recentSeeds.size() - 1; i >= 0; i--) {
            PlayerStats.RecentSeed recent = legacy.recentSeeds.get(i);
            archive = archive.withSeedPlayed(
                recent.seed, recent.variant, recent.difficulty);
        }
        return archive;
    }

    static int normalizeVariant(int variant) {
        return variant >= StartMenu.VARIANT_BROGUE
            && variant <= StartMenu.VARIANT_BULLET
            ? variant
            : StartMenu.VARIANT_BROGUE;
    }

    static int normalizeDifficulty(int difficulty) {
        return difficulty == StartMenu.DIFFICULTY_EASY
            ? StartMenu.DIFFICULTY_EASY
            : StartMenu.DIFFICULTY_DEFAULT;
    }
}
