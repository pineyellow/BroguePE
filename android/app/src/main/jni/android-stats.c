/*
 * android-stats.c — JNI dispatchers for game-lifecycle stat events.
 *
 * Bridges monster-killed / player-died / player-won / player-quit from the
 * brogue engine to BrogueActivity's corresponding Java handlers. All calls
 * are fire-and-forget: the Java handlers immediately post onto a background
 * HandlerThread and return, so the game loop's timing is unaffected.
 *
 * Call sites in the engine (Combat.c killCreature, RogueMain.c gameOver /
 * victory) guard these with !rogue.playbackMode so that save-load and
 * recording playback don't re-dispatch historical events.
 */

#include <SDL.h>
#include <jni.h>
#include <string.h>
#include "android-stats.h"
#include "Globals.h"
#include "platform.h"

static int clampColorComponent(int component) {
    return component < 0 ? 0 : (component > 100 ? 100 : component);
}

/*
 * Returns both graphical and text-font atlas indices plus a stable
 * representative catalog color for a saved monster name.
 */
JNIEXPORT jintArray JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeMonsterTileInfo(
        JNIEnv *env, jobject thiz, jstring monsterName) {
    (void)thiz;
    if (!monsterName) return NULL;

    const char *name = (*env)->GetStringUTFChars(env, monsterName, NULL);
    if (!name) return NULL;

    const creatureType *match = NULL;
    for (int i = 1; i < NUMBER_MONSTER_KINDS; i++) {
        if (strcmp(monsterCatalog[i].monsterName, name) == 0) {
            match = &monsterCatalog[i];
            break;
        }
    }
    (*env)->ReleaseStringUTFChars(env, monsterName, name);

    if (!match || match->displayChar < 128 || !match->foreColor) return NULL;

    const color *fore = match->foreColor;
    int sharedRand = fore->rand / 2;
    jint values[5] = {
        (jint)match->displayChar + 126,
        clampColorComponent(fore->red + fore->redRand / 2 + sharedRand),
        clampColorComponent(fore->green + fore->greenRand / 2 + sharedRand),
        clampColorComponent(fore->blue + fore->blueRand / 2 + sharedRand),
        textFontIndex(match->displayChar)
    };

    jintArray result = (*env)->NewIntArray(env, 5);
    if (result) {
        (*env)->SetIntArrayRegion(env, result, 0, 5, values);
    }
    return result;
}

/*
 * Returns the graphical and text-font atlas indices of the glyph the Recent
 * Games list shows for how a run ended: the amulet for a win, the doorway for
 * an abandoned run and bones for a death. The third value is 1 for a part of
 * the environment, which hybrid graphics draws as a tile.
 */
JNIEXPORT jintArray JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeRunOutcomeGlyph(
        JNIEnv *env, jobject thiz, jstring outcome) {
    (void)thiz;
    enum displayGlyph glyph = G_BONES;
    const char *name = outcome ? (*env)->GetStringUTFChars(env, outcome, NULL) : NULL;
    if (name) {
        if (strcmp(name, "escaped") == 0 || strcmp(name, "mastered") == 0) {
            glyph = G_AMULET;
        } else if (strcmp(name, "quit") == 0) {
            glyph = G_DOORWAY;
        }
        (*env)->ReleaseStringUTFChars(env, outcome, name);
    }

    // Tile sprites follow the two font-only arrows, as in fontIndex().
    jint values[3] = { (jint)glyph + 126, textFontIndex(glyph), isEnvironmentGlyph(glyph) ? 1 : 0 };
    jintArray result = (*env)->NewIntArray(env, 3);
    if (result) {
        (*env)->SetIntArrayRegion(env, result, 0, 3, values);
    }
    return result;
}

/*
 * The deaths the game words as a sentence of its own instead of a monster's
 * name, with the short label and the icon the stats screen shows for each.
 * A terrain death reads "Killed by " and the terrain's description, as in
 * Time.c, and takes that terrain's glyph and color. A cell icon is drawn
 * as the game draws that terrain's cell: its background color, with its
 * glyph on top if it has one. A gas has none, so it is just the color.
 */
typedef struct {
    const char *cause;          // NULL for "Killed by " and the terrain's description
    boolean causeIsPrefix;
    enum tileType terrain;      // NOTHING when it has none
    const char *label;
    enum displayGlyph glyph;    // used without a terrain; 0 for none
    const color *glyphColor;    // NULL for the terrain's
    const color *cellColor;     // NULL for the terrain's
    boolean cell;
} androidDeathCause;

