/*
 * android-touch.c — Gesture recognition layer for Brogue PE on Android.
 *
 * This file hooks into SDL2's event stream and translates multi-touch gestures
 * into rogueEvents that the existing game code already understands. It does NOT
 * modify any game logic — it only produces the same event types that a mouse
 * and keyboard would.
 *
 * Gesture map:
 *   Tap              → MOUSE_DOWN + MOUSE_UP  (left click at cell)
 *   Long press+drag  → MOUSE_ENTERED_CELL     (inspect / pathfind highlight)
 *   Single-finger drag → free-look camera pan
 *   Two-finger pinch  → continuous camera zoom
 *   Three-plus fingers → consumed
 *
 * With Movement set to Swipe, during a game:
 *   Single-finger swipe → move; holding it repeats, steered like a joystick
 *                         whose anchor trails the finger on a leash
 *   Two fingers         → zoom or free-look pan, whichever they do first
 *
 * A physical mouse hovers and right-clicks as on desktop, and additionally:
 *   Left click       → MOUSE_DOWN + MOUSE_UP  (on release)
 *   Left drag        → free-look camera pan
 *   Wheel            → camera zoom
 */

#include <SDL.h>
#include <jni.h>
#include <math.h>
#include <pthread.h>
#include <string.h>
#include "platform.h"
#include "Rogue.h"
#include "GlobalsBase.h"
#include "tiles.h"
#include "android-touch.h"

/* ---- Tuning constants ---- */

#define LONG_PRESS_MS         400   /* hold time for inspect mode */
#define TAP_MAX_MOVE_PX        20   /* max drift before a tap becomes a drag */
#define MIN_ZOOM_LEVEL       1.0f
#define MAX_ZOOM_LEVEL       3.0f
#define MIN_PINCH_DISTANCE_PX 8.0f
#define WHEEL_ZOOM_STEP      1.1f   /* zoom factor per mouse wheel notch */

#define ANDROID_MOVEMENT_DPAD    1     /* "movement_controls" value for D-pad */
#define ANDROID_MOVEMENT_SWIPE   2     /* "movement_controls" value for Swipe */
#define SWIPE_THRESHOLD_DP    24.0f    /* travel that turns a touch into a swipe */
#define SWIPE_DEAD_ZONE_DP    16.0f    /* this close to the anchor, a hold pauses */
#define SWIPE_LEASH_DP        40.0f    /* farthest the anchor trails the finger */
#define SWIPE_HYSTERESIS_DEG   5.0f    /* past a direction's edge before turning */
#define SWIPE_HOLD_DELAY_MS     300    /* after the first step, as on the D-pad */
#define SWIPE_REPEAT_MS          70    /* between held steps, as on the D-pad */
#define PINCH_DECIDE_DP       24.0f    /* two-finger spread or travel that picks
                                          zoom or pan, in Swipe mode */

/* ---- Internal state ---- */

typedef enum {
    TOUCH_IDLE,
    TOUCH_DOWN,         /* single finger down, waiting to classify */
    TOUCH_DRAGGING,     /* one-finger free-look camera pan */
    TOUCH_INSPECTING,   /* long press engaged — emitting hover events */
    TOUCH_SWIPING,      /* swipe to move — finger steering the player */
    TOUCH_PINCHING,     /* two-finger continuous zoom */
    TOUCH_MULTITOUCH,   /* three-plus fingers; consume until release */
} TouchState;

static TouchState state = TOUCH_IDLE;

static float startX, startY;        /* first finger down position (px) */
static Uint32 startTime;            /* timestamp of first finger down */
static SDL_FingerID primaryFinger;
static float primaryX, primaryY;

static float lastPanX, lastPanY;
static SDL_FingerID secondFinger;
static float secondX, secondY;
static float lastPinchDistance;
static float pinchStartMidX, pinchStartMidY;

/* In Swipe mode a two-finger gesture zooms or pans, whichever it does first,
 * until the fingers lift. Otherwise it always zooms. */
typedef enum {
    PINCH_UNDECIDED,
    PINCH_ZOOMING,
    PINCH_PANNING,
} PinchGesture;

static PinchGesture pinchGesture = PINCH_UNDECIDED;

/* Swipe to move. The anchor starts at the touch-down point. */
static float swipeAnchorX, swipeAnchorY;
static boolean swipeControlsEnabled = false;
/* The D-pad covers part of the sidebar, so with it the entries take taps only
 * when the player turns that on. The D-pad's view sits above the game and
 * consumes touches inside its box, so it wins where they overlap. */
static boolean sidebarTouchEnabled = false;
static float displayDensity = 1.0f;
static int swipeDirection = -1;     /* index into swipeKeys; -1 in the dead zone */
static Uint32 swipeLastStepTime;
static boolean swipeHolding = false; /* held past the delay; repeating */
static boolean swipeHalted = false;  /* walk stopped; nothing more until lift */

/* Clockwise from right, matching atan2 with screen y pointing down. */
static const char swipeKeys[8] = {
    RIGHT_KEY, DOWNRIGHT_KEY, DOWN_KEY, DOWNLEFT_KEY,
    LEFT_KEY, UPLEFT_KEY, UP_KEY, UPRIGHT_KEY,
};

/* Physical mouse: left button held, and whether it has moved far enough to
 * be a camera drag instead of a click. */
static boolean mouseHeld = false;
static boolean mouseDragging = false;
static float mouseX, mouseY;        /* press position, then last drag position (px) */

static boolean pendingMouseUp = false;
static int pendingUpX, pendingUpY;
static SDL_atomic_t autoMoveSessionActive;
/* Set by the UI thread when it sends a D-pad key and cleared by the game
 * thread when a keystroke reaches the engine, so a held direction sends its
 * next repeat only once the previous one has been taken. */
static SDL_atomic_t dpadStepPending;
boolean androidContinuousMoveActive = false;
boolean androidQuickTargetSelectionRequested = false;
/* Shared by the game thread and UI-thread JNI queries. */
static SDL_atomic_t targetingActive;

