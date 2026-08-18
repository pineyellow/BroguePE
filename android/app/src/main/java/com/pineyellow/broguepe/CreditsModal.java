package com.pineyellow.broguepe;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Credits overlay, layered above the start menu via the modal stack. */
final class CreditsModal {

    private static final String BROGUE_URL =
        "https://sites.google.com/site/broguegame/";
    private static final String BROGUE_CE_URL =
        "https://github.com/tmewett/BrogueCE";
    private static final String BROGUE_CE_ANDROID_URL =
        "https://github.com/tyrannotorus/c-brogue-ce-android";
    private static final String BROGUE_PE_URL =
        "https://github.com/pineyellow/BroguePE/";
    private static final String LICENSES_URL =
        "https://github.com/pineyellow/BroguePE#license";

    private final BrogueActivity activity;

    CreditsModal(BrogueActivity activity) {
        this.activity = activity;
    }

    void show() {
        activity.modalStack.push(this::build);
    }

    private View build() {
        FrameLayout root = new FrameLayout(activity);

        View backdrop = new View(activity);
        backdrop.setBackgroundColor(Color.argb(160, 0, 0, 0));
        backdrop.setOnClickListener(v -> activity.modalStack.pop());
        root.addView(backdrop, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

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

        TextView header = new TextView(activity);
        header.setText(R.string.credits_title);
        header.setTextColor(Palette.DIM_WHITE_BLUE);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        header.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.setLetterSpacing(0.2f);
        header.setGravity(Gravity.CENTER);
        header.setPadding(0, activity.dpToPx(4), 0, activity.dpToPx(8));
        panel.addView(header);

        View sep = new View(activity);
        sep.setBackgroundColor(Palette.BORDER_DIM);
        LinearLayout.LayoutParams sepP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        sepP.setMargins(activity.dpToPx(8), 0, activity.dpToPx(8), activity.dpToPx(12));
        panel.addView(sep, sepP);

        addCredit(panel,
            "Brogue",
            "Original game by Brian Walker (Pender)",
            BROGUE_URL);
        addCredit(panel,
            "Brogue: Community Edition",
            "tmewett and the Brogue CE contributors",
            BROGUE_CE_URL);
        addCredit(panel,
            "Brogue CE Android",
            "Base Android port by tyrannotorus",
            BROGUE_CE_ANDROID_URL);
        addCredit(panel,
            "Brogue PE " + BuildConfig.VERSION_NAME,
            "Remastered Android Port",
            BROGUE_PE_URL);
        addCredit(panel,
            "Licenses",
            "GNU AGPL v3; tiles under CC BY-SA 4.0",
            LICENSES_URL);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(panel);

        int panelWidth = Math.min(activity.dpToPx(320),
            (int)(activity.getResources().getDisplayMetrics().widthPixels * 0.8f));
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
            panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(scroll, panelParams);

        activity.addContentView(root, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        panel.setAlpha(0f);
        panel.setScaleX(0.94f);
        panel.setScaleY(0.94f);
        panel.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(220)
            .setInterpolator(new DecelerateInterpolator(1.5f))
            .start();

        return root;
    }

    private void addCredit(LinearLayout panel, String title, String line, String url) {
        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(activity.dpToPx(10), activity.dpToPx(8),
                         activity.dpToPx(10), activity.dpToPx(8));
        block.setClickable(true);
        block.setFocusable(true);
        block.setContentDescription("Open " + title);

        GradientDrawable blockBg = new GradientDrawable();
        blockBg.setShape(GradientDrawable.RECTANGLE);
        blockBg.setCornerRadius(activity.dpToPx(UiStyle.MENU_ITEM_CORNER_RADIUS_DP));
        blockBg.setColor(Palette.ITEM_BG);
        blockBg.setStroke(1, Palette.BORDER_DIM);
        block.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.RIPPLE_GLOW), blockBg, null));
        block.setOnClickListener(v -> openUrl(url));

        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(Palette.GHOST_WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        titleView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        block.addView(titleView);

        TextView lineView = new TextView(activity);
        lineView.setText(line);
        lineView.setTextColor(Palette.PALE_BLUE);
        lineView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        lineView.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams lineP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lineP.setMargins(0, activity.dpToPx(2), 0, activity.dpToPx(2));
        block.addView(lineView, lineP);

        LinearLayout.LayoutParams blockP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        blockP.setMargins(0, activity.dpToPx(4), 0, activity.dpToPx(4));
        panel.addView(block, blockP);
    }

    private void openUrl(String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "No browser available", Toast.LENGTH_SHORT).show();
        }
    }
}
