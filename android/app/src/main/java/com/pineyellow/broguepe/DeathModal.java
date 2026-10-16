package com.pineyellow.broguepe;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

final class DeathModal {

    private enum State {
        INACTIVE,
        FADING_TO_BLACK,
        WAITING_FOR_FLAMES,
        SHOWING,
        DISMISSING,
        WAITING_FOR_TITLE,
        FADING_TO_TITLE,
        DESTROYING
    }

    private static final String NOT_TRACKED = "TBD";
    private static final String MISSING = "—";

    private final BrogueActivity activity;
    private FrameLayout root;
    private View fadeView;
    private View modalView;
    private String summaryJson;
    private State state = State.INACTIVE;

    DeathModal(BrogueActivity activity) {
        this.activity = activity;
    }

    void show(String summaryJson) {
        if (!transition(State.INACTIVE, State.FADING_TO_BLACK)) {
            // A late JNI request can race Activity teardown. Never leave its
            // native caller waiting for UI that can no longer be displayed.
            activity.nativeCancelDeathScreen();
            return;
        }
        activity.runOnUiThread(() -> fadeToBlack(summaryJson));
    }

    void onFlamesReady() {
        activity.runOnUiThread(this::revealAndShowModal);
    }

    /** Returns true when the death sequence owns and consumed Back. */
    boolean handleBackPressed() {
        synchronized (this) {
            if (state == State.INACTIVE) {
                return false;
            }
            if (state != State.SHOWING) {
                // The mandatory fades have no safe partial-dismiss state.
                return true;
            }
            state = State.DISMISSING;
        }
        beginDismissAnimation();
        return true;
    }

    /** Called before SDLActivity.onDestroy() sends quit and joins SDL. */
    void onActivityDestroying() {
        synchronized (this) {
            if (state == State.DESTROYING) {
                return;
            }
            state = State.DESTROYING;
        }

        // This native signal is deliberately independent of animation end
        // actions: the UI thread is about to block while SDL shuts down.
        activity.nativeCancelDeathScreen();

        if (fadeView != null) {
            fadeView.animate().cancel();
        }
        if (modalView != null) {
            modalView.animate().cancel();
        }
        removeOverlay();
    }

    void fadeOutOverlay() {
        synchronized (this) {
            if (state == State.DESTROYING) {
                return;
            }
            if (root != null) {
                state = State.FADING_TO_TITLE;
            }
        }
        if (root == null || fadeView == null) {
            removeOverlay();
            return;
        }
        fadeView.animate().cancel();
        fadeView.setAlpha(1f);
        fadeView.animate()
            .alpha(0f)
            .setDuration(500)
            .setInterpolator(new DecelerateInterpolator(1.5f))
            .withEndAction(this::removeOverlay)
            .start();
    }

    void removeOverlay() {
        if (root != null && root.getParent() != null) {
            ((ViewGroup) root.getParent()).removeView(root);
        }
        root = null;
        fadeView = null;
        modalView = null;
        summaryJson = null;
        synchronized (this) {
            if (state != State.DESTROYING) {
                state = State.INACTIVE;
            }
        }
    }

    private void fadeToBlack(String json) {
        if (!isInState(State.FADING_TO_BLACK)) {
            return;
        }

        root = new FrameLayout(activity);
        root.setClickable(true);
        // Touches never reach root (fadeView covers it); this handles Enter /
        // D-pad centre while root holds focus, and a screen reader's click.
        root.setOnClickListener(v -> requestDismiss());
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        root.setContentDescription(activity.getString(R.string.death_title));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            root.setAccessibilityPaneTitle(activity.getString(R.string.death_title));
            root.setScreenReaderFocusable(true);
        }