float androidZoomLevel = 2.0f;
float androidPanX = 0.0f, androidPanY = 0.0f;
boolean androidCameraSnap = false;
boolean androidPanOverride = false;
boolean androidCameraDetached = false;
boolean androidCameraFastRecenter = false;
boolean androidPointerIsMouse = false;
boolean androidDescriptionOpen = false;
enum androidCameraFollowModes androidCameraFollowMode = ANDROID_CAMERA_FOLLOW_SMOOTH;
volatile int androidTopLeftCornerRadiusPx = 0;
volatile int androidTopLeftCornerCenterXPx = 0;
volatile int androidTopLeftCornerCenterYPx = 0;

JNIEXPORT void JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeSetTopLeftRoundedCorner(
        JNIEnv *env, jobject thiz, jint radius, jint centerX, jint centerY) {
    (void)env; (void)thiz;
    androidTopLeftCornerRadiusPx = max(0, (int)radius);
    androidTopLeftCornerCenterXPx = max(0, (int)centerX);
    androidTopLeftCornerCenterYPx = max(0, (int)centerY);
}

static void beginAutoMoveSession(void) {
    if (!SDL_AtomicCAS(&autoMoveSessionActive, 0, 1)) {
        return;
    }

    androidContinuousMoveActive = false;
    rogue.disturbed = false;
    rogue.automationActive = true;
    for (creatureIterator it = iterateCreatures(monsters); hasNextCreature(it);) {
        creature *monst = nextCreature(&it);
        if (canSeeMonster(monst)) {
            monst->bookkeepingFlags |= MB_ALREADY_SEEN;
        } else {
            monst->bookkeepingFlags &= ~MB_ALREADY_SEEN;
        }
    }
}

static boolean shouldStopAutoMoveSession(void) {
    return SDL_AtomicGet(&autoMoveSessionActive) && rogue.disturbed;
}

static void endAutoMoveSession(void) {
    if (!SDL_AtomicGet(&autoMoveSessionActive)) {
        return;
    }

    // Match Brogue's other automation paths: leave the game disturbed so
    // quiet turns after releasing the D-pad continue to refresh flavor text.
    // Set this before clearing the session flag so updateFlavorText() always
    // sees at least one of the two refresh conditions during a concurrent turn.
    rogue.disturbed = true;
    SDL_AtomicSet(&autoMoveSessionActive, 0);
    androidContinuousMoveActive = false;
    rogue.automationActive = false;
    for (creatureIterator it = iterateCreatures(monsters); hasNextCreature(it);) {
        creature *monst = nextCreature(&it);
        monst->bookkeepingFlags &= ~MB_ALREADY_SEEN;
    }
}

boolean androidDpadAutoMoveSessionActive(void) {
    return SDL_AtomicGet(&autoMoveSessionActive) != 0;
}

void androidKeystrokeDelivered(void) {
    SDL_AtomicSet(&dpadStepPending, 0);
}

void androidSetTargetingActive(boolean active) {
    if (SDL_AtomicSet(&targetingActive, active) == active) {
        return;
    }

    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(
        env, cls, "onTargetingStateChanged", "(Z)V");
    if (mid) {
        (*env)->CallVoidMethod(env, activity, mid, (jboolean)active);
    }
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

/* Stops a held swipe's walk, as a monster coming into view stops a held
 * D-pad. Nothing more happens until the finger lifts. */
static void haltSwipe(void) {
    endAutoMoveSession();
    swipeHolding = false;
    swipeHalted = true;
}

/* Ends a swipe: on lift, a second finger, or a reset. */
static void endSwipe(void) {
    haltSwipe();
    swipeHalted = false;
    swipeDirection = -1;
}

void androidResetTouchState(void) {
    if (state == TOUCH_SWIPING) {
        endSwipe();
    }
    pinchGesture = PINCH_UNDECIDED;
    state = TOUCH_IDLE;
    mouseHeld = false;
    mouseDragging = false;
    pendingMouseUp = false;
    androidPanOverride = false;
    androidCameraDetached = false;
    androidCameraFastRecenter = false;
    endAutoMoveSession();
    SDL_AtomicSet(&dpadStepPending, 0);
}

void androidDiscardConsumedTouchInput(void) {
    // A swipe whose first step closed a description keeps going, like a held
    // D-pad below; its finger is still down and will end it on lift.
    if (state != TOUCH_SWIPING) {
        state = TOUCH_IDLE;
    }
    pendingMouseUp = false;
    androidPanOverride = false;
    // A held D-pad's walk is left running. The D-pad ends it on release, and
    // ending it here would turn off its stop when, say, a monster comes into
    // view, for the rest of the hold.
}

void androidSetOverlayVisible(boolean visible) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "setOverlayVisible", "(Z)V");
    if (mid) (*env)->CallVoidMethod(env, activity, mid, (jboolean)visible);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

void androidSetPlaybackVisible(boolean visible) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "setPlaybackVisible", "(Z)V");
    if (mid) (*env)->CallVoidMethod(env, activity, mid, (jboolean)visible);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

void androidPlaybackPausedChanged(boolean paused) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "onPlaybackPausedChanged", "(Z)V");
    if (mid) (*env)->CallVoidMethod(env, activity, mid, (jboolean)paused);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

void androidHideGameUI(void) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "hideGameUI", "()V");
    if (mid) (*env)->CallVoidMethod(env, activity, mid);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

void androidSetLoadingVisible(boolean visible) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "setLoadingVisible", "(Z)V");
    if (mid) (*env)->CallVoidMethod(env, activity, mid, (jboolean)visible);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

void androidSetRestoringVisible(boolean visible) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "setRestoringVisible", "(Z)V");
    if (mid) (*env)->CallVoidMethod(env, activity, mid, (jboolean)visible);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

float androidDisplayRefreshRate(void) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "getDisplayRefreshRate", "()F");
    float rate = 0.0f;
    if (mid) rate = (*env)->CallFloatMethod(env, activity, mid);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
    return rate;
}

#ifndef NDEBUG
void androidUpdateDebugFps(int fps) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "updateDebugFps", "(I)V");
    if (mid) (*env)->CallVoidMethod(env, activity, mid, (jint)fps);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}
