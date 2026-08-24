package com.pineyellow.broguepe;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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

    private final BrogueActivity activity;
    private FrameLayout root;
    private View fadeView;
    private View modalView;
    private DeathDetails details;
    private State state = State.INACTIVE;

    DeathModal(BrogueActivity activity) {
        this.activity = activity;
    }

    void show(String description, int turns) {
        if (!transition(State.INACTIVE, State.FADING_TO_BLACK)) {
            // A late JNI request can race Activity teardown. Never leave its
            // native caller waiting for UI that can no longer be displayed.
            activity.nativeCancelDeathScreen();
            return;
        }
        activity.runOnUiThread(() -> fadeToBlack(description, turns));
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
        details = null;
        synchronized (this) {
            if (state != State.DESTROYING) {
                state = State.INACTIVE;
            }
        }
    }

    private void fadeToBlack(String description, int turns) {
        if (!isInState(State.FADING_TO_BLACK)) {
            return;
        }

        root = new FrameLayout(activity);
        root.setClickable(true);
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
        // outside it, so input can never fall through to SDL.
        fadeView.setClickable(true);
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
        details = new DeathDetails(description, turns);
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
        if (root == null || details == null) {
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
        DeathDetails currentDetails = details;
        details = null;
        buildModal(currentDetails.description, currentDetails.turns);
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

    private void buildModal(String description, int turns) {
        if (root == null) return;

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
        header.setText(R.string.death_title);
        header.setTextColor(Palette.DIM_WHITE_BLUE);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        header.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.setLetterSpacing(0.2f);
        header.setGravity(Gravity.CENTER);
        header.setPadding(0, activity.dpToPx(4), 0, activity.dpToPx(8));
        panel.addView(header);

        panel.addView(ModalChrome.makeDimSeparator(activity),
                      ModalChrome.separatorParams(activity, 8, 8, 0, 12));

        addLine(panel, description, Palette.GHOST_WHITE, 13);
        addLine(panel, turns + " turns", Palette.PALE_BLUE, 12);

        panel.addView(new View(activity), new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, activity.dpToPx(16)));

        View continueButton = StartMenu.addButton(
            panel, "Continue", true, v -> requestDismiss());
        continueButton.setFocusable(true);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(panel);

        int panelWidth = Math.min(activity.dpToPx(340),
            (int)(activity.getResources().getDisplayMetrics().widthPixels * 0.85f));
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
            panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(scroll, panelParams);
        modalView = scroll;

        ModalChrome.animateIn(panel);
        continueButton.requestFocus();
    }

    private void addLine(LinearLayout panel, String text, int color, int sp) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, activity.dpToPx(4), 0, 0);
        panel.addView(tv, p);
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

    private static final class DeathDetails {
        final String description;
        final int turns;

        DeathDetails(String description, int turns) {
            this.description = description;
            this.turns = turns;
        }
    }
}
