package com.pineyellow.broguepe;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Detailed local player-stats view opened from the title menu. */
final class PlayerStatsModal {

    /** Largest size of the Recent Games outcome glyph, in both graphics
     *  modes. Its box is as wide as the glyph. */
    private static final int OUTCOME_ICON_SIZE_DP = 16;

    static final String[] VARIANT_LABELS = {
        "Classic",
        "Rapid",
        "Bullet"
    };
    static final String[] DIFFICULTY_LABELS = {
        "Default",
        "Easy"
    };

    private final BrogueActivity activity;
    private int selectedVariant = StartMenu.VARIANT_BROGUE;
    private int selectedDifficulty = StartMenu.DIFFICULTY_DEFAULT;
    // Kept while the modal is open, so switching modes leaves it as it was.
    private boolean recentGamesExpanded;

    PlayerStatsModal(BrogueActivity activity) {
        this.activity = activity;
    }

    void show() {
        show(StartMenu.VARIANT_BROGUE, StartMenu.DIFFICULTY_DEFAULT, false);
    }

    /** Opens on the given mode. Coming back from a recording, the Recent
     *  Games list starts expanded. */
    void show(int variant, int difficulty, boolean expandRecentGames) {
        selectedVariant = PlayerStatsArchive.normalizeVariant(variant);
        selectedDifficulty = PlayerStatsArchive.normalizeDifficulty(difficulty);
        recentGamesExpanded = expandRecentGames;
        activity.modalStack.push(this::build);
    }

    private View build() {
        FrameLayout root = new FrameLayout(activity);
        LinearLayout panel = ModalChrome.buildPanel(activity, root, "PERSONAL STATS");

        LinearLayout statsContent = new LinearLayout(activity);
        statsContent.setOrientation(LinearLayout.VERTICAL);

        View playtimeCard = makePlaytimeCard();
        LinearLayout.LayoutParams playtimeParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        playtimeParams.setMargins(activity.dpToPx(2), 0,
                                  activity.dpToPx(2), activity.dpToPx(6));
        panel.addView(playtimeCard, playtimeParams);

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

        panel.addView(ModalChrome.makeDimSeparator(activity),
            ModalChrome.separatorParams(activity, 8, 8, 8, 12));
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
        stats = PlayerStatsPreview.apply(stats, selectedVariant, selectedDifficulty);

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
            stats.deepestDepth > 0 ? "Depth " + stats.deepestDepth : "-");
        int deadliest = stats.deadliestDepth();
        addKeyValueRow(panel, "Deadliest depth",
            deadliest > 0 ? "Depth " + deadliest : "-");
        addKeyValueRow(panel, "Most gold collected",
            stats.mostGoldCollected > 0
                ? Long.toString(stats.mostGoldCollected) : "-");
        addKeyValueRow(panel, "Longest game",
            stats.longestRunTurns > 0 ? formatTurns(stats.longestRunTurns) : "-");
        addKeyValueRow(panel, "Mastery wins",
            stats.masteryWins > 0 ? String.valueOf(stats.masteryWins) : "-");
        addKeyValueRow(panel, "Fastest win",
            stats.fastestWinTurns > 0 ? formatTurns(stats.fastestWinTurns) : "-");

        addRecentGamesSection(panel);