#endif

static float androidGetDisplayDensity(void) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "getDisplayDensity", "()F");
    float density = 1.0f;
    if (mid) density = (*env)->CallFloatMethod(env, activity, mid);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
    return density;
}

static boolean androidGetSettingBool(const char *key) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "getSettingBool",
                                        "(Ljava/lang/String;)Z");
    boolean result = false;
    if (mid) {
        jstring jkey = (*env)->NewStringUTF(env, key);
        result = (*env)->CallBooleanMethod(env, activity, mid, jkey);
        (*env)->DeleteLocalRef(env, jkey);
    }
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
    return result;
}

static int androidGetSettingInt(const char *key, int defaultValue) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "getSettingInt",
                                        "(Ljava/lang/String;I)I");
    int result = defaultValue;
    if (mid) {
        jstring jkey = (*env)->NewStringUTF(env, key);
        result = (*env)->CallIntMethod(env, activity, mid, jkey, (jint)defaultValue);
        (*env)->DeleteLocalRef(env, jkey);
    }
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
    return result;
}

void androidApplySettings(void) {
    extern enum graphicsModes graphicsMode;
    extern enum graphicsModes setGraphicsMode(enum graphicsModes mode);

    rogue.trueColorMode = androidGetSettingBool("hide_color_effects");
    rogue.displayStealthRangeMode = androidGetSettingBool("display_stealth_range");
    int cameraFollowMode = androidGetSettingInt(
        "camera_follow_mode", ANDROID_CAMERA_FOLLOW_SMOOTH);
    androidCameraFollowMode = cameraFollowMode >= ANDROID_CAMERA_FOLLOW_SMOOTH
            && cameraFollowMode <= ANDROID_CAMERA_FOLLOW_INSTANT
        ? (enum androidCameraFollowModes)cameraFollowMode
        : ANDROID_CAMERA_FOLLOW_SMOOTH;
    // Before the first launch migrates it, the setting reads as the D-pad
    // default, which matches the old "enable_dpad" default.
    int movementControls = androidGetSettingInt("movement_controls",
        ANDROID_MOVEMENT_DPAD);
    swipeControlsEnabled = movementControls == ANDROID_MOVEMENT_SWIPE;
    sidebarTouchEnabled = movementControls != ANDROID_MOVEMENT_DPAD
        || androidGetSettingBool("dpad_sidebar_taps");
    displayDensity = fmaxf(0.5f, androidGetDisplayDensity());
    int mode = androidGetSettingInt("graphics_mode", TILES_GRAPHICS);
    if (mode < TEXT_GRAPHICS || mode > HYBRID_GRAPHICS) mode = TILES_GRAPHICS;
    if (mode != (int)graphicsMode) {
        graphicsMode = setGraphicsMode(mode);
    }
}

JNIEXPORT void JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeApplyGameSettings(
        JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    SDL_Event event;
    SDL_zero(event);
    event.type = SDL_USEREVENT;
    event.user.code = ANDROID_SETTINGS_CHANGED_EVENT_CODE;
    SDL_PushEvent(&event);
}

void androidShowInventory(const char *json) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jstring jstr = (*env)->NewStringUTF(env, json);
    jmethodID mid = (*env)->GetMethodID(env, cls, "showInventory", "(Ljava/lang/String;)V");
    if (mid) (*env)->CallVoidMethod(env, activity, mid, jstr);
    (*env)->DeleteLocalRef(env, jstr);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

void androidHideInventory(void) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "hideInventory", "()V");
    if (mid) (*env)->CallVoidMethod(env, activity, mid);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

void androidShowDiscoveries(const char *json) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jstring jstr = (*env)->NewStringUTF(env, json);
    jmethodID mid = (*env)->GetMethodID(env, cls, "showDiscoveries", "(Ljava/lang/String;)V");
    if (mid) (*env)->CallVoidMethod(env, activity, mid, jstr);
    (*env)->DeleteLocalRef(env, jstr);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

void androidHideDiscoveries(void) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "hideDiscoveries", "()V");
    if (mid) (*env)->CallVoidMethod(env, activity, mid);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

/* ---- Get dungeon seed ---- */

JNIEXPORT jlong JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeGetSeed(
        JNIEnv *env, jobject thiz) {
    return (jlong)rogue.seed;
}

JNIEXPORT jboolean JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeHasVisibleEnemy(
        JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    for (creatureIterator it = iterateCreatures(monsters); hasNextCreature(it);) {
        creature *monst = nextCreature(&it);
        if (monst != &player
                && canSeeMonster(monst)
                && monstersAreEnemies(&player, monst)) {
            return true;
        }
    }
    return false;
}

JNIEXPORT void JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeArmQuickTargetSelection(
        JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    androidQuickTargetSelectionRequested = true;
}

JNIEXPORT jboolean JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeIsTargetingActive(
        JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return SDL_AtomicGet(&targetingActive) != 0;
}

JNIEXPORT jboolean JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeHasSelectedTarget(
        JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return isPosInMap(rogue.cursorLoc) && !posEq(rogue.cursorLoc, player.loc);
}

JNIEXPORT void JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeBeginDpadAutoMove(
        JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    beginAutoMoveSession();
}

JNIEXPORT void JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeSetDpadRepeatActive(
        JNIEnv *env, jobject thiz, jboolean active) {
    (void)env; (void)thiz;
    androidContinuousMoveActive = active ? true : false;
}

