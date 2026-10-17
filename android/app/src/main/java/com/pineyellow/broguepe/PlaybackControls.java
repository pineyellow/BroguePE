package com.pineyellow.broguepe;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The controls shown instead of the game's own while a Recent Games
 *  recording plays. A bar at the bottom right pauses, moves between depths,
 *  steps one turn and changes the speed, and opens a menu for the pack, the
 *  discoveries, the message log and the whole map. Exit sits at the top
 *  right, where the game menu button is. Each control sends a key the
 *  engine's playback already handles (executePlaybackInput in Recordings.c),
 *  except the speed, which is set directly. */
final class PlaybackControls {

    private static final char TIMES = (char) 0x00D7;
    // Delays per turn in milliseconds; 50 is the engine's default.
    private static final int[] SPEED_DELAYS = { 50, 25, 12, 6, 100 };
    private static final String[] SPEED_LABELS = {
        "1" + TIMES, "2" + TIMES, "4" + TIMES, "8" + TIMES, "0.5" + TIMES };
    private static final int BUTTON_HALF_GAP_DP = 2;
    private static final int BAR_PADDING_DP = 2;
    private static final float DISABLED_BUTTON_ALPHA = 0.35f;

    private final BrogueActivity activity;
    private FrameLayout root;
    private LinearLayout menu;
    private View menuBackdrop;
    private ImageButton playPause;
    private ImageButton step;
    private TextView speedChip;
    private TextView wholeMapItem;
    private int speedIndex;
    private boolean paused;
    private boolean wholeMap;
    // A recording is being watched. The controls can still be hidden while
    // it loads another depth.
    private boolean active;
    private boolean loading;

    PlaybackControls(BrogueActivity activity) {
        this.activity = activity;
    }

    /** The layer the controls live in, empty and hidden until show(). */
    View build() {
        root = new FrameLayout(activity);
        root.setVisibility(View.GONE);
        return root;
    }

    /** A recording has started: playing, at normal speed, as the player
     *  sees it. Built afresh so the action button settings apply. */
    void show() {
        speedIndex = 0;
        paused = false;
        wholeMap = false;
        active = true;
        loading = false;
        populate();
        root.setVisibility(View.VISIBLE);
    }

    void hide() {
        active = false;
        loading = false;
        if (root == null) return;
        collapseMenu();
        root.setVisibility(View.GONE);
    }

    /** True while a recording is being watched, including while it loads. */
    boolean isShowing() {
        return active;
    }

    /** Hidden while the recording loads another depth, as nothing they do
     *  can act until it's there. */
    void setLoading(boolean loading) {
        if (!active || root == null) return;
        this.loading = loading;
        collapseMenu();
        root.setVisibility(loading ? View.GONE : View.VISIBLE);
    }

    /** Back closes the menu first. Returns false when it isn't open. While
     *  loading, Back does nothing. */
    boolean handleBack() {
        if (loading) return true;
        if (menu != null && menu.getVisibility() == View.VISIBLE) {
            collapseMenu();
            return true;
        }
        return false;
    }

    /** The engine paused the recording or played it again. */
    void setPaused(boolean paused) {
        this.paused = paused;
        if (playPause == null) return;
        playPause.setImageResource(paused ? R.drawable.ic_play : R.drawable.ic_pause);
        playPause.setContentDescription(paused ? "Play" : "Pause");
        // Kept as a touch target while disabled, so taps can't reach the game.
        step.setEnabled(paused);
        step.setAlpha(paused ? buttonOpacity() : DISABLED_BUTTON_ALPHA * buttonOpacity());
    }

    /** Leaves the recording. Escape first answers a waiting "tap to
     *  continue", or pauses; Q then asks, and ends the playback on Yes. */
    void exit() {
        collapseMenu();
        KeyInput.sendKey(activity, KeyEvent.KEYCODE_ESCAPE);
        KeyInput.sendChar(activity, 'Q');
    }