        fadeView = new View(activity);
        fadeView.setBackgroundColor(Color.BLACK);
        fadeView.setAlpha(0f);
        // This view remains behind the centered panel and consumes every tap
        // outside it, so input can never fall through to SDL. Once the panel
        // is showing, such a tap dismisses it; earlier taps are ignored.
        fadeView.setClickable(true);
        fadeView.setOnClickListener(v -> requestDismiss());
        fadeView.setImportantForAccessibility(
            View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(fadeView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        activity.addContentView(root, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        root.requestFocus();

        // This field is written and later read only on the UI thread.
        summaryJson = json;
        fadeView.animate()
            .alpha(1f)
            .setDuration(3000)
            .setInterpolator(new DecelerateInterpolator(2f))
            .withEndAction(this::finishFadeToBlack)
            .start();
    }

    private void revealAndShowModal() {
        if (!transition(State.WAITING_FOR_FLAMES, State.SHOWING)) {
            return;
        }
        if (root == null || summaryJson == null) {
            synchronized (this) {
                if (state == State.SHOWING) {
                    state = State.WAITING_FOR_TITLE;
                }
            }
            activity.nativeCancelDeathScreen();
            return;
        }

        if (fadeView != null) {
            fadeView.animate()
                .alpha(0f)
                .setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .start();
        }

        // During the empty fade the root itself is announced. Once controls
        // exist, expose their individual labels instead of grouping the whole
        // modal under the root's temporary description.
        root.setContentDescription(null);
        String json = summaryJson;
        summaryJson = null;
        buildModal(json);
    }

    private void requestDismiss() {
        if (!transition(State.SHOWING, State.DISMISSING)) {
            return;
        }
        beginDismissAnimation();
    }

    private void beginDismissAnimation() {
        // Remove the modal panel, keep the black overlay for transition.
        if (root != null && modalView != null) {
            root.removeView(modalView);
            modalView = null;
        }
        // Fade to black over the red flames.
        if (fadeView != null) {
            fadeView.animate().cancel();
            fadeView.setAlpha(0f);
            fadeView.animate()
                .alpha(1f)
                .setDuration(500)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .withEndAction(this::finishDismissal)
                .start();
        } else {
            finishDismissal();
        }
    }

    private void finishFadeToBlack() {
        if (transition(State.FADING_TO_BLACK, State.WAITING_FOR_FLAMES)) {
            activity.nativeDeathFadeDone();
        }
    }

    private void finishDismissal() {
        if (transition(State.DISMISSING, State.WAITING_FOR_TITLE)) {
            activity.nativeDeathScreenDismissed();
        }
    }

    /** One panel: the run summary on the left, the pack at death on the
     *  right. Each column scrolls on its own. A tap outside the panel, or
     *  Back, dismisses it. */
    private void buildModal(String json) {
        if (root == null) return;

        JSONObject summary;
        try {
            summary = new JSONObject(json);
        } catch (JSONException e) {
            // Still show the panel so the player can always dismiss it.
            summary = new JSONObject();
        }

        LinearLayout panel = ModalChrome.makePanel(activity);
        // Tighter header than the other modals so the columns get more room.
        panel.setPadding(panel.getPaddingLeft(), activity.dpToPx(12),
                         panel.getPaddingRight(), panel.getPaddingBottom());
        // Taps inside the panel must not reach fadeView, which dismisses.
        panel.setClickable(true);
        TextView title = ModalChrome.makeTitle(
            activity, activity.getString(R.string.death_title));
        title.setPadding(0, 0, 0, activity.dpToPx(8));
        panel.addView(title);

        LinearLayout columns = new LinearLayout(activity);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setBaselineAligned(false);
        panel.addView(columns, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout summaryColumn = new LinearLayout(activity);
        summaryColumn.setOrientation(LinearLayout.VERTICAL);
        addSummary(addScrollingBody(summaryColumn), summary);
        columns.addView(summaryColumn, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 0.8f));

        View divider = ModalChrome.makeDimSeparator(activity);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
            1, LinearLayout.LayoutParams.MATCH_PARENT);
        dividerParams.setMargins(activity.dpToPx(12), 0, activity.dpToPx(12), 0);
        columns.addView(divider, dividerParams);

        LinearLayout inventoryColumn = new LinearLayout(activity);
        inventoryColumn.setOrientation(LinearLayout.VERTICAL);
        addInventory(addScrollingBody(inventoryColumn), summary.optJSONArray("items"));
        columns.addView(inventoryColumn, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1.2f));

        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int width = Math.min(activity.dpToPx(600), (int)(metrics.widthPixels * 0.9f));
        int height = Math.min(activity.dpToPx(440), (int)(metrics.heightPixels * 0.85f));
        root.addView(panel, new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
        modalView = panel;

        ModalChrome.animateIn(panel);
        root.requestFocus();
    }

    /** Adds a scroll area that fills the rest of the column and returns its
     *  content. */
    private LinearLayout addScrollingBody(LinearLayout card) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalFadingEdgeEnabled(false);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);