JNIEXPORT jboolean JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeClaimDpadStep(
        JNIEnv *env, jobject thiz, jboolean firstPress) {
    (void)env; (void)thiz;
    if (firstPress) {
        // A press is always sent. Claiming unconditionally also recovers from
        // a claim left behind by a key that never reached the engine.
        SDL_AtomicSet(&dpadStepPending, 1);
        return JNI_TRUE;
    }
    return SDL_AtomicCAS(&dpadStepPending, 0, 1) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeShouldStopDpadAutoMove(
        JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return shouldStopAutoMoveSession();
}

JNIEXPORT void JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeEndDpadAutoMove(
        JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    endAutoMoveSession();
}

/* ---- Start menu ---- */

#define START_MENU_NEW_GAME  0
#define START_MENU_RESUME    1
#define START_MENU_PLAY_SEED 2

extern volatile enum NGCommands startMenuChoice; // defined in MainMenu.c
extern volatile boolean startMenuCancelled;      // defined in MainMenu.c

void androidShowStartMenu(boolean hasSave, boolean saveCompatible,
                          int saveVariant, int saveDifficulty) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "showStartMenu", "(ZZII)V");
    if (mid) (*env)->CallVoidMethod(env, activity, mid,
                                    (jboolean)hasSave, (jboolean)saveCompatible,
                                    (jint)saveVariant, (jint)saveDifficulty);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

JNIEXPORT void JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeStartMenuResult(
        JNIEnv *env, jobject thiz, jint choice) {
    switch (choice) {
        case START_MENU_NEW_GAME:  startMenuChoice = NG_NEW_GAME; break;
        case START_MENU_RESUME:    startMenuChoice = NG_OPEN_GAME; break;
        case START_MENU_PLAY_SEED: startMenuChoice = NG_NEW_GAME_WITH_SEED; break;
        default:                   startMenuChoice = NG_NEW_GAME; break;
    }
}

/*
 * Same as nativeStartMenuResult but lets the caller supply a specific seed.
 * When choice == START_MENU_PLAY_SEED and seed != 0, pre-populating
 * rogue.nextGameSeed causes MainMenu.c to skip its in-engine seed prompt.
 * Java passes the uint64_t seed's bits in a jlong, so seeds above
 * INT64_MAX arrive negative.
 */
JNIEXPORT void JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeStartMenuResultWithSeed(
        JNIEnv *env, jobject thiz, jint choice, jlong seed) {
    if (choice == START_MENU_PLAY_SEED && seed != 0) {
        rogue.nextGameSeed = (uint64_t)seed;
    }
    switch (choice) {
        case START_MENU_NEW_GAME:  startMenuChoice = NG_NEW_GAME; break;
        case START_MENU_RESUME:    startMenuChoice = NG_OPEN_GAME; break;
        case START_MENU_PLAY_SEED: startMenuChoice = NG_NEW_GAME_WITH_SEED; break;
        default:                   startMenuChoice = NG_NEW_GAME; break;
    }
}

JNIEXPORT void JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeStartMenuResultWithSeedAndVariant(
        JNIEnv *env, jobject thiz, jint choice, jlong seed, jint variant) {
    if (variant >= VARIANT_BROGUE && variant <= VARIANT_BULLET_BROGUE) {
        gameVariant = (int)variant;
    } else {
        gameVariant = VARIANT_BROGUE;
    }
    Java_com_pineyellow_broguepe_BrogueActivity_nativeStartMenuResultWithSeed(
        env, thiz, choice, seed);
}

JNIEXPORT void JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeStartMenuResultWithSeedVariantAndDifficulty(
        JNIEnv *env, jobject thiz, jint choice, jlong seed, jint variant,
        jint difficulty) {
    rogue.mode = difficulty == GAME_DIFFICULTY_EASY
        ? GAME_MODE_BALANCED_EASY
        : GAME_MODE_NORMAL;
    Java_com_pineyellow_broguepe_BrogueActivity_nativeStartMenuResultWithSeedAndVariant(
        env, thiz, choice, seed, variant);
}

/*
 * Move the fixed mobile save file aside as the abandoned run's recording.
 * Called from the Play button of any seed-details modal that starts a new
 * run: a fresh run overwrites the slot, so the save could never be resumed.
 */
JNIEXPORT void JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeSetAsideSaveFile(
        JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    androidSetAsideSaveFile();
}

/* ---- Recent Games recordings ---- */

extern char androidPendingRecordingPath[]; // defined in MainMenu.c

/*
 * Watch a Recent Games recording. Like the other start menu results, the
 * title menu picks the choice up on the game thread; the path is written
 * first so it is in place when it does.
 */
JNIEXPORT void JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeStartMenuViewRecording(
        JNIEnv *env, jobject thiz, jstring path) {
    (void)thiz;
    const char *utf = (*env)->GetStringUTFChars(env, path, NULL);
    if (!utf) return;
    snprintf(androidPendingRecordingPath, BROGUE_FILENAME_MAX, "%s", utf);
    (*env)->ReleaseStringUTFChars(env, path, utf);
    startMenuChoice = NG_VIEW_RECORDING;
}

volatile int androidPlaybackDelay = 0;

/* The playback speed control: the delay per turn, in milliseconds. The game
 * thread applies it before the next turn it plays. */
JNIEXPORT void JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeSetPlaybackDelay(
        JNIEnv *env, jobject thiz, jint delayMillis) {
    (void)env; (void)thiz;
    androidPlaybackDelay = delayMillis > 0 ? (int)delayMillis : 0;
}

/* Whether a Recent Games recording can be watched in this version. Only
 * reads the file's header, so it is fine on the UI thread. */
JNIEXPORT jboolean JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeRecordingIsPlayable(
        JNIEnv *env, jobject thiz, jstring path) {
    (void)thiz;
    const char *utf = (*env)->GetStringUTFChars(env, path, NULL);
    if (!utf) return JNI_FALSE;
    boolean playable = androidRecordingIsPlayable(utf);
    (*env)->ReleaseStringUTFChars(env, path, utf);
    return playable ? JNI_TRUE : JNI_FALSE;
}

/*
 * User tapped the start-menu backdrop. Signals the Phase 2 loop in
 * titleMenu() to break out and re-enter Phase 1 (the "BROGUE" title flames).
 */
JNIEXPORT void JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeStartMenuCancel(
        JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    startMenuCancelled = true;
}

/* ---- Native text input dialog ---- */

static pthread_mutex_t textInputMutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  textInputCond  = PTHREAD_COND_INITIALIZER;
static boolean textInputReady = false;
static boolean textInputConfirmed = false;
static char    textInputResult[256];

