package com.pineyellow.broguepe;

import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

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
    private String summaryJson;
    private State state = State.INACTIVE;

    DeathModal(BrogueActivity activity) {
        this.activity = activity;
    }

    void show(String summaryJson) {
        if (!transition(State.INACTIVE, State.FADING_TO_BLACK)) {
            // A late JNI request can race Activity teardown. Never leave its
            // native caller waiting for UI that can no longer be displayed.
            activity.nativeCancelDeathScreen();
            return;
        }
        activity.runOnUiThread(() -> fadeToBlack(summaryJson));
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
        summaryJson = null;
        synchronized (this) {
            if (state != State.DESTROYING) {
                state = State.INACTIVE;
            }
        }
    }

    private void fadeToBlack(String json) {
        if (!isInState(State.FADING_TO_BLACK)) {
            return;
        }

        root = new FrameLayout(activity);
        root.setClickable(true);
        // Touches never reach root (fadeView covers it); this handles Enter /
        // D-pad centre while root holds focus, and a screen reader's click.
        root.setOnClickListener(v -> requestDismiss());
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
        // outside it, so input can never fall through to SDL. Once the panel
        // is showing, such a tap dismisses it; earlier taps are ignored.
        fadeView.setClickable(true);
        fadeView.setOnClickListener(v -> requestDismiss());
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
        summaryJson = json;
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
        if (root == null || summaryJson == null) {
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
        String json = summaryJson;
        summaryJson = null;
        buildModal(json);
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

    /** The run summary panel (see RunSummaryPanel). A tap outside the panel,
     *  or Back, dismisses it. */
    private void buildModal(String json) {
        if (root == null) return;

        RunSummaryPanel summaryPanel = new RunSummaryPanel(activity);
        LinearLayout panel = summaryPanel.build(RunSummaryPanel.parseSummary(json),
            activity.getString(R.string.death_title), activity.runPlaytimeMillis(), null);
        // Taps inside the panel must not reach fadeView, which dismisses.
        // Clickable views are keyboard-focusable by default since Android 8;
        // keep focus on root so Enter always dismisses.
        panel.setClickable(true);
        panel.setFocusable(false);
        root.addView(panel, summaryPanel.panelParams());
        modalView = panel;

        ModalChrome.animateIn(panel);
        root.requestFocus();
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
}