    private void populate() {
        root.removeAllViews();
        float scale = buttonSizeScale();
        int size = dp(ActionsToolbar.BASE_BUTTON_SIZE_DP * scale);

        // Taps outside the open menu close it without reaching the game.
        menuBackdrop = new View(activity);
        menuBackdrop.setVisibility(View.GONE);
        menuBackdrop.setOnClickListener(v -> collapseMenu());
        root.addView(menuBackdrop, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        int barPad = dp(BAR_PADDING_DP);
        bar.setPadding(barPad, barPad, barPad, barPad);
        // Taps between the buttons don't reach the game either.
        bar.setOnClickListener(v -> collapseMenu());
        bar.setSoundEffectsEnabled(false);
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        addToBar(bar, makeIconButton(R.drawable.ic_skip_previous, "Previous depth",
            scale, v -> sendChar('<')), size);
        playPause = makeIconButton(R.drawable.ic_pause, "Pause", scale, v -> sendChar(' '));
        addToBar(bar, playPause, size);
        addToBar(bar, makeIconButton(R.drawable.ic_skip_next, "Next depth",
            scale, v -> sendChar('>')), size);
        step = makeIconButton(R.drawable.ic_step, "Next turn", scale, v -> sendChar('l'));
        addToBar(bar, step, size);
        speedChip = makeSpeedChip(scale);
        addToBar(bar, speedChip, size);
        addToBar(bar, makeIconButton(R.drawable.ic_more, "More", scale, v -> toggleMenu()), size);

        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM | Gravity.END);
        barParams.setMargins(0, 0, dp(4), 0);
        root.addView(bar, barParams);

        menu = new LinearLayout(activity);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackground(makeMenuBackground());
        menu.setPadding(dp(3), dp(4), dp(3), dp(3));
        menu.setVisibility(View.GONE);
        menu.setElevation(dp(8));
        menu.addView(makeMenuItem("Inventory", v -> sendChar('i')), menuItemParams());
        menu.addView(makeMenuItem("Discoveries", v -> sendChar('D')), menuItemParams());
        menu.addView(makeMenuItem("Message Log", v -> sendChar('M')), menuItemParams());
        wholeMapItem = makeMenuItem(wholeMapLabel(), v -> toggleWholeMap());
        menu.addView(wholeMapItem, menuItemParams());
        // Just above the bar, lined up with its right end.
        FrameLayout.LayoutParams menuParams = new FrameLayout.LayoutParams(
            dp(170), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        int buttonInset = dp(BAR_PADDING_DP + BUTTON_HALF_GAP_DP);
        menuParams.setMargins(0, 0, dp(4) + buttonInset, size + 2 * buttonInset + dp(6));
        root.addView(menu, menuParams);

        ImageButton exitButton = makeIconButton(R.drawable.ic_close, "Stop watching",
            scale, v -> exit());
        FrameLayout.LayoutParams exitParams = new FrameLayout.LayoutParams(
            size, size, Gravity.TOP | Gravity.END);
        exitParams.setMargins(0, dp(8), dp(8), 0);
        root.addView(exitButton, exitParams);

        setPaused(paused);
    }

    private void addToBar(LinearLayout bar, View button, int size) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        int gap = dp(BUTTON_HALF_GAP_DP);
        params.setMargins(gap, gap, gap, gap);
        bar.addView(button, params);
    }

    private void sendChar(char c) {
        collapseMenu();
        KeyInput.sendChar(activity, c);
    }

    private void nextSpeed() {
        collapseMenu();
        speedIndex = (speedIndex + 1) % SPEED_DELAYS.length;
        speedChip.setText(SPEED_LABELS[speedIndex]);
        speedChip.setContentDescription("Speed " + SPEED_LABELS[speedIndex]);
        activity.nativeSetPlaybackDelay(SPEED_DELAYS[speedIndex]);
    }

    private void toggleWholeMap() {
        collapseMenu();
        KeyInput.sendKey(activity, KeyEvent.KEYCODE_TAB);
        wholeMap = !wholeMap;
        wholeMapItem.setText(wholeMapLabel());
    }

    private String wholeMapLabel() {
        return wholeMap ? "Hide Whole Map" : "Show Whole Map";
    }

    private void toggleMenu() {
        if (menu.getVisibility() == View.VISIBLE) {
            collapseMenu();
            return;
        }
        menuBackdrop.setVisibility(View.VISIBLE);
        menu.animate().cancel();
        menu.setAlpha(0f);
        menu.setTranslationY(dp(8));
        menu.setVisibility(View.VISIBLE);
        menu.animate()
            .alpha(1f).translationY(0)
            .setDuration(150)
            .setInterpolator(new DecelerateInterpolator())
            .start();
    }

    private void collapseMenu() {
        if (menu == null || menu.getVisibility() != View.VISIBLE) return;
        menuBackdrop.setVisibility(View.GONE);
        menu.animate().cancel();
        menu.setVisibility(View.GONE);
    }

    // ---- View factories, styled like ActionsToolbar's ------------------------