static void copyPrintableAscii(char *destination, size_t capacity,
                               const char *source) {
    if (!destination || capacity == 0) return;

    size_t written = 0;
    if (source) {
        for (size_t i = 0; source[i] && written + 1 < capacity; i++) {
            unsigned char byte = (unsigned char)source[i];
            if (byte >= 0x20 && byte <= 0x7e) {
                destination[written++] = (char)byte;
            }
        }
    }
    destination[written] = '\0';
}

boolean androidGetTextInput(const char *prompt, const char *defaultText,
                            int maxLen, boolean numericOnly, char *outBuf) {
    outBuf[0] = '\0';

    pthread_mutex_lock(&textInputMutex);
    textInputReady = false;
    textInputConfirmed = false;
    textInputResult[0] = '\0';
    pthread_mutex_unlock(&textInputMutex);

    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jstring jPrompt = (*env)->NewStringUTF(env, prompt);
    jstring jDefault = (*env)->NewStringUTF(env, defaultText);
    jmethodID mid = (*env)->GetMethodID(env, cls, "showTextInputDialog",
        "(Ljava/lang/String;Ljava/lang/String;IZ)V");
    if (mid) (*env)->CallVoidMethod(env, activity, mid, jPrompt, jDefault,
                                    (jint)maxLen, (jboolean)numericOnly);
    (*env)->DeleteLocalRef(env, jPrompt);
    (*env)->DeleteLocalRef(env, jDefault);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);

    /* Block until the Java dialog signals a result. */
    pthread_mutex_lock(&textInputMutex);
    while (!textInputReady) {
        pthread_cond_wait(&textInputCond, &textInputMutex);
    }
    boolean confirmed = textInputConfirmed;
    if (confirmed) {
        if (maxLen < 0) maxLen = 0;
        int len = (int)strlen(textInputResult);
        if (len > maxLen) len = maxLen;
        memcpy(outBuf, textInputResult, len);
        outBuf[len] = '\0';
    }
    pthread_mutex_unlock(&textInputMutex);

    return confirmed;
}

/* Called from Java when the text input dialog is dismissed. */
JNIEXPORT void JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeTextInputResult(
        JNIEnv *env, jobject thiz, jboolean confirmed, jstring text) {
    pthread_mutex_lock(&textInputMutex);
    textInputConfirmed = (boolean)confirmed;
    if (confirmed && text) {
        const char *utf = (*env)->GetStringUTFChars(env, text, NULL);
        copyPrintableAscii(textInputResult, sizeof(textInputResult), utf);
        if (utf) (*env)->ReleaseStringUTFChars(env, text, utf);
    } else {
        textInputResult[0] = '\0';
    }
    textInputReady = true;
    pthread_cond_signal(&textInputCond);
    pthread_mutex_unlock(&textInputMutex);
}

/* ---- Native confirmation dialog ---- */

static pthread_mutex_t confirmationMutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t confirmationCond = PTHREAD_COND_INITIALIZER;
static boolean confirmationReady = false;
static boolean confirmationResult = false;

boolean androidGetConfirmation(const char *prompt) {
    pthread_mutex_lock(&confirmationMutex);
    confirmationReady = false;
    confirmationResult = false;
    pthread_mutex_unlock(&confirmationMutex);

    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jstring jPrompt = (*env)->NewStringUTF(env, prompt);
    jmethodID mid = (*env)->GetMethodID(env, cls, "showConfirmationDialog",
                                        "(Ljava/lang/String;)V");
    if (mid) {
        (*env)->CallVoidMethod(env, activity, mid, jPrompt);
    }
    (*env)->DeleteLocalRef(env, jPrompt);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);

    pthread_mutex_lock(&confirmationMutex);
    while (!confirmationReady) {
        pthread_cond_wait(&confirmationCond, &confirmationMutex);
    }
    boolean result = confirmationResult;
    pthread_mutex_unlock(&confirmationMutex);
    resetCameraFrameClock();
    return result;
}

JNIEXPORT void JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeConfirmationResult(
        JNIEnv *env, jobject thiz, jboolean confirmed) {
    (void)env; (void)thiz;
    pthread_mutex_lock(&confirmationMutex);
    confirmationResult = (boolean)confirmed;
    confirmationReady = true;
    pthread_cond_signal(&confirmationCond);
    pthread_mutex_unlock(&confirmationMutex);
}

/* ---- Death screen (two-phase: fade then flames+modal) ---- */

static pthread_mutex_t deathMutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  deathCond  = PTHREAD_COND_INITIALIZER;
static boolean deathFadeDone = false;
static SDL_atomic_t deathScreenDismissed;

static void signalDeathFadeDone(void) {
    pthread_mutex_lock(&deathMutex);
    if (!deathFadeDone) {
        deathFadeDone = true;
        pthread_cond_broadcast(&deathCond);
    }
    pthread_mutex_unlock(&deathMutex);
}

void androidShowDeathScreen(const char *summaryJson) {
    pthread_mutex_lock(&deathMutex);
    deathFadeDone = false;
    SDL_AtomicSet(&deathScreenDismissed, 0);
    pthread_mutex_unlock(&deathMutex);

    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "showDeathScreen",
        "(Ljava/lang/String;)V");
    if (mid) {
        jstring jjson = (*env)->NewStringUTF(env, summaryJson ? summaryJson : "{}");
        (*env)->CallVoidMethod(env, activity, mid, jjson);
        (*env)->DeleteLocalRef(env, jjson);
    }
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);

    pthread_mutex_lock(&deathMutex);
    while (!deathFadeDone) {
        pthread_cond_wait(&deathCond, &deathMutex);
    }
    pthread_mutex_unlock(&deathMutex);
}

JNIEXPORT void JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeDeathFadeDone(
        JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    signalDeathFadeDone();
}

JNIEXPORT void JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeDeathScreenDismissed(
        JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    SDL_AtomicSet(&deathScreenDismissed, 1);
}

JNIEXPORT void JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeCancelDeathScreen(
        JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    pthread_mutex_lock(&deathMutex);
    deathFadeDone = true;
    SDL_AtomicSet(&deathScreenDismissed, 1);
    // Teardown must release the game thread even if no animation callback can
    // run. Broadcast also makes this safe if the wait implementation changes.
    pthread_cond_broadcast(&deathCond);
    pthread_mutex_unlock(&deathMutex);
}

