package com.pineyellow.broguepe;

import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Offline seed preview shared by new-game and recent-seed flows. */
class SeedDetailsModal {

    static final long TWEEN_MS = 400;

    protected final BrogueActivity activity;
    protected long seed;
    protected TextView headerLabelView;

    SeedDetailsModal(BrogueActivity activity) {
        this.activity = activity;
    }

    protected String getTitleUpper() { return "SEED"; }

    protected String getHeaderLabel() { return String.valueOf(seed); }

    protected void onSeedViewBuilt(TextView seedView) {}

    void show(long seed) {
        this.seed = seed;
        activity.modalStack.push(this::buildOverlay);
    }

    protected final View buildOverlay() {
        FrameLayout root = new FrameLayout(activity);
        LinearLayout panel = ModalChrome.buildPanel(activity, root, getTitleUpper());

        headerLabelView = makeHeaderLabelView();
        panel.addView(headerLabelView);
        onSeedViewBuilt(headerLabelView);

        StartMenu.addButton(panel, "Play", true, v -> launchRun());
        StartMenu.addButton(panel, "Back", true, v -> activity.modalStack.pop());

        ModalChrome.present(activity, root, panel);
        return root;
    }

    private TextView makeHeaderLabelView() {
        TextView view = new TextView(activity);
        view.setText(getHeaderLabel());
        view.setTextColor(Palette.GHOST_WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, activity.dpToPx(12), 0, activity.dpToPx(16));
        return view;
    }

    private void launchRun() {
        if (seed <= 0) return;
        activity.modalStack.clear();
        activity.startMenu.dismiss();
        activity.nativeDeleteSaveFile();
        activity.nativeStartMenuResultWithSeed(StartMenu.CHOICE_PLAY_SEED, seed);
    }
}
