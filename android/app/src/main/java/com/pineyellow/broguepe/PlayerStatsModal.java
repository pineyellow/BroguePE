package com.pineyellow.broguepe;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Detailed local player-stats view opened from the title menu. */
final class PlayerStatsModal {

    private static final String[] VARIANT_LABELS = {
        "Classic",
        "Rapid",
        "Bullet"
    };
    private static final String[] DIFFICULTY_LABELS = {
        "Default",
        "Easy"
    };

    private final BrogueActivity activity;
    private int selectedVariant = StartMenu.VARIANT_BROGUE;
    private int selectedDifficulty = StartMenu.DIFFICULTY_DEFAULT;

    PlayerStatsModal(BrogueActivity activity) {
        this.activity = activity;
    }

    void show() {
        selectedVariant = StartMenu.VARIANT_BROGUE;
        selectedDifficulty = StartMenu.DIFFICULTY_DEFAULT;
        activity.modalStack.push(this::build);
    }

    private View build() {
        FrameLayout root = new FrameLayout(activity);
        LinearLayout panel = ModalChrome.buildPanel(activity, root, "PERSONAL STATS");

        LinearLayout statsContent = new LinearLayout(activity);
        statsContent.setOrientation(LinearLayout.VERTICAL);

        LinearLayout selectors = new LinearLayout(activity);
        selectors.setOrientation(LinearLayout.HORIZONTAL);
        selectors.setBaselineAligned(false);

        LinearLayout modeHolder = new LinearLayout(activity);
        modeHolder.setOrientation(LinearLayout.VERTICAL);
        View modeRow = StartMenu.addButton(
            modeHolder, VARIANT_LABELS[selectedVariant], true, null);
        TextView modeLabel = (TextView) ((LinearLayout) modeRow).getChildAt(0);
        modeRow.setOnClickListener(v -> {
            selectedVariant = (selectedVariant + 1) % VARIANT_LABELS.length;
            modeLabel.setText(VARIANT_LABELS[selectedVariant]);
            renderStats(statsContent);
        });

        LinearLayout difficultyHolder = new LinearLayout(activity);
        difficultyHolder.setOrientation(LinearLayout.VERTICAL);
        View difficultyRow = StartMenu.addButton(
            difficultyHolder, DIFFICULTY_LABELS[selectedDifficulty], true, null);
        TextView difficultyLabel =
            (TextView) ((LinearLayout) difficultyRow).getChildAt(0);
        difficultyRow.setOnClickListener(v -> {
            selectedDifficulty = (selectedDifficulty + 1) % DIFFICULTY_LABELS.length;
            difficultyLabel.setText(DIFFICULTY_LABELS[selectedDifficulty]);
            renderStats(statsContent);
        });

        int selectorGap = activity.dpToPx(3);
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        modeParams.rightMargin = selectorGap;
        selectors.addView(modeHolder, modeParams);
        LinearLayout.LayoutParams difficultyParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        difficultyParams.leftMargin = selectorGap;
        selectors.addView(difficultyHolder, difficultyParams);
        panel.addView(selectors, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        panel.addView(ModalChrome.makeEmberSeparator(activity),
            ModalChrome.emberSeparatorParams(activity, 8, 8, 8, 12));
        panel.addView(statsContent);
        renderStats(statsContent);

        // Breathing room between the last section and the Back button so the
        // button doesn't hug the last row of content when scrolled to bottom.
        View spacer = new View(activity);
        panel.addView(spacer, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, activity.dpToPx(20)));

        StartMenu.addButton(panel, "Back", true, v -> activity.modalStack.pop());

        ModalChrome.present(activity, root, panel);
        return root;
    }

