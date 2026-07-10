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

/* ---- Internal state ---- */

typedef enum {
    TOUCH_IDLE,
    TOUCH_DOWN,         /* single finger down, waiting to classify */
    TOUCH_DRAGGING,     /* one-finger free-look camera pan */
    TOUCH_INSPECTING,   /* long press engaged — emitting hover events */
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

static boolean pendingMouseUp = false;
static int pendingUpX, pendingUpY;
static boolean autoMoveSessionActive = false;
boolean androidContinuousMoveActive = false;
boolean androidQuickTargetSelectionRequested = false;
boolean androidTargetingActive = false;

float androidZoomLevel = 2.0f;
float androidPanX = 0.0f, androidPanY = 0.0f;
boolean androidCameraSnap = false;
boolean androidPanOverride = false;
boolean androidCameraDetached = false;
boolean androidCameraFastRecenter = false;
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

JNIEXPORT void JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeRequestRendererRecovery(
        JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    requestRendererRecovery();
}

static void beginAutoMoveSession(void) {
    if (autoMoveSessionActive) {
        return;
    }

    autoMoveSessionActive = true;
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
    return autoMoveSessionActive && rogue.disturbed;
}

static void endAutoMoveSession(void) {
    if (!autoMoveSessionActive) {
        return;
    }

    autoMoveSessionActive = false;
    androidContinuousMoveActive = false;
    rogue.automationActive = false;
    rogue.disturbed = false;
    for (creatureIterator it = iterateCreatures(monsters); hasNextCreature(it);) {
        creature *monst = nextCreature(&it);
        monst->bookkeepingFlags &= ~MB_ALREADY_SEEN;
    }
}

void androidResetTouchState(void) {
    state = TOUCH_IDLE;
    pendingMouseUp = false;
    androidPanOverride = false;
    androidCameraDetached = false;
    androidCameraFastRecenter = false;
    endAutoMoveSession();
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
    return androidTargetingActive;
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

void androidShowStartMenu(boolean hasSave, boolean saveCompatible) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "showStartMenu", "(ZZ)V");
    if (mid) (*env)->CallVoidMethod(env, activity, mid,
                                    (jboolean)hasSave, (jboolean)saveCompatible);
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
 * When choice == START_MENU_PLAY_SEED and seed > 0, pre-populating
 * rogue.nextGameSeed causes MainMenu.c to skip its in-engine seed prompt.
 */
JNIEXPORT void JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeStartMenuResultWithSeed(
        JNIEnv *env, jobject thiz, jint choice, jlong seed) {
    if (choice == START_MENU_PLAY_SEED && seed > 0) {
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

/*
 * Remove the fixed mobile save file. Called from the Play button of any
 * seed-details modal that starts a new run — a fresh run overwrites the
 * slot, so any lingering save would never be reachable again.
 */
JNIEXPORT void JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeDeleteSaveFile(
        JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    androidDeleteSaveFile();
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
        int len = (int)strlen(textInputResult);
        if (len >= maxLen) len = maxLen - 1;
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
        strncpy(textInputResult, utf, sizeof(textInputResult) - 1);
        textInputResult[sizeof(textInputResult) - 1] = '\0';
        (*env)->ReleaseStringUTFChars(env, text, utf);
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
volatile boolean deathScreenDismissed = false;

void androidShowDeathScreen(const char *description, int turns) {
    pthread_mutex_lock(&deathMutex);
    deathFadeDone = false;
    deathScreenDismissed = false;
    pthread_mutex_unlock(&deathMutex);

    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "showDeathScreen",
        "(Ljava/lang/String;I)V");
    if (mid) {
        jstring jdesc = (*env)->NewStringUTF(env, description ? description : "");
        (*env)->CallVoidMethod(env, activity, mid, jdesc, (jint)turns);
        (*env)->DeleteLocalRef(env, jdesc);
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
    pthread_mutex_lock(&deathMutex);
    deathFadeDone = true;
    pthread_cond_signal(&deathCond);
    pthread_mutex_unlock(&deathMutex);
}

JNIEXPORT void JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeDeathScreenDismissed(
        JNIEnv *env, jobject thiz) {
    deathScreenDismissed = true;
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

static void cellFromPixel(float px, float py, int *cx, int *cy) {
    extern int windowWidth, windowHeight;

    int fitW = windowHeight * 16 / 10;
    int fitH = windowHeight;
    if (fitW > windowWidth) { fitW = windowWidth; fitH = windowWidth * 10 / 16; }

    float zoom;
    int ofsX, ofsY, w, h;

    if (getRenderMode() == RENDER_MODAL || !rogue.gameInProgress) {
        // UI layer: 1x, centered
        zoom = 1.0f;
        w = fitW;
        h = fitH;
        ofsX = (windowWidth - w) / 2;
        ofsY = (windowHeight - h) / 2;
    } else {
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

/* ---- Public API ---- */

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

        if (state == TOUCH_IDLE) {
            state = TOUCH_DOWN;
            primaryFinger = event->tfinger.fingerId;
            startX = primaryX = px;
            startY = primaryY = py;
            startTime = event->tfinger.timestamp;
        } else if (event->tfinger.fingerId != primaryFinger) {
            if (state != TOUCH_PINCHING && state != TOUCH_MULTITOUCH) {
                state = TOUCH_PINCHING;
                secondFinger = event->tfinger.fingerId;
                secondX = px;
                secondY = py;
                lastPinchDistance = fmaxf(MIN_PINCH_DISTANCE_PX,
                    dist(primaryX, primaryY, secondX, secondY));
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
