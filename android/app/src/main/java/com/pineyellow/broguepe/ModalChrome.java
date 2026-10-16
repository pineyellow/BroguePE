package com.pineyellow.broguepe;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Static chrome helpers shared by title-screen modals (Custom Seed,
 *  Seed Detail). No state; each call takes the {@link BrogueActivity}. */
final class ModalChrome {

    private ModalChrome() {}

    /** Attaches a translucent backdrop to {@code root} (tap to popModal),
     *  creates the panel with background + header + dim separator, and
     *  returns the panel for callers to append content to.
     *  The caller is responsible for the scroll wrap / addContentView /
     *  animation — see {@link #present(BrogueActivity, FrameLayout, LinearLayout)}. */
    static LinearLayout buildPanel(BrogueActivity activity, FrameLayout root, String titleUpper) {
        return buildPanel(activity, root, titleUpper, 12);
    }

    static LinearLayout buildPanel(BrogueActivity activity, FrameLayout root, String titleUpper,
            int contentTopGapDp) {
        View backdrop = new View(activity);
        backdrop.setBackgroundColor(Color.argb(160, 0, 0, 0));
        backdrop.setOnClickListener(v -> activity.modalStack.pop());
        root.addView(backdrop, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout panel = makePanel(activity);
        panel.addView(makeTitle(activity, titleUpper));
        panel.addView(makeDimSeparator(activity),
                      separatorParams(activity, 8, 8, 0, contentTopGapDp));
        return panel;
    }

    /** Empty vertical panel with the standard modal background and padding. */
    static LinearLayout makePanel(BrogueActivity activity) {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = activity.dpToPx(16);
        panel.setPadding(pad, pad, pad, pad);

        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setShape(GradientDrawable.RECTANGLE);
        panelBg.setCornerRadius(activity.dpToPx(UiStyle.PANEL_CORNER_RADIUS_DP));
        panelBg.setColor(Palette.INVENTORY_BG);
        panelBg.setStroke(1, Palette.BORDER_DIM);
        panel.setBackground(panelBg);
        panel.setElevation(activity.dpToPx(12));
        return panel;
    }

    static TextView makeTitle(BrogueActivity activity, String titleUpper) {
        TextView header = new TextView(activity);
        header.setText(titleUpper);
        header.setTextColor(Palette.DIM_WHITE_BLUE);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        header.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.setLetterSpacing(0.2f);
        header.setGravity(Gravity.CENTER);
        header.setPadding(0, activity.dpToPx(4), 0, activity.dpToPx(8));
        return header;
    }

    /** Boxed number over a caption, laid out as one of a row of equal cells. */
    static void addStatCell(BrogueActivity activity, LinearLayout grid,
                            String value, String label) {
        LinearLayout cell = new LinearLayout(activity);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(activity.dpToPx(6), activity.dpToPx(10),
                        activity.dpToPx(6), activity.dpToPx(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(activity.dpToPx(UiStyle.MENU_ITEM_CORNER_RADIUS_DP));
        bg.setColor(Palette.ITEM_BG);
        cell.setBackground(bg);

        TextView number = new TextView(activity);
        number.setText(value);
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

    /** Label on the left, value on the right. */
    static void addKeyValueRow(BrogueActivity activity, LinearLayout panel,
                               String key, String value, int valueColor) {
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
        valueView.setTextColor(valueColor);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        valueView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.addView(valueView);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = activity.dpToPx(2);
        panel.addView(row, p);
    }

    static View makeDimSeparator(BrogueActivity activity) {
        View sep = new View(activity);
        sep.setBackgroundColor(Palette.BORDER_DIM);
        return sep;
    }

    static LinearLayout.LayoutParams separatorParams(BrogueActivity activity,
            int leftDp, int rightDp, int topDp, int bottomDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        p.setMargins(activity.dpToPx(leftDp), activity.dpToPx(topDp),
                     activity.dpToPx(rightDp), activity.dpToPx(bottomDp));
        return p;
    }

    /** Wraps the panel in a ScrollView, attaches at center at ~85% width, calls
     *  addContentView, and runs the standard fade+scale-in. For modals needing
     *  a specialized scrolling container, inline the tail instead. */
    static void present(BrogueActivity activity, FrameLayout root, LinearLayout panel) {
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(panel);

        int panelWidth = Math.min(activity.dpToPx(340),
            (int)(activity.getResources().getDisplayMetrics().widthPixels * 0.85f));
        int maxHeight = (int)(activity.getResources().getDisplayMetrics().heightPixels * 0.85f);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
            panelWidth, Math.min(maxHeight, FrameLayout.LayoutParams.WRAP_CONTENT),
            Gravity.CENTER);
        root.addView(scroll, panelParams);

        activity.addContentView(root, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        animateIn(panel);
    }

    static void animateIn(LinearLayout panel) {
        panel.setAlpha(0f);
        panel.setScaleX(0.94f);
        panel.setScaleY(0.94f);
        panel.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(220)
            .setInterpolator(new DecelerateInterpolator(1.5f))
            .start();
    }

    /** Panel size (width, max height) shared by custom
     *  SwipeRefreshLayout-wrapped tail. */
    static FrameLayout.LayoutParams centeredPanelParams(BrogueActivity activity) {
        int panelWidth = Math.min(activity.dpToPx(340),
            (int)(activity.getResources().getDisplayMetrics().widthPixels * 0.85f));
        int maxHeight = (int)(activity.getResources().getDisplayMetrics().heightPixels * 0.85f);
        return new FrameLayout.LayoutParams(
            panelWidth, Math.min(maxHeight, FrameLayout.LayoutParams.WRAP_CONTENT),
            Gravity.CENTER);
    }
}