// These game colors are too dark or too bright on a stats pill.
static const color steamIconColor =  {80, 80, 80, 0, 0, 0, 0, false};
static const color poisonIconColor = {20, 75, 20, 0, 0, 0, 0, false};
static const color fallIconColor =   {40, 40, 65, 0, 0, 0, 0, false};
// Lava as it looks in play, lit by its own glow: a gold cell with a darker orange
// glyph. Its colors before lighting are a dull orange-red.
static const color lavaIconCellColor =  {100, 75, 10, 0, 0, 0, 0, false};
static const color lavaIconGlyphColor = {90,  45,  0, 0, 0, 0, 0, false};

static const androidDeathCause androidDeathCauses[] = {
    {NULL, false, POISON_GAS,           "caustic gas",     0, NULL, NULL,            true},
    {NULL, false, STEAM,                "scalding steam",  0, NULL, &steamIconColor, true},
    {NULL, false, LAVA,                 "lava",            0, &lavaIconGlyphColor, &lavaIconCellColor, true},
    {NULL, false, LAVA_RETRACTING,      "lava",            0, &lavaIconGlyphColor, &lavaIconCellColor, true},
    // What the sacrifice altar turns into: the same lava, under its own name.
    {NULL, false, SACRIFICE_LAVA,       "sacrificial pit", 0, &lavaIconGlyphColor, &lavaIconCellColor, true},
    {NULL, false, GAS_EXPLOSION,        "explosion",       0, NULL, NULL,            false},
    {NULL, false, ANCIENT_SPIRIT_VINES, "thorned vines",   0, NULL, NULL,            false},
    {"Burned to death",          false, PLAIN_FIRE, "fire",           0,       NULL,           NULL, false},
    {"Died from poison",         false, NOTHING,    "poison",         0,       NULL, &poisonIconColor, true},
    {"Starved to death",         false, NOTHING,    "starvation",     G_FOOD,  &itemColor,     NULL, false},
    {"Drained by a cursed ring", false, NOTHING,    "cursed ring",    G_RING,  &itemColor,     NULL, false},
    // Followed by the bolt's name: lightning or flame.
    {"Killed by a reflected ",   true,  NOTHING,    "reflected bolt", G_STAFF, &itemColor,     NULL, false},
    // The chasm's black cell. Its glyph is lifted, since the game's is barely lighter than black.
    {"Killed by a fall",         false, CHASM,      "fall",           0,       &fallIconColor, NULL, true},
};

/* The same representative color as nativeMonsterTileInfo(). */
static void colorComponents(const color *c, jint out[3]) {
    int sharedRand = c->rand / 2;
    out[0] = clampColorComponent(c->red + c->redRand / 2 + sharedRand);
    out[1] = clampColorComponent(c->green + c->greenRand / 2 + sharedRand);
    out[2] = clampColorComponent(c->blue + c->blueRand / 2 + sharedRand);
}

#define ANDROID_DEATH_CAUSE_COUNT (sizeof(androidDeathCauses) / sizeof(androidDeathCauses[0]))

static boolean deathCauseMatches(const androidDeathCause *entry, const char *cause) {
    if (!entry->cause) {
        static const char prefix[] = "Killed by ";
        return strncmp(cause, prefix, sizeof(prefix) - 1) == 0
            && strcmp(cause + sizeof(prefix) - 1, tileCatalog[entry->terrain].description) == 0;
    }
    return entry->causeIsPrefix
        ? strncmp(cause, entry->cause, strlen(entry->cause)) == 0
        : strcmp(cause, entry->cause) == 0;
}

/*
 * Returns the short label for a death the game worded as a sentence, or null
 * for a monster's name or a sentence this table doesn't know.
 */
JNIEXPORT jstring JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeDeathCauseLabel(
        JNIEnv *env, jobject thiz, jstring cause) {
    (void)thiz;
    if (!cause) return NULL;
    const char *text = (*env)->GetStringUTFChars(env, cause, NULL);
    if (!text) return NULL;

    const char *label = NULL;
    for (size_t i = 0; i < ANDROID_DEATH_CAUSE_COUNT && !label; i++) {
        if (deathCauseMatches(&androidDeathCauses[i], text)) {
            label = androidDeathCauses[i].label;
        }
    }
    (*env)->ReleaseStringUTFChars(env, cause, text);
    return label ? (*env)->NewStringUTF(env, label) : NULL;
}

/*
 * Returns the icon for a short label from nativeDeathCauseLabel: the
 * graphical and text-font atlas indices of its glyph (-1 for none), 1 if the
 * glyph is part of the environment, the glyph's color, 1 for a cell icon,
 * and the cell's color. Null for any other label.
 */
