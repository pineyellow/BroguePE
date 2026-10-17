package com.pineyellow.broguepe;

import android.graphics.Color;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.json.JSONObject;

import java.io.File;

/** A past run from the Recent Games list, in the death screen's panel over
 *  Personal Stats. Back or a tap outside the panel goes back to Personal
 *  Stats; Watch plays the run's recording; Play Again opens the seed screen
 *  with the run's seed and mode. */
final class RecentGameModal {

    private final BrogueActivity activity;

    RecentGameModal(BrogueActivity activity) {
        this.activity = activity;
    }

    void show(JSONObject game) {
        activity.modalStack.push(() -> build(game));
    }

    private View build(JSONObject game) {
        FrameLayout root = new FrameLayout(activity);
        // Same backdrop as ModalChrome.buildPanel.
        View backdrop = new View(activity);
        backdrop.setBackgroundColor(Color.argb(160, 0, 0, 0));
        backdrop.setOnClickListener(v -> activity.modalStack.pop());
        root.addView(backdrop, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        // Only a recording this version of the game can play back.
        File recording = RecentGamesStore.recordingFile(activity, game);
        View.OnClickListener watch = recording == null || !recording.isFile()
                || !activity.nativeRecordingIsPlayable(recording.getAbsolutePath())
            ? null
            : v -> activity.watchRecording(game, recording);

        Long seed = RecentGames.seed(game);
        View.OnClickListener playAgain = seed == null ? null : v -> {
            // Closes this and Personal Stats, as the old recent seed rows did.
            activity.modalStack.pop();
            activity.modalStack.pop();
            activity.newGameSeedModal.show(seed,
                RecentGames.variant(game), RecentGames.difficulty(game));
        };

        RunSummaryPanel summaryPanel = new RunSummaryPanel(activity);
        LinearLayout panel = summaryPanel.build(game,
            activity.getString(RecentGames.titleRes(game)),
            game.optLong("playtimeMillis", -1L), watch, playAgain);
        // Taps inside the panel must not reach the backdrop.
        panel.setClickable(true);
        root.addView(panel, summaryPanel.panelParams());

        activity.addContentView(root, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        ModalChrome.animateIn(panel);
        return root;
    }
}
