package com.pineyellow.broguepe;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only "Discovered Items" screen, opened from the eye icon in the
 *  inventory header. Lists every item kind per category in a single column:
 *  identified kinds in white beside their category's item glyph, as Brogue CE
 *  draws them, and undiscovered kinds dimmed with their good/bad
 *  magic indicator and their spawn-probability among the still-unknown kinds.
 *  The inventory icon in the header sends the player back to the inventory;
 *  tapping the backdrop closes the screen like any other menu. */
final class DiscoveriesOverlay {

    /** The height of a tile item's whole cell. Item sprites fill less of their
     *  cell than a text glyph does, so their cell is drawn taller than a text
     *  glyph's to bring them up near the text's size. */
    private static final int TILE_CELL_DP = 28;

    private final BrogueActivity activity;
    private final FrameLayout host;
    // True only while the engine is waiting on this screen. A second tap on
    // the inventory icon would otherwise reach the inventory as item 'i'.
    private boolean acceptingInput;

    DiscoveriesOverlay(BrogueActivity activity, FrameLayout host) {
        this.activity = activity;
        this.host = host;
    }

    /** Claims the screen's single answer to the engine. Every later tap gets
     *  false until the engine shows the screen again. */
    private boolean claimInput() {
        if (!acceptingInput) return false;
        acceptingInput = false;
        return true;
    }

    /** Android Back closes the screen, the same as a tap outside it. Returns
     *  false when the engine is not waiting on this screen. */
    boolean handleBack() {
        if (host.getVisibility() != View.VISIBLE || !claimInput()) return false;
        KeyInput.sendKey(activity, KeyEvent.KEYCODE_ESCAPE);
        return true;
    }

    void show(final String json) {
        activity.runOnUiThread(() -> {
            acceptingInput = false;

            // Cancel any in-flight hide animation (e.g. the inventory sliding
            // out) so its withEndAction doesn't wipe the content we add here.
            for (int i = 0; i < host.getChildCount(); i++) {
                host.getChildAt(i).animate().cancel();
            }
            host.removeAllViews();

            try {
                JSONArray sections = new JSONObject(json).getJSONArray("sections");

                View backdrop = new View(activity);
                backdrop.setBackgroundColor(Color.argb(140, 0, 0, 0));
                backdrop.setOnClickListener(v -> {
                    if (claimInput()) KeyInput.sendKey(activity, KeyEvent.KEYCODE_ESCAPE);
                });
                host.addView(backdrop, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
                backdrop.setAlpha(0f);
                backdrop.animate().alpha(1f).setDuration(280).start();

                ScrollView scrollView = new ScrollView(activity);
                scrollView.setFillViewport(false);
                scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
                scrollView.setFadingEdgeLength(activity.dpToPx(24));
                scrollView.setVerticalFadingEdgeEnabled(true);

                LinearLayout panel = new LinearLayout(activity);
                panel.setOrientation(LinearLayout.VERTICAL);
                int pad = activity.dpToPx(8);
                panel.setPadding(pad, pad, pad, pad);

                GradientDrawable panelBg = new GradientDrawable();
                panelBg.setShape(GradientDrawable.RECTANGLE);
                panelBg.setCornerRadii(new float[]{
                    activity.dpToPx(UiStyle.PANEL_CORNER_RADIUS_DP),
                    activity.dpToPx(UiStyle.PANEL_CORNER_RADIUS_DP), 0, 0, 0, 0,
                    activity.dpToPx(UiStyle.PANEL_CORNER_RADIUS_DP),
                    activity.dpToPx(UiStyle.PANEL_CORNER_RADIUS_DP)});
                panelBg.setColor(Palette.INVENTORY_BG);
                panelBg.setStroke(1, Palette.BORDER_DIM);
                scrollView.setBackground(panelBg);

                // The button's touch area reaches above its box, so pull the
                // header up by that much: the box then sits the panel's
                // padding from the top edge, as it does from the right.
                LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
                headerParams.topMargin = -activity.dpToPx(
                    (UiStyle.HEADER_ICON_BUTTON_HEIGHT_DP - UiStyle.HEADER_ICON_BOX_HEIGHT_DP) / 2f);
                panel.addView(makeHeader(), headerParams);

                for (int s = 0; s < sections.length(); s++) {
                    JSONObject section = sections.getJSONObject(s);
                    if (s > 0) panel.addView(makeSectionDivider());
                    panel.addView(makeSectionLabel(section.optString("label", "")));
                    JSONArray items = section.getJSONArray("items");
                    for (int i = 0; i < items.length(); i++) {
                        panel.addView(makeItemRow(items.getJSONObject(i), section));
                    }
                }

                scrollView.addView(panel);

                int panelWidth = Math.min(activity.dpToPx(340),
                    (int)(activity.getResources().getDisplayMetrics().widthPixels * 0.75f));

                FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
                    panelWidth, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END);
                scrollParams.setMargins(0, activity.dpToPx(8),
                    activity.dpToPx(BrogueActivity.EDGE_SAFE_DP),
                    activity.dpToPx(BrogueActivity.EDGE_SAFE_DP));

                host.addView(scrollView, scrollParams);
                host.setVisibility(View.VISIBLE);
                acceptingInput = true;

                scrollView.setTranslationX(panelWidth);
                scrollView.animate()
                    .translationX(0)
                    .setDuration(280)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .start();

            } catch (Exception e) {
                KeyInput.sendKey(activity, KeyEvent.KEYCODE_ESCAPE);
            }
        });
    }