        addTallySection(panel, "Primary Causes of Death", stats.deathCauses);
        addTallySection(panel, "Monsters Slain",          stats.kills);
        addTallySection(panel, "Allies Freed",            stats.alliesFreed);
        addTallySection(panel, "Allies you let die",      stats.alliesLost);
    }

    private void addKeyValueRow(LinearLayout panel, String key, String value) {
        ModalChrome.addKeyValueRow(activity, panel, key, value, Palette.GHOST_WHITE);
    }

    private void addTallySection(LinearLayout panel, String title,
                                  java.util.List<PlayerStats.Tally> tallies) {
        addTallySection(activity, panel, title, tallies, 20, R.string.stats_none_yet);
    }

    /** Subheading plus a wrapping row of monster pills, or the emptyText
     *  placeholder when the list is empty. Also used by the death screen. */
    static void addTallySection(BrogueActivity activity, LinearLayout panel,
                                String title, java.util.List<PlayerStats.Tally> tallies,
                                int topPaddingDp, int emptyText) {
        TextView subhead = new TextView(activity);
        subhead.setText(title);
        subhead.setTextColor(Palette.DIM_WHITE_BLUE);
        subhead.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subhead.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        subhead.setLetterSpacing(0.1f);
        subhead.setPadding(activity.dpToPx(4), activity.dpToPx(topPaddingDp),
                           activity.dpToPx(4), activity.dpToPx(6));
        panel.addView(subhead);

        if (tallies == null || tallies.isEmpty()) {
            panel.addView(makeEmptyPlaceholder(activity, emptyText));
            return;
        }

        TagFlowLayout flow = new TagFlowLayout(activity, activity.dpToPx(6));
        for (PlayerStats.Tally t : tallies) {
            flow.addView(makeTagPill(activity, t));
        }
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        fp.setMargins(activity.dpToPx(4), 0, activity.dpToPx(4), 0);
        panel.addView(flow, fp);
    }

    /** Shown inside a section whose content list is empty, so every section's
     *  header is always visible regardless of player progress. */
    private static TextView makeEmptyPlaceholder(BrogueActivity activity, int text) {
        TextView empty = new TextView(activity);
        empty.setText(text);
        empty.setTextColor(Palette.DISABLED_TEXT);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        empty.setTypeface(Typeface.MONOSPACE);
        empty.setPadding(activity.dpToPx(4), activity.dpToPx(2),
                         activity.dpToPx(4), activity.dpToPx(2));
        return empty;
    }

    /** This mode's last ten games, newest first. Each row opens the run's
     *  summary, where its seed can be played again. */
    private void addRecentGamesSection(LinearLayout panel) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(activity.dpToPx(44));
        header.setPadding(activity.dpToPx(12), activity.dpToPx(8),
                          activity.dpToPx(8), activity.dpToPx(8));

        GradientDrawable headerBg = new GradientDrawable();
        headerBg.setShape(GradientDrawable.RECTANGLE);
        headerBg.setCornerRadius(
            activity.dpToPx(UiStyle.MENU_ITEM_CORNER_RADIUS_DP));
        headerBg.setColor(Palette.ITEM_BG);
        headerBg.setStroke(1, Palette.BORDER_DIM);
        header.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.RIPPLE_GLOW), headerBg, null));

        TextView subhead = new TextView(activity);
        subhead.setText(R.string.stats_recent_games);
        subhead.setTextColor(Palette.DIM_WHITE_BLUE);
        subhead.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subhead.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        subhead.setLetterSpacing(0.1f);
        header.addView(subhead, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Points right when collapsed and is turned down when expanded.
        ImageView chevron = new ImageView(activity);
        chevron.setImageResource(R.drawable.ic_chevron);
        chevron.setColorFilter(Palette.PALE_BLUE);
        chevron.setScaleType(ImageView.ScaleType.FIT_CENTER);
        header.addView(chevron, new LinearLayout.LayoutParams(
            activity.dpToPx(32), activity.dpToPx(14)));
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.setMargins(activity.dpToPx(4), activity.dpToPx(16),
                                activity.dpToPx(4), 0);
        panel.addView(header, headerParams);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setVisibility(recentGamesExpanded ? View.VISIBLE : View.GONE);
        chevron.setRotation(recentGamesExpanded ? 90f : 0f);

        List<JSONObject> games = RecentGamesStore.get(activity).games(
            selectedVariant, selectedDifficulty);
        if (games.isEmpty()) {
            content.addView(makeEmptyPlaceholder(activity, R.string.stats_none_yet));
        } else {
            // Month and day in the order the locale uses, e.g. "Oct 17".
            Locale locale = Locale.getDefault();
            SimpleDateFormat dateFormat = new SimpleDateFormat(
                android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"),
                locale);
            // Every glyph gets the width of the widest outcome glyph in this
            // graphics mode, so the text after them always starts at the
            // same spot while the gap stays as small as it can be.
            int iconWidth = 0;
            for (String outcome : new String[] { RecentGames.OUTCOME_DIED,
                    RecentGames.OUTCOME_QUIT, RecentGames.OUTCOME_ESCAPED }) {
                iconWidth = Math.max(iconWidth, outcomeIconWidth(makeOutcomeIcon(outcome)));
            }
            for (JSONObject game : games) {
                content.addView(makeRecentGameRow(game, iconWidth, dateFormat));
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
            recentGamesExpanded = content.getVisibility() != View.VISIBLE;
            content.setVisibility(recentGamesExpanded ? View.VISIBLE : View.GONE);
            chevron.setRotation(recentGamesExpanded ? 90f : 0f);
        });
    }

    /** One line: a glyph for how the run ended, centred in a box iconWidth
     *  wide, its depth, and on the right the day it ended. */
    private View makeRecentGameRow(JSONObject game, int iconWidth,
                                   SimpleDateFormat dateFormat) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // The same space on both sides of the glyph's box.
        int glyphGap = activity.dpToPx(6);
        row.setPadding(glyphGap, activity.dpToPx(8),
                       activity.dpToPx(12), activity.dpToPx(8));

        // A missing glyph still takes its box, so the text stays in line.
        View icon = makeOutcomeIcon(RecentGames.outcome(game));
        if (icon == null) icon = new View(activity);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
            iconWidth, icon instanceof TextView
                ? LinearLayout.LayoutParams.WRAP_CONTENT
                : activity.dpToPx(OUTCOME_ICON_SIZE_DP));
        iconParams.rightMargin = glyphGap;
        row.addView(icon, iconParams);

        // A win ends at the surface or the bottom, so show how deep it went.
        int depth = RecentGames.isVictory(game)
            ? game.optInt("deepestDepth") : game.optInt("depth");
        TextView depthView = new TextView(activity);
        String outcome = RecentGames.outcome(game);
        String prefix = RecentGames.OUTCOME_QUIT.equals(outcome) ? "Abandoned at "
            : RecentGames.isVictory(game) ? "" : "Died at ";
        depthView.setText(prefix + "Depth " + depth);
        depthView.setTextColor(Palette.GHOST_WHITE);
        depthView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        depthView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.addView(depthView, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        String date = "";
        long endedAt = game.optLong("endedAt", 0L);
        if (endedAt > 0) {
            date = dateFormat.format(new Date(endedAt));
            TextView dateView = new TextView(activity);
            dateView.setText(date);
            dateView.setTextColor(Palette.PALE_BLUE);
            dateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            dateView.setTypeface(Typeface.MONOSPACE);
            row.addView(dateView);
        }
        // The icon is hidden from screen readers, so say how the run ended.
        row.setContentDescription(activity.getString(RecentGames.titleRes(game))
            + ", depth " + depth + (date.isEmpty() ? "" : ", " + date));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(activity.dpToPx(UiStyle.MENU_ITEM_CORNER_RADIUS_DP));
        bg.setColor(Palette.ITEM_BG);
        // Bordered like the other tappable rows here.
        bg.setStroke(1, Palette.BORDER_DIM);
        row.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.RIPPLE_GLOW), bg, null));

        row.setOnClickListener(v -> activity.recentGameModal.show(game));

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, activity.dpToPx(2), 0, activity.dpToPx(2));
        row.setLayoutParams(p);
        return row;
    }

    /** The game's own glyph for how the run ended, drawn from the atlas like
     *  the monster pills: the amulet for a win, the doorway for an abandoned
     *  run and bones for a death. The text font has only a comma for bones,
     *  so in text mode a death is a dagger instead. Glyphs are cropped to
     *  their artwork, since a text-font cell is mostly empty space. */
    private View makeOutcomeIcon(String outcome) {
        boolean useTileGlyph = GameSettings.useTileCreatureAndItemGlyphs(activity);
        int color = outcomeColor(outcome);
        if (!useTileGlyph && RecentGames.OUTCOME_DIED.equals(outcome)) {
            TextView dagger = new TextView(activity);
            dagger.setText(String.valueOf((char) 0x2020));
            dagger.setTextColor(color);
            dagger.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            dagger.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            dagger.setGravity(Gravity.CENTER);
            dagger.setIncludeFontPadding(false);
            dagger.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            return dagger;
        }

        int[] info = activity.nativeRunOutcomeGlyph(outcome);
        if (info == null || info.length < 2) return null;
        int iconSize = activity.dpToPx(OUTCOME_ICON_SIZE_DP);
        Bitmap mask = TileAtlasMask.get(activity, useTileGlyph ? info[0] : info[1],
                                        iconSize, iconSize, true);
        if (mask == null) return null;

        ImageView icon = new ImageView(activity);
        BitmapDrawable drawable = new BitmapDrawable(activity.getResources(), mask);
        // The mask is already at its final pixel size; see MonsterTileIcon.
        drawable.setFilterBitmap(false);
        icon.setImageDrawable(drawable);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    /** The width an outcome glyph needs, in pixels. */
    private static int outcomeIconWidth(View icon) {
        if (icon instanceof ImageView) {
            return ((BitmapDrawable) ((ImageView) icon).getDrawable()).getBitmap().getWidth();
        }
        if (icon instanceof TextView) {
            TextView text = (TextView) icon;
            return (int) Math.ceil(text.getPaint().measureText(text.getText().toString()));
        }
        return 0;
    }

    /** White for a death, gold for a victory, purple for a mastery win and
     *  grey for an abandoned run. */
    private static int outcomeColor(String outcome) {
        switch (outcome) {
            case RecentGames.OUTCOME_ESCAPED:  return Color.rgb(230, 190, 80);
            case RecentGames.OUTCOME_MASTERED: return Palette.GOOD_MAGIC;
            case RecentGames.OUTCOME_QUIT:     return Palette.DIM_BLUE_GRAY;
            default:                           return Palette.GHOST_WHITE;
        }
    }

    private static View makeTagPill(BrogueActivity activity, PlayerStats.Tally t) {
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);

        TextView count = new TextView(activity);
        count.setText(t.count + "\u00D7");
        count.setTextColor(Palette.DIM_WHITE_BLUE);
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        count.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        countParams.rightMargin = activity.dpToPx(4);
        container.addView(count, countParams);

        ImageView icon = MonsterTileIcon.makeView(activity, t.label);
        if (icon != null) {
            int iconSize = activity.dpToPx(MonsterTileIcon.sizeDp(activity));
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                iconSize, iconSize);
            iconParams.rightMargin = activity.dpToPx(4);
            container.addView(icon, iconParams);
            int verticalPadding = GameSettings.useTileCreatureAndItemGlyphs(activity)
                ? activity.dpToPx(4) : 0;
            container.setPadding(activity.dpToPx(6), verticalPadding,
                                 activity.dpToPx(10), verticalPadding);
        } else {
            container.setPadding(activity.dpToPx(10), activity.dpToPx(5),
                                 activity.dpToPx(10), activity.dpToPx(5));
        }

        TextView label = new TextView(activity);
        label.setText(capitalize(t.label));
        label.setTextColor(Palette.GHOST_WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTypeface(Typeface.MONOSPACE);
        container.addView(label);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(activity.dpToPx(8));
        bg.setColor(Palette.ITEM_BG);
        container.setBackground(bg);

        return container;
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

    private View makePlaytimeCard() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(activity.dpToPx(10), activity.dpToPx(10),
                        activity.dpToPx(10), activity.dpToPx(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(activity.dpToPx(UiStyle.MENU_ITEM_CORNER_RADIUS_DP));
        bg.setColor(Palette.ITEM_BG);
        card.setBackground(bg);

        TextView time = new TextView(activity);
        time.setTextColor(Palette.GHOST_WHITE);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        time.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        time.setGravity(Gravity.CENTER);
        card.addView(time, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView label = new TextView(activity);
        label.setText("Total Playtime");
        label.setTextColor(Palette.PALE_BLUE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        label.setTypeface(Typeface.MONOSPACE);
        label.setLetterSpacing(0.05f);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, activity.dpToPx(2), 0, 0);
        card.addView(label, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        Runnable ticker = new Runnable() {
            @Override
            public void run() {
                time.setText(formatPlaytime(activity.totalPlaytimeMillis()));
                if (time.isAttachedToWindow()) time.postDelayed(this, 1000L);
            }
        };
        time.setText(formatPlaytime(activity.totalPlaytimeMillis()));
        time.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                time.removeCallbacks(ticker);
                time.postDelayed(ticker, 1000L);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                time.removeCallbacks(ticker);
            }
        });

        return card;
    }

    static String formatPlaytime(long millis) {
        long totalMinutes = Math.max(0L, millis) / 60_000L;
        return (totalMinutes / 60L) + "h " + (totalMinutes % 60L) + "m";
    }

    private void addStatCell(LinearLayout grid, int value, String label) {
        ModalChrome.addStatCell(activity, grid, String.valueOf(value), label);
    }
}
