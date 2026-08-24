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
