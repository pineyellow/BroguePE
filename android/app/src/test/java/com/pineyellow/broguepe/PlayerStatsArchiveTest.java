package com.pineyellow.broguepe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class PlayerStatsArchiveTest {

    @Test
    public void bucketsKeepEveryStatisticIndependent() {
        PlayerStatsArchive archive = PlayerStatsArchive.empty();
        PlayerStats rapidEasy = archive.bucket(
            StartMenu.VARIANT_RAPID, StartMenu.DIFFICULTY_EASY)
            .withGameStart()
            .withMonsterKilled("dragon")
            .withAllyFreed("goblin")
            .withAllyDied("goblin")
            .withPlayerDied("dragon", 8, 1234, 1500)
            .withPlayerWon(true, 10, 900, 1200);
        archive = archive.withBucket(
            StartMenu.VARIANT_RAPID, StartMenu.DIFFICULTY_EASY, rapidEasy);

        PlayerStats selected = archive.bucket(
            StartMenu.VARIANT_RAPID, StartMenu.DIFFICULTY_EASY);
        assertEquals(1, selected.gamesPlayed);
        assertEquals(1, selected.wins);
        assertEquals(1, selected.masteryWins);
        assertEquals(1, selected.deaths);
        assertEquals(10, selected.deepestDepth);
        assertEquals(1234, selected.longestRunTurns);
        assertEquals(900, selected.fastestWinTurns);
        assertEquals(1500L, selected.mostGoldCollected);
        assertEquals(8, selected.deadliestDepth());
        assertEquals(1, selected.kills.get(0).count);
        assertEquals(1, selected.alliesFreed.get(0).count);
        assertEquals(1, selected.alliesLost.get(0).count);
        assertEquals(1, selected.deathCauses.get(0).count);

        assertBucketEmpty(archive.bucket(
            StartMenu.VARIANT_RAPID, StartMenu.DIFFICULTY_DEFAULT));
        assertBucketEmpty(archive.bucket(
            StartMenu.VARIANT_BROGUE, StartMenu.DIFFICULTY_EASY));
    }

    @Test
    public void roundTripPreservesAllSixBuckets() throws Exception {
        PlayerStatsArchive archive = PlayerStatsArchive.empty();
        for (int variant = StartMenu.VARIANT_BROGUE;
             variant <= StartMenu.VARIANT_BULLET; variant++) {
            for (int difficulty = StartMenu.DIFFICULTY_DEFAULT;
                 difficulty <= StartMenu.DIFFICULTY_EASY; difficulty++) {
                long seed = 1000L + variant * 10L + difficulty;
                archive = archive.withRunStarted(seed, variant, difficulty, false);
                PlayerStats stats = archive.bucket(variant, difficulty)
                    .withMonsterKilled("mode-" + variant + "-" + difficulty)
                    .withPlayerQuit(variant + 1, difficulty + 10, seed);
                archive = archive.withBucket(variant, difficulty, stats);
            }
        }

        JSONObject json = archive.toJson();
        assertEquals(PlayerStatsArchive.SCHEMA_VERSION, json.getInt("schemaVersion"));
        PlayerStatsArchive restored = PlayerStatsArchive.fromJson(
            new JSONObject(json.toString()));

        for (int variant = StartMenu.VARIANT_BROGUE;
             variant <= StartMenu.VARIANT_BULLET; variant++) {
            for (int difficulty = StartMenu.DIFFICULTY_DEFAULT;
                 difficulty <= StartMenu.DIFFICULTY_EASY; difficulty++) {
                PlayerStats stats = restored.bucket(variant, difficulty);
                assertEquals(1, stats.gamesPlayed);
                assertEquals(1, stats.kills.size());
                assertEquals(1000L + variant * 10L + difficulty,
                    stats.mostGoldCollected);
                assertEquals(1000L + variant * 10L + difficulty,
                    stats.recentSeeds.get(0).seed);
            }
        }
    }

    @Test
    public void legacyAggregateMovesToClassicDefaultAndTaggedSeedsKeepMode()
            throws Exception {
        PlayerStats legacy = PlayerStats.empty()
            .withGameStart()
            .withMonsterKilled("rat")
            .withPlayerDied("rat", 3, 200, 75);
        JSONObject json = legacy.toJson();
        json.remove("mostGoldCollected");
        JSONArray seeds = new JSONArray();
        seeds.put(111L);
        seeds.put(new JSONObject()
            .put("seed", 222L)
            .put("variant", StartMenu.VARIANT_RAPID)
            .put("difficulty", StartMenu.DIFFICULTY_EASY));
        json.put("recentSeeds", seeds);

        PlayerStatsArchive migrated = PlayerStatsArchive.fromJson(json);
        PlayerStats classic = migrated.bucket(
            StartMenu.VARIANT_BROGUE, StartMenu.DIFFICULTY_DEFAULT);
        assertEquals(1, classic.gamesPlayed);
        assertEquals(1, classic.deaths);
        assertEquals(0L, classic.mostGoldCollected);
        assertEquals("rat", classic.kills.get(0).label);
        assertEquals(111L, classic.recentSeeds.get(0).seed);

        PlayerStats rapidEasy = migrated.bucket(
            StartMenu.VARIANT_RAPID, StartMenu.DIFFICULTY_EASY);
        assertBucketEmptyExceptSeeds(rapidEasy);
        assertEquals(222L, rapidEasy.recentSeeds.get(0).seed);
    }

    @Test
    public void recentSeedsAreCappedAndDeduplicatedPerBucket() {
        PlayerStatsArchive archive = PlayerStatsArchive.empty();
        for (long seed = 1; seed <= 12; seed++) {
            archive = archive.withSeedPlayed(seed,
                StartMenu.VARIANT_BULLET, StartMenu.DIFFICULTY_EASY);
        }
        archive = archive.withSeedPlayed(5,
            StartMenu.VARIANT_BULLET, StartMenu.DIFFICULTY_EASY);
        archive = archive.withSeedPlayed(5,
            StartMenu.VARIANT_BROGUE, StartMenu.DIFFICULTY_DEFAULT);

        PlayerStats bulletEasy = archive.bucket(
            StartMenu.VARIANT_BULLET, StartMenu.DIFFICULTY_EASY);
        assertEquals(PlayerStats.RECENT_SEEDS_MAX, bulletEasy.recentSeeds.size());
        assertEquals(5L, bulletEasy.recentSeeds.get(0).seed);
        assertEquals(1, countSeed(bulletEasy, 5L));

        PlayerStats classic = archive.bucket(
            StartMenu.VARIANT_BROGUE, StartMenu.DIFFICULTY_DEFAULT);
        assertEquals(1, classic.recentSeeds.size());
        assertEquals(5L, classic.recentSeeds.get(0).seed);
    }

    @Test
    public void invalidMetadataFallsBackToClassicDefault() throws Exception {
        PlayerStatsArchive archive = PlayerStatsArchive.empty()
            .withRunStarted(77L, 99, -4, false);

        PlayerStats classic = archive.bucket(
            StartMenu.VARIANT_BROGUE, StartMenu.DIFFICULTY_DEFAULT);
        assertEquals(1, classic.gamesPlayed);
        assertEquals(77L, classic.recentSeeds.get(0).seed);
        assertEquals(StartMenu.VARIANT_BROGUE,
            classic.recentSeeds.get(0).variant);
        assertEquals(StartMenu.DIFFICULTY_DEFAULT,
            classic.recentSeeds.get(0).difficulty);

        JSONObject malformedEntry = new JSONObject()
            .put("variant", 99)
            .put("difficulty", -4)
            .put("stats", PlayerStats.empty()
                .withGameStart()
                .withSeedPlayed(88L, StartMenu.VARIANT_BULLET,
                    StartMenu.DIFFICULTY_EASY)
                .toJson());
        JSONObject malformedArchive = new JSONObject()
            .put("schemaVersion", PlayerStatsArchive.SCHEMA_VERSION)
            .put("buckets", new JSONArray().put(malformedEntry));
        PlayerStatsArchive restored = PlayerStatsArchive.fromJson(malformedArchive);
        PlayerStats restoredClassic = restored.bucket(
            StartMenu.VARIANT_BROGUE,
            StartMenu.DIFFICULTY_DEFAULT);
        assertEquals(1, restoredClassic.gamesPlayed);
        assertEquals(StartMenu.VARIANT_BROGUE,
            restoredClassic.recentSeeds.get(0).variant);
        assertEquals(StartMenu.DIFFICULTY_DEFAULT,
            restoredClassic.recentSeeds.get(0).difficulty);
    }

    @Test
    public void resumedRunDoesNotCountAnotherGameOrSeed() {
        PlayerStatsArchive archive = PlayerStatsArchive.empty()
            .withRunStarted(42L, StartMenu.VARIANT_RAPID,
                StartMenu.DIFFICULTY_EASY, false);
        PlayerStatsArchive resumed = archive.withRunStarted(
            42L, StartMenu.VARIANT_RAPID, StartMenu.DIFFICULTY_EASY, true);

        PlayerStats stats = resumed.bucket(
            StartMenu.VARIANT_RAPID, StartMenu.DIFFICULTY_EASY);
        assertEquals(1, stats.gamesPlayed);
        assertEquals(1, stats.recentSeeds.size());
        assertEquals(42L, stats.recentSeeds.get(0).seed);
    }

    @Test
    public void quitUpdatesRunRecordsWithoutCountingADeathOrWin() {
        PlayerStats stats = PlayerStats.empty()
            .withGameStart()
            .withPlayerQuit(12, 3456, 789);

        assertEquals(1, stats.gamesPlayed);
        assertEquals(0, stats.wins);
        assertEquals(0, stats.deaths);
        assertEquals(12, stats.deepestDepth);
        assertEquals(3456, stats.longestRunTurns);
        assertEquals(0, stats.fastestWinTurns);
        assertEquals(789L, stats.mostGoldCollected);
        assertEquals(0, stats.deadliestDepth());
    }

    private static int countSeed(PlayerStats stats, long seed) {
        int count = 0;
        for (PlayerStats.RecentSeed recent : stats.recentSeeds) {
            if (recent.seed == seed) count++;
        }
        return count;
    }

    private static void assertBucketEmpty(PlayerStats stats) {
        assertBucketEmptyExceptSeeds(stats);
        assertTrue(stats.recentSeeds.isEmpty());
    }

    private static void assertBucketEmptyExceptSeeds(PlayerStats stats) {
        assertEquals(0, stats.gamesPlayed);
        assertEquals(0, stats.wins);
        assertEquals(0, stats.masteryWins);
        assertEquals(0, stats.deaths);
        assertEquals(0, stats.deepestDepth);
        assertEquals(0, stats.longestRunTurns);
        assertEquals(0, stats.fastestWinTurns);
        assertEquals(0L, stats.mostGoldCollected);
        assertTrue(stats.kills.isEmpty());
        assertTrue(stats.deathCauses.isEmpty());
        assertTrue(stats.alliesFreed.isEmpty());
        assertTrue(stats.alliesLost.isEmpty());
    }
}
