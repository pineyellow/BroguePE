package com.pineyellow.broguepe;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.DecimalFormat;

/** Engine-driven text prompt (e.g. name, custom seed). Invoked from C when
 *  the engine wants a string from the user. OK/Cancel and the system back
 *  dispatch feed the result back into the native thread via
 *  {@link BrogueActivity#nativeTextInputResult}. */
final class TextInputDialog {

    private enum InputMode { TEXT, INTEGER, DECIMAL }

    private final BrogueActivity activity;

    TextInputDialog(BrogueActivity activity) {
        this.activity = activity;
    }

    void show(final String prompt, final String defaultText,
              final int maxLen, final boolean numericOnly) {
        show(prompt, defaultText, maxLen, numericOnly, null);
    }

    /** Java-driven variant used by overlays that don't go through the engine's
     *  text-input channel (NewGameSeedModal's tap-to-edit seed). Passing a
     *  non-null {@code onResult} bypasses {@link BrogueActivity#nativeTextInputResult}
     *  and delivers the string to the caller instead; null on cancel. */
    void show(final String prompt, final String defaultText,
              final int maxLen, final boolean numericOnly,
              final java.util.function.Consumer<String> onResult) {
        show(prompt, defaultText, maxLen,
            numericOnly ? InputMode.INTEGER : InputMode.TEXT, onResult);
    }

    void showDecimal(final String prompt, final String defaultText,
                     final int maxLen,
                     final java.util.function.Consumer<String> onResult) {
        show(prompt, defaultText, maxLen, InputMode.DECIMAL, onResult);
    }