    @SuppressLint("ClickableViewAccessibility")
    private ImageButton makeIconButton(int drawableResId, String label, float scale,
                                       View.OnClickListener listener) {
        ImageButton button = new ImageButton(activity);
        button.setImageResource(drawableResId);
        button.setColorFilter(Palette.ACTION_BUTTON_TEXT);
        button.setScaleType(ImageButton.ScaleType.FIT_CENTER);
        int padding = dp(ActionsToolbar.BASE_BUTTON_PADDING_DP * scale);
        button.setPadding(padding, padding, padding, padding);
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setBackground(makeButtonBackground());
        button.setAlpha(buttonOpacity());
        button.setContentDescription(label);
        button.setOnTouchListener(PlaybackControls::pressAnimation);
        button.setOnClickListener(listener);
        return button;
    }

    @SuppressLint("ClickableViewAccessibility")
    private TextView makeSpeedChip(float scale) {
        TextView chip = new TextView(activity);
        chip.setText(SPEED_LABELS[speedIndex]);
        chip.setContentDescription("Speed " + SPEED_LABELS[speedIndex]);
        chip.setTextColor(iconColor());
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14 * scale);
        chip.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setBackground(makeButtonBackground());
        chip.setAlpha(buttonOpacity());
        chip.setClickable(true);
        chip.setOnTouchListener(PlaybackControls::pressAnimation);
        chip.setOnClickListener(v -> nextSpeed());
        return chip;
    }

    /** The colour the icons show in: white under the half-transparent
     *  ACTION_BUTTON_TEXT filter. The speed label matches it. */
    private static int iconColor() {
        int filter = Palette.ACTION_BUTTON_TEXT;
        float a = Color.alpha(filter) / 255f;
        return Color.rgb(
            Math.round(Color.red(filter) * a + 255 * (1 - a)),
            Math.round(Color.green(filter) * a + 255 * (1 - a)),
            Math.round(Color.blue(filter) * a + 255 * (1 - a)));
    }

    private static boolean pressAnimation(View v, MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(60).start();
        } else if (e.getAction() == MotionEvent.ACTION_UP
                || e.getAction() == MotionEvent.ACTION_CANCEL) {
            v.animate().scaleX(1f).scaleY(1f).setDuration(120)
                .setInterpolator(new OvershootInterpolator(2.5f)).start();
        }
        return false;
    }

    private RippleDrawable makeButtonBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(UiStyle.MENU_ITEM_CORNER_RADIUS_DP));
        bg.setColor(Palette.ACTION_BUTTON_BG);
        bg.setStroke(1, Palette.BORDER_DIM);
        return new RippleDrawable(ColorStateList.valueOf(Palette.RIPPLE_GLOW), bg, null);
    }

    private TextView makeMenuItem(String label, View.OnClickListener listener) {
        TextView item = new TextView(activity);
        item.setText(label);
        item.setTextColor(Palette.GHOST_WHITE);
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        item.setTypeface(Typeface.MONOSPACE);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(10), dp(6), dp(10), dp(6));
        item.setMinimumHeight(dp(36));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(UiStyle.MENU_ITEM_CORNER_RADIUS_DP));
        bg.setColor(Palette.ITEM_BG);
        bg.setStroke(1, Palette.BORDER_DIM);
        item.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.RIPPLE_GLOW), bg, null));
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(listener);
        return item;
    }

    private LinearLayout.LayoutParams menuItemParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(3), dp(2), dp(3), dp(2));
        return params;
    }

    private GradientDrawable makeMenuBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(UiStyle.PANEL_CORNER_RADIUS_DP));
        bg.setColor(Palette.SUBMENU_BG);
        bg.setStroke(1, Palette.BORDER_DIM);
        return bg;
    }

    // The action buttons' size and opacity settings apply here too.
    private float buttonSizeScale() {
        float saved = GameSettings.getFloat(activity, ActionsToolbar.PREF_BUTTON_SIZE,
            ActionsToolbar.DEFAULT_BUTTON_SIZE);
        return Float.isFinite(saved)
            ? Math.max(ActionsToolbar.MIN_BUTTON_SIZE,
                       Math.min(ActionsToolbar.MAX_BUTTON_SIZE, saved))
            : ActionsToolbar.DEFAULT_BUTTON_SIZE;
    }

    private float buttonOpacity() {
        float saved = GameSettings.getFloat(activity, ActionsToolbar.PREF_BUTTON_OPACITY,
            ActionsToolbar.DEFAULT_BUTTON_OPACITY);
        return Float.isFinite(saved)
            ? Math.max(ActionsToolbar.MIN_BUTTON_OPACITY,
                       Math.min(ActionsToolbar.MAX_BUTTON_OPACITY, saved))
            : ActionsToolbar.DEFAULT_BUTTON_OPACITY;
    }

    private int dp(int v) { return activity.dpToPx(v); }
    private int dp(float v) { return activity.dpToPx(v); }
}
