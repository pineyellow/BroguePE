package com.pineyellow.broguepe;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DecimalFormat;

/** In-game Settings panel — bottom-right card launched from the hamburger
 *  submenu. Toggles mirror C-side state by sending the corresponding keystroke
 *  (e.g. '\\' for hide color effects) so the engine and SharedPreferences stay
 *  in sync. Also exposes seed copy. */
final class SettingsPanel {

    private static final String[] GRAPHICS_MODE_LABELS = {
        "Graphics: ASCII", "Graphics: Tiles", "Graphics: Hybrid"
    };

    private final BrogueActivity activity;
    private final FrameLayout host;

    SettingsPanel(BrogueActivity activity, FrameLayout host) {
        this.activity = activity;
        this.host = host;
    }

    void show() {
        host.removeAllViews();

        View backdrop = new View(activity);
        backdrop.setBackgroundColor(Color.argb(140, 0, 0, 0));
        backdrop.setOnClickListener(v -> hide());
        host.addView(backdrop, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        backdrop.setAlpha(0f);
        backdrop.animate().alpha(1f).setDuration(280).start();

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = activity.dpToPx(10);
        panel.setPadding(pad, pad, pad, pad);

        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setShape(GradientDrawable.RECTANGLE);
        panelBg.setCornerRadii(new float[]{
            activity.dpToPx(4), activity.dpToPx(4), 0, 0, 0, 0,
            activity.dpToPx(4), activity.dpToPx(4)});
        panelBg.setColor(Palette.INVENTORY_BG);
        panelBg.setStroke(1, Palette.BORDER_DIM);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setBackground(panelBg);
        scrollView.addView(panel);

        TextView header = new TextView(activity);
        header.setText(R.string.settings_title);
        header.setTextColor(Palette.DIM_WHITE_BLUE);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.setLetterSpacing(0.15f);
        header.setPadding(activity.dpToPx(4), activity.dpToPx(2), 0, activity.dpToPx(4));
        panel.addView(header);

        View headerSep = new View(activity);
        GradientDrawable sepGrad = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{ Palette.DIM_BLUE_GRAY, Palette.DIM_WHITE_BLUE, Palette.DIM_BLUE_GRAY });
        headerSep.setBackground(sepGrad);
        LinearLayout.LayoutParams hSepP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        hSepP.setMargins(activity.dpToPx(4), 0, activity.dpToPx(4), activity.dpToPx(8));
        panel.addView(headerSep, hSepP);

        // Game-state toggles — sendChar notifies the engine of the change.
        boolean dpadEnabled = GameSettings.getBool(activity, DPadOverlay.PREF_ENABLED, true);
        addGameToggle(panel, "Hide Color Effects", "hide_color_effects", '\\');
        addGameToggle(panel, "Display Stealth Range", "display_stealth_range", ']');
        addAppToggle(panel, "Enable DPAD", DPadOverlay.PREF_ENABLED, true,
            enabled -> {
                activity.setDpadEnabled(enabled);
                show();
            });
        if (dpadEnabled) {
            addFloatSetting(panel, "DPAD X", DPadOverlay.PREF_OFFSET_X, 0f, null,
                "DPAD X offset in dp (+ goes right, - goes left)",
                activity::applyDpadSettings);
            addFloatSetting(panel, "DPAD Y", DPadOverlay.PREF_OFFSET_Y, 0f, null,
                "DPAD Y offset in dp (+ goes up, - goes down)",
                activity::applyDpadSettings);
            addFloatSetting(panel, "DPAD Size", DPadOverlay.PREF_SIZE, DPadOverlay.DEFAULT_SIZE, 0.25f,
                "DPAD size multiplier",
                activity::applyDpadSettings);
            addFloatSetting(panel, "DPAD Button Width", DPadOverlay.PREF_BUTTON_WIDTH,
                DPadOverlay.DEFAULT_BUTTON_WIDTH, 0.25f,
                "DPAD button width ratio (1 = square buttons)",
                activity::applyDpadSettings);
        }
        addGraphicsModeCycler(panel);

        addSeparator(panel);

        addAction(activity, panel, "Copy Seed to Clipboard", v -> {
            long seed = activity.nativeGetSeed();
            ClipboardManager clipboard =
                (ClipboardManager) activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Brogue Seed", String.valueOf(seed)));
            Toast.makeText(activity,
                "Seed " + seed + " copied", Toast.LENGTH_SHORT).show();
            hide();
        });

        int panelWidth = Math.min(activity.dpToPx(280),
            (int)(activity.getResources().getDisplayMetrics().widthPixels * 0.6f));

        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
            panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM | Gravity.END);
        scrollParams.setMargins(0, activity.dpToPx(8),
            activity.dpToPx(BrogueActivity.EDGE_SAFE_DP),
            activity.dpToPx(BrogueActivity.EDGE_SAFE_DP));