    private void renderStats(LinearLayout panel) {
        panel.removeAllViews();
        PlayerStats stats = StatsStore.get(activity).snapshot(
            selectedVariant, selectedDifficulty);

        // Top: 3-cell stat grid (played / won / died).
        LinearLayout grid = new LinearLayout(activity);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        grid.setBaselineAligned(false);
        addStatCell(grid, stats.gamesPlayed, "Games");
        addStatCell(grid, stats.wins,        "Wins");
        addStatCell(grid, stats.deaths,      "Deaths");
        panel.addView(grid, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        // Highest-watermark stats.
        addKeyValueRow(panel, "Deepest depth",
            stats.deepestDepth > 0 ? "Depth " + stats.deepestDepth : "—");
        int deadliest = stats.deadliestDepth();
        addKeyValueRow(panel, "Deadliest depth",
            deadliest > 0 ? "Depth " + deadliest : "—");
        addKeyValueRow(panel, "Most gold collected",
            stats.mostGoldCollected > 0
                ? Long.toString(stats.mostGoldCollected) : "—");
        addKeyValueRow(panel, "Longest game",
            stats.longestRunTurns > 0 ? formatTurns(stats.longestRunTurns) : "—");
        addKeyValueRow(panel, "Mastery wins",
            stats.masteryWins > 0 ? String.valueOf(stats.masteryWins) : "—");
        addKeyValueRow(panel, "Fastest win",
            stats.fastestWinTurns > 0 ? formatTurns(stats.fastestWinTurns) : "—");

        addRecentSeedsSection(panel, stats);

        addTallySection(panel, "Primary Causes of Death", stats.deathCauses);
        addTallySection(panel, "Monsters Slain",          stats.kills);
        addTallySection(panel, "Allies Freed",            stats.alliesFreed);
        addTallySection(panel, "Allies you let die",      stats.alliesLost);
    }

    private void addKeyValueRow(LinearLayout panel, String key, String value) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(activity.dpToPx(8), activity.dpToPx(8),
                       activity.dpToPx(8), activity.dpToPx(8));

        TextView keyView = new TextView(activity);
        keyView.setText(key);
        keyView.setTextColor(Palette.PALE_BLUE);
        keyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        keyView.setTypeface(Typeface.MONOSPACE);
        row.addView(keyView, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView valueView = new TextView(activity);
        valueView.setText(value);
        valueView.setTextColor(Palette.GHOST_WHITE);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        valueView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.addView(valueView);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = activity.dpToPx(2);
        panel.addView(row, p);
    }

    private void addTallySection(LinearLayout panel, String title,
                                  java.util.List<PlayerStats.Tally> tallies) {
        TextView subhead = new TextView(activity);
        subhead.setText(title);
        subhead.setTextColor(Palette.DIM_WHITE_BLUE);
        subhead.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subhead.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        subhead.setLetterSpacing(0.1f);
        subhead.setPadding(activity.dpToPx(4), activity.dpToPx(20),
                           activity.dpToPx(4), activity.dpToPx(6));
        panel.addView(subhead);

        if (tallies == null || tallies.isEmpty()) {
            panel.addView(makeEmptyPlaceholder());
            return;
        }

        TagFlowLayout flow = new TagFlowLayout(activity, activity.dpToPx(6));
        for (PlayerStats.Tally t : tallies) {
            flow.addView(makeTagPill(t));
        }
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        fp.setMargins(activity.dpToPx(4), 0, activity.dpToPx(4), 0);
        panel.addView(flow, fp);
    }

    /** Shown inside a section whose content list is empty, so every section's
     *  header is always visible regardless of player progress. */
    private TextView makeEmptyPlaceholder() {
        TextView empty = new TextView(activity);
        empty.setText(R.string.stats_none_yet);
        empty.setTextColor(Palette.DISABLED_TEXT);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        empty.setTypeface(Typeface.MONOSPACE);
        empty.setPadding(activity.dpToPx(4), activity.dpToPx(2),
                         activity.dpToPx(4), activity.dpToPx(2));
        return empty;
    }

    /** Last ten distinct seeds this install version has played, newest first. Each
     *  row is clickable and can be replayed offline. */
    private void addRecentSeedsSection(LinearLayout panel, PlayerStats stats) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(activity.dpToPx(44));
        header.setPadding(activity.dpToPx(12), activity.dpToPx(8),
                          activity.dpToPx(8), activity.dpToPx(8));

        GradientDrawable headerBg = new GradientDrawable();
        headerBg.setShape(GradientDrawable.RECTANGLE);
        headerBg.setCornerRadius(activity.dpToPx(6));
        headerBg.setColor(Palette.ITEM_BG);
        headerBg.setStroke(1, Palette.BORDER_DIM);
        header.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.RIPPLE_GLOW), headerBg, null));

        TextView subhead = new TextView(activity);
        subhead.setText(R.string.stats_recent_seeds);
        subhead.setTextColor(Palette.DIM_WHITE_BLUE);
        subhead.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subhead.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        subhead.setLetterSpacing(0.1f);
        header.addView(subhead, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = new TextView(activity);
        chevron.setText("\u25B8");
        chevron.setTextColor(Palette.PALE_BLUE);
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        chevron.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        chevron.setGravity(Gravity.CENTER);
        header.addView(chevron, new LinearLayout.LayoutParams(
            activity.dpToPx(32), LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.setMargins(activity.dpToPx(4), activity.dpToPx(16),
                                activity.dpToPx(4), 0);
        panel.addView(header, headerParams);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setVisibility(View.GONE);

        if (stats.recentSeeds.isEmpty()) {
            content.addView(makeEmptyPlaceholder());
        } else {
            for (PlayerStats.RecentSeed recent : stats.recentSeeds) {
                content.addView(makeRecentSeedRow(recent));
            }
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(activity.dpToPx(4), activity.dpToPx(4),
                      activity.dpToPx(4), 0);
        panel.addView(content, lp);

        header.setClickable(true);
        header.setFocusable(true);
        header.setOnClickListener(v -> {
            boolean expand = content.getVisibility() != View.VISIBLE;
            content.setVisibility(expand ? View.VISIBLE : View.GONE);
            chevron.setText(expand ? "\u25BE" : "\u25B8");
        });
    }

    private View makeRecentSeedRow(PlayerStats.RecentSeed recent) {
        TextView row = new TextView(activity);
        String mode = variantName(recent.variant);
        if (recent.difficulty == StartMenu.DIFFICULTY_EASY) {
            mode += "/easy";
        }
        row.setText(recent.seed + " (" + mode + ")");
        row.setTextColor(Palette.GHOST_WHITE);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.setPadding(activity.dpToPx(12), activity.dpToPx(10),
                       activity.dpToPx(12), activity.dpToPx(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(activity.dpToPx(6));
        bg.setColor(Palette.ITEM_BG);
        row.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.RIPPLE_GLOW), bg, null));

        row.setOnClickListener(v -> {
            activity.modalStack.pop();
            activity.newGameSeedModal.show(
                recent.seed, recent.variant, recent.difficulty);
        });

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, activity.dpToPx(2), 0, activity.dpToPx(2));
        row.setLayoutParams(p);
        return row;
    }

    private static String variantName(int variant) {
        switch (variant) {
            case StartMenu.VARIANT_RAPID:  return "rapid";
            case StartMenu.VARIANT_BULLET: return "bullet";
            default:                       return "classic";
        }
    }

    private View makeTagPill(PlayerStats.Tally t) {
        // Count portion is ember+bold, label portion is white+regular, packed
        // into one TextView so the whole pill sizes and wraps as one unit.
        String countPart = t.count + "\u00D7 ";
        SpannableString text = new SpannableString(countPart + capitalize(t.label));
        text.setSpan(new ForegroundColorSpan(Palette.DIM_WHITE_BLUE),
                     0, countPart.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new StyleSpan(Typeface.BOLD),
                     0, countPart.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        TextView pill = new TextView(activity);
        pill.setText(text);
        pill.setTextColor(Palette.GHOST_WHITE);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pill.setTypeface(Typeface.MONOSPACE);
        pill.setPadding(activity.dpToPx(10), activity.dpToPx(5),
                        activity.dpToPx(10), activity.dpToPx(5));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(activity.dpToPx(4));
        bg.setColor(Palette.ITEM_BG);
        pill.setBackground(bg);

        return pill;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        char first = s.charAt(0);
        if (Character.isUpperCase(first)) return s;
        return Character.toUpperCase(first) + s.substring(1);
    }

    /** Lays children left-to-right, wrapping to a new line when the next
     *  child would overflow the parent width. `gap` is used for both inter
     *  pill horizontal spacing and inter-row vertical spacing. */
    private static final class TagFlowLayout extends ViewGroup {
        private final int gap;

        TagFlowLayout(Context ctx, int gapPx) {
            super(ctx);
            this.gap = gapPx;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int maxWidth = MeasureSpec.getSize(widthSpec);
            int rowWidth = 0, rowHeight = 0, totalHeight = 0;
            int n = getChildCount();
            for (int i = 0; i < n; i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                measureChild(c, widthSpec, heightSpec);
                int cw = c.getMeasuredWidth();
                int ch = c.getMeasuredHeight();
                if (rowWidth > 0 && rowWidth + gap + cw > maxWidth) {
                    totalHeight += rowHeight + gap;
                    rowWidth = 0;
                    rowHeight = 0;
                }
                rowWidth += (rowWidth > 0 ? gap : 0) + cw;
                rowHeight = Math.max(rowHeight, ch);
            }
            totalHeight += rowHeight;
            setMeasuredDimension(maxWidth, totalHeight);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int maxWidth = r - l;
            int x = 0, y = 0, rowHeight = 0;
            int n = getChildCount();
            for (int i = 0; i < n; i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                int cw = c.getMeasuredWidth();
                int ch = c.getMeasuredHeight();
                if (x > 0 && x + gap + cw > maxWidth) {
                    y += rowHeight + gap;
                    x = 0;
                    rowHeight = 0;
                }
                int left = x + (x > 0 ? gap : 0);
                c.layout(left, y, left + cw, y + ch);
                x = left + cw;
                rowHeight = Math.max(rowHeight, ch);
            }
        }
    }

    private static String formatTurns(int turns) {
        return String.format(java.util.Locale.US, "%,d turns", turns);
    }

    private void addStatCell(LinearLayout grid, int value, String label) {
        LinearLayout cell = new LinearLayout(activity);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(activity.dpToPx(6), activity.dpToPx(10),
                        activity.dpToPx(6), activity.dpToPx(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(activity.dpToPx(6));
        bg.setColor(Palette.ITEM_BG);
        cell.setBackground(bg);

        TextView number = new TextView(activity);
        number.setText(String.valueOf(value));
        number.setTextColor(Palette.GHOST_WHITE);
        number.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        number.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        number.setGravity(Gravity.CENTER);
        cell.addView(number, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView caption = new TextView(activity);
        caption.setText(label);
        caption.setTextColor(Palette.PALE_BLUE);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        caption.setTypeface(Typeface.MONOSPACE);
        caption.setLetterSpacing(0.05f);
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(0, activity.dpToPx(2), 0, 0);
        cell.addView(caption, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams cellP = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        int margin = activity.dpToPx(2);
        cellP.setMargins(margin, 0, margin, 0);
        grid.addView(cell, cellP);
    }
}