    void showStepper(final String prompt, final float currentValue,
                     final Float minValue, final Float maxValue, final float step,
                     final java.util.function.Consumer<Float> onPreview,
                     final java.util.function.Consumer<Float> onResult) {
        activity.runOnUiThread(() -> {
            float snapped = Math.round(currentValue / step) * step;
            if (minValue != null) snapped = Math.max(minValue, snapped);
            if (maxValue != null) snapped = Math.min(maxValue, snapped);
            final float[] value = { snapped };

            TextView titleView = new TextView(activity);
            titleView.setText(prompt);
            titleView.setTextColor(Palette.DIM_WHITE_BLUE);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            titleView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            titleView.setLetterSpacing(0.1f);
            titleView.setPadding(activity.dpToPx(20), activity.dpToPx(16),
                                 activity.dpToPx(20), activity.dpToPx(8));

            Button minusBtn = makeDialogButton("−", Palette.DIM_WHITE_BLUE,
                Typeface.create(Typeface.MONOSPACE, Typeface.BOLD),
                Palette.ACTION_BG, true);
            Button plusBtn = makeDialogButton("+", Palette.DIM_WHITE_BLUE,
                Typeface.create(Typeface.MONOSPACE, Typeface.BOLD),
                Palette.ACTION_BG, true);

            TextView valueView = new TextView(activity);
            valueView.setTextColor(Palette.GHOST_WHITE);
            valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            valueView.setTypeface(Typeface.MONOSPACE);
            valueView.setGravity(Gravity.CENTER);
            GradientDrawable valueBg = new GradientDrawable();
            valueBg.setShape(GradientDrawable.RECTANGLE);
            valueBg.setCornerRadius(activity.dpToPx(3));
            valueBg.setColor(Color.argb(255, 20, 17, 42));
            valueBg.setStroke(1, Palette.DIM_BLUE_GRAY);
            valueView.setBackground(valueBg);

            Runnable refreshValue = () -> {
                valueView.setText(new DecimalFormat("0.##").format(value[0]));
                boolean canDecrease = minValue == null
                    || value[0] > minValue + 0.0001f;
                boolean canIncrease = maxValue == null
                    || value[0] < maxValue - 0.0001f;
                minusBtn.setEnabled(canDecrease);
                minusBtn.setAlpha(canDecrease ? 1f : 0.4f);
                plusBtn.setEnabled(canIncrease);
                plusBtn.setAlpha(canIncrease ? 1f : 0.4f);
            };
            minusBtn.setOnClickListener(v -> {
                value[0] = Math.round((value[0] - step) / step) * step;
                if (minValue != null) value[0] = Math.max(minValue, value[0]);
                refreshValue.run();
                onPreview.accept(value[0]);
            });
            plusBtn.setOnClickListener(v -> {
                value[0] = Math.round((value[0] + step) / step) * step;
                if (maxValue != null) value[0] = Math.min(maxValue, value[0]);
                refreshValue.run();
                onPreview.accept(value[0]);
            });
            refreshValue.run();

            LinearLayout stepperRow = new LinearLayout(activity);
            stepperRow.setOrientation(LinearLayout.HORIZONTAL);
            stepperRow.setGravity(Gravity.CENTER_VERTICAL);
            int controlSize = activity.dpToPx(48);
            stepperRow.addView(minusBtn, new LinearLayout.LayoutParams(
                controlSize, controlSize));
            LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                0, controlSize, 1f);
            valueParams.setMargins(activity.dpToPx(8), 0, activity.dpToPx(8), 0);
            stepperRow.addView(valueView, valueParams);
            stepperRow.addView(plusBtn, new LinearLayout.LayoutParams(
                controlSize, controlSize));

            Button cancelBtn = makeDialogButton("CANCEL", Palette.PALE_BLUE,
                Typeface.MONOSPACE, Color.TRANSPARENT, false);
            Button okBtn = makeDialogButton("OK", Palette.DIM_WHITE_BLUE,
                Typeface.create(Typeface.MONOSPACE, Typeface.BOLD),
                Palette.ACTION_BG, true);
            LinearLayout buttonRow = new LinearLayout(activity);
            buttonRow.setOrientation(LinearLayout.HORIZONTAL);
            buttonRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            cancelParams.setMargins(0, 0, activity.dpToPx(8), 0);
            buttonRow.addView(cancelBtn, cancelParams);
            buttonRow.addView(okBtn);

            LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.addView(titleView);
            int horizontalPad = activity.dpToPx(20);
            LinearLayout.LayoutParams stepperParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            stepperParams.setMargins(horizontalPad, activity.dpToPx(4),
                horizontalPad, activity.dpToPx(8));
            layout.addView(stepperRow, stepperParams);
            LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            buttonsParams.setMargins(horizontalPad, 0, horizontalPad,
                activity.dpToPx(16));
            layout.addView(buttonRow, buttonsParams);

            GradientDrawable dialogBg = new GradientDrawable();
            dialogBg.setCornerRadius(activity.dpToPx(6));
            dialogBg.setColor(Palette.INVENTORY_BG);
            dialogBg.setStroke(1, Palette.BORDER_DIM);
            layout.setBackground(dialogBg);

            AlertDialog dialog = new AlertDialog.Builder(activity,
                    android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
                .setView(layout)
                .setCancelable(true)
                .setOnCancelListener(d -> onResult.accept(null))
                .create();
            cancelBtn.setOnClickListener(v -> {
                onResult.accept(null);
                dialog.dismiss();
            });
            okBtn.setOnClickListener(v -> {
                onResult.accept(value[0]);
                dialog.dismiss();
            });

            // Configure the window before show(). Doing this from OnShowListener
            // is too late: changing its geometry after it becomes visible causes
            // a horizontal jump on devices with side-mounted navigation bars.
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(dialogBg);
                window.setGravity(Gravity.CENTER);
                window.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
                Point realSize = new Point();
                activity.getWindowManager().getDefaultDisplay().getRealSize(realSize);
                window.setLayout(Math.max(realSize.x / 3,
                        activity.dpToPx(280)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
                window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
            dialog.show();
        });
    }

    private void show(final String prompt, final String defaultText,
                      final int maxLen, final InputMode inputMode,
                      final java.util.function.Consumer<String> onResult) {
        activity.runOnUiThread(() -> {
            EditText input = new EditText(activity);
            input.setTextColor(Palette.GHOST_WHITE);
            input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            input.setTypeface(Typeface.MONOSPACE);
            if (inputMode == InputMode.DECIMAL) {
                // Hint a compact decimal keyboard without installing a live
                // character filter. Some IMEs submit composing text in chunks;
                // filtering those chunks can drop valid digits or retain stale
                // composition. SettingsPanel validates the finished value.
                input.setRawInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | InputType.TYPE_NUMBER_FLAG_SIGNED);
            } else {
                input.setInputType(inputMode == InputMode.INTEGER
                    ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
            }
            input.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(maxLen) });
            input.setText(defaultText);
            input.setSelectAllOnFocus(true);
            input.setHighlightColor(Color.argb(80, 180, 120, 50));

            GradientDrawable inputBg = new GradientDrawable();
            inputBg.setShape(GradientDrawable.RECTANGLE);
            inputBg.setCornerRadius(activity.dpToPx(3));
            inputBg.setColor(Color.argb(255, 20, 17, 42));
            inputBg.setStroke(1, Palette.DIM_BLUE_GRAY);
            input.setBackground(inputBg);
            input.setPadding(activity.dpToPx(12), activity.dpToPx(10),
                             activity.dpToPx(12), activity.dpToPx(10));

            TextView titleView = new TextView(activity);
            titleView.setText(prompt);
            titleView.setTextColor(Palette.DIM_WHITE_BLUE);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            titleView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            titleView.setLetterSpacing(0.1f);
            titleView.setPadding(activity.dpToPx(20), activity.dpToPx(16),
                                 activity.dpToPx(20), activity.dpToPx(8));

            Button cancelBtn = makeDialogButton("CANCEL", Palette.PALE_BLUE,
                Typeface.MONOSPACE, Color.TRANSPARENT, false);
            Button okBtn = makeDialogButton("OK", Palette.DIM_WHITE_BLUE,
                Typeface.create(Typeface.MONOSPACE, Typeface.BOLD),
                Palette.ACTION_BG, true);

            LinearLayout buttonRow = new LinearLayout(activity);
            buttonRow.setOrientation(LinearLayout.HORIZONTAL);
            buttonRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

            LinearLayout.LayoutParams cancelP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            cancelP.setMargins(0, 0, activity.dpToPx(8), 0);
            buttonRow.addView(cancelBtn, cancelP);
            buttonRow.addView(okBtn);

            LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.addView(titleView);
            int hPad = activity.dpToPx(20);
            LinearLayout.LayoutParams inputP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            inputP.setMargins(hPad, activity.dpToPx(4), hPad, activity.dpToPx(8));
            layout.addView(input, inputP);
            LinearLayout.LayoutParams btnRowP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            btnRowP.setMargins(hPad, 0, hPad, activity.dpToPx(16));
            layout.addView(buttonRow, btnRowP);

            GradientDrawable dialogBg = new GradientDrawable();
            dialogBg.setShape(GradientDrawable.RECTANGLE);
            dialogBg.setCornerRadius(activity.dpToPx(6));
            dialogBg.setColor(Palette.INVENTORY_BG);
            dialogBg.setStroke(1, Palette.BORDER_DIM);
            layout.setBackground(dialogBg);

            // Use the real full-screen dimensions (including nav bar area) so
            // the dialog position doesn't shift when the keyboard or
            // navigation bar appears/disappears.
            Point realSize = new Point();
            activity.getWindowManager().getDefaultDisplay().getRealSize(realSize);
            int fullScreenWidth = realSize.x;

            AlertDialog dialog = new AlertDialog.Builder(activity,
                    android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
                .setView(layout)
                .setCancelable(true)
                .setOnCancelListener(d -> {
                    if (onResult != null) onResult.accept(null);
                    else activity.nativeTextInputResult(false, "");
                })
                .create();

            cancelBtn.setOnClickListener(v -> {
                if (onResult != null) onResult.accept(null);
                else activity.nativeTextInputResult(false, "");
                dialog.dismiss();
            });
            okBtn.setOnClickListener(v -> {
                String text = input.getText().toString();
                if (onResult != null) onResult.accept(text);
                else activity.nativeTextInputResult(true, text);
                dialog.dismiss();
            });

            // Configure window before show() so there is no default slide
            // animation or gravity snap.
            Window window = dialog.getWindow();
            if (window != null) {
                window.setWindowAnimations(0);
                window.setBackgroundDrawable(dialogBg);
                window.setGravity(Gravity.CENTER);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
                int minWidth = fullScreenWidth / 3;
                window.setLayout(Math.max(minWidth, ViewGroup.LayoutParams.WRAP_CONTENT),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            }

            dialog.setOnShowListener(d -> {
                if (window != null) {
                    // Match the activity's immersive flags so the nav bar
                    // stays hidden and the dialog doesn't shift.
                    window.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                }
                // Don't auto-show keyboard; let the user tap the input field.
                input.setFocusable(true);
                input.setFocusableInTouchMode(true);
                input.clearFocus();
            });

            dialog.show();
        });
    }

    private Button makeDialogButton(String label, int textColor, Typeface face,
                                     int bgColor, boolean stroked) {
        Button btn = new Button(activity);
        btn.setText(label);
        btn.setTextColor(textColor);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btn.setTypeface(face);
        btn.setAllCaps(true);
        btn.setStateListAnimator(null);
        btn.setElevation(0);
        btn.setPadding(activity.dpToPx(16), activity.dpToPx(10),
                       activity.dpToPx(16), activity.dpToPx(10));
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        btn.setMinHeight(activity.dpToPx(36));
        btn.setMinimumHeight(activity.dpToPx(36));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(activity.dpToPx(3));
        bg.setColor(bgColor);
        if (stroked) bg.setStroke(1, Palette.BORDER_DIM);
        btn.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.RIPPLE_GLOW), bg, null));
        return btn;
    }
}