boolean androidIsDeathScreenDismissed(void) {
    return SDL_AtomicGet(&deathScreenDismissed) != 0;
}

void androidDeathFlamesReady(void) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "onDeathFlamesReady", "()V");
    if (mid) (*env)->CallVoidMethod(env, activity, mid);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

/* ---- Helpers ---- */

void androidRecenterCameraOnPlayer(void) {
    if (!androidCameraDetached && !androidPanOverride) {
        return;
    }
    androidCameraDetached = false;
    androidPanOverride = false;
    androidCameraSnap = false;
    androidCameraFastRecenter = true;
}

static float dist(float x1, float y1, float x2, float y2) {
    float dx = x2 - x1, dy = y2 - y1;
    return sqrtf(dx * dx + dy * dy);
}

/* The sidebar is drawn at 1x against the left edge, over the zoomed dungeon
 * (see tiles.c). A touch on a row that lists something gets that sidebar
 * cell; anywhere else falls through to the dungeon drawn behind it. */
static boolean sidebarCellFromPixel(float px, float py, int fitW, int fitH,
                                    int *cx, int *cy) {
    extern int windowHeight;

    int baseY = (windowHeight - fitH) / 2;
    if (py < baseY) return false;
    int row = (int)((py - baseY) * ROWS / fitH);
    if (row >= ROWS - 1 || !isPosInMap(rogue.sidebarLocationList[row])) {
        return false;
    }
    int rowTop = baseY + row * fitH / ROWS;
    int rowHeight = (row + 1) * fitH / ROWS - row * fitH / ROWS;
    float cellWidth = (float)fitW / COLS;
    int offset = sidebarOffsetPixels(rowTop + rowHeight / 2, cellWidth);
    int column = (int)floorf((px - offset) / cellWidth);
    if (column >= STAT_BAR_WIDTH) return false;
    *cx = column < 0 ? 0 : column;  /* the margin left of it counts too */
    *cy = row;
    return true;
}

static void cellFromPixel(float px, float py, int *cx, int *cy) {
    extern int windowWidth, windowHeight;

    int fitW = windowHeight * 16 / 10;
    int fitH = windowHeight;
    if (fitW > windowWidth) { fitW = windowWidth; fitH = windowWidth * 10 / 16; }

    float zoom;
    int ofsX, ofsY, w, h;

    /* A description box opened under a hovering mouse is drawn as a modal,
     * but the mouse keeps addressing the dungeon tiles underneath it. */
    boolean mouseOverDungeon = androidDescriptionOpen && androidPointerIsMouse;

    if ((getRenderMode() == RENDER_MODAL && !mouseOverDungeon) || !rogue.gameInProgress) {
        // UI layer: 1x, centered
        zoom = 1.0f;
        w = fitW;
        h = fitH;
        ofsX = (windowWidth - w) / 2;
        ofsY = (windowHeight - h) / 2;
    } else {
        if (sidebarTouchEnabled
                && sidebarCellFromPixel(px, py, fitW, fitH, cx, cy)) {
            return;
        }
        // Dungeon layer: zoomed + panned
        zoom = androidZoomLevel;
        float panX = androidPanX;
        float panY = androidPanY;
        SDL_Rect viewport;
        calculateDungeonViewport(windowWidth, windowHeight, zoom,
                                 &panX, &panY, &viewport);
        w = viewport.w;
        h = viewport.h;
        ofsX = viewport.x;
        ofsY = viewport.y;
    }

    *cx = (int)((px - ofsX) * COLS / w);
    *cy = (int)((py - ofsY) * ROWS / h);
    if (*cx < 0) *cx = 0;
    if (*cx >= COLS) *cx = COLS - 1;
    if (*cy < 0) *cy = 0;
    if (*cy >= ROWS) *cy = ROWS - 1;
}

/* ---- Swipe to move ---- */

/* Only during play: on the title screen a direction key is a menu hotkey. */
static boolean swipeAllowed(void) {
    return swipeControlsEnabled && rogue.gameInProgress && !rogue.gameHasEnded
        && !rogue.playbackMode;
}

/* The direction for a finger at (dx, dy) from the anchor, or -1 inside the
 * dead zone. Keeps `current` until the finger is clearly in another
 * direction's slice, so a slowly drifting thumb does not flip between
 * neighbours. */
static int swipeSectorFor(float dx, float dy, int current) {
    float deadZone = SWIPE_DEAD_ZONE_DP * displayDensity;
    if (dx * dx + dy * dy < deadZone * deadZone) {
        return -1;
    }
    float angle = atan2f(dy, dx) * 57.29578f; /* degrees, -180 to 180 */
    if (current >= 0
            && fabsf(remainderf(angle - current * 45.0f, 360.0f))
                <= 22.5f + SWIPE_HYSTERESIS_DEG) {
        return current;
    }
    return ((int)lroundf(angle / 45.0f) + 8) % 8;
}

/* Points the swipe at the finger. The anchor trails the finger on a leash,
 * so however far the finger has travelled, a short swipe back reverses. */
static void steerSwipe(float px, float py) {
    float dx = px - swipeAnchorX;
    float dy = py - swipeAnchorY;
    float distance = sqrtf(dx * dx + dy * dy);
    float leash = SWIPE_LEASH_DP * displayDensity;
    if (distance > leash) {
        /* Pull the anchor along the same line, so the angle is unchanged. */
        swipeAnchorX = px - dx * leash / distance;
        swipeAnchorY = py - dy * leash / distance;
    }
    swipeDirection = swipeSectorFor(dx, dy, swipeDirection);
}

static void setSwipeKey(rogueEvent *out) {
    out->eventType = KEYSTROKE;
    out->param1 = swipeKeys[swipeDirection];
    out->param2 = 0;
    out->shiftKey = false;
    out->controlKey = false;
}

