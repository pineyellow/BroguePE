package com.pineyellow.broguepe;

import android.app.Dialog;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicBoolean;

/** Large, centered Android presentation for Brogue's Yes/No prompts. */
final class ConfirmationDialog {

    private final BrogueActivity activity;
    private final AtomicBoolean activityResumed = new AtomicBoolean(false);
    private final AtomicBoolean resultPending = new AtomicBoolean(false);
    private Dialog activeDialog;

    ConfirmationDialog(BrogueActivity activity) {
        this.activity = activity;
    }

    void show(String prompt) {
        resultPending.set(true);
        if (!activityResumed.get()) {
            completePending(false);
            return;
        }

        activity.runOnUiThread(() -> {
            // The lifecycle can move to paused after the engine requests the
            // dialog but before this UI work runs. In that case onPause has
            // already answered No; never attach a stale dialog afterward.
            if (!activityResumed.get() || !resultPending.get()) {
                completePending(false);
                return;
            }

            // The game thread blocks while it waits for this dialog. Stop any
            // held DPAD input before showing it so repeat key events do not
            // accumulate in SDL and reopen the same warning after dismissal.
            activity.cancelDpadInput();

            TextView message = new TextView(activity);
            message.setText(prompt);
            message.setTextColor(Palette.GHOST_WHITE);
            message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            message.setTypeface(Typeface.MONOSPACE);
            message.setGravity(Gravity.CENTER);
            message.setLineSpacing(0, 1.15f);

            Button noButton = makeButton("NO", false);
            Button yesButton = makeButton("YES", true);

            LinearLayout buttonRow = new LinearLayout(activity);
            buttonRow.setOrientation(LinearLayout.HORIZONTAL);
            buttonRow.setWeightSum(2f);
            int gap = activity.dpToPx(6);
            LinearLayout.LayoutParams noParams = new LinearLayout.LayoutParams(
                0, activity.dpToPx(35), 1f);
            noParams.setMargins(0, 0, gap / 2, 0);
            buttonRow.addView(noButton, noParams);
            LinearLayout.LayoutParams yesParams = new LinearLayout.LayoutParams(
                0, activity.dpToPx(35), 1f);
            yesParams.setMargins(gap / 2, 0, 0, 0);
            buttonRow.addView(yesButton, yesParams);

            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            int horizontalPad = activity.dpToPx(17);
            int verticalPad = activity.dpToPx(14);
            content.setPadding(horizontalPad, verticalPad, horizontalPad, verticalPad);
            content.setBackground(panelBackground());
            content.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = activity.dpToPx(14);
            content.addView(buttonRow, rowParams);

            Dialog dialog = new Dialog(activity,
                android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
            activeDialog = dialog;
            dialog.setContentView(content);
            dialog.setCancelable(true);
            dialog.setCanceledOnTouchOutside(true);
            dialog.setOnCancelListener(d -> finish(dialog, false));

            noButton.setOnClickListener(v -> finish(dialog, false));
            yesButton.setOnClickListener(v -> finish(dialog, true));

            Window window = dialog.getWindow();
            if (window != null) {
                Point size = new Point();
                activity.getWindowManager().getDefaultDisplay().getRealSize(size);
                int width = Math.min(activity.dpToPx(336), Math.round(size.x * 0.312f));
                width = Math.max(activity.dpToPx(228), width);
                window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
                window.setGravity(Gravity.CENTER);
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.setDimAmount(0.45f);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            }
            activity.showImmersiveDialog(dialog);
        });
    }

    void onActivityResumed() {
        activityResumed.set(true);
    }

    void onActivityPaused() {
        activityResumed.set(false);
        cancelActiveAsNo();
    }

    void onActivityDestroying() {
        activityResumed.set(false);
        cancelActiveAsNo();
    }

    private void cancelActiveAsNo() {
        Dialog dialog = activeDialog;
        activeDialog = null;
        if (dialog != null) {
            dialog.setOnCancelListener(null);
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
        }
        completePending(false);
    }

    private void finish(Dialog dialog, boolean confirmed) {
        if (dialog != activeDialog) return;

        activeDialog = null;
        dialog.setOnCancelListener(null);
        if (dialog.isShowing()) {
            dialog.dismiss();
        }
        completePending(confirmed);
    }

    private void completePending(boolean confirmed) {
        if (resultPending.compareAndSet(true, false)) {
            activity.nativeConfirmationResult(confirmed);
        }
    }

    private Button makeButton(String label, boolean primary) {
        Button button = new Button(activity);
        button.setText(label);
        button.setTextColor(primary ? Palette.GHOST_WHITE : Palette.PALE_BLUE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        button.setTypeface(Typeface.MONOSPACE, primary ? Typeface.BOLD : Typeface.NORMAL);
        button.setGravity(Gravity.CENTER);
        button.setAllCaps(false);
        button.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.RIPPLE_GLOW),
            buttonBackground(primary), null));
        return button;
    }

    private GradientDrawable panelBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Palette.INVENTORY_BG);
        background.setCornerRadius(activity.dpToPx(UiStyle.PANEL_CORNER_RADIUS_DP));
        background.setStroke(1, Palette.BORDER_DIM);
        return background;
    }

    private GradientDrawable buttonBackground(boolean primary) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(primary ? Palette.ACTION_BG : Palette.ITEM_BG);
        background.setCornerRadius(
            activity.dpToPx(UiStyle.MENU_ITEM_CORNER_RADIUS_DP));
        background.setStroke(1, primary ? Palette.BORDER_ACTIVE : Palette.BORDER_DIM);
        return background;
    }
}
