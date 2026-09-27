package com.pineyellow.broguepe;

/** Production and ordinary debug builds always display the real snapshot. */
final class PlayerStatsPreview {
    private PlayerStatsPreview() { }

    static PlayerStats apply(PlayerStats stats, int variant, int difficulty) {
        return stats;
    }
}