        card.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return content;
    }

    private void addSummary(LinearLayout content, JSONObject s) {
        String cause = s.optString("cause", "");
        if (!cause.isEmpty()) {
            TextView causeView = new TextView(activity);
            causeView.setText(cause);
            causeView.setTextColor(Palette.GHOST_WHITE);
            causeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            causeView.setTypeface(Typeface.MONOSPACE);
            causeView.setGravity(Gravity.CENTER);
            causeView.setPadding(activity.dpToPx(4), 0,
                                 activity.dpToPx(4), activity.dpToPx(12));
            content.addView(causeView);
        }

        // All stats in one wrapping text block. Spaces inside a stat are
        // non-breaking, so a line only ever breaks between two stats.
        SpannableStringBuilder stats = new SpannableStringBuilder();
        addStat(stats, "Depth",
            s.has("depth") ? String.valueOf(s.optInt("depth")) : MISSING);
        addStat(stats, "Turns",
            s.has("turns")
                ? String.format(Locale.US, "%,d", s.optLong("turns")) : MISSING);
        addStat(stats, "Gold",
            s.has("gold") ? Long.toString(s.optLong("gold")) : MISSING);
        addStat(stats, "Deepest depth",
            s.has("deepestDepth") ? "Depth " + s.optInt("deepestDepth") : MISSING);
        // On a death the score is the gold, except with gems in the pack or
        // after switching to easy mode mid-run, so only show it then.
        if (s.has("score") && s.optLong("score") != s.optLong("gold", -1)) {
            addStat(stats, "Score", Long.toString(s.optLong("score")));
        }
        if (s.optBoolean("amulet", false)) {
            addStat(stats, "Amulet", "In hand");
        }
        addStat(stats, "Seed", s.optString("seed", MISSING));
        addStat(stats, "Mode", modeLabel(s));
        // Left out for a run that isn't timed, e.g. resumed from an older save.
        long playtime = activity.runPlaytimeMillis();
        if (playtime >= 0) {
            addStat(stats, "Run playtime", PlayerStatsModal.formatPlaytime(playtime));
        }

        // Not tracked per run yet.
        addPlaceholderStat(stats, "Monsters slain");
        addPlaceholderStat(stats, "Allies freed");
        addPlaceholderStat(stats, "Allies lost");

        TextView statsView = new TextView(activity);
        statsView.setText(stats);
        statsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statsView.setTypeface(Typeface.MONOSPACE);
        statsView.setLineSpacing(activity.dpToPx(10), 1f);
        statsView.setPadding(activity.dpToPx(4), 0, activity.dpToPx(4), 0);
        content.addView(statsView);
    }

    private static void addStat(SpannableStringBuilder stats, String key,
                                String value) {
        appendStat(stats, key, value, Palette.GHOST_WHITE);
    }

    private static void addPlaceholderStat(SpannableStringBuilder stats,
                                           String key) {
        appendStat(stats, key, NOT_TRACKED, Palette.DISABLED_TEXT);
    }

    /** Appends "Key: Value", coloured like ModalChrome.addKeyValueRow. */
    private static void appendStat(SpannableStringBuilder stats, String key,
                                   String value, int valueColor) {
        if (stats.length() > 0) {
            stats.append("  ");
        }
        stats.append(nonBreaking(key + ": "),
                     new ForegroundColorSpan(Palette.PALE_BLUE),
                     Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int valueStart = stats.length();
        stats.append(nonBreaking(value));
        stats.setSpan(new ForegroundColorSpan(valueColor), valueStart,
                      stats.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        stats.setSpan(new StyleSpan(Typeface.BOLD), valueStart,
                      stats.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static String nonBreaking(String text) {
        return text.replace(' ', (char) 0xA0); // no-break space
    }

    private static String modeLabel(JSONObject s) {
        if (!s.has("variant")) return MISSING;
        int variant = s.optInt("variant");
        String label = variant >= 0 && variant < PlayerStatsModal.VARIANT_LABELS.length
            ? PlayerStatsModal.VARIANT_LABELS[variant]
            : PlayerStatsModal.VARIANT_LABELS[StartMenu.VARIANT_BROGUE];
        if (s.optInt("difficulty") == StartMenu.DIFFICULTY_EASY) {
            label += " / " + PlayerStatsModal.DIFFICULTY_LABELS[StartMenu.DIFFICULTY_EASY];
        }
        return label;
    }

    /** The pack at death, equipped items first and a divider after them.
     *  Names are what the player knew when they died. */
    private void addInventory(LinearLayout content, JSONArray items) {
        if (items == null || items.length() == 0) {
            TextView empty = new TextView(activity);
            empty.setText(R.string.inventory_empty);
            empty.setTextColor(Color.argb(160, 144, 148, 190));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            empty.setTypeface(Typeface.MONOSPACE, Typeface.ITALIC);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(activity.dpToPx(8), activity.dpToPx(16),
                             activity.dpToPx(8), activity.dpToPx(16));
            content.addView(empty);
            return;
        }

        int equippedCount = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && item.optBoolean("equipped", false)) equippedCount++;
        }

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            if (i == equippedCount && equippedCount > 0) {
                content.addView(ModalChrome.makeDimSeparator(activity),
                                ModalChrome.separatorParams(activity, 4, 4, 4, 4));
            }
            content.addView(makeItemRow(item));
        }
    }

    /** A plain line: icon, good/bad magic mark and name. Nothing here is
     *  tapped, so it is far denser than the inventory panel's rows. */
    private View makeItemRow(JSONObject item) {
        String name = item.optString("name", "???");

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(activity.dpToPx(2), activity.dpToPx(2),
                       activity.dpToPx(6), activity.dpToPx(2));
        row.setContentDescription(name);

        // Same fills as the rows in the inventory panel.
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(activity.dpToPx(5));
        bg.setColor(item.optBoolean("equipped", false)
            ? Palette.EQUIPPED_GLOW : Palette.ITEM_BG);
        row.setBackground(bg);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, activity.dpToPx(1), 0, activity.dpToPx(2));
        row.setLayoutParams(params);

        InventoryOverlay.addItemIcon(activity, row, item, true, 22, 25);
        InventoryOverlay.addMagicIndicator(activity, row, item.optInt("magicPolarity", 0));

        TextView nameView = new TextView(activity);
        nameView.setText(name);
        nameView.setTextColor(Palette.GHOST_WHITE);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        nameView.setTypeface(Typeface.MONOSPACE);
        nameView.setPadding(activity.dpToPx(2), 0, 0, 0);
        row.addView(nameView, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private synchronized boolean transition(State expected, State next) {
        if (state != expected) {
            return false;
        }
        state = next;
        return true;
    }

    private synchronized boolean isInState(State expected) {
        return state == expected;
    }
}
