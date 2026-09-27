package com.pineyellow.broguepe;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class PlayerStatsPreviewTest {
    @Test public void onlyClassicEasyCanBeReplaced() {
        PlayerStats real = PlayerStats.empty();
        for (int variant = 0; variant < 3; variant++) {
            for (int difficulty = 0; difficulty < 2; difficulty++) {
                if (variant == StartMenu.VARIANT_BROGUE
                        && difficulty == StartMenu.DIFFICULTY_EASY) continue;
                assertSame(real, PlayerStatsPreview.apply(real, variant, difficulty));
            }
        }
    }

    @Test public void fixtureIsDisplayOnlyAndExcludedWithoutOverlay() throws Exception {
        PlayerStats real = PlayerStats.empty().withMonsterKilled("rat");
        String before = real.toJson().toString();
        PlayerStats preview = PlayerStatsPreview.apply(real,
            StartMenu.VARIANT_BROGUE, StartMenu.DIFFICULTY_EASY);
        assertEquals(before, real.toJson().toString());
        if (!BuildConfig.DEBUG || !BuildConfig.DEBUG_OVERLAY) {
            assertSame(real, preview);
            return;
        }
        assertNotSame(real, preview);
        for (List<PlayerStats.Tally> section : java.util.Arrays.asList(
                preview.kills, preview.deathCauses, preview.alliesFreed, preview.alliesLost)) {
            assertTrue(section.size() >= 40);
            assertEquals(500, section.get(0).count);
            assertEquals(1, section.get(section.size() - 1).count);
            for (PlayerStats.Tally tally : section) {
                assertTrue(tally.count >= 1 && tally.count <= 500);
            }
        }
        assertEquals(real.gamesPlayed, preview.gamesPlayed);
        assertEquals(real.wins, preview.wins);
    }
}