    void hide() {
        activity.runOnUiThread(() -> {
            acceptingInput = false;
            if (host.getChildCount() < 2) {
                host.setVisibility(View.GONE);
                host.removeAllViews();
                return;
            }
            View backdrop = host.getChildAt(0);
            View scrollView = host.getChildAt(1);

            scrollView.animate()
                .translationX(scrollView.getWidth())
                .setDuration(200)
                .setInterpolator(new DecelerateInterpolator())
                .start();

            backdrop.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction(() -> {
                    host.setVisibility(View.GONE);
                    host.removeAllViews();
                })
                .start();
        });
    }

    /** Title row with the inventory icon right-aligned; tapping it sends the
     *  inventory key so the engine swaps back to the inventory panel. */
    private View makeHeader() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(activity);
        title.setText(R.string.discoveries_title);
        title.setTextColor(Palette.DIM_WHITE_BLUE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setLetterSpacing(0.15f);
        title.setPadding(activity.dpToPx(4), activity.dpToPx(2), 0, activity.dpToPx(4));
        row.addView(title, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton inventoryBtn = makeHeaderIcon(R.drawable.ic_money_bag, "Back to inventory");
        inventoryBtn.setOnClickListener(v -> {
            if (claimInput()) KeyInput.sendChar(activity, 'i');
        });
        int headerButtonWidth = activity.dpToPx(UiStyle.HEADER_ICON_BOX_WIDTH_DP);
        int headerButtonHeight = activity.dpToPx(UiStyle.HEADER_ICON_BUTTON_HEIGHT_DP);
        row.addView(inventoryBtn, new LinearLayout.LayoutParams(
            headerButtonWidth, headerButtonHeight));

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(row);

        View sep = new View(activity);
        sep.setBackgroundColor(Palette.BORDER_DIM);
        LinearLayout.LayoutParams sepP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        // Even space between the header button's box, the divider and the
        // first section label (which adds its own padding).
        sepP.setMargins(activity.dpToPx(4), activity.dpToPx(4),
                        activity.dpToPx(4), activity.dpToPx(3));
        column.addView(sep, sepP);

        return column;
    }

    private TextView makeSectionLabel(String label) {
        TextView view = new TextView(activity);
        view.setText(activity.getString(R.string.discoveries_section, label));
        view.setTextColor(Palette.PALE_BLUE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        view.setLetterSpacing(0.1f);
        view.setPadding(activity.dpToPx(4), activity.dpToPx(2),
                        0, activity.dpToPx(4));
        return view;
    }

    private View makeSectionDivider() {
        View divider = new View(activity);
        divider.setBackgroundColor(Palette.BORDER_DIM);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.setMargins(activity.dpToPx(4), activity.dpToPx(8),
            activity.dpToPx(4), activity.dpToPx(6));
        divider.setLayoutParams(params);
        return divider;
    }

    /** The section's item glyph from the tile or the text sheet, as the
     *  inventory picks it, or null if the atlas has none. Both are drawn as a
     *  whole cell, so the five sprites keep their sizes relative to each other;
     *  trimming would stretch every one of them to fill the box. */
    private ImageView makeCategoryIcon(JSONObject section, boolean useTileGlyph,
                                       int cellDp) {
        ImageView icon = MonsterTileIcon.makeGlyphView(activity,
            section.optInt(useTileGlyph ? "tileGlyph" : "textGlyph", -1), false, cellDp,
            Color.rgb(section.optInt("iconRed", 255), section.optInt("iconGreen", 242),
                      section.optInt("iconBlue", 0)));
        if (icon == null) return null;
        // The mask is already at its final size, so the wider slot must not scale it.
        icon.setScaleType(ImageView.ScaleType.CENTER);
        // The lift lines a text glyph up with the name beside it. A sprite already
        // sits in the middle of its cell, and lifted it sat high.
        if (useTileGlyph) icon.setTranslationY(0);
        return icon;
    }

    private View makeItemRow(JSONObject item, JSONObject section) {
        boolean identified = item.optBoolean("identified", false);
        int polarity = item.optInt("polarity", 0);
        int pct = item.optInt("pct", 0);
        String name = item.optString("name", "???");

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(activity.dpToPx(6), activity.dpToPx(4),
                       activity.dpToPx(6), activity.dpToPx(4));

        // Fixed-width slot so names align across rows: the good/bad magic
        // indicator while a kind is unknown, its category's item glyph once known.
        int slotWidth = activity.dpToPx(18);
        boolean useTileGlyph = GameSettings.useTileCreatureAndItemGlyphs(activity);
        int cellDp = useTileGlyph ? TILE_CELL_DP : MonsterTileIcon.sizeDp(false);
        ImageView icon = identified ? makeCategoryIcon(section, useTileGlyph, cellDp) : null;
        if (icon != null) {
            // Centered on the row without adding to its height, so identified rows
            // stay as tall as the rest. The glyph's cell is taller than the row,
            // but the glyph inside it fits.
            int iconHeight = activity.dpToPx(cellDp);
            LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(slotWidth, iconHeight);
            iconParams.topMargin = -iconHeight / 2;
            iconParams.bottomMargin = -iconHeight / 2;
            row.setClipToPadding(false);
            row.addView(icon, iconParams);
        } else {
            TextView glyph = new TextView(activity);
            glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            glyph.setTypeface(Typeface.MONOSPACE);
            glyph.setGravity(Gravity.CENTER);
            if (!identified && polarity != 0) {
                glyph.setText(polarity > 0 ? Palette.GOOD_MAGIC_GLYPH : Palette.BAD_MAGIC_GLYPH);
                glyph.setTextColor(polarity > 0 ? Palette.GOOD_MAGIC : Palette.BAD_MAGIC);
            }
            row.addView(glyph, new LinearLayout.LayoutParams(
                slotWidth, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        TextView nameView = new TextView(activity);
        nameView.setText(name);
        nameView.setTextColor(identified ? Palette.GHOST_WHITE
                                          : Color.argb(160, 130, 125, 145));
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        nameView.setTypeface(Typeface.MONOSPACE);
        nameView.setPadding(activity.dpToPx(4), 0, 0, 0);
        row.addView(nameView, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (!identified && pct > 0) {
            TextView pctView = new TextView(activity);
            pctView.setText(activity.getString(R.string.discovery_chance, pct));
            pctView.setTextColor(Color.argb(140, 110, 105, 125));
            pctView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            pctView.setTypeface(Typeface.MONOSPACE);
            pctView.setPadding(activity.dpToPx(6), 0, activity.dpToPx(2), 0);
            row.addView(pctView);
        }

        return row;
    }

    // This listener only animates; returning false preserves normal click handling.
    @SuppressLint("ClickableViewAccessibility")
    private ImageButton makeHeaderIcon(int drawableRes, String contentDesc) {
        ImageButton btn = new ImageButton(activity);
        btn.setImageResource(drawableRes);
        btn.setColorFilter(Palette.PALE_BLUE);
        btn.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(activity.dpToPx(UiStyle.MENU_ITEM_CORNER_RADIUS_DP));
        bg.setColor(Palette.ITEM_BG);
        bg.setStroke(1, Palette.BORDER_DIM);
        // The box is drawn shorter than the button, so the touch area is taller
        // than what shows. The ripple stays inside the box.
        int width = activity.dpToPx(UiStyle.HEADER_ICON_BOX_WIDTH_DP);
        int height = activity.dpToPx(UiStyle.HEADER_ICON_BUTTON_HEIGHT_DP);
        int insetY = (height - activity.dpToPx(UiStyle.HEADER_ICON_BOX_HEIGHT_DP)) / 2;
        btn.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.RIPPLE_GLOW),
            new InsetDrawable(bg, 0, insetY, 0, insetY), null));
        btn.setStateListAnimator(null);
        btn.setElevation(0);
        btn.setContentDescription(contentDesc);
        btn.setMinimumWidth(width);
        btn.setMinimumHeight(height);
        int glyphPadX = activity.dpToPx(
            (UiStyle.HEADER_ICON_BOX_WIDTH_DP - UiStyle.HEADER_ICON_GLYPH_DP) / 2f);
        int glyphPadY = insetY + activity.dpToPx(
            (UiStyle.HEADER_ICON_BOX_HEIGHT_DP - UiStyle.HEADER_ICON_GLYPH_DP) / 2f);
        btn.setPadding(glyphPadX, glyphPadY, glyphPadX, glyphPadY);
        btn.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(60).start();
            } else if (e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
            }
            return false;
        });
        return btn;
    }
}
