package com.pineyellow.broguepe;

import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Display;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.RoundedCorner;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.libsdl.app.SDLActivity;

/** Thin coordinator. Owns the two overlay roots, holds the feature classes,
 *  and forwards the JNI entry points the engine calls into. Everything else
 *  lives in a dedicated class under this package. */
public class BrogueActivity extends SDLActivity {

    /** Shared right-edge gutter for side drawers. */
    static final int EDGE_SAFE_DP = 4;

    // Overlay roots — allocated in onCreate, shared with feature classes.
    FrameLayout gameOverlay;
    FrameLayout inventoryOverlay;
    private ImageView resumeSnapshotView;
    private View resumeInputBlocker;
    private Bitmap resumeSnapshotBitmap;
    private HandlerThread snapshotThread;
    private Handler snapshotHandler;
    private SurfaceHolder.Callback rendererSurfaceCallback;
    private boolean hasPaused;
    private Object appBackCallback;

    // Feature classes. Package-private so related UI components can reference
    // one another directly.
    final ModalStack modalStack = new ModalStack(this);
    final CreditsModal creditsModal = new CreditsModal(this);
    final PlayerStatsModal playerStatsModal = new PlayerStatsModal(this);
    final StartMenu startMenu = new StartMenu(this);
    // Offline seed modals share one visual frame.
    final NewGameSeedModal newGameSeedModal = new NewGameSeedModal(this);
    final ReplayRecentSeedModal replayRecentSeedModal = new ReplayRecentSeedModal(this);
    final DeathModal deathModal = new DeathModal(this);
    private SettingsPanel settingsPanel;
    private ActionsToolbar actionsToolbar;
    private DPadOverlay dpadOverlay;
    private View dpadView;
    private InventoryOverlay inventoryRenderer;
    private DiscoveriesOverlay discoveriesRenderer;
    TextInputDialog textInputDialog;
    ConfirmationDialog confirmationDialog;

    @Override
    protected String[] getLibraries() {
        return new String[]{ "SDL2", "SDL2_image", "brogue" };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestHighestRefreshRate();
        configureRoundedCornerInsets();

        rendererSurfaceCallback = new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {}

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format,
                                       int width, int height) {
                // SDL registered its callback first, so its EGL surface is
                // ready before recovery is queued for the game thread.
                requestRendererRecoveryIfSurfaceReady();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {}
        };
        ((SurfaceView) mSurface).getHolder().addCallback(rendererSurfaceCallback);

        snapshotThread = new HandlerThread("BrogueFrameCapture");
        snapshotThread.start();
        snapshotHandler = new Handler(snapshotThread.getLooper());

