package com.pineyellow.broguepe;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Title-screen start menu. Shown by the engine via showStartMenu() and
 *  dismissed when the user chooses a path. Button-row styling is exposed
 *  statically so title-screen modals can reuse the exact visual style. */
final class StartMenu {

    // Constants shared with android-touch.c — must match.
    static final int CHOICE_NEW_GAME  = 0;
    static final int CHOICE_RESUME    = 1;
    static final int CHOICE_PLAY_SEED = 2;

    static final int VARIANT_BROGUE = 0;
    static final int VARIANT_RAPID  = 1;
    static final int VARIANT_BULLET = 2;

    static final int DIFFICULTY_DEFAULT = 0;
    static final int DIFFICULTY_EASY = 1;

    private final BrogueActivity activity;
    private View overlay;

    StartMenu(BrogueActivity activity) {
        this.activity = activity;
    }

    void show(final boolean hasSave, final boolean saveCompatible,
              final int saveVariant, final int saveDifficulty) {
        android.util.Log.d("BrogueModal", "showStartMenu(hasSave=" + hasSave + ")");
        activity.runOnUiThread(() -> {
            dismiss();
            // Returning to the title menu means any prior resume attempt
            // ended without reaching onGameStart.
            activity.nextGameIsResume = false;

            FrameLayout root = new FrameLayout(activity);
            overlay = root;

            LinearLayout panel = new LinearLayout(activity);
            panel.setOrientation(LinearLayout.VERTICAL);

            // Resume is disabled when there is no compatible save.
            boolean canResume = hasSave && saveCompatible;
            addButton(panel, resumeLabel(saveVariant, saveDifficulty), canResume, v -> {
                activity.modalStack.clear();
                dismiss();
                activity.nextGameIsResume = true;
                activity.nativeStartMenuResult(CHOICE_RESUME);
            });

            // New Game opens seed details before starting the run.
            addButton(panel, "New Game", true,
                v -> activity.newGameSeedModal.show());

            addButton(panel, "Personal Stats", true,
                v -> activity.playerStatsModal.show());
            addButton(panel, "Credits", true,
                v -> activity.creditsModal.show());

            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
            int panelWidth = Math.min(activity.dpToPx(280), (int)(screenWidth * 0.32f));
            FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
            panelParams.rightMargin = Math.max(activity.dpToPx(12),
                (int)(screenWidth * 0.04f));
            panelParams.bottomMargin = Math.max(activity.dpToPx(12),
                (int)(screenHeight * 0.04f));
            root.addView(panel, panelParams);

            activity.addContentView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

            panel.setAlpha(0f);
            panel.animate()
                .alpha(1f)
                .setDuration(250)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .start();
        });
    }

    private static String resumeLabel(int saveVariant, int saveDifficulty) {
        String mode = "";
        if (saveVariant == VARIANT_RAPID) {
            mode = "Rapid";
        } else if (saveVariant == VARIANT_BULLET) {
            mode = "Bullet";
        }
        if (saveDifficulty == DIFFICULTY_EASY) {
            mode += mode.isEmpty() ? "Easy" : "/Easy";
        }
        return mode.isEmpty() ? "Resume Game" : "Resume Game (" + mode + ")";
    }

    void dismiss() {
        if (overlay != null && overlay.getParent() != null) {
            ((ViewGroup) overlay.getParent()).removeView(overlay);
        }
        overlay = null;
    }

    boolean isShowing() {
        return overlay != null && overlay.getParent() != null;
    }

    /** Adds a row-styled button to a start-menu or submodal panel. Returns the
     *  row view so callers can flip enabled state later via
     *  {@link #setButtonEnabled}. */
    static View addButton(LinearLayout panel, String label, boolean enabled,
                          View.OnClickListener listener) {
        BrogueActivity activity = (BrogueActivity) panel.getContext();
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(activity.dpToPx(16), activity.dpToPx(12),
                       activity.dpToPx(16), activity.dpToPx(12));
        row.setMinimumHeight(activity.dpToPx(48));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(activity.dpToPx(6));
        bg.setColor(enabled ? Palette.ITEM_BG : Palette.DISABLED_BG);
        if (enabled) {
            row.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Palette.RIPPLE_GLOW), bg, null));
        } else {
            row.setBackground(bg);
        }

        TextView labelView = new TextView(activity);
        labelView.setText(label);
        labelView.setTextColor(enabled ? Palette.GHOST_WHITE : Palette.DISABLED_TEXT);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        labelView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        labelView.setGravity(Gravity.CENTER);
        row.addView(labelView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        row.setEnabled(enabled);
        row.setClickable(enabled);
        if (enabled) row.setOnClickListener(listener);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, activity.dpToPx(3), 0, activity.dpToPx(3));
        panel.addView(row, p);
        return row;
    }

    /** Re-applies enabled/disabled styling to a button produced by
     *  {@link #addButton}. The button must have been built with the same
     *  structure (LinearLayout row with a single TextView child). */
    static void setButtonEnabled(View row, boolean enabled, View.OnClickListener listener) {
        BrogueActivity activity = (BrogueActivity) row.getContext();
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(activity.dpToPx(6));
        bg.setColor(enabled ? Palette.ITEM_BG : Palette.DISABLED_BG);
        if (enabled) {
            row.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Palette.RIPPLE_GLOW), bg, null));
        } else {
            row.setBackground(bg);
        }
        if (row instanceof LinearLayout) {
            LinearLayout ll = (LinearLayout) row;
            View first = ll.getChildCount() > 0 ? ll.getChildAt(0) : null;
            if (first instanceof TextView) {
                ((TextView) first).setTextColor(enabled ? Palette.GHOST_WHITE : Palette.DISABLED_TEXT);
            }
        }
        row.setEnabled(enabled);
        row.setClickable(enabled);
        row.setOnClickListener(enabled ? listener : null);
    }
}