boolean androidSwipeRepeatEvent(rogueEvent *out) {
    if (state != TOUCH_SWIPING || swipeHalted) {
        return false;
    }
    if (!swipeAllowed() || !SDL_AtomicGet(&autoMoveSessionActive)
            || rogue.disturbed) {
        haltSwipe();
        return false;
    }
    if (swipeDirection < 0) {
        return false; /* paused in the dead zone */
    }
    Uint32 now = SDL_GetTicks();
    Uint32 wait = swipeHolding ? SWIPE_REPEAT_MS : SWIPE_HOLD_DELAY_MS;
    if (now - swipeLastStepTime < wait) {
        return false;
    }
    swipeHolding = true;
    swipeLastStepTime = now;
    setSwipeKey(out);
    return true;
}

/* ---- Public API ---- */

boolean androidSidebarTouchEnabled(void) {
    return sidebarTouchEnabled;
}

void androidCellFromPixel(int px, int py, int *cx, int *cy) {
    cellFromPixel((float)px, (float)py, cx, cy);
}

boolean androidMouseButton(boolean pressed, int px, int py, rogueEvent *out) {
    androidPointerIsMouse = true;
    if (pressed) {
        mouseHeld = true;
        mouseDragging = false;
        mouseX = px;
        mouseY = py;
        return false;
    }
    if (!mouseHeld) {
        return false;
    }
    mouseHeld = false;
    if (mouseDragging) {
        mouseDragging = false;
        androidPanOverride = false;
        return false;
    }

    /* Released without dragging → left click (down now, up queued) */
    int cx, cy;
    cellFromPixel(px, py, &cx, &cy);
    out->eventType = MOUSE_DOWN;
    out->param1 = cx;
    out->param2 = cy;
    out->shiftKey = false;
    out->controlKey = false;
    pendingMouseUp = true;
    pendingUpX = cx;
    pendingUpY = cy;
    return true;
}

boolean androidMouseMotion(int px, int py) {
    androidPointerIsMouse = true;
    if (!mouseHeld) {
        return false;
    }
    if (!mouseDragging && dist(mouseX, mouseY, px, py) > TAP_MAX_MOVE_PX) {
        /* Crossed the click threshold: detach and begin free-look. */
        mouseDragging = true;
        androidPanOverride = true;
        androidCameraDetached = true;
        androidCameraFastRecenter = false;
    }
    if (mouseDragging) {
        androidPanX += px - mouseX;
        androidPanY += py - mouseY;
        mouseX = px;
        mouseY = py;
    }
    return true;
}

void androidMouseWheel(int notches) {
    if (!notches || !rogue.gameInProgress) {
        return;
    }
    float oldZoom = androidZoomLevel;
    androidZoomLevel = fminf(MAX_ZOOM_LEVEL,
        fmaxf(MIN_ZOOM_LEVEL, oldZoom * powf(WHEEL_ZOOM_STEP, (float)notches)));
    if (androidCameraDetached) {
        /* Free-look: keep the point at the centre of the screen in place. */
        androidPanX *= androidZoomLevel / oldZoom;
        androidPanY *= androidZoomLevel / oldZoom;
    } else {
        /* Same as pinch: keep an attached camera locked to the player. */
        androidCameraSnap = true;
    }
}