JNIEXPORT jintArray JNICALL
Java_com_pineyellow_broguepe_BrogueActivity_nativeDeathCauseIcon(
        JNIEnv *env, jobject thiz, jstring label) {
    (void)thiz;
    if (!label) return NULL;
    const char *text = (*env)->GetStringUTFChars(env, label, NULL);
    if (!text) return NULL;

    const androidDeathCause *entry = NULL;
    for (size_t i = 0; i < ANDROID_DEATH_CAUSE_COUNT && !entry; i++) {
        if (strcmp(androidDeathCauses[i].label, text) == 0) {
            entry = &androidDeathCauses[i];
        }
    }
    (*env)->ReleaseStringUTFChars(env, label, text);
    if (!entry) return NULL;

    enum displayGlyph glyph = entry->glyph;
    const color *fore = NULL;
    const color *back = NULL;
    if (entry->terrain != NOTHING) {
        const floorTileType *tile = &tileCatalog[entry->terrain];
        if (tile->displayChar >= 128 && tile->foreColor) {
            glyph = tile->displayChar;
            fore = tile->foreColor;
        }
        back = tile->backColor;
    }
    if (entry->glyphColor) fore = entry->glyphColor;
    if (entry->cellColor) back = entry->cellColor;
    if (!entry->cell) back = NULL;
    if (glyph ? !fore : !back) return NULL;

    jint values[10] = {
        glyph ? (jint)glyph + 126 : -1,
        glyph ? textFontIndex(glyph) : -1,
        glyph && isEnvironmentGlyph(glyph) ? 1 : 0,
        0, 0, 0,
        back ? 1 : 0,
        0, 0, 0
    };
    if (fore) colorComponents(fore, &values[3]);
    if (back) colorComponents(back, &values[7]);
    jintArray result = (*env)->NewIntArray(env, 10);
    if (result) {
        (*env)->SetIntArrayRegion(env, result, 0, 10, values);
    }
    return result;
}

void androidNotifyGameStart(unsigned long long seed, int variant, int difficulty) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "onGameStart", "(JII)V");
    if (mid) (*env)->CallVoidMethod(env, activity, mid,
                                    (jlong)seed, (jint)variant, (jint)difficulty);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

void androidNotifyMonsterKilled(const char *monsterName) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "onMonsterKilled",
                                        "(Ljava/lang/String;)V");
    if (mid) {
        jstring jname = (*env)->NewStringUTF(env, monsterName ? monsterName : "");
        (*env)->CallVoidMethod(env, activity, mid, jname);
        (*env)->DeleteLocalRef(env, jname);
    }
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

void androidNotifyAllyFreed(const char *monsterName) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "onAllyFreed",
                                        "(Ljava/lang/String;)V");
    if (mid) {
        jstring jname = (*env)->NewStringUTF(env, monsterName ? monsterName : "");
        (*env)->CallVoidMethod(env, activity, mid, jname);
        (*env)->DeleteLocalRef(env, jname);
    }
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

void androidNotifyAllyDied(const char *monsterName) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "onAllyDied",
                                        "(Ljava/lang/String;)V");
    if (mid) {
        jstring jname = (*env)->NewStringUTF(env, monsterName ? monsterName : "");
        (*env)->CallVoidMethod(env, activity, mid, jname);
        (*env)->DeleteLocalRef(env, jname);
    }
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

void androidNotifyPlayerDied(const char *killedBy, int currentDepth,
                             int deepestDepthReached, int turns,
                             unsigned long gold) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "onPlayerDied",
                                        "(Ljava/lang/String;IIIJ)V");
    if (mid) {
        jstring jcause = (*env)->NewStringUTF(env, killedBy ? killedBy : "");
        (*env)->CallVoidMethod(env, activity, mid, jcause,
                               (jint)currentDepth, (jint)deepestDepthReached,
                               (jint)turns, (jlong)gold);
        (*env)->DeleteLocalRef(env, jcause);
    }
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

void androidNotifyPlayerWon(boolean superVictory, int currentDepth,
                            int deepestDepthReached, int turns,
                            unsigned long gold) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "onPlayerWon", "(ZIIIJ)V");
    if (mid) (*env)->CallVoidMethod(env, activity, mid,
                                    (jboolean)superVictory, (jint)currentDepth,
                                    (jint)deepestDepthReached, (jint)turns,
                                    (jlong)gold);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

void androidNotifyPlayerQuit(int currentDepth, int deepestDepthReached,
                             int turns, unsigned long gold) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "onPlayerQuit", "(IIIJ)V");
    if (mid) (*env)->CallVoidMethod(env, activity, mid,
                                    (jint)currentDepth, (jint)deepestDepthReached,
                                    (jint)turns, (jlong)gold);
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}

void androidRecordRun(const char *summaryJson, boolean finished) {
    JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    jclass cls = (*env)->GetObjectClass(env, activity);
    jmethodID mid = (*env)->GetMethodID(env, cls, "onRunSummary",
                                        "(Ljava/lang/String;Z)V");
    if (mid) {
        jstring jjson = (*env)->NewStringUTF(env, summaryJson ? summaryJson : "{}");
        (*env)->CallVoidMethod(env, activity, mid, jjson, (jboolean)finished);
        (*env)->DeleteLocalRef(env, jjson);
    }
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, activity);
}