        resumeSnapshotView = new ImageView(this);
        resumeSnapshotView.setScaleType(ImageView.ScaleType.FIT_XY);
        resumeSnapshotView.setVisibility(View.GONE);
        mLayout.addView(resumeSnapshotView, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

        gameOverlay = new FrameLayout(this);
        gameOverlay.setVisibility(View.GONE);
        addContentView(gameOverlay, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        inventoryOverlay = new FrameLayout(this);
        inventoryOverlay.setVisibility(View.GONE);
        // Absorb touches so they don't reach the game underneath.
        inventoryOverlay.setClickable(true);
        addContentView(inventoryOverlay, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        settingsPanel = new SettingsPanel(this, inventoryOverlay);
        inventoryRenderer = new InventoryOverlay(this, inventoryOverlay);
        discoveriesRenderer = new DiscoveriesOverlay(this, inventoryOverlay);
        textInputDialog = new TextInputDialog(this);
        confirmationDialog = new ConfirmationDialog(this);

        actionsToolbar = new ActionsToolbar(this, gameOverlay, inventoryOverlay,
            settingsPanel::show);
        View controlsOverlay = actionsToolbar.build();
        gameOverlay.addView(controlsOverlay, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        dpadOverlay = new DPadOverlay(this);
        dpadView = dpadOverlay.getView();
        FrameLayout.LayoutParams dpadParams = new FrameLayout.LayoutParams(
            dpToPx(DPadOverlay.SIZE_DP),
            dpToPx(DPadOverlay.SIZE_DP),
            Gravity.TOP | Gravity.START);
        gameOverlay.addView(dpadView, dpadParams);
        setDpadEnabled(GameSettings.getBool(this, DPadOverlay.PREF_ENABLED, true));

        resumeInputBlocker = new View(this);
        resumeInputBlocker.setBackgroundColor(Color.TRANSPARENT);
        resumeInputBlocker.setClickable(true);
        resumeInputBlocker.setFocusable(true);
        resumeInputBlocker.setVisibility(View.GONE);
        addContentView(resumeInputBlocker, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        setBackHandlingEnabled(true);

    }

    @Override
    protected void onResume() {
        super.onResume();
        requestHighestRefreshRate();
        getWindow().getDecorView().requestApplyInsets();
        requestRendererRecoveryIfSurfaceReady();
    }

    private void configureRoundedCornerInsets() {
        View decorView = getWindow().getDecorView();
        decorView.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RoundedCorner corner = insets.getRoundedCorner(
                    RoundedCorner.POSITION_TOP_LEFT);
                if (corner != null) {
                    Point center = corner.getCenter();
                    nativeSetTopLeftRoundedCorner(
                        corner.getRadius(), center.x, center.y);
                } else {
                    nativeSetTopLeftRoundedCorner(0, 0, 0);
                }
            } else {
                nativeSetTopLeftRoundedCorner(0, 0, 0);
            }
            return insets;
        });
        decorView.requestApplyInsets();
    }

    @Override
    protected void onPause() {
        hasPaused = true;
        captureResumeSnapshot();
        if (dpadOverlay != null) {
            dpadOverlay.cancelInput();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        setBackHandlingEnabled(false);
        if (mSurface != null && rendererSurfaceCallback != null) {
            ((SurfaceView) mSurface).getHolder().removeCallback(rendererSurfaceCallback);
            rendererSurfaceCallback = null;
        }
        if (snapshotThread != null) {
            snapshotThread.quitSafely();
            snapshotThread = null;
            snapshotHandler = null;
        }
        if (resumeSnapshotBitmap != null) {
            resumeSnapshotBitmap.recycle();
            resumeSnapshotBitmap = null;
        }
        super.onDestroy();
    }

    private void requestRendererRecoveryIfSurfaceReady() {
        if (!hasPaused || mSurface == null) return;
        Surface surface = ((SurfaceView) mSurface).getHolder().getSurface();
        if (surface != null && surface.isValid()) {
            nativeRequestRendererRecovery();
        }
    }

    private void captureResumeSnapshot() {
        if (mSurface == null || snapshotHandler == null) return;
        SurfaceView surfaceView = (SurfaceView) mSurface;
        Surface surface = surfaceView.getHolder().getSurface();
        int width = surfaceView.getWidth();
        int height = surfaceView.getHeight();
        if (!surface.isValid() || width <= 0 || height <= 0) return;

        Bitmap candidate = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        CountDownLatch finished = new CountDownLatch(1);
        int[] result = { PixelCopy.ERROR_UNKNOWN };

        try {
            PixelCopy.request(surface, candidate, copyResult -> {
                result[0] = copyResult;
                finished.countDown();
            }, snapshotHandler);

            boolean completed = finished.await(200, TimeUnit.MILLISECONDS);
            if (!completed) return;
            if (result[0] != PixelCopy.SUCCESS) {
                candidate.recycle();
                return;
            }

            Bitmap previous = resumeSnapshotBitmap;
            resumeSnapshotBitmap = candidate;
            resumeSnapshotView.setImageBitmap(candidate);
            if (previous != null && previous != candidate) {
                previous.recycle();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            candidate.recycle();
        }
    }

    private void showResumeSnapshot() {
        // A locked cold start can pause before SDL has produced a frame. Never
        // install an invisible full-screen touch shield without a snapshot.
        if (resumeSnapshotBitmap == null || resumeSnapshotView == null
                || resumeInputBlocker == null) {
            hideResumeSnapshot();
            return;
        }
        resumeSnapshotView.setImageBitmap(resumeSnapshotBitmap);
        resumeSnapshotView.setVisibility(View.VISIBLE);
        resumeInputBlocker.setVisibility(View.VISIBLE);
    }

    private void hideResumeSnapshot() {
        if (resumeSnapshotView != null) {
            resumeSnapshotView.setVisibility(View.GONE);
        }
        if (resumeInputBlocker != null) {
            resumeInputBlocker.setVisibility(View.GONE);
        }
    }

    private void requestHighestRefreshRate() {
        Display display = getWindowManager().getDefaultDisplay();
        Display.Mode currentMode = display.getMode();
        Display.Mode bestMode = currentMode;

        for (Display.Mode mode : display.getSupportedModes()) {
            boolean sameResolution = mode.getPhysicalWidth() == currentMode.getPhysicalWidth()
                && mode.getPhysicalHeight() == currentMode.getPhysicalHeight();
            if (sameResolution && mode.getRefreshRate() > bestMode.getRefreshRate()) {
                bestMode = mode;
            }
        }

        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        if (attributes.preferredDisplayModeId != bestMode.getModeId()) {
            attributes.preferredDisplayModeId = bestMode.getModeId();
            getWindow().setAttributes(attributes);
        }
    }

    // ---- JNI entry points -------------------------------------------------
    // These methods are called from C. Keep them on the activity so the
    // Java_com_pineyellow_broguepe_BrogueActivity_* binding names match. Each is
    // a thin forward to the feature class that actually handles the work.

    public void showStartMenu(final boolean hasSave, final boolean saveCompatible) {
        startMenu.show(hasSave, saveCompatible);
    }

    public void showInventory(final String json) {
        inventoryRenderer.show(json);
    }

    public void hideInventory() {
        inventoryRenderer.hide();
    }

    public void showDiscoveries(final String json) {
        discoveriesRenderer.show(json);
    }

    public void hideDiscoveries() {
        discoveriesRenderer.hide();
    }

    public void showTextInputDialog(final String prompt, final String defaultText,
                                     final int maxLen, final boolean numericOnly) {
        textInputDialog.show(prompt, defaultText, maxLen, numericOnly);
    }

    public void showConfirmationDialog(final String prompt) {
        confirmationDialog.show(prompt);
    }

    void cancelDpadInput() {
        if (dpadOverlay != null) {
            dpadOverlay.cancelInput();
        }
    }

    // Stat-event callbacks dispatched from android-stats.c. These are on the
    // engine thread — do no real work here; hand off to StatsStore's own
    // background HandlerThread so we return fast and don't perturb the game
    // loop. Call sites in C already guard !rogue.playbackMode, so save-load
    // and recording playback don't re-dispatch historical events.
    // Set by StartMenu's Resume button immediately before the JNI hop into
    // NG_OPEN_GAME. Read-and-cleared on the next onGameStart so resumes can
    // be told apart from fresh runs without threading a flag through C.
    boolean nextGameIsResume;

    public void onGameStart(long seed) {
        boolean isResume = nextGameIsResume;
        nextGameIsResume = false;
        if (isResume) {
            // Resuming continues an already-counted local run.
        } else {
            StatsStore.get(this).recordGameStart();
            StatsStore.get(this).recordSeedPlayed(seed);
        }
    }

    public void onMonsterKilled(final String monsterName) {
        StatsStore.get(this).recordMonsterKilled(monsterName);
    }

    public void onAllyFreed(final String monsterName) {
        StatsStore.get(this).recordAllyFreed(monsterName);
    }

    public void onAllyDied(final String monsterName) {
        StatsStore.get(this).recordAllyDied(monsterName);
    }

    public void onPlayerDied(final String killedBy, final int depth, final int turns) {
        StatsStore.get(this).recordPlayerDied(killedBy, depth, turns);
    }

    public void showDeathScreen(String description, int turns) {
        deathModal.show(description, turns);
    }

    public native void nativeDeathFadeDone();
    public native void nativeDeathScreenDismissed();

    public void onDeathFlamesReady() {
        deathModal.onFlamesReady();
    }

    public void onPlayerWon(final boolean superVictory, final int depth, final int turns) {
        StatsStore.get(this).recordPlayerWon(superVictory, depth, turns);
    }

    public void onPlayerQuit(final int depth, final int turns) {
        StatsStore.get(this).recordPlayerQuit();
    }

    public void hideGameUI() {
        final java.util.concurrent.CountDownLatch latch =
            new java.util.concurrent.CountDownLatch(1);
        runOnUiThread(() -> {
            gameOverlay.setVisibility(View.GONE);
            inventoryOverlay.setVisibility(View.GONE);
            latch.countDown();
        });
        try { latch.await(); } catch (InterruptedException ignored) {}
    }

    public void setOverlayVisible(final boolean visible) {
        android.util.Log.d("BrogueModal", "setOverlayVisible(" + visible + ")");
        runOnUiThread(() -> {
            if (visible) {
                // A game is on-screen — drop title-menu modals so they can't
                // resurface after the engine returns to the title later.
                applyDpadSettings();
                modalStack.clear();
            } else {
                // Engine is returning to the title menu. This fires at the
                // top of titleMenu() in C, before its Phase 1 tap-to-continue
                // flame loop, so restoring here puts the modal back up
                // immediately instead of making the user tap through flames.
                modalStack.restore();
                deathModal.fadeOutOverlay();
            }
            gameOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        });
    }

    public void setLoadingVisible(final boolean visible) {
        // Retained as a JNI hook; startup and save loading now render directly.
    }

    public void setRestoringVisible(final boolean visible) {
        runOnUiThread(() -> {
            if (visible) {
                showResumeSnapshot();
            } else {
                hideResumeSnapshot();
            }
        });
    }

    /** Called from C to read a saved boolean setting. */
    public boolean getSettingBool(String key) {
        return GameSettings.getBool(this, key);
    }

    /** Called from C to read a saved int setting. */
    public int getSettingInt(String key, int defaultValue) {
        return GameSettings.getInt(this, key, defaultValue);
    }

    void setDpadEnabled(boolean enabled) {
        if (enabled) {
            applyDpadSettings();
        }
        if (dpadView != null) {
            dpadView.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
    }

    void applyDpadSettings() {
        if (dpadView == null || gameOverlay == null) return;
        if (gameOverlay.getWidth() == 0 || gameOverlay.getHeight() == 0) {
            gameOverlay.post(this::applyDpadSettings);
            return;
        }

        float sizeScale = positiveOrDefault(
            GameSettings.getFloat(this, DPadOverlay.PREF_SIZE, DPadOverlay.DEFAULT_SIZE),
            DPadOverlay.DEFAULT_SIZE);
        float buttonWidthScale = positiveOrDefault(
            GameSettings.getFloat(this, DPadOverlay.PREF_BUTTON_WIDTH, DPadOverlay.DEFAULT_BUTTON_WIDTH),
            DPadOverlay.DEFAULT_BUTTON_WIDTH);

        int widthPx = dpToPx(DPadOverlay.SIZE_DP * sizeScale * buttonWidthScale);
        int heightPx = dpToPx(DPadOverlay.SIZE_DP * sizeScale);

        float defaultAnchorXPx = dpToPx(DPadOverlay.MARGIN_DP + DPadOverlay.SIZE_DP / 2f);
        float defaultAnchorYPx = gameOverlay.getHeight()
            - dpToPx(DPadOverlay.MARGIN_DP + DPadOverlay.SIZE_DP / 2f);
        float anchorXPx = defaultAnchorXPx
            + dpToPx(GameSettings.getFloat(this, DPadOverlay.PREF_OFFSET_X, 0f));
        float anchorYPx = defaultAnchorYPx
            - dpToPx(GameSettings.getFloat(this, DPadOverlay.PREF_OFFSET_Y, 0f));

        int left = Math.round(anchorXPx - widthPx / 2f);
        int top = Math.round(anchorYPx - heightPx / 2f);
        left = Math.max(0, Math.min(gameOverlay.getWidth() - widthPx, left));
        top = Math.max(0, Math.min(gameOverlay.getHeight() - heightPx, top));

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) dpadView.getLayoutParams();
        if (params == null) {
            params = new FrameLayout.LayoutParams(widthPx, heightPx, Gravity.TOP | Gravity.START);
        }
        params.width = widthPx;
        params.height = heightPx;
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = left;
        params.topMargin = top;
        params.rightMargin = 0;
        params.bottomMargin = 0;
        dpadView.setLayoutParams(params);
    }

    // ---- Native declarations ---------------------------------------------
    // Implemented in C (android-touch.c). Must stay on BrogueActivity so the
    // Java_com_pineyellow_broguepe_BrogueActivity_* binding names match.

    native void nativeStartMenuResult(int choice);
    native void nativeStartMenuResultWithSeed(int choice, long seed);
    native void nativeStartMenuCancel();
    native void nativeTextInputResult(boolean confirmed, String text);
    native void nativeConfirmationResult(boolean confirmed);
    native void nativeSetTopLeftRoundedCorner(int radius, int centerX, int centerY);
    native void nativeRequestRendererRecovery();
    native long nativeGetSeed();
    native boolean nativeHasVisibleEnemy();
    native void nativeArmQuickTargetSelection();
    native boolean nativeIsTargetingActive();
    native boolean nativeHasSelectedTarget();
    native void nativeBeginDpadAutoMove();
    native void nativeSetDpadRepeatActive(boolean active);
    native boolean nativeShouldStopDpadAutoMove();
    native void nativeEndDpadAutoMove();
    native void nativeDeleteSaveFile();

    // ---- Navigation ------------------------------------------------------

    @Override
    public void onBackPressed() {
        handleBackNavigation();
    }

    private void setBackHandlingEnabled(boolean enabled) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;

        if (enabled && appBackCallback == null) {
            appBackCallback = Api33Back.register(this);
        } else if (!enabled && appBackCallback != null) {
            Api33Back.unregister(this, appBackCallback);
            appBackCallback = null;
        }
    }

    private void handleBackNavigation() {
        if (startMenu.isShowing()) {
            handleMainMenuBack();
        } else if (gameOverlay != null && gameOverlay.getVisibility() == View.VISIBLE) {
            actionsToolbar.collapseSubmenu();
            KeyInput.sendChar(this, 'S');
        } else {
            finish();
        }
    }

    private void handleMainMenuBack() {
        if (!modalStack.isEmpty()) {
            modalStack.pop();
        } else {
            finish();
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.TIRAMISU)
    private static final class Api33Back {
        static Object register(BrogueActivity activity) {
            android.window.OnBackInvokedCallback callback =
                activity::handleBackNavigation;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback);
            return callback;
        }

        static void unregister(BrogueActivity activity, Object callback) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                (android.window.OnBackInvokedCallback) callback);
        }
    }

    // ---- Utilities -------------------------------------------------------

    int dpToPx(int dp) {
        return dpToPx((float) dp);
    }

    int dpToPx(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private static float positiveOrDefault(float value, float fallback) {
        return Float.isFinite(value) && value > 0f ? value : fallback;
    }
}