boolean androidTouchEvent(SDL_Event *event, rogueEvent *out) {
    extern int windowWidth, windowHeight;

    /* Deliver queued MOUSE_UP from a previous tap */
    if (pendingMouseUp) {
        pendingMouseUp = false;
        out->eventType = MOUSE_UP;
        out->param1 = pendingUpX;
        out->param2 = pendingUpY;
        out->shiftKey = false;
        out->controlKey = false;
        return true;
    }

    /* Called with NULL event just to check pending queue. */
    if (!event) {
        return false;
    }

    /* Convert normalized touch coords to pixels */
    float px, py;

    switch (event->type) {

    case SDL_FINGERDOWN:
        px = event->tfinger.x * windowWidth;
        py = event->tfinger.y * windowHeight;
        androidPointerIsMouse = false;

        if (state == TOUCH_IDLE) {
            state = TOUCH_DOWN;
            primaryFinger = event->tfinger.fingerId;
            startX = primaryX = px;
            startY = primaryY = py;
            startTime = event->tfinger.timestamp;
        } else if (event->tfinger.fingerId != primaryFinger) {
            if (state != TOUCH_PINCHING && state != TOUCH_MULTITOUCH) {
                if (state == TOUCH_SWIPING) {
                    /* A second finger ends the swipe and starts a pinch. */
                    endSwipe();
                }
                state = TOUCH_PINCHING;
                secondFinger = event->tfinger.fingerId;
                secondX = px;
                secondY = py;
                lastPinchDistance = fmaxf(MIN_PINCH_DISTANCE_PX,
                    dist(primaryX, primaryY, secondX, secondY));
                pinchStartMidX = (primaryX + secondX) / 2.0f;
                pinchStartMidY = (primaryY + secondY) / 2.0f;
                pinchGesture = PINCH_UNDECIDED;
                androidPanOverride = true;
            } else if (state == TOUCH_PINCHING
                    && event->tfinger.fingerId != secondFinger) {
                androidPanOverride = false;
                state = TOUCH_MULTITOUCH;
            }
        }
        return false;

    case SDL_FINGERMOTION:
        px = event->tfinger.x * windowWidth;
        py = event->tfinger.y * windowHeight;

        if (event->tfinger.fingerId == primaryFinger) {
            primaryX = px;
            primaryY = py;
        } else if (state == TOUCH_PINCHING
                && event->tfinger.fingerId == secondFinger) {
            secondX = px;
            secondY = py;
        }

        if (state == TOUCH_PINCHING) {
            float pinchDistance = fmaxf(MIN_PINCH_DISTANCE_PX,
                dist(primaryX, primaryY, secondX, secondY));
            float midX = (primaryX + secondX) / 2.0f;
            float midY = (primaryY + secondY) / 2.0f;
            if (swipeAllowed() && pinchGesture == PINCH_UNDECIDED) {
                /* One finger moves the player, so two fingers zoom or pan.
                 * Until one is picked nothing moves, and lastPinchDistance
                 * stays at the start, so the picked one loses no travel. */
                float threshold = PINCH_DECIDE_DP * displayDensity;
                if (fabsf(pinchDistance - lastPinchDistance) > threshold) {
                    pinchGesture = PINCH_ZOOMING;
                } else if (dist(pinchStartMidX, pinchStartMidY, midX, midY)
                        > threshold) {
                    /* Enter free-look. */
                    pinchGesture = PINCH_PANNING;
                    androidCameraDetached = true;
                    androidCameraFastRecenter = false;
                    lastPanX = pinchStartMidX;
                    lastPanY = pinchStartMidY;
                } else {
                    return false;
                }
            }
            if (pinchGesture == PINCH_PANNING) {
                androidPanX += midX - lastPanX;
                androidPanY += midY - lastPanY;
                lastPanX = midX;
                lastPanY = midY;
                return false;
            }
            androidZoomLevel = fminf(MAX_ZOOM_LEVEL,
                fmaxf(MIN_ZOOM_LEVEL,
                    androidZoomLevel * pinchDistance / lastPinchDistance));
            // Keep an attached camera locked to the player as the viewport
            // scale changes. Free-look remains detached and zooms in place.
            if (!androidCameraDetached) {
                androidCameraSnap = true;
            }
            lastPinchDistance = pinchDistance;
            return false;
        }

        if (state == TOUCH_DOWN && event->tfinger.fingerId == primaryFinger) {
            Uint32 elapsed = event->tfinger.timestamp - startTime;
            float moved = dist(startX, startY, px, py);

            if (elapsed >= LONG_PRESS_MS) {
                /* Held long enough — enter inspect mode */
                state = TOUCH_INSPECTING;
                int cx, cy;
                cellFromPixel(px, py, &cx, &cy);
                out->eventType = MOUSE_ENTERED_CELL;
                out->param1 = cx;
                out->param2 = cy;
                out->shiftKey = false;
                out->controlKey = false;
                return true;
            } else if (swipeAllowed()) {
                if (moved > SWIPE_THRESHOLD_DP * displayDensity) {
                    /* Crossed the swipe threshold: step now, and keep
                     * stepping while held. The threshold is beyond the dead
                     * zone, so this always picks a direction. */
                    state = TOUCH_SWIPING;
                    swipeHolding = false;
                    swipeHalted = false;
                    swipeAnchorX = startX;
                    swipeAnchorY = startY;
                    swipeDirection = -1;
                    steerSwipe(px, py);
                    swipeLastStepTime = SDL_GetTicks();
                    beginAutoMoveSession();
                    setSwipeKey(out);
                    return true;
                }
            } else if (moved > TAP_MAX_MOVE_PX) {
                /* Crossed the tap threshold: detach and begin free-look. */
                state = TOUCH_DRAGGING;
                androidPanOverride = true;
                androidCameraDetached = true;
                androidCameraFastRecenter = false;
                lastPanX = startX;
                lastPanY = startY;
            }
        }

        if (state == TOUCH_DRAGGING && event->tfinger.fingerId == primaryFinger) {
            androidPanX += px - lastPanX;
            androidPanY += py - lastPanY;
            lastPanX = px;
            lastPanY = py;
        } else if (state == TOUCH_SWIPING && event->tfinger.fingerId == primaryFinger) {
            /* Steer: the next held step goes the way the finger now points. */
            if (!swipeHalted) {
                steerSwipe(px, py);
            }
        } else if (state == TOUCH_INSPECTING && event->tfinger.fingerId == primaryFinger) {
            /* Drag during inspect — emit hover events */
            int cx, cy;
            cellFromPixel(px, py, &cx, &cy);
            out->eventType = MOUSE_ENTERED_CELL;
            out->param1 = cx;
            out->param2 = cy;
            out->shiftKey = false;
            out->controlKey = false;
            return true;
        }
        return false;

    case SDL_FINGERUP:
        px = event->tfinger.x * windowWidth;
        py = event->tfinger.y * windowHeight;

        if (state == TOUCH_DOWN && event->tfinger.fingerId == primaryFinger) {
            Uint32 elapsed = event->tfinger.timestamp - startTime;
            float moved = dist(startX, startY, px, py);

            if (moved <= TAP_MAX_MOVE_PX && elapsed < LONG_PRESS_MS) {
                /* Tap → left click (down now, up queued) */
                int cx, cy;
                cellFromPixel(startX, startY, &cx, &cy);
                out->eventType = MOUSE_DOWN;
                out->param1 = cx;
                out->param2 = cy;
                out->shiftKey = false;
                out->controlKey = false;
                pendingMouseUp = true;
                pendingUpX = cx;
                pendingUpY = cy;
                state = TOUCH_IDLE;
                return true;
            }
            state = TOUCH_IDLE;
            return false;
        }

        if (state == TOUCH_DRAGGING && event->tfinger.fingerId == primaryFinger) {
            androidPanOverride = false;
            state = TOUCH_IDLE;
            return false;
        }

        if (state == TOUCH_SWIPING && event->tfinger.fingerId == primaryFinger) {
            endSwipe();
            state = TOUCH_IDLE;
            return false;
        }

        if (state == TOUCH_INSPECTING && event->tfinger.fingerId == primaryFinger) {
            /* Lift after inspecting — click where we lifted */
            int cx, cy;
            cellFromPixel(px, py, &cx, &cy);
            out->eventType = MOUSE_DOWN;
            out->param1 = cx;
            out->param2 = cy;
            out->shiftKey = false;
            out->controlKey = false;
            pendingMouseUp = true;
            pendingUpX = cx;
            pendingUpY = cy;
            state = TOUCH_IDLE;
            return true;
        }

        if (state == TOUCH_PINCHING) {
            androidPanOverride = false;
            pinchGesture = PINCH_UNDECIDED;
            state = TOUCH_IDLE;
            return false;
        }

        if (state == TOUCH_MULTITOUCH) {
            androidPanOverride = false;
            state = TOUCH_IDLE;
            return false;
        }

        state = TOUCH_IDLE;
        return false;
    }

    return false;
}