        host.addView(scrollView, scrollParams);
        host.setVisibility(View.VISIBLE);

        scrollView.setTranslationX(panelWidth);
        scrollView.animate()
            .translationX(0)
            .setDuration(280)
            .setInterpolator(new DecelerateInterpolator(1.5f))
            .start();
    }

    void hide() {
        if (host.getChildCount() < 2) {
            host.setVisibility(View.GONE);
            host.removeAllViews();
            return;
        }
        View backdrop = host.getChildAt(0);
        View panel = host.getChildAt(1);

        panel.animate()
            .translationX(panel.getWidth())
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
    }

    // ---- Row builders ----

    // These listeners only animate; returning false preserves normal click handling.
    @SuppressLint("ClickableViewAccessibility")
    private LinearLayout addRow(LinearLayout panel, String label) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(activity.dpToPx(12), activity.dpToPx(10),
                       activity.dpToPx(12), activity.dpToPx(10));
        row.setMinimumHeight(activity.dpToPx(44));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(activity.dpToPx(3));
        bg.setColor(Palette.ITEM_BG);
        row.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.RIPPLE_GLOW), bg, null));

        row.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(60).start();
            } else if (e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
            }
            return false;
        });

        TextView labelView = new TextView(activity);
        labelView.setText(label);
        labelView.setTextColor(Palette.GHOST_WHITE);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        labelView.setTypeface(Typeface.MONOSPACE);
        row.addView(labelView, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, activity.dpToPx(2), 0, activity.dpToPx(2));
        panel.addView(row, p);

        return row;
    }

    /** Shared action-row builder. */
    @SuppressLint("ClickableViewAccessibility")
    static void addAction(BrogueActivity activity, LinearLayout panel, String label,
                          View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(activity.dpToPx(12), activity.dpToPx(10),
                       activity.dpToPx(12), activity.dpToPx(10));
        row.setMinimumHeight(activity.dpToPx(44));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(activity.dpToPx(3));
        bg.setColor(Palette.ITEM_BG);
        row.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.RIPPLE_GLOW), bg, null));

        row.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(60).start();
            } else if (e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
            }
            return false;
        });

        TextView labelView = new TextView(activity);
        labelView.setText(label);
        labelView.setTextColor(Palette.GHOST_WHITE);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        labelView.setTypeface(Typeface.MONOSPACE);
        row.addView(labelView, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(listener);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, activity.dpToPx(2), 0, activity.dpToPx(2));
        panel.addView(row, p);
    }

    private void addSeparator(LinearLayout panel) {
        View sep = new View(activity);
        sep.setBackgroundColor(Palette.BORDER_DIM);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        p.setMargins(activity.dpToPx(4), activity.dpToPx(4),
                     activity.dpToPx(4), activity.dpToPx(4));
        panel.addView(sep, p);
    }

    /** Toggle backed by {@link GameSettings}; sends {@code gameKey} to the
     *  engine on change so the C side picks up the new state. */
    private void addGameToggle(LinearLayout panel, String label, String prefKey, char gameKey) {
        LinearLayout row = addRow(panel, label);

        boolean on = GameSettings.getBool(activity, prefKey);
        TextView check = makeCheckIndicator(on);
        row.addView(check, new LinearLayout.LayoutParams(
            activity.dpToPx(28), activity.dpToPx(28)));

        row.setOnClickListener(v -> {
            boolean nowOn = !GameSettings.getBool(activity, prefKey);
            GameSettings.setBool(activity, prefKey, nowOn);
            updateCheckIndicator(check, nowOn);
            KeyInput.sendChar(activity, gameKey);
        });
    }

    /** Java-only toggle (no engine notification). {@code onChange} fires after
     *  the new value is persisted, with the new boolean state. */
    private void addAppToggle(LinearLayout panel, String label, String prefKey,
                              java.util.function.Consumer<Boolean> onChange) {
        addAppToggle(panel, label, prefKey, false, onChange);
    }

    private void addAppToggle(LinearLayout panel, String label, String prefKey,
                              boolean defaultValue,
                              java.util.function.Consumer<Boolean> onChange) {
        LinearLayout row = addRow(panel, label);

        boolean on = GameSettings.getBool(activity, prefKey, defaultValue);
        TextView check = makeCheckIndicator(on);
        row.addView(check, new LinearLayout.LayoutParams(
            activity.dpToPx(28), activity.dpToPx(28)));

        row.setOnClickListener(v -> {
            boolean nowOn = !GameSettings.getBool(activity, prefKey, defaultValue);
            GameSettings.setBool(activity, prefKey, nowOn);
            updateCheckIndicator(check, nowOn);
            if (onChange != null) onChange.accept(nowOn);
        });
    }

    private void addIntSetting(LinearLayout panel, String label, String prefKey, int defaultValue,
                               String prompt, Runnable onChange) {
        LinearLayout row = addRow(panel, label);
        TextView valueView = makeValueIndicator(String.valueOf(
            GameSettings.getInt(activity, prefKey, defaultValue)));
        row.addView(valueView);

        row.setOnClickListener(v -> activity.textInputDialog.show(
            prompt,
            String.valueOf(GameSettings.getInt(activity, prefKey, defaultValue)),
            8,
            false,
            result -> {
                if (result == null) return;
                Integer parsed = tryParseInt(result);
                if (parsed == null) {
                    Toast.makeText(activity, "Enter a whole number.", Toast.LENGTH_SHORT).show();
                    return;
                }
                GameSettings.setInt(activity, prefKey, parsed);
                valueView.setText(String.valueOf(parsed));
                if (onChange != null) onChange.run();
            }));
    }

    private void addFloatSetting(LinearLayout panel, String label, String prefKey, float defaultValue,
                                 Float minValue, String prompt, Runnable onChange) {
        LinearLayout row = addRow(panel, label);
        TextView valueView = makeValueIndicator(formatFloat(
            GameSettings.getFloat(activity, prefKey, defaultValue)));
        row.addView(valueView);

        row.setOnClickListener(v -> activity.textInputDialog.showDecimal(
            prompt,
            formatFloat(GameSettings.getFloat(activity, prefKey, defaultValue)),
            8,
            result -> {
                if (result == null) return;
                Float parsed = tryParseFloat(result);
                if (parsed == null) {
                    Toast.makeText(activity, "Enter a valid number.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (minValue != null && parsed < minValue) {
                    Toast.makeText(activity,
                        "Value must be at least " + formatFloat(minValue) + ".",
                        Toast.LENGTH_SHORT).show();
                    return;
                }
                GameSettings.setFloat(activity, prefKey, parsed);
                valueView.setText(formatFloat(parsed));
                if (onChange != null) onChange.run();
            }));
    }

    private void addGraphicsModeCycler(LinearLayout panel) {
        int mode = GameSettings.getInt(activity, "graphics_mode", 1);
        if (mode < 0 || mode > 2) mode = 1;

        LinearLayout row = addRow(panel, GRAPHICS_MODE_LABELS[mode]);
        TextView labelView = (TextView) row.getChildAt(0);

        final int[] currentMode = {mode};
        row.setOnClickListener(v -> {
            currentMode[0] = (currentMode[0] + 1) % GRAPHICS_MODE_LABELS.length;
            GameSettings.setInt(activity, "graphics_mode", currentMode[0]);
            labelView.setText(GRAPHICS_MODE_LABELS[currentMode[0]]);
            KeyInput.sendChar(activity, 'G');
        });
    }

    private TextView makeCheckIndicator(boolean on) {
        TextView check = new TextView(activity);
        check.setText(on ? "\u2713" : "");
        check.setTextColor(Palette.DIM_WHITE_BLUE);
        check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        check.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        check.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(activity.dpToPx(3));
        bg.setColor(on ? Palette.ACTION_BG : Color.TRANSPARENT);
        bg.setStroke(1, Palette.BORDER_ACTIVE);
        check.setBackground(bg);
        return check;
    }

    private TextView makeValueIndicator(String value) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextColor(Palette.PALE_BLUE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        text.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        text.setGravity(Gravity.CENTER);
        text.setMinWidth(activity.dpToPx(54));
        text.setPadding(activity.dpToPx(8), activity.dpToPx(4),
            activity.dpToPx(8), activity.dpToPx(4));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(activity.dpToPx(3));
        bg.setColor(Color.TRANSPARENT);
        bg.setStroke(1, Palette.BORDER_ACTIVE);
        text.setBackground(bg);
        return text;
    }

    private void updateCheckIndicator(TextView check, boolean on) {
        check.setText(on ? "\u2713" : "");
        GradientDrawable bg = (GradientDrawable) check.getBackground();
        bg.setColor(on ? Palette.ACTION_BG : Color.TRANSPARENT);
        bg.setStroke(1, Palette.BORDER_ACTIVE);
    }

    private Integer tryParseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Float tryParseFloat(String value) {
        try {
            return Float.parseFloat(value.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String formatFloat(float value) {
        return new DecimalFormat("0.##").format(value);
    }

}
