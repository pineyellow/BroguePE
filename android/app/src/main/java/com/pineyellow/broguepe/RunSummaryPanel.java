package com.pineyellow.broguepe;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One panel summing up a run: its stats on the left; on the right, the pack
 *  at its end or the monsters and allies met, switched by a title-row button.
 *  Next to it, a reveal button shows what the pack's items really were. Each list
 *  scrolls on its own. Shown by the death screen and, for past runs, from the
 *  Recent Games list. The summary is the engine's runSummaryJson. */
final class RunSummaryPanel {

    private static final String SHOW_CREATURES = "Show monsters and allies";
    private static final String SHOW_PACK = "Show inventory";
    private static final String REVEAL = "Reveal items";
    private static final String MISSING = "-";

    private final BrogueActivity activity;

    RunSummaryPanel(BrogueActivity activity) {
        this.activity = activity;
    }

    static JSONObject parseSummary(String json) {
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            // Still show the panel so the player can always dismiss it.
            return new JSONObject();
        }
    }

    /** playtimeMillis is the run's playtime, or -1 to leave it out. A non-null
     *  onWatch or onPlayAgain adds a Watch or Play Again button under the
     *  stats. */
    LinearLayout build(JSONObject summary, String title, long playtimeMillis,
                       View.OnClickListener onWatch, View.OnClickListener onPlayAgain) {
        LinearLayout panel = ModalChrome.makePanel(activity);
        // Tighter header than the other modals so the columns get more room.
        // 12dp above the title; the switch button reaches 6dp further up.
        panel.setPadding(panel.getPaddingLeft(), activity.dpToPx(6),
                         panel.getPaddingRight(), panel.getPaddingBottom());
        // Title row: the title centred, and on the right a button that
        // switches the right column between the pack and the monsters.
        FrameLayout header = new FrameLayout(activity);
        TextView titleView = ModalChrome.makeTitle(activity, title);
        titleView.setPadding(0, 0, 0, 0);
        // The title stays centred in the lower 30dp of the row, where it was
        // before the button grew upward.
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT);
        titleParams.topMargin = activity.dpToPx(6);
        header.addView(titleView, titleParams);
        ImageView switchButton = makeIconButton(R.drawable.ic_swap);
        int switchSize = activity.dpToPx(36);
        header.addView(switchButton, new FrameLayout.LayoutParams(
            switchSize, switchSize, Gravity.END | Gravity.TOP));
        // Left of the switch button, and only when an item has more to show.
        // Games saved before the engine sent true names have none.
        JSONArray items = summary.optJSONArray("items");
        ImageView revealButton = hasReveal(items) ? makeIconButton(R.drawable.ic_wand_shine) : null;
        if (revealButton != null) {
            revealButton.setContentDescription(REVEAL);
            FrameLayout.LayoutParams revealParams = new FrameLayout.LayoutParams(
                switchSize, switchSize, Gravity.END | Gravity.TOP);
            revealParams.rightMargin = switchSize + activity.dpToPx(6);
            header.addView(revealButton, revealParams);
        }
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.bottomMargin = activity.dpToPx(8);
        panel.addView(header, headerParams);

        LinearLayout columns = new LinearLayout(activity);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setBaselineAligned(false);
        panel.addView(columns, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // The summary sits on a rounded card, like the Personal Stats cards.
        LinearLayout summaryColumn = new LinearLayout(activity);
        summaryColumn.setOrientation(LinearLayout.VERTICAL);
        int cardPad = activity.dpToPx(10);
        summaryColumn.setPadding(cardPad, cardPad, cardPad, cardPad);
        GradientDrawable summaryBg = new GradientDrawable();
        summaryBg.setShape(GradientDrawable.RECTANGLE);
        summaryBg.setCornerRadius(activity.dpToPx(UiStyle.MENU_ITEM_CORNER_RADIUS_DP));
        summaryBg.setColor(Palette.ITEM_BG);
        summaryColumn.setBackground(summaryBg);
        addSummary(addScrollingBody(summaryColumn), summary, playtimeMillis);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 0.8f);
        if (onWatch == null && onPlayAgain == null) {
            columns.addView(summaryColumn, leftParams);
        } else {
            // Under the card, sharing the left column's width equally.
            LinearLayout leftColumn = new LinearLayout(activity);
            leftColumn.setOrientation(LinearLayout.VERTICAL);
            leftColumn.addView(summaryColumn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            LinearLayout buttons = new LinearLayout(activity);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            if (onWatch != null) {
                buttons.addView(makeSmallButton("Watch", onWatch), new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }
            if (onPlayAgain != null) {
                LinearLayout.LayoutParams playAgainParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                if (onWatch != null) playAgainParams.leftMargin = activity.dpToPx(8);
                buttons.addView(makeSmallButton("Play Again", onPlayAgain), playAgainParams);
            }
            LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            buttonsParams.topMargin = activity.dpToPx(8);
            leftColumn.addView(buttons, buttonsParams);
            columns.addView(leftColumn, leftParams);
            // Less panel padding under the buttons, so they sit lower.
            panel.setPadding(panel.getPaddingLeft(), panel.getPaddingTop(),
                             panel.getPaddingRight(), activity.dpToPx(8));
        }

        LinearLayout inventoryColumn = new LinearLayout(activity);
        inventoryColumn.setOrientation(LinearLayout.VERTICAL);
        // The right column shows either the pack or the monsters slain and
        // allies met this run; the title-row button switches between them.
        LinearLayout packContent = addScrollingBody(inventoryColumn);
        addInventory(packContent, items, false);
        if (revealButton != null) {
            // Unrevealed each time the panel opens. Revealing can't be undone,
            // so the button goes away; disabled, it stays gone below.
            revealButton.setOnClickListener(v -> {
                v.setEnabled(false);
                v.setVisibility(View.GONE);
                packContent.removeAllViews();
                addInventory(packContent, items, true);
            });
        }
        LinearLayout creaturesContent = addScrollingBody(inventoryColumn);
        View packBody = (View) packContent.getParent();
        View creaturesBody = (View) creaturesContent.getParent();
        creaturesBody.setVisibility(View.GONE);
        switchButton.setContentDescription(SHOW_CREATURES);
        switchButton.setOnClickListener(v -> {
            boolean showCreatures = creaturesBody.getVisibility() != View.VISIBLE;
            // Built on first open, since a long run means a lot of pills.
            if (showCreatures && creaturesContent.getChildCount() == 0) {
                addCreatures(creaturesContent, summary);
            }
            creaturesBody.setVisibility(showCreatures ? View.VISIBLE : View.GONE);
            packBody.setVisibility(showCreatures ? View.GONE : View.VISIBLE);
            if (revealButton != null && revealButton.isEnabled()) {
                revealButton.setVisibility(showCreatures ? View.GONE : View.VISIBLE);
            }
            v.setContentDescription(showCreatures ? SHOW_PACK : SHOW_CREATURES);
        });
        LinearLayout.LayoutParams inventoryParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1.2f);
        inventoryParams.leftMargin = activity.dpToPx(12);
        columns.addView(inventoryColumn, inventoryParams);
        return panel;
    }

    /** Centred, up to 600x440dp and at most 85% of the width and height, so
     *  a small screen still has backdrop at the sides to tap outside. */
    FrameLayout.LayoutParams panelParams() {
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int width = Math.min(activity.dpToPx(600), (int)(metrics.widthPixels * 0.85f));
        int height = Math.min(activity.dpToPx(440), (int)(metrics.heightPixels * 0.85f));
        return new FrameLayout.LayoutParams(width, height, Gravity.CENTER);
    }

    /** Adds a scroll area that fills the rest of the column and returns its
     *  content. */
    private LinearLayout addScrollingBody(LinearLayout card) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalFadingEdgeEnabled(false);
        // ScrollView makes itself focusable; on the death screen, focus stays
        // on its root instead.
        scroll.setFocusable(false);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);

        card.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return content;
    }

    private void addSummary(LinearLayout content, JSONObject s, long playtimeMillis) {
        String cause = s.optString("cause", "");
        if (!cause.isEmpty()) {
            TextView causeView = new TextView(activity);
            causeView.setText(cause);
            causeView.setTextColor(Palette.GHOST_WHITE);
            causeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            causeView.setTypeface(Typeface.MONOSPACE);
            causeView.setGravity(Gravity.CENTER);
            int sidePad = activity.dpToPx(4);
            int bottomPad = activity.dpToPx(12);

            // A death's cause has its icon from Personal Stats' Primary Causes of
            // Death just left of its first line. The text is centred as without
            // the icon, with room for it on both sides so it is never cut off. A
            // cause without one, or a win's, is the text alone.
            PlayerStatsModal.TallyIcon icon = RecentGames.OUTCOME_DIED.equals(RecentGames.outcome(s))
                ? PlayerStatsModal.TallyIcon.find(activity, DeathCauseIcon.label(activity, cause))
                : null;
            if (icon == null) {
                causeView.setPadding(sidePad, 0, sidePad, bottomPad);
                content.addView(causeView);
            } else {
                int room = icon.width + activity.dpToPx(6);
                // An icon taller than a line would stick out above the first one.
                int topPad = Math.max(0, (icon.height - causeView.getLineHeight() + 1) / 2);
                causeView.setPadding(sidePad + room, topPad, sidePad + room, bottomPad);
                FrameLayout causeRow = new FrameLayout(activity);
                causeRow.addView(causeView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT));
                causeRow.addView(icon.view, new FrameLayout.LayoutParams(icon.width, icon.height));
                // Moved against the first line once the text is laid out.
                causeView.addOnLayoutChangeListener((v, left, top, right, bottom,
                                                     oldLeft, oldTop, oldRight, oldBottom) -> {
                    Layout layout = causeView.getLayout();
                    if (layout == null) return;
                    float lineLeft = causeView.getTotalPaddingLeft() + layout.getLineLeft(0);
                    float lineMiddle = causeView.getTotalPaddingTop()
                        + (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f;
                    icon.view.setTranslationX(lineLeft - room);
                    icon.view.setTranslationY(lineMiddle - icon.height / 2f);
                });
                content.addView(causeRow);
            }
        }

        // A win ends at the surface or the deepest depth with the amulet in
        // hand, and the cause line already says which, so those two rows are
        // left out.
        boolean victory = RecentGames.isVictory(s);

        // One stat per row; a value too long for the column wraps in its row.
        if (!victory) {
            addStat(content,"Depth",
                s.has("depth") ? String.valueOf(s.optInt("depth")) : MISSING);
        }
        addStat(content,"Turns",
            s.has("turns")
                ? String.format(Locale.US, "%,d", s.optLong("turns")) : MISSING);
        addStat(content,"Gold",
            s.has("gold") ? Long.toString(s.optLong("gold")) : MISSING);
        // Only when it says more than the Depth row, as it does on a win.
        boolean sameDepth = !victory && s.has("depth") && s.has("deepestDepth")
            && s.optInt("depth") == s.optInt("deepestDepth");
        if (!sameDepth) {
            addStat(content,"Deepest depth",
                s.has("deepestDepth") ? String.valueOf(s.optInt("deepestDepth")) : MISSING);
        }
        // On a death the score is the gold, except with gems in the pack or
        // after switching to easy mode mid-run, so only show it then.
        if (s.has("score") && s.optLong("score") != s.optLong("gold", -1)) {
            addStat(content,"Score", Long.toString(s.optLong("score")));
        }
        if (!victory && s.optBoolean("amulet", false)) {
            addStat(content,"Amulet", "In hand");
        }
        String seed = s.optString("seed", "");
        TextView seedRow = addStat(content,"Seed", seed.isEmpty() ? MISSING : seed);
        if (!seed.isEmpty()) {
            // Tapping the seed copies it. The ripple only shows while pressed.
            GradientDrawable pressMask = new GradientDrawable();
            pressMask.setCornerRadius(activity.dpToPx(5));
            pressMask.setColor(Color.WHITE);
            seedRow.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Palette.RIPPLE_GLOW), null, pressMask));
            seedRow.setOnClickListener(v -> SettingsPanel.copySeed(activity, seed));
            // As with the switch button, so the death screen's root keeps focus.
            seedRow.setFocusable(false);
        }
        addStat(content,"Mode", modeLabel(s));
        // Left out for a run that isn't timed, e.g. resumed from an older save.
        if (playtimeMillis >= 0) {
            addStat(content,"Run playtime", formatRunPlaytime(playtimeMillis));
        }
    }

    /** Like PlayerStatsModal.formatPlaytime, but rounded up to the next whole
     *  minute, so a run's first minute shows as 1m. The lifetime total rounds
     *  down, since it is saved at each whole minute. */
    static String formatRunPlaytime(long millis) {
        long ms = Math.max(0L, millis);
        long minutes = ms / 60_000L + (ms % 60_000L == 0 ? 0 : 1);
        return PlayerStatsModal.formatPlaytime(minutes * 60_000L);
    }

    /** Adds a "Key: Value" row, coloured like ModalChrome.addKeyValueRow. */
    private TextView addStat(LinearLayout content, String key, String value) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        text.append(key + ": ", new ForegroundColorSpan(Palette.PALE_BLUE),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int valueStart = text.length();
        text.append(value);
        text.setSpan(new ForegroundColorSpan(Palette.GHOST_WHITE), valueStart,
                     text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new StyleSpan(Typeface.BOLD), valueStart,
                     text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        TextView row = new TextView(activity);
        row.setText(text);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        row.setTypeface(Typeface.MONOSPACE);
        row.setPadding(activity.dpToPx(4), activity.dpToPx(4),
                       activity.dpToPx(4), activity.dpToPx(4));
        content.addView(row);
        return row;
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

    /** Small square icon button for the title row: the in-game action
     *  buttons' icon color on the stats card's fill. Not focusable, so the
     *  death screen's root keeps focus and Enter still dismisses it. */
    private ImageView makeIconButton(int iconRes) {
        ImageView button = new ImageView(activity);
        button.setImageResource(iconRes);
        button.setColorFilter(Palette.ACTION_BUTTON_TEXT);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = activity.dpToPx(7);
        button.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(activity.dpToPx(UiStyle.MENU_ITEM_CORNER_RADIUS_DP));
        bg.setColor(Palette.ITEM_BG);
        bg.setStroke(1, Palette.BORDER_DIM);
        button.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.RIPPLE_GLOW), bg, null));
        button.setClickable(true);
        button.setFocusable(false);
        return button;
    }

    /** Whether any item has a true name the player didn't know. */
    private static boolean hasReveal(JSONArray items) {
        if (items == null) return false;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && item.has("revealedName")) {
                return true;
            }
        }
        return false;
    }

    /** A small button, coloured like the menu buttons. */
    private TextView makeSmallButton(String label, View.OnClickListener onClick) {
        TextView button = new TextView(activity);
        button.setText(label);
        button.setTextColor(Palette.GHOST_WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(activity.dpToPx(12), activity.dpToPx(10),
                          activity.dpToPx(12), activity.dpToPx(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(activity.dpToPx(UiStyle.MENU_ITEM_CORNER_RADIUS_DP));
        bg.setColor(Palette.ITEM_BG);
        bg.setStroke(1, Palette.BORDER_DIM);
        button.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.RIPPLE_GLOW), bg, null));
        button.setOnClickListener(onClick);
        return button;
    }

    /** This run's monsters slain and allies freed or lost, in the same pill
     *  sections as Personal Stats. */
    private void addCreatures(LinearLayout content, JSONObject summary) {
        PlayerStatsModal.addTallySection(activity, content, "Monsters Slain",
            runTallies(summary.optJSONArray("kills")), 0, R.string.stats_none);
        PlayerStatsModal.addTallySection(activity, content, "Allies Freed",
            runTallies(summary.optJSONArray("alliesFreed")), 16, R.string.stats_none);
        PlayerStatsModal.addTallySection(activity, content, "Allies you let die",
            runTallies(summary.optJSONArray("alliesLost")), 16, R.string.stats_none);
    }

    /** Reads one of the engine's [{"name": ..., "count": N}, ...] tallies,
     *  merging any repeated name, highest count first. */
    static List<PlayerStats.Tally> runTallies(JSONArray entries) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (entries != null) {
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                String name = entry.optString("name", "");
                int count = entry.optInt("count", 0);
                if (name.isEmpty() || count <= 0) continue;
                Integer previous = counts.get(name);
                counts.put(name, (previous == null ? 0 : previous) + count);
            }
        }
        List<PlayerStats.Tally> tallies = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            tallies.add(new PlayerStats.Tally(e.getKey(), e.getValue()));
        }
        Collections.sort(tallies, (a, b) -> Integer.compare(b.count, a.count));
        return tallies;
    }

    /** The pack at the run's end, equipped items first and a divider after
     *  them. Names are what the player knew at the time or, revealed, what
     *  the items really were. */
    private void addInventory(LinearLayout content, JSONArray items, boolean revealed) {
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
            content.addView(makeItemRow(item, revealed));
        }
    }

    /** A plain line: icon, good/bad magic mark and name. Nothing here is
     *  tapped, so it is far denser than the inventory panel's rows. */
    private View makeItemRow(JSONObject item, boolean revealed) {
        String name = item.optString("name", "???");
        JSONArray runs = item.optJSONArray("nameRuns");
        if (revealed && item.has("revealedName")) {
            name = item.optString("revealedName", name);
            runs = item.optJSONArray("revealedNameRuns");
        }

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
        // Games saved before names carried colors show them plain.
        nameView.setText(InventoryOverlay.styledItemName(item, name, runs));
        nameView.setTextColor(Palette.GHOST_WHITE);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        nameView.setTypeface(Typeface.MONOSPACE);
        nameView.setPadding(activity.dpToPx(2), 0, 0, 0);
        row.addView(nameView, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }
}
