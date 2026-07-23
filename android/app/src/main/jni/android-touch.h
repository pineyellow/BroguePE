#ifndef ANDROID_TOUCH_H
#define ANDROID_TOUCH_H

#include <SDL.h>
#include "Rogue.h"

/* Internal SDL event used to apply Java preferences on Brogue's game thread
 * without turning the change into gameplay/modal input. */
#define ANDROID_SETTINGS_CHANGED_EVENT_CODE 0x42505345

/*
 * Process an SDL event for Android touch gestures.
 * Returns true and fills `out` if a rogueEvent was produced.
 */
boolean androidTouchEvent(SDL_Event *event, rogueEvent *out);

/*
 * Extract bundled assets from the APK to internal storage so that
 * fopen-based game code can access them as regular files.
 * `destDir` is the app's internal files path (from SDL_GetPrefPath).
 */
void androidExtractAssets(const char *destDir);

/* Clear pending touch state. Call on game state transitions to prevent
 * events from one screen leaking into the next. */
void androidResetTouchState(void);

/* Show/hide the native Android inventory UI.
 * json is a JSON array of item objects. */
void androidShowInventory(const char *json);
void androidHideInventory(void);

/* One-shot flag armed by the Android Target action so the next THROW_KEY
 * command opens the filtered projectile/staff/wand picker. */
extern boolean androidQuickTargetSelectionRequested;

/* True while throw or zap targeting is active, so the Android Target action
 * can toggle it off by sending Escape instead of starting another flow. */
extern boolean androidTargetingActive;

/* Show/hide the native read-only Discovered Items UI. json is
 * { "sections": [ { "label", "items": [ { name, identified, polarity, pct } ] } ] }. */
void androidShowDiscoveries(const char *json);
void androidHideDiscoveries(void);

/* Show the start menu overlay (New Game / Resume / Play Seed).
 * Non-blocking — the Java callback sets rogue.nextGame when the user picks. */
void androidShowStartMenu(boolean hasSave, boolean saveCompatible,
                          int saveVariant, int saveDifficulty);

/* Show a native Android text input dialog.
 * Blocks until the user confirms or cancels.
 * On confirm, copies input into `outBuf` (up to maxLen-1 chars) and returns true.
 * On cancel, returns false (outBuf is set to empty string). */
boolean androidGetTextInput(const char *prompt, const char *defaultText,
                            int maxLen, boolean numericOnly, char *outBuf);

/* Show a centered Android Yes/No dialog and block until it is dismissed. */
boolean androidGetConfirmation(const char *prompt);

/* Camera zoom level from settings or pinch. 1.0 = full grid, >1.0 = zoomed in. */
extern float androidZoomLevel;

/* Pan offset in pixels, applied when zoomed in. */
extern float androidPanX, androidPanY;

/* True while the player is actively dragging the free-look camera. */
extern boolean androidPanOverride;

/* True after the player has entered free-look and before gameplay
 * explicitly reattaches the camera to the player. */
extern boolean androidCameraDetached;

/* True while an action is smoothly returning the camera from free-look. */
extern boolean androidCameraFastRecenter;

enum androidCameraFollowModes {
    ANDROID_CAMERA_FOLLOW_SMOOTH = 0,
    ANDROID_CAMERA_FOLLOW_FAST = 1,
    ANDROID_CAMERA_FOLLOW_INSTANT = 2,
};
extern enum androidCameraFollowModes androidCameraFollowMode;

/* Top-left physical display corner supplied by Android WindowInsets. */
extern volatile int androidTopLeftCornerRadiusPx;
extern volatile int androidTopLeftCornerCenterXPx;
extern volatile int androidTopLeftCornerCenterYPx;

/* True while Android DPAD continuous-repeat is actively repeating. */
extern boolean androidContinuousMoveActive;

/* When true, snap the camera to the player immediately instead of tweening.
 * Set on game load and stair transitions; consumed after the first frame. */
extern boolean androidCameraSnap;

/* Reattach the gameplay camera to the player and snap to center on the next
 * frame. Safe to call repeatedly. */
void androidRecenterCameraOnPlayer(void);

#endif
