/*
 *  Recordings.c
 *  Brogue
 *
 *  Created by Brian Walker on 8/8/10.
 *  Copyright 2012. All rights reserved.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

#include <time.h>
#include <math.h>
#include <limits.h>
#include "Rogue.h"
#include "GlobalsBase.h"
#include "Globals.h"
#include "platform.h"
#ifdef BROGUE_ANDROID
#include <dirent.h>
#include <unistd.h>
#include <sys/stat.h>
#include "android-stats.h"
#endif

#define RECORDING_HEADER_LENGTH     36  // bytes at the start of the recording file to store global data

static const long keystrokeTable[] = {UP_ARROW, LEFT_ARROW, DOWN_ARROW, RIGHT_ARROW,
    ESCAPE_KEY, RETURN_KEY, DELETE_KEY, TAB_KEY, NUMPAD_0, NUMPAD_1,
    NUMPAD_2, NUMPAD_3, NUMPAD_4, NUMPAD_5, NUMPAD_6, NUMPAD_7, NUMPAD_8, NUMPAD_9};

static const int keystrokeCount = sizeof(keystrokeTable) / sizeof(*keystrokeTable);

enum recordingSeekModes {
    RECORDING_SEEK_MODE_TURN,
    RECORDING_SEEK_MODE_DEPTH
};

static void recordChar(unsigned char c) {
    if (locationInRecordingBuffer < INPUT_RECORD_BUFFER_MAX_SIZE) {
        inputRecordBuffer[locationInRecordingBuffer++] = c;
        recordingLocation++;
    } else {
        printf("Recording buffer length exceeded at location %li! Turn number %li.\n", recordingLocation - 1, rogue.playerTurnNumber);
    }
}

static void considerFlushingBufferToFile() {
    if (locationInRecordingBuffer >= INPUT_RECORD_BUFFER) {
        flushBufferToFile();
    }
}

// compresses a int into a char, discarding stuff we don't need
static unsigned char compressKeystroke(long c) {
    short i;

    for (i = 0; i < keystrokeCount; i++) {
        if (keystrokeTable[i] == c) {
            return (unsigned char) (128 + i);
        }
    }
    if (c < 256) {
        return (unsigned char) c;
    }
    return UNKNOWN_KEY;
}

static void numberToString(uint64_t number, short numberOfBytes, unsigned char *recordTo) {
    short i;
    uint64_t n;

    n = number;
    for (i=numberOfBytes - 1; i >= 0; i--) {
        recordTo[i] = n % 256;
        n /= 256;
    }
    brogueAssert(n == 0);
}

// numberOfBytes can't be greater than 10
static void recordNumber(unsigned long number, short numberOfBytes) {
    short i;
    unsigned char c[10];

    numberToString(number, numberOfBytes, c);
    for (i=0; i<numberOfBytes; i++) {
        recordChar(c[i]);
    }
}

// Events are recorded as follows:
// Keystrokes: Event type, keystroke value, modifier flags. (3 bytes.)
// All other events: Event type, x-coordinate of the event, y-coordinate of the event, modifier flags. (4 bytes.)
// Note: these must be sanitized, because user input may contain more than one byte per parameter.
void recordEvent(rogueEvent *event) {
    unsigned char c;

    if (rogue.playbackMode) {
        return;
    }

    recordChar((unsigned char) event->eventType);

    if (event->eventType == KEYSTROKE) {
        // record which key
        c = compressKeystroke(event->param1);
        if (c == UNKNOWN_KEY) {
            return;
        }
        recordChar(c);
    } else {
        recordChar((unsigned char) event->param1);
        recordChar((unsigned char) event->param2);
    }

    // record the modifier keys
    c = 0;
    if (event->controlKey) {
        c += Fl(1);
    }
    if (event->shiftKey) {
        c += Fl(2);
    }
    recordChar(c);
}

// For convenience.
void recordKeystroke(int keystroke, boolean controlKey, boolean shiftKey) {
    rogueEvent theEvent;

    if (rogue.playbackMode) {
        return;
    }

    theEvent.eventType = KEYSTROKE;
    theEvent.param1 = keystroke;
    theEvent.controlKey = controlKey;
    theEvent.shiftKey = shiftKey;
    recordEvent(&theEvent);
}

void cancelKeystroke() {
    brogueAssert(locationInRecordingBuffer >= 3);
    locationInRecordingBuffer -= 3; // a keystroke is encoded into 3 bytes
    recordingLocation -= 3;
}

// record a series of keystrokes; string must end with a null terminator
void recordKeystrokeSequence(unsigned char *keystrokeSequence) {
    short i;
    for (i=0; keystrokeSequence[i] != '\0'; i++) {
        recordKeystroke(keystrokeSequence[i], false, false);
    }
}

// For convenience.
void recordMouseClick(short x, short y, boolean controlKey, boolean shiftKey) {
    rogueEvent theEvent;

    if (rogue.playbackMode) {
        return;
    }

    theEvent.eventType = MOUSE_UP;
    theEvent.param1 = x;
    theEvent.param2 = y;
    theEvent.controlKey = controlKey;
    theEvent.shiftKey = shiftKey;
    recordEvent(&theEvent);
}

static void writeHeaderInfo(char *path) {
    unsigned char c[RECORDING_HEADER_LENGTH];
    short i;
    FILE *recordFile;

    // Zero out the entire header to start.
    for (i=0; i<RECORDING_HEADER_LENGTH; i++) {
        c[i] = 0;
    }

    // Note the version string to gracefully deny compatibility when necessary.
    for (i = 0; rogue.versionString[i] != '\0'; i++) {
        c[i] = rogue.versionString[i];
    }
    c[15] = rogue.mode;
    i = 16;
    numberToString(rogue.seed, 8, &c[i]);
    i += 8;
    numberToString(rogue.playerTurnNumber, 4, &c[i]);
    i += 4;
    numberToString(rogue.deepestLevel, 4, &c[i]);
    i += 4;
    numberToString(lengthOfPlaybackFile, 4, &c[i]);
    i += 4;

    if (!fileExists(path)) {
        recordFile = fopen(path, "wb");
        if (recordFile) {
            fclose(recordFile);
        }
    }

    recordFile = fopen(path, "r+b");
    rewind(recordFile);
    for (i=0; i<RECORDING_HEADER_LENGTH; i++) {
        putc(c[i], recordFile);
    }
    if (recordFile) {
        fclose(recordFile);
    }

    if (lengthOfPlaybackFile < RECORDING_HEADER_LENGTH) {
        lengthOfPlaybackFile = RECORDING_HEADER_LENGTH;
    }
}

void flushBufferToFile() {
    if (rogue.playbackMode) {
        return;
    }

    if (!currentFilePath[0] == '\0') {
        short i;
        FILE *recordFile;

        lengthOfPlaybackFile += locationInRecordingBuffer;
        writeHeaderInfo(currentFilePath);

        if (locationInRecordingBuffer != 0) {

            recordFile = fopen(currentFilePath, "ab");

            for (i=0; i<locationInRecordingBuffer; i++) {
                putc(inputRecordBuffer[i], recordFile);
            }

            if (recordFile) {
                fclose(recordFile);
            }
        }
    }
    locationInRecordingBuffer = 0;
}

void fillBufferFromFile() {
//  short i;
    FILE *recordFile;

    recordFile = fopen(currentFilePath, "rb");
    fseek(recordFile, positionInPlaybackFile, SEEK_SET);

    fread((void *) inputRecordBuffer, 1, INPUT_RECORD_BUFFER, recordFile);

    positionInPlaybackFile = ftell(recordFile);
    fclose(recordFile);

    locationInRecordingBuffer = 0;
}

static unsigned char recallChar() {
    unsigned char c;
    if (recordingLocation > lengthOfPlaybackFile) {
        return END_OF_RECORDING;
    }
    c = inputRecordBuffer[locationInRecordingBuffer++];
    recordingLocation++;
    if (locationInRecordingBuffer >= INPUT_RECORD_BUFFER) {
        fillBufferFromFile();
    }
    return c;
}

static long uncompressKeystroke(unsigned char c) {
    if (c >= 128 && (c - 128) < keystrokeCount) {
        return keystrokeTable[c - 128];
    }
    return (long)c;
}

static uint64_t recallNumber(short numberOfBytes) {
    short i;
    uint64_t n;

    n = 0;

    for (i=0; i<numberOfBytes; i++) {
        n *= 256;
        n += (uint64_t) recallChar();
    }
    return n;
}

// While a recording is watched: the turn on which it first reached each depth, as far as it has
// played. Going back a depth replays exactly that far, so its loading bar fills evenly.
#define ANDROID_TRACKED_DEPTHS 128
static boolean androidDepthArrivalKnown[ANDROID_TRACKED_DEPTHS];
static unsigned long androidDepthArrivalTurn[ANDROID_TRACKED_DEPTHS];

void androidForgetDepthArrivals(void) {
    memset(androidDepthArrivalKnown, 0, sizeof(androidDepthArrivalKnown));
}

// Called before each recorded input is read, so nothing has happened since the player arrived.
static void androidNoteDepthArrival(void) {
    short depth = rogue.depthLevel;
    if (depth > 0 && depth < ANDROID_TRACKED_DEPTHS && !androidDepthArrivalKnown[depth]) {
        androidDepthArrivalKnown[depth] = true;
        androidDepthArrivalTurn[depth] = rogue.playerTurnNumber;
    }
}

#define OOS_APOLOGY "Playback of the recording has diverged from the originally recorded game.\n\n\
This could be caused by recording or playing the file on a modified version of Brogue, or it could \
simply be the result of a bug.\n\n\
If this is a different computer from the one on which the recording was saved, the recording \
might succeed on the original computer."

static void playbackPanic() {

    if (!rogue.playbackOOS) {
        rogue.playbackFastForward = false;
        rogue.playbackPaused = true;
        androidPlaybackPausedChanged(true);
        rogue.playbackOOS = true;
        rogue.creaturesWillFlashThisTurn = false;
        blackOutScreen();
        displayLevel();
        refreshSideBar(-1, -1, false);

        confirmMessages();
        message("Playback is out of sync.", 0);

        const SavedDisplayBuffer rbuf = saveDisplayBuffer();
        printTextBox(OOS_APOLOGY, 0, 0, 0, &white, &black, NULL, 0);

        rogue.playbackMode = false;
        displayMoreSign();
        rogue.playbackMode = true;

        restoreDisplayBuffer(&rbuf);

        if (nonInteractivePlayback) {
            rogue.gameHasEnded = true;
        }
        rogue.gameExitStatusCode = EXIT_STATUS_FAILURE_RECORDING_OOS;

        printf("Playback panic at location %li! Turn number %li.\n", recordingLocation - 1, rogue.playerTurnNumber);
        restoreDisplayBuffer(&rbuf);

        mainInputLoop();
    }
}

void recallEvent(rogueEvent *event) {
    unsigned char c;
    boolean tryAgain;

    if (androidWatchingRecording) {
        androidNoteDepthArrival();
    }

    do {
        tryAgain = false;
        c = recallChar();
        event->eventType = c;

        switch (c) {
            case KEYSTROKE:
                // record which key
                event->param1 = uncompressKeystroke(recallChar());
                event->param2 = 0;
                break;
            case SAVED_GAME_LOADED:
                tryAgain = true;
                if (!androidWatchingRecording) {
                    // Every Resume adds one, and on Android that happens after each restart.
                    flashTemporaryAlert(" Saved game loaded ", 1000);
                }
                if (androidWatchingRecording && recordingLocation >= lengthOfPlaybackFile) {
                    // Resumed, then abandoned before another input. The game ends here, so
                    // the event is left as this marker, which nothing acts on.
                    androidEndOfRecording();
                }
                break;
            case MOUSE_UP:
            case MOUSE_DOWN:
            case MOUSE_ENTERED_CELL:
            case RIGHT_MOUSE_UP:
            case RIGHT_MOUSE_DOWN:
                event->param1 = recallChar();
                event->param2 = recallChar();
                break;
            case RNG_CHECK:
            case END_OF_RECORDING:
            case EVENT_ERROR:
            default:
                message("Unrecognized event type in playback.", REQUIRE_ACKNOWLEDGMENT);
                printf("Unrecognized event type in playback: event ID %i", c);
                tryAgain = true;
                playbackPanic();
                break;
        }
    } while (tryAgain && !rogue.gameHasEnded);

    // record the modifier keys
    c = recallChar();
    event->controlKey = (c & Fl(1)) ? true : false;
    event->shiftKey =   (c & Fl(2)) ? true : false;
}

static void loadNextAnnotation() {
    unsigned long currentReadTurn;
    short i;
    FILE *annotationFile;

    if (rogue.nextAnnotationTurn == -1) {
        return;
    }

    annotationFile =  fopen(annotationPathname, "r");
    fseek(annotationFile, rogue.locationInAnnotationFile, SEEK_SET);

    for (;;) {

        // load turn number
        if (fscanf(annotationFile, "%lu\t", &(currentReadTurn)) != 1) {
            if (feof(annotationFile)) {
                rogue.nextAnnotation[0] = '\0';
                rogue.nextAnnotationTurn = -1;
                break;
            } else {
                // advance to the end of the line
                fgets(rogue.nextAnnotation, 5000, annotationFile);
                continue;
            }
        }

        // load description
        fgets(rogue.nextAnnotation, 5000, annotationFile);

        if (currentReadTurn > rogue.playerTurnNumber ||
            (currentReadTurn <= 1 && rogue.playerTurnNumber <= 1 && currentReadTurn >= rogue.playerTurnNumber)) {
            rogue.nextAnnotationTurn = currentReadTurn;

            // strip the newline off the end
            rogue.nextAnnotation[strlen(rogue.nextAnnotation) - 1] = '\0';
            // strip out any gremlins in the annotation
            for (i=0; i<5000 && rogue.nextAnnotation[i]; i++) {
                if (rogue.nextAnnotation[i] < ' '
                    || rogue.nextAnnotation[i] > '~') {
                    rogue.nextAnnotation[i] = ' ';
                }
            }
            break;
        }
    }
    rogue.locationInAnnotationFile = ftell(annotationFile);
    fclose(annotationFile);
}

void displayAnnotation() {
    if (rogue.playbackMode
        && rogue.playerTurnNumber == rogue.nextAnnotationTurn) {

        if (!rogue.playbackFastForward) {
            refreshSideBar(-1, -1, false);

            const SavedDisplayBuffer rbuf = saveDisplayBuffer();
            printTextBox(rogue.nextAnnotation, player.loc.x, 0, 0, &black, &white, NULL, 0);

            rogue.playbackMode = false;
            displayMoreSign();
            rogue.playbackMode = true;

            restoreDisplayBuffer(&rbuf);
        }

        loadNextAnnotation();
    }
}

// Attempts to extract the patch version of versionString into patchVersion,
// according to the global pattern. The Major and Minor versions must match ours.
// Returns true if successful.
static boolean getPatchVersion(char *versionString, unsigned short *patchVersion) {
    return sscanf(versionString, gameConst->patchVersionPattern, patchVersion) == 1;
}

// Whether a save or recording with this version string can be opened by the variant these
// constants belong to. The same test initRecording() makes.
static boolean versionIsCompatible(const char *versionString, const gameConstants *constants) {
    unsigned short recPatch;
    if (sscanf(versionString, constants->patchVersionPattern, &recPatch) == 1
        && recPatch <= constants->patchVersion) {
        return true; // same major.minor, patch <= ours
    }
    return strcmp(versionString, constants->recordingVersionString) == 0;
}

// creates a game recording file, or if in playback mode,
// initializes based on and starts reading from the recording file
void initRecording() {
    if (currentFilePath[0] == '\0') {
        return;
    }

    short i;
    unsigned short recPatch;
    char buf[1000], *versionString = rogue.versionString;
    FILE *recordFile;

#ifdef AUDIT_RNG
    if (fileExists(RNG_LOG)) {
        remove(RNG_LOG);
    }
    RNGLogFile = fopen(RNG_LOG, "a");
#endif

    locationInRecordingBuffer   = 0;
    positionInPlaybackFile      = 0;
    recordingLocation           = 0;
    maxLevelChanges             = 0;
    rogue.playbackOOS           = false;
    rogue.playbackOmniscience   = false;
    rogue.nextAnnotationTurn    = 0;
    rogue.nextAnnotation[0]     = '\0';
    rogue.locationInAnnotationFile  = 0;
    rogue.patchVersion          = 0;

    if (rogue.playbackMode) {
        lengthOfPlaybackFile        = 100000; // so recall functions don't freak out
        rogue.playbackDelayPerTurn  = DEFAULT_PLAYBACK_DELAY;
        rogue.playbackDelayThisTurn = rogue.playbackDelayPerTurn;
        rogue.playbackPaused        = false;

        fillBufferFromFile();

        for (i=0; i<15; i++) {
            versionString[i] = recallChar();
        }
        rogue.mode = recallChar();

        if (getPatchVersion(versionString, &recPatch) && recPatch <= gameConst->patchVersion) {
            // Major and Minor match ours, Patch is less than or equal to ours: we are compatible.
            rogue.patchVersion = recPatch;
        } else if (strcmp(versionString, gameConst->recordingVersionString) != 0) {
            // We have neither a compatible pattern match nor an exact match: we cannot load it.
            rogue.playbackMode = false;
            rogue.playbackFastForward = false;

            if (!nonInteractivePlayback) {
                snprintf(buf, 1000, "This file is from version %s and cannot be opened in version %s.", versionString, gameConst->versionString);
                dialogAlert(buf);
            } else {
                printf("This file is from version %s and cannot be opened in version %s.", versionString, gameConst->versionString);
            }

            rogue.playbackMode = true;
            rogue.playbackPaused = true;
            rogue.playbackFastForward = false;
            rogue.playbackOOS = false;
            rogue.gameHasEnded = true;
            rogue.gameExitStatusCode = EXIT_STATUS_FAILURE_RECORDING_WRONG_VERSION;

        }

        rogue.seed              = recallNumber(8);          // master random seed
        rogue.howManyTurns      = recallNumber(4);          // how many turns are in this recording
        maxLevelChanges         = recallNumber(4);          // how many times the player changes depths
        lengthOfPlaybackFile    = recallNumber(4);
        seedRandomGenerator(rogue.seed);
        previousGameSeed = rogue.seed;

        if (fileExists(annotationPathname)) {
            loadNextAnnotation();
        } else {
            rogue.nextAnnotationTurn = -1;
        }
    } else {
        // If present, set the patch version for playing the game.
        rogue.patchVersion = BROGUE_PATCH;
        strcpy(versionString, gameConst->recordingVersionString);

        lengthOfPlaybackFile = 1;
        remove(currentFilePath);
        recordFile = fopen(currentFilePath, "wb"); // create the file
        fclose(recordFile);

        flushBufferToFile(); // header info never makes it into inputRecordBuffer when recording
        rogue.recording = true;
    }
    rogue.currentTurnNumber = 0;
}

void OOSCheck(unsigned long x, short numberOfBytes) {
    unsigned char eventType;
    unsigned long recordedNumber;

    if (rogue.playbackMode) {
        if (androidWatchingRecording && recordingLocation >= lengthOfPlaybackFile) {
            // An abandoned run's recording stops at a save, which may be partway through a
            // turn. There is nothing left to check against; the next event read ends it.
            return;
        }
        eventType = recallChar();
        recordedNumber = recallNumber(numberOfBytes);
        if (eventType != RNG_CHECK || recordedNumber != x) {
            if (eventType != RNG_CHECK) {
                printf("Event type mismatch in RNG check.\n");
                playbackPanic();
            } else if (recordedNumber != x) {
                printf("Expected RNG output of %li; got %i.\n", recordedNumber, (int) x);
                playbackPanic();
            }
        }
    } else {
        recordChar(RNG_CHECK);
        recordNumber(x, numberOfBytes);
        considerFlushingBufferToFile();
    }
}

// compare a random number once per player turn so we instantly know if we are out of sync during playback
void RNGCheck() {
    short oldRNG;
    unsigned long randomNumber;

    oldRNG = rogue.RNG;
    rogue.RNG = RNG_SUBSTANTIVE;

//#ifdef AUDIT_RNG
//reportRNGState();
//#endif

    randomNumber = (unsigned long) rand_range(0, 255);
    OOSCheck(randomNumber, 1);

    rogue.RNG = oldRNG;
}

static boolean unpause() {
    if (rogue.playbackOOS) {
        flashTemporaryAlert(" Out of sync ", 2000);
    } else if (rogue.playbackPaused) {
        rogue.playbackPaused = false;
        return true;
    }
    return false;
}

#define PLAYBACK_HELP_LINE_COUNT    20

static void printPlaybackHelpScreen() {
    short i, j;
    screenDisplayBuffer dbuf;
    char helpText[PLAYBACK_HELP_LINE_COUNT][80] = {
        "Playback",
        "",
        "Use the on-screen controls to inspect the recording.",
        "",
        "The game menu provides inventory, discoveries,",
        "saved-game, and exit options.",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "        -- tap anywhere to continue --"
    };

    // Replace the "****"s with color escapes.
    for (i=0; i<PLAYBACK_HELP_LINE_COUNT; i++) {
        for (j=0; helpText[i][j]; j++) {
            if (helpText[i][j] == '*') {
                j = encodeMessageColor(helpText[i], j, &white);
            }
        }
    }

    clearDisplayBuffer(&dbuf);

    for (i=0; i<PLAYBACK_HELP_LINE_COUNT; i++) {
        printString(helpText[i], mapToWindowX(5), mapToWindowY(i), &itemMessageColor, &black, &dbuf);
    }

    const SavedDisplayBuffer rbuf = saveDisplayBuffer();
    overlayDisplayBuffer(&dbuf);

    rogue.playbackMode = false;
    waitForAcknowledgment();
    rogue.playbackMode = true;
    restoreDisplayBuffer(&rbuf);
}

static void resetPlayback() {
    boolean omniscient, stealth, trueColors;

    omniscient = rogue.playbackOmniscience;
    stealth = rogue.displayStealthRangeMode;
    trueColors = rogue.trueColorMode;

    freeEverything();
    randomNumbersGenerated = 0;
    rogue.playbackMode = true;
    initializeRogue(0); // Seed argument is ignored because we're in playback.

    rogue.playbackOmniscience = omniscient;
    rogue.displayStealthRangeMode = stealth;
    rogue.trueColorMode = trueColors;

    rogue.playbackFastForward = false;
    blackOutScreen();
    rogue.playbackFastForward = true;
    startLevel(rogue.depthLevel, 1);
}

// Leaves the loading screen a long seek shows. The camera jumps to the player instead of sliding
// there, and the playback controls come back.
static void leaveSeekLoadingScreen(void) {
    if (getRenderMode() != RENDER_LOADING) {
        return;
    }
    androidCameraSnap = true;
    setRenderMode(RENDER_GAMEPLAY);
    androidSetLoadingVisible(false);
}

static void seek(unsigned long seekTarget, enum recordingSeekModes seekMode) {
    unsigned long startTurnNumber = 0, targetTurnNumber = 0, avgTurnsPerLevel = 1;
    unsigned long turnsToGo = 1, turnsDone, progressStep = 0;
    rogueEvent theEvent;
    boolean pauseState, useProgressBar = false, arrivedAtDestination = false;

    pauseState = rogue.playbackPaused;
    if (androidWatchingRecording) {
        androidNoteDepthArrival();
    }

    // configure progress bar
    switch (seekMode) {
        case RECORDING_SEEK_MODE_DEPTH :
            if (maxLevelChanges > 0) {
                avgTurnsPerLevel = rogue.howManyTurns / maxLevelChanges;
            }
            if (seekTarget <= rogue.depthLevel) {
                startTurnNumber = 0;
                targetTurnNumber = avgTurnsPerLevel * seekTarget;
            } else {
                startTurnNumber = rogue.playerTurnNumber;
                targetTurnNumber = rogue.playerTurnNumber + avgTurnsPerLevel;
            }
            // A depth already watched was reached on a known turn, so the guess isn't needed.
            if (seekTarget < ANDROID_TRACKED_DEPTHS && androidDepthArrivalKnown[seekTarget]
                && androidDepthArrivalTurn[seekTarget] >= startTurnNumber) {

                targetTurnNumber = androidDepthArrivalTurn[seekTarget];
            }
            break;
        case RECORDING_SEEK_MODE_TURN :
            if (seekTarget < rogue.playerTurnNumber) {
                startTurnNumber = 0;
                targetTurnNumber = seekTarget;
            } else {
                startTurnNumber = rogue.playerTurnNumber;
                targetTurnNumber = seekTarget;
            }
            break;
    }

    if (targetTurnNumber > startTurnNumber + 100) {
        useProgressBar = true;
        turnsToGo = targetTurnNumber - startTurnNumber;
        // As when resuming a game: only the bar, on its own screen, so neither the zoom nor the
        // player rushing around the level moves it. The controls can't act meanwhile.
        setRenderMode(RENDER_LOADING);
        androidSetLoadingVisible(true);
        setLoadingProgress(0, turnsToGo);
    }

    // there is no rewind, so start over at depth 1
    if ((seekMode == RECORDING_SEEK_MODE_TURN && seekTarget < rogue.playerTurnNumber)
        || (seekMode == RECORDING_SEEK_MODE_DEPTH && seekTarget <= rogue.depthLevel)) {

        resetPlayback();
        if ((seekMode == RECORDING_SEEK_MODE_DEPTH && seekTarget == 1)
            || (seekMode == RECORDING_SEEK_MODE_TURN && seekTarget == 0)) {
            arrivedAtDestination = true;
        }
    }

    rogue.playbackFastForward = true;

    while (!arrivedAtDestination && !rogue.gameHasEnded && !rogue.playbackOOS) {
        turnsDone = rogue.playerTurnNumber > startTurnNumber ? rogue.playerTurnNumber - startTurnNumber : 0;
        // About 20 updates, as when resuming: each one waits for the display.
        if (useProgressBar && min(turnsDone, turnsToGo) * 20 / turnsToGo != progressStep) {
            progressStep = min(turnsDone, turnsToGo) * 20 / turnsToGo;
            rogue.playbackFastForward = false; // so that pauseBrogue looks for inputs
            setLoadingProgress(turnsDone, turnsToGo);
            while (pauseBrogue(0, PAUSE_BEHAVIOR_DEFAULT)) { // pauseBrogue(0) is necessary to flush the display to the window in SDL
                if (rogue.gameHasEnded) {
                    break;
                }
                rogue.creaturesWillFlashThisTurn = false; // prevent monster flashes from showing up on screen
                nextBrogueEvent(&theEvent, true, false, true); // eat the input if it isn't clicking x
            }
            rogue.playbackFastForward = true;
            if (rogue.gameHasEnded) {
                break;
            }
        }

        rogue.RNG = RNG_COSMETIC; // dancing terrain colors can't influence recordings
        rogue.playbackDelayThisTurn = 0;
        nextBrogueEvent(&theEvent, false, true, false);
        rogue.RNG = RNG_SUBSTANTIVE;
        executeEvent(&theEvent);

        if ((seekMode == RECORDING_SEEK_MODE_DEPTH && rogue.depthLevel == seekTarget)
            || (seekMode == RECORDING_SEEK_MODE_TURN && rogue.playerTurnNumber >= seekTarget)) {

            arrivedAtDestination = true;
        }
    }

    rogue.playbackPaused = pauseState;
    rogue.playbackFastForward = false;
    leaveSeekLoadingScreen();
    if (seekMode == RECORDING_SEEK_MODE_DEPTH) {
        // Another depth is another map, so the camera jumps there too.
        androidCameraSnap = true;
    }
    confirmMessages();
    updateMessageDisplay();
    refreshSideBar(-1, -1, false);
    displayLevel();
}

static void promptToAdvanceToLocation(short keystroke) {
    char entryText[30], buf[max(30, DCOLS)];
    unsigned long destinationFrame;
    boolean enteredText;

    if (rogue.playbackOOS || !rogue.playbackPaused || unpause()) {
        buf[0] = (keystroke == '0' ? '\0' : keystroke);
        buf[1] = '\0';

        rogue.playbackMode = false;
        enteredText = getInputTextString(entryText, "Go to turn number: ", 9, buf, "", TEXT_INPUT_NUMBERS, false);
        confirmMessages();
        rogue.playbackMode = true;

        if (enteredText && entryText[0] != '\0') {
            sscanf(entryText, "%lu", &destinationFrame);

            if (rogue.playbackOOS && destinationFrame > rogue.playerTurnNumber) {
                flashTemporaryAlert(" Out of sync ", 3000);
            } else if (destinationFrame == rogue.playerTurnNumber) {
                sprintf(buf, " Already at turn %li ", destinationFrame);
                flashTemporaryAlert(buf, 1000);
            } else {
                seek(min(destinationFrame, rogue.howManyTurns), RECORDING_SEEK_MODE_TURN);
            }
            rogue.playbackPaused = true;
        }
    }
}

void pausePlayback() {
    //short oldRNG;
    if (!rogue.playbackPaused) {
        rogue.playbackPaused = true;
        androidPlaybackPausedChanged(true);
        messageWithColor(KEYBOARD_LABELS ? "recording paused. Press space to play." : "recording paused.",
                         &teal, 0);
        refreshSideBar(-1, -1, false);
        //oldRNG = rogue.RNG;
        //rogue.RNG = RNG_SUBSTANTIVE;
        mainInputLoop();
        //rogue.RNG = oldRNG;
        messageWithColor("recording unpaused.", &teal, 0);
        rogue.playbackPaused = false;
        if (!rogue.gameHasEnded) {
            // Leaving the recording ends the pause too. Its controls are about to close, and
            // their play/pause button would change for a frame.
            androidPlaybackPausedChanged(false);
        }
        refreshSideBar(-1, -1, false);
        rogue.playbackDelayThisTurn = DEFAULT_PLAYBACK_DELAY;
    }
}

// Used to interact with playback -- e.g. changing speed, pausing.
boolean executePlaybackInput(rogueEvent *recordingInput) {
    signed long key;
    short newDelay, frameCount;
    unsigned long destinationFrame;
    boolean proceed;
    char path[BROGUE_FILENAME_MAX];

    if (!rogue.playbackMode) {
        return false;
    }

    if (nonInteractivePlayback) {
        return false;
    }

    if (recordingInput->eventType == KEYSTROKE) {
        key = recordingInput->param1;
        stripShiftFromMovementKeystroke(&key);

        switch (key) {
            case UP_ARROW:
            case UP_KEY:
                newDelay = max(1, min(rogue.playbackDelayPerTurn * 2/3, rogue.playbackDelayPerTurn - 1));
                if (newDelay != rogue.playbackDelayPerTurn) {
                    flashTemporaryAlert(" Faster ", 300);
                }
                rogue.playbackDelayPerTurn = newDelay;
                rogue.playbackDelayThisTurn = rogue.playbackDelayPerTurn;
                return true;
            case DOWN_ARROW:
            case DOWN_KEY:
                newDelay = min(3000, max(rogue.playbackDelayPerTurn * 3/2, rogue.playbackDelayPerTurn + 1));
                if (newDelay != rogue.playbackDelayPerTurn) {
                    flashTemporaryAlert(" Slower ", 300);
                }
                rogue.playbackDelayPerTurn = newDelay;
                rogue.playbackDelayThisTurn = rogue.playbackDelayPerTurn;
                return true;
            case ACKNOWLEDGE_KEY:
                if (rogue.playbackOOS && rogue.playbackPaused) {
                    flashTemporaryAlert(" Out of sync ", 2000);
                } else {
                    rogue.playbackPaused = !rogue.playbackPaused;
                }
                return true;
            case TAB_KEY:
                rogue.playbackOmniscience = !rogue.playbackOmniscience;
                displayLevel();
                refreshSideBar(-1, -1, false);
                if (rogue.playbackOmniscience) {
                    messageWithColor("Omniscience enabled.", &teal, 0);
                } else {
                    messageWithColor("Omniscience disabled.", &teal, 0);
                }
                return true;
            case ASCEND_KEY:
                seek(max(rogue.depthLevel - 1, 1), RECORDING_SEEK_MODE_DEPTH);
                return true;
            case DESCEND_KEY:
                if (rogue.depthLevel == maxLevelChanges) {
                    flashTemporaryAlert(" Already reached deepest depth explored ", 2000);
                    return false;
                }
                seek(rogue.depthLevel + 1, RECORDING_SEEK_MODE_DEPTH);
                return true;
            case INVENTORY_KEY:
                rogue.playbackMode = false;
                displayInventory(ALL_ITEMS, 0, 0, true, false);
                rogue.playbackMode = true;
                return true;
            case RIGHT_KEY:
            case RIGHT_ARROW:
            case LEFT_KEY:
            case LEFT_ARROW:
                if (key == RIGHT_KEY || key == RIGHT_ARROW) {
                    frameCount = 1;
                } else {
                    frameCount = -1;
                }
                if (recordingInput->shiftKey) {
                    frameCount *= 5;
                }
                if (recordingInput->controlKey) {
                    frameCount *= 20;
                }

                if (frameCount < 0) {
                    if ((unsigned long) (frameCount * -1) > rogue.playerTurnNumber) {
                        destinationFrame = 0;
                    } else {
                        destinationFrame = rogue.playerTurnNumber + frameCount;
                    }
                } else {
                    destinationFrame = min(rogue.playerTurnNumber + frameCount, rogue.howManyTurns);
                }

                if (destinationFrame == rogue.playerTurnNumber) {
                    flashTemporaryAlert(" Already at end of recording ", 1000);
                } else if (frameCount < 0) {
                    rogue.playbackMode = false;
                    proceed = (rogue.playerTurnNumber < 100 || confirm("Rewind?", true));
                    rogue.playbackMode = true;
                    if (proceed) {
                        seek(destinationFrame, RECORDING_SEEK_MODE_TURN);
                    }
                } else {
                    // advance by the right number of turns
                    seek(destinationFrame, RECORDING_SEEK_MODE_TURN);
                }
                return true;
            case BROGUE_HELP_KEY:
                printPlaybackHelpScreen();
                return true;
            case FEATS_KEY:
                rogue.playbackMode = false;
                displayFeatsScreen();
                rogue.playbackMode = true;
                return true;
            case DISCOVERIES_KEY:
                rogue.playbackMode = false;
                // Its inventory icon goes back to the inventory, as when it is opened from there.
                if (displayDiscoveries() == INVENTORY_KEY) {
                    displayInventory(ALL_ITEMS, 0, 0, true, false);
                }
                rogue.playbackMode = true;
                return true;
            case MESSAGE_ARCHIVE_KEY:
                rogue.playbackMode = false;
                displayMessageArchive();
                rogue.playbackMode = true;
                return true;
            case VIEW_RECORDING_KEY:
                confirmMessages();
                rogue.playbackMode = false;
                if (dialogChooseFile(path, RECORDING_SUFFIX, "View recording: ")) {
                    if (fileExists(path)) {
                        strcpy(rogue.nextGamePath, path);
                        strcpy(rogue.currentGamePath, path);
                        rogue.nextGame = NG_VIEW_RECORDING;
                        rogue.gameHasEnded = true;
                        rogue.gameExitStatusCode = EXIT_STATUS_SUCCESS;
                    } else {
                        message("File not found.", 0);
                    }
                }
                rogue.playbackMode = true;
                return true;
            case LOAD_SAVED_GAME_KEY:
                confirmMessages();
                rogue.playbackMode = false;
                if (dialogChooseFile(path, GAME_SUFFIX, "Open saved game: ")) {
                    if (fileExists(path)) {
                        strcpy(rogue.nextGamePath, path);
                        strcpy(rogue.currentGamePath, path);
                        rogue.nextGame = NG_OPEN_GAME;
                        rogue.gameHasEnded = true;
                    } else {
                        message("File not found.", 0);
                    }
                }
                rogue.playbackMode = true;
                return true;
            case NEW_GAME_KEY:
                rogue.playbackMode = false;
                if (confirm("Close recording and begin a new game?", true)) {
                    rogue.nextGame = NG_NEW_GAME;
                    rogue.gameHasEnded = true;
                }
                rogue.playbackMode = true;
                return true;
            case QUIT_KEY:
                // Asked like leaving a game. The recording waits behind the question, and on No
                // goes on playing or stays paused, as it was.
                if (androidWatchingRecording && !confirm("Stop watching this replay?", true)) {
                    return true;
                }
                //freeEverything();
                rogue.gameHasEnded = true;
                rogue.gameExitStatusCode = EXIT_STATUS_SUCCESS;
                rogue.playbackOOS = false;
                rogue.creaturesWillFlashThisTurn = false;
                // When the save itself was being replayed, remove it so the player isn't stuck in
                // a load loop. Leaving a Recent Games recording must keep the run in progress.
                {
                    char savePath[BROGUE_FILENAME_MAX];
                    snprintf(savePath, sizeof(savePath), "%s%s", ANDROID_SAVE_NAME, GAME_SUFFIX);
                    if (strcmp(rogue.currentGamePath, savePath) == 0) {
                        remove(savePath);
                    }
                }
                notifyEvent(GAMEOVER_RECORDING, 0, 0, "recording ended", "none");
                return true;
            case TRUE_COLORS_KEY:
                rogue.trueColorMode = !rogue.trueColorMode;
                displayLevel();
                refreshSideBar(-1, -1, false);
                if (rogue.trueColorMode) {
                    messageWithColor(KEYBOARD_LABELS ? "Color effects disabled. Press '\\' again to enable." : "Color effects disabled.",
                                     &teal, 0);
                } else {
                    messageWithColor(KEYBOARD_LABELS ? "Color effects enabled. Press '\\' again to disable." : "Color effects enabled.",
                                     &teal, 0);
                }
                return true;
            case STEALTH_RANGE_KEY:
                rogue.displayStealthRangeMode = !rogue.displayStealthRangeMode;
                displayLevel();
                refreshSideBar(-1, -1, false);
                if (rogue.displayStealthRangeMode) {
                    messageWithColor(KEYBOARD_LABELS ? "Stealth range displayed. Press ']' again to hide." : "Stealth range displayed.",
                                     &teal, 0);
                } else {
                    messageWithColor(KEYBOARD_LABELS ? "Stealth range hidden. Press ']' again to display." : "Stealth range hidden.",
                                     &teal, 0);
                }
                return true;
            case GRAPHICS_KEY:
                if (hasGraphics) {
                    graphicsMode = setGraphicsMode((graphicsMode + 1) % 3);
                    switch (graphicsMode) {
                        case TEXT_GRAPHICS:
                            messageWithColor(KEYBOARD_LABELS
                                ? "Switched to text mode. Press 'G' again to enable tiles."
                                : "Switched to text mode.", &teal, 0);
                            break;
                        case TILES_GRAPHICS:
                            messageWithColor(KEYBOARD_LABELS
                                ? "Switched to graphical tiles. Press 'G' again to enable hybrid mode."
                                : "Switched to graphical tiles.", &teal, 0);
                            break;
                        case HYBRID_GRAPHICS:
                            messageWithColor(KEYBOARD_LABELS
                                ? "Switched to hybrid mode. Press 'G' again to disable tiles."
                                : "Switched to hybrid mode.", &teal, 0);
                            break;
                    }
                }
                return true;
            case SEED_KEY:
                //rogue.playbackMode = false;
                //DEBUG {displayGrid(safetyMap); displayMoreSign(); displayLevel();}
                //rogue.playbackMode = true;
                printSeed();
                return true;
            case SWITCH_TO_PLAYING_KEY:
#ifdef ENABLE_PLAYBACK_SWITCH
                    if (!rogue.gameHasEnded && !rogue.playbackOOS) {
                        switchToPlaying();
                        lengthOfPlaybackFile = recordingLocation;
                    }
                    return true;
#endif
                return false;
            case ESCAPE_KEY:
                if (!rogue.playbackPaused) {
                    rogue.playbackPaused = true;
                    return true;
                }
                return false;
            default:
                if (key >= '0' && key <= '9'
                    || key >= NUMPAD_0 && key <= NUMPAD_9) {

                    promptToAdvanceToLocation(key);
                    return true;
                }
                return false;
        }
    } else if (recordingInput->eventType == MOUSE_UP) {
        if (!rogue.playbackPaused) {
            // clicking anywhere else pauses the playback
            rogue.playbackPaused = true;
            return true;
        }
    } else if (recordingInput->eventType == RIGHT_MOUSE_UP) {
        rogue.playbackMode = false;
        displayInventory(ALL_ITEMS, 0, 0, true, false);
        rogue.playbackMode = true;
        return true;
    }
    return false;
}

// Pass in defaultPath (the file name WITHOUT suffix), and the suffix.
// Get back either defaultPath, or "defaultPath N",
// where N is the lowest counting number that doesn't collide with an existing file.
void getAvailableFilePath(char *returnPath, const char *defaultPath, const char *suffix) {
    char fullPath[BROGUE_FILENAME_MAX];
    short fileNameIterator = 2;

    strcpy(returnPath, defaultPath);
    sprintf(fullPath, "%s%s", returnPath, suffix);
    while (fileExists(fullPath)) {
        sprintf(returnPath, "%s (%i)", defaultPath, fileNameIterator);
        sprintf(fullPath, "%s%s", returnPath, suffix);
        fileNameIterator++;
    }
}

boolean characterForbiddenInFilename(const char theChar) {
    if (theChar == '/' || theChar == '\\' || theChar == ':') {
        return true;
    } else {
        return false;
    }
}

static void getDefaultFilePath(char *defaultPath, boolean gameOver) {
    char seed[21];

    // 32-bit numbers are printed in full
    // 64-bit numbers longer than 11 digits are shortened to e.g "184...51615"
    sprintf(seed, "%llu", (unsigned long long)rogue.seed);
    if (strlen(seed) > 11) sprintf(seed+3, "...%05lu", (unsigned long)(rogue.seed % 100000));

    if (serverMode) {
        // With WebBrogue, filenames must fit into 30 bytes, including extension and terminal \0.
        // It is enough for the seed and the optional file counter. The longest filename will be:
        // "#184...51615 (999).broguesave" (30 bytes)
        sprintf(defaultPath, "#%s", seed);
        return;
    }

    if (!gameOver) {
        sprintf(defaultPath, "Saved %s #%s at depth %d", gameConst->versionString, seed, rogue.depthLevel);
    } else if (rogue.quit) {
        sprintf(defaultPath, "%s #%s Quit at depth %d", gameConst->versionString, seed, rogue.depthLevel);
    } else if (player.bookkeepingFlags & MB_IS_DYING) {
        sprintf(defaultPath, "%s #%s Died at depth %d", gameConst->versionString, seed, rogue.depthLevel);
    } else if (rogue.depthLevel > 26) {
        sprintf(defaultPath, "%s #%s Mastered the dungeons", gameConst->versionString, seed);
    } else {
        sprintf(defaultPath, "%s #%s Escaped the dungeons", gameConst->versionString, seed);
    }
    if (rogue.mode == GAME_MODE_WIZARD) {
        strcat(defaultPath, " (wizard)");
    } else if (EASY_MODE) {
        strcat(defaultPath, " (easy)");
    }
}

void saveGameNoPrompt() {
    char filePath[BROGUE_FILENAME_MAX], defaultPath[BROGUE_FILENAME_MAX];
    if (rogue.playbackMode) {
        return;
    }
    getDefaultFilePath(defaultPath, false);
    getAvailableFilePath(filePath, defaultPath, GAME_SUFFIX);
    flushBufferToFile();
    strcat(filePath, GAME_SUFFIX);
    rename(currentFilePath, filePath);
    strcpy(currentFilePath, filePath);
    rogue.gameHasEnded = true;
    rogue.gameExitStatusCode = EXIT_STATUS_SUCCESS;
    rogue.recording = false;
}

static void copyFile(char *fromFilePath, char *toFilePath, unsigned long fromFileLength);

// Copies up to `length` bytes of the recording into a new save file and pushes it to storage.
// A recording shorter than `length` is copied as far as it goes. Returns false if anything could
// not be read or written, in which case the new file must not be used.
static boolean copyRecordingToSaveFile(const char *fromFilePath, const char *toFilePath, unsigned long length) {
    unsigned char fileBuffer[INPUT_RECORD_BUFFER];
    unsigned long copied = 0;
    boolean succeeded = true;

    FILE *fromFile = fopen(fromFilePath, "rb");
    if (!fromFile) {
        return false;
    }
    FILE *toFile = fopen(toFilePath, "wb");
    if (!toFile) {
        fclose(fromFile);
        return false;
    }

    while (copied < length) {
        size_t wanted = min(INPUT_RECORD_BUFFER, length - copied);
        size_t got = fread((void *) fileBuffer, 1, wanted, fromFile);
        if (got > 0 && fwrite((void *) fileBuffer, 1, got, toFile) != got) {
            succeeded = false;
            break;
        }
        copied += got;
        if (got < wanted) {
            succeeded = !ferror(fromFile);
            break;
        }
    }

    // A save that does not even hold the header cannot be resumed.
    if (copied < RECORDING_HEADER_LENGTH) {
        succeeded = false;
    }
    if (fflush(toFile) != 0) {
        succeeded = false;
    }
#ifdef BROGUE_ANDROID
    if (fsync(fileno(toFile)) != 0) {
        succeeded = false;
    }
#endif
    fclose(fromFile);
    if (fclose(toFile) != 0) {
        succeeded = false;
    }
    return succeeded;
}

// Writes the current recording to ANDROID_SAVE_NAME.broguesave and returns whether it worked.
// The new save is completed under a temporary name and then renamed over the old one, so the
// previous save stays intact if the app is killed or the write fails partway.
boolean androidWriteSaveFile(void) {
    // Only a game being recorded has a save to write. rogue.playbackMode alone isn't enough:
    // screens opened during a replay, or while a save loads, turn it off while they wait for taps.
    if (!rogue.recording || rogue.playbackMode || !rogue.gameInProgress || rogue.gameHasEnded
        || currentFilePath[0] == '\0') {
        return false;
    }

    char finalPath[BROGUE_FILENAME_MAX], tmpPath[BROGUE_FILENAME_MAX];
    snprintf(finalPath, sizeof(finalPath), "%s%s", ANDROID_SAVE_NAME, GAME_SUFFIX);
    snprintf(tmpPath, sizeof(tmpPath), "%s.tmp", finalPath);

    flushBufferToFile();
    // lengthOfPlaybackFile is the on-disk size; recordingLocation excludes the header.
    if (!copyRecordingToSaveFile(currentFilePath, tmpPath, lengthOfPlaybackFile)
        || rename(tmpPath, finalPath) != 0) {

        remove(tmpPath);
        return false;
    }

    // Saving is also how Android preserves an active run when the app is
    // closed or backgrounded. Record its high-water marks without ending
    // the run or counting a death, win or additional game.
    androidNotifyPlayerQuit(rogue.depthLevel, rogue.deepestLevel,
                            (int)rogue.playerTurnNumber, rogue.gold);

    // Snapshot the run as it stands in this save. If a new run starts before this one ends,
    // the Recent Games list shows it as abandoned, as of here. Gems count as in gameOver().
    long score = rogue.gold + 500 * numberOfMatchingPackItems(GEM, 0, 0, false);
    char *summaryJson = runSummaryJson("quit", "", rogue.gold, score);
    if (summaryJson) {
        androidRecordRun(summaryJson, false);
        free(summaryJson);
    }
    return true;
}

void androidSaveGameAndReturnToMenu(void) {
    if (rogue.playbackMode) return;
    if (!androidWriteSaveFile()) {
        // Leaving now would discard everything since the last save, so keep the run going.
        messageWithColor("Unable to save the game.", &badMessageColor, 0);
        return;
    }
    rogue.gameHasEnded = true;
    rogue.gameExitStatusCode = EXIT_STATUS_SUCCESS;
    rogue.nextGame = NG_NOTHING;
    rogue.recording = false;
}

// Delete the fixed save file so the next launch starts a new game.
void androidDeleteSaveFile(void) {
    char filePath[BROGUE_FILENAME_MAX];
    snprintf(filePath, sizeof(filePath), "%s%s", ANDROID_SAVE_NAME, GAME_SUFFIX);
    remove(filePath);
}

// Recent Games keeps each run's recording in this folder. The engine leaves a finished or
// abandoned run's recording under one of the fixed names below, and Java renames it for the
// run's entry. androidRemoveStaleRecordings() only clears the top folder, so it never sees these.
#define ANDROID_RECORDINGS_DIR      "recordings"
#define ANDROID_FINISHED_RECORDING  ANDROID_RECORDINGS_DIR "/finished" RECORDING_SUFFIX
#define ANDROID_ABANDONED_RECORDING ANDROID_RECORDINGS_DIR "/abandoned" RECORDING_SUFFIX

// Copies the recording of the run that just ended to ANDROID_FINISHED_RECORDING. Call it before
// androidRecordRun(), whose Recent Games entry takes the file.
void androidKeepFinishedRecording(void) {
    if (rogue.playbackMode || currentFilePath[0] == '\0') {
        return;
    }

    char tmpPath[BROGUE_FILENAME_MAX];
    snprintf(tmpPath, sizeof(tmpPath), "%s.tmp", ANDROID_FINISHED_RECORDING);

    mkdir(ANDROID_RECORDINGS_DIR, 0700);
    flushBufferToFile();
    if (!copyRecordingToSaveFile(currentFilePath, tmpPath, lengthOfPlaybackFile)
        || rename(tmpPath, ANDROID_FINISHED_RECORDING) != 0) {

        remove(tmpPath);
    }
}

// A new run is replacing the saved one, so that run was abandoned. Its save is also its
// recording: move it to ANDROID_ABANDONED_RECORDING for Recent Games instead of deleting it.
void androidSetAsideSaveFile(void) {
    char savePath[BROGUE_FILENAME_MAX];
    snprintf(savePath, sizeof(savePath), "%s%s", ANDROID_SAVE_NAME, GAME_SUFFIX);
    if (!fileExists(savePath)) {
        return;
    }

    mkdir(ANDROID_RECORDINGS_DIR, 0700);
    if (rename(savePath, ANDROID_ABANDONED_RECORDING) != 0) {
        remove(savePath);
    }
}

// True while a Recent Games recording is being watched, rather than a save being loaded.
boolean androidWatchingRecording = false;

// A watched recording has run out of input before the game ended, as an abandoned run's does
// at its last save. CE would call this out of sync. Instead, say so, wait for a tap, and end
// the playback.
void androidEndOfRecording(void) {
    // A seek can run out of recording too, and the message must not wait behind its loading screen.
    leaveSeekLoadingScreen();
    if (rogue.playbackFastForward) {
        rogue.playbackFastForward = false;
        displayLevel();
        refreshSideBar(-1, -1, false);
    }
    rogue.creaturesWillFlashThisTurn = false;
    androidPlaybackPausedChanged(true);

    rogue.playbackMode = false;
    message("End of recording.", REQUIRE_ACKNOWLEDGMENT);
    rogue.playbackMode = true;

    rogue.gameHasEnded = true;
    rogue.gameExitStatusCode = EXIT_STATUS_SUCCESS;
}

static boolean stringEndsWith(const char *string, const char *suffix) {
    size_t stringLength = strlen(string), suffixLength = strlen(suffix);
    return stringLength >= suffixLength
        && strcmp(string + stringLength - suffixLength, suffix) == 0;
}

// Deletes the saves and recordings left behind by earlier runs: the working file each run records
// into, and whatever that file was renamed to when the app was closed. Nothing on Android can open
// them again, since the only file a player can resume from is ANDROID_SAVE_NAME.broguesave.
// That save is always left alone, and so is `keepPath` if it is not NULL.
void androidRemoveStaleRecordings(const char *keepPath) {
    char savePath[BROGUE_FILENAME_MAX];
    snprintf(savePath, sizeof(savePath), "%s%s", ANDROID_SAVE_NAME, GAME_SUFFIX);

    DIR *directory = opendir("./");
    if (!directory) {
        return;
    }

    struct dirent *entry;
    while ((entry = readdir(directory))) {
        const char *name = entry->d_name;
        if (!stringEndsWith(name, GAME_SUFFIX) && !stringEndsWith(name, RECORDING_SUFFIX)) {
            continue;
        }
        if (strcmp(name, savePath) == 0 || (keepPath && strcmp(name, keepPath) == 0)) {
            continue;
        }
        remove(name);
    }
    closedir(directory);
}

void androidAbandonGame(void) {
    rogue.quit = true;
    gameOver("Quit", true);
}

// Check whether the fixed mobile save file exists.
boolean androidSaveFileExists(void) {
    char filePath[BROGUE_FILENAME_MAX];
    snprintf(filePath, sizeof(filePath), "%s%s", ANDROID_SAVE_NAME, GAME_SUFFIX);
    return fileExists(filePath);
}

// The game variant named by the prefix of a recording version string, or -1.
static int variantOfVersion(const char *versionStr) {
    if (strncmp(versionStr, "CE ", 3) == 0) return VARIANT_BROGUE;
    if (strncmp(versionStr, "RB ", 3) == 0) return VARIANT_RAPID_BROGUE;
    if (strncmp(versionStr, "BB ", 3) == 0) return VARIANT_BULLET_BROGUE;
    return -1;
}

// Return the game variant encoded by the version header of a save or
// recording. Android saves created before variant-aware resume already contain
// these prefixes, so no save migration or sidecar metadata is needed.
int androidRecordingVariant(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) return -1;

    char versionStr[16];
    size_t n = fread(versionStr, 1, 15, f);
    fclose(f);
    if (n < 15) return -1;
    versionStr[15] = '\0';

    return variantOfVersion(versionStr);
}

int androidSaveVariant(void) {
    char filePath[BROGUE_FILENAME_MAX];
    snprintf(filePath, sizeof(filePath), "%s%s", ANDROID_SAVE_NAME, GAME_SUFFIX);
    return androidRecordingVariant(filePath);
}

// Return the user-facing difficulty stored immediately after the 15-byte
// recording version. Saves predating difficulty selection store normal mode
// here and therefore migrate to Default without a format change.
int androidSaveDifficulty(void) {
    char filePath[BROGUE_FILENAME_MAX];
    snprintf(filePath, sizeof(filePath), "%s%s", ANDROID_SAVE_NAME, GAME_SUFFIX);

    FILE *f = fopen(filePath, "rb");
    if (!f) return GAME_DIFFICULTY_DEFAULT;

    if (fseek(f, 15, SEEK_SET) != 0) {
        fclose(f);
        return GAME_DIFFICULTY_DEFAULT;
    }
    int mode = fgetc(f);
    fclose(f);

    return mode == GAME_MODE_EASY || mode == GAME_MODE_BALANCED_EASY
        ? GAME_DIFFICULTY_EASY
        : GAME_DIFFICULTY_DEFAULT;
}

// Check whether the fixed mobile save file is compatible with the current version.
// Reads just the version header (first 15 bytes) without loading the full game.
boolean androidSaveIsCompatible(void) {
    char filePath[BROGUE_FILENAME_MAX];
    snprintf(filePath, sizeof(filePath), "%s%s", ANDROID_SAVE_NAME, GAME_SUFFIX);

    FILE *f = fopen(filePath, "rb");
    if (!f) return false;

    char versionStr[16];
    size_t n = fread(versionStr, 1, 15, f);
    fclose(f);
    if (n < 15) return false;
    versionStr[15] = '\0';

    return versionIsCompatible(versionStr, gameConst);
}

extern const gameConstants brogueGameConst, rapidBrogueGameConst, bulletBrogueGameConst;

// Whether the recording at `path` can be watched in this version of the game: it holds at least
// its header, and its version passes the same test as a save's for the variant it was played in.
// Safe to call from any thread.
boolean androidRecordingIsPlayable(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) return false;

    char header[RECORDING_HEADER_LENGTH];
    size_t n = fread(header, 1, RECORDING_HEADER_LENGTH, f);
    fclose(f);
    if (n < RECORDING_HEADER_LENGTH) return false;

    char versionStr[16];
    memcpy(versionStr, header, 15);
    versionStr[15] = '\0';

    switch (variantOfVersion(versionStr)) {
        case VARIANT_BROGUE:        return versionIsCompatible(versionStr, &brogueGameConst);
        case VARIANT_RAPID_BROGUE:  return versionIsCompatible(versionStr, &rapidBrogueGameConst);
        case VARIANT_BULLET_BROGUE: return versionIsCompatible(versionStr, &bulletBrogueGameConst);
        default:                    return false;
    }
}

#define MAX_TEXT_INPUT_FILENAME_LENGTH (COLS - 12) // max length including the suffix

void saveGame() {
    char filePathWithoutSuffix[BROGUE_FILENAME_MAX - sizeof(GAME_SUFFIX)], filePath[BROGUE_FILENAME_MAX], defaultPath[BROGUE_FILENAME_MAX];
    boolean askAgain;

    if (rogue.playbackMode) {
        return; // Call me paranoid, but I'd rather it be impossible to embed malware in a recording.
    }

    getDefaultFilePath(defaultPath, false);
    getAvailableFilePath(filePathWithoutSuffix, defaultPath, GAME_SUFFIX);
    filePath[0] = '\0';

    do {
        askAgain = false;
        if (getInputTextString(filePathWithoutSuffix, "Save game as:",
                               MAX_TEXT_INPUT_FILENAME_LENGTH, filePathWithoutSuffix, GAME_SUFFIX, TEXT_INPUT_FILENAME, true)) {
            snprintf(filePath, BROGUE_FILENAME_MAX, "%s%s", filePathWithoutSuffix, GAME_SUFFIX);
            if (!fileExists(filePath) || confirm("File of that name already exists. Overwrite?", true)) {
                remove(filePath);
                flushBufferToFile();
                rename(currentFilePath, filePath);
                strcpy(currentFilePath, filePath);
                rogue.recording = false;
                message("Saved.", REQUIRE_ACKNOWLEDGMENT);
                rogue.gameHasEnded = true;
                rogue.gameExitStatusCode = EXIT_STATUS_SUCCESS;
            } else {
                askAgain = true;
            }
        }
    } while (askAgain);
}

void saveRecordingNoPrompt(char *filePath) {
    char defaultPath[BROGUE_FILENAME_MAX];
    if (rogue.playbackMode) {
        return;
    }
    getDefaultFilePath(defaultPath, true);
    getAvailableFilePath(filePath, defaultPath, RECORDING_SUFFIX);
    strcat(filePath, RECORDING_SUFFIX);
    remove(filePath);
    rename(currentFilePath, filePath);
    rogue.recording = false;
}

void saveRecording(char *filePathWithoutSuffix) {
    char filePath[BROGUE_FILENAME_MAX], defaultPath[BROGUE_FILENAME_MAX];
    boolean askAgain;

    if (rogue.playbackMode) {
        return;
    }

    getDefaultFilePath(defaultPath, true);
    getAvailableFilePath(filePathWithoutSuffix, defaultPath, RECORDING_SUFFIX);
    filePath[0] = '\0';

    do {
        askAgain = false;
        if (getInputTextString(filePathWithoutSuffix, "Save recording as:",
                               MAX_TEXT_INPUT_FILENAME_LENGTH, filePathWithoutSuffix, RECORDING_SUFFIX, TEXT_INPUT_FILENAME, true)) {

            snprintf(filePath, BROGUE_FILENAME_MAX, "%s%s", filePathWithoutSuffix, RECORDING_SUFFIX);
            if (!fileExists(filePath) || confirm("File of that name already exists. Overwrite?", true)) {
                remove(filePath);
                rename(currentFilePath, filePath);
                rogue.recording = false;
            } else {
                askAgain = true;
            }
        } else { // Declined to save recording; save it anyway as LastRecording, and delete LastRecording if it already exists.
            snprintf(filePath, BROGUE_FILENAME_MAX, "%s%s", LAST_RECORDING_NAME, RECORDING_SUFFIX);
            if (fileExists(filePath)) {
                remove(filePath);
            }
            rename(currentFilePath, filePath);
            rogue.recording = false;
        }
    } while (askAgain);
}

static void copyFile(char *fromFilePath, char *toFilePath, unsigned long fromFileLength) {
    unsigned long m, n;
    unsigned char fileBuffer[INPUT_RECORD_BUFFER];
    FILE *fromFile, *toFile;

    remove(toFilePath);

    fromFile    = fopen(fromFilePath, "rb");
    toFile      = fopen(toFilePath, "wb");

    for (n = 0; n < fromFileLength; n += m) {
        m = min(INPUT_RECORD_BUFFER, fromFileLength - n);
        fread((void *) fileBuffer, 1, m, fromFile);
        fwrite((void *) fileBuffer, 1, m, toFile);
    }

    fclose(fromFile);
    fclose(toFile);
}

// at the end of loading a saved game, this function transitions into active play mode.
void switchToPlaying() {
    char lastGamePath[BROGUE_FILENAME_MAX];

    displayMonsterFlashes(false);
    getAvailableFilePath(lastGamePath, LAST_GAME_NAME, GAME_SUFFIX);
    strcat(lastGamePath, GAME_SUFFIX);

    rogue.playbackMode          = false;
    rogue.playbackFastForward   = false;
    rogue.playbackOmniscience   = false;
    rogue.recording             = true;
    locationInRecordingBuffer   = 0;
    copyFile(currentFilePath, lastGamePath, recordingLocation);
#if !defined(ENABLE_PLAYBACK_SWITCH) && !defined(BROGUE_ANDROID)
    // On Android the save that was just loaded stays on disk until the next save replaces it,
    // so a crash before then resumes from the same point instead of losing the run.
    if (DELETE_SAVE_FILE_AFTER_LOADING) {
        remove(currentFilePath);
    }
#endif

    strcpy(currentFilePath, lastGamePath);

    androidSetLoadingVisible(false);
    androidCameraSnap = true;
    setRenderMode(RENDER_GAMEPLAY);
    blackOutScreen();
    refreshSideBar(-1, -1, false);
    updateMessageDisplay();
    displayLevel();
    androidSetOverlayVisible(true);
}

// Return whether the load was cancelled by an event
boolean loadSavedGame() {
    unsigned long progressBarInterval;
    unsigned long previousRecordingLocation;
    rogueEvent theEvent;

    randomNumbersGenerated = 0;
    rogue.playbackMode = true;
    rogue.playbackFastForward = true;
    setRenderMode(RENDER_LOADING);
    androidSetLoadingVisible(true);
    initializeRogue(0); // Calls initRecording(). Seed argument is ignored because we're initially in playback mode.
    setLoadingProgress(0, lengthOfPlaybackFile);
    if (!rogue.gameHasEnded) {
        blackOutScreen();
        startLevel(rogue.depthLevel, 1);
    }

    if (rogue.howManyTurns > 0) {

        // The loading bar is about 20 cells wide, so finer updates only add
        // redundant VSync-bound presents during fast-forward replay.
        progressBarInterval = max(1, lengthOfPlaybackFile / 20);
        previousRecordingLocation = -1; // unsigned

        while (recordingLocation < lengthOfPlaybackFile
               && rogue.playerTurnNumber < rogue.howManyTurns
               && !rogue.gameHasEnded
               && !rogue.playbackOOS) {

            rogue.RNG = RNG_COSMETIC;
            nextBrogueEvent(&theEvent, false, true, false);
            rogue.RNG = RNG_SUBSTANTIVE;

            executeEvent(&theEvent);

            if (recordingLocation / progressBarInterval != previousRecordingLocation / progressBarInterval && !rogue.playbackOOS) {
                rogue.playbackFastForward = false; // so that pauseBrogue looks for inputs
                setLoadingProgress(recordingLocation, lengthOfPlaybackFile);
                while (pauseBrogue(0, PAUSE_BEHAVIOR_DEFAULT)) { // pauseBrogue(0) is necessary to flush the display to the window in SDL, as well as look for inputs
                    rogue.creaturesWillFlashThisTurn = false; // prevent monster flashes from showing up on screen
                    nextBrogueEvent(&theEvent, true, false, true);
                    if (rogue.gameHasEnded || theEvent.eventType == KEYSTROKE && theEvent.param1 == ESCAPE_KEY) {
                        androidSetLoadingVisible(false);
                        setRenderMode(RENDER_GAMEPLAY);
                        return false;
                    }
                }
                rogue.playbackFastForward = true;
                previousRecordingLocation = recordingLocation;
            }
        }
    }

    if (!rogue.gameHasEnded && !rogue.playbackOOS) {
        switchToPlaying();
        recordChar(SAVED_GAME_LOADED);
    } else {
        androidSetLoadingVisible(false);
        setRenderMode(RENDER_GAMEPLAY);
    }
    return true;
}

// the following functions are used to create human-readable descriptions of playback files for debugging purposes

static void describeKeystroke(unsigned char key, char *description) {
    short i;
    long c;
    const long keyList[51] = {UP_KEY, DOWN_KEY, LEFT_KEY, RIGHT_KEY, UP_ARROW, LEFT_ARROW,
        DOWN_ARROW, RIGHT_ARROW, UPLEFT_KEY, UPRIGHT_KEY, DOWNLEFT_KEY, DOWNRIGHT_KEY,
        DESCEND_KEY, ASCEND_KEY, REST_KEY, AUTO_REST_KEY, SEARCH_KEY, INVENTORY_KEY,
        ACKNOWLEDGE_KEY, EQUIP_KEY, UNEQUIP_KEY, APPLY_KEY, THROW_KEY, RELABEL_KEY, DROP_KEY, CALL_KEY,
        //FIGHT_KEY, FIGHT_TO_DEATH_KEY,
        BROGUE_HELP_KEY, FEATS_KEY, DISCOVERIES_KEY, RETURN_KEY,
        EXPLORE_KEY, AUTOPLAY_KEY, SEED_KEY, EASY_MODE_KEY, ESCAPE_KEY,
        RETURN_KEY, DELETE_KEY, TAB_KEY, PERIOD_KEY, VIEW_RECORDING_KEY, NUMPAD_0,
        NUMPAD_1, NUMPAD_2, NUMPAD_3, NUMPAD_4, NUMPAD_5, NUMPAD_6, NUMPAD_7, NUMPAD_8,
        NUMPAD_9, UNKNOWN_KEY};
    const char descList[51][30] = {"up", "down", "left", "right", "up arrow", "left arrow",
        "down arrow", "right arrow", "upleft", "upright", "downleft", "downright",
        "descend", "ascend", "rest", "auto rest", "search", "inventory", "acknowledge",
        "equip", "unequip", "apply", "throw", "relabel", "drop", "call",
        //"fight", "fight to death",
        "help", "discoveries", "repeat travel", "explore", "autoplay", "seed",
        "easy mode", "escape", "return", "delete", "tab", "period", "open file",
        "numpad 0", "numpad 1", "numpad 2", "numpad 3", "numpad 4", "numpad 5", "numpad 6",
        "numpad 7", "numpad 8", "numpad 9", "unknown", "ERROR"};

    c = uncompressKeystroke(key);
    for (i=0; i < 50 && keyList[i] != c; i++);
    if (key >= 32 && key <= 126) {
        sprintf(description, "Key: %c\t(%s)", key, descList[i]);
    } else {
        sprintf(description, "Key: %i\t(%s)", key, descList[i]);
    }
}

static void appendModifierKeyDescription(char *description) {
    unsigned char c = recallChar();

    if (c & Fl(1)) {
        strcat(description, " + CRTL");
    }
    if (c & Fl(2)) {
        strcat(description, " + SHIFT");
    }
}

// Deprecated! Only used to parse recordings, a debugging feature.
static boolean selectFile(char *prompt, char *defaultName, char *suffix) {
    boolean retval;
    char newFilePath[BROGUE_FILENAME_MAX];

    retval = false;

    if (chooseFile(newFilePath, prompt, defaultName, suffix)) {
        if (openFile(newFilePath)) {
            retval = true;
        } else {
            confirmMessages();
            message("File not found.", 0);
            retval = false;
        }
    }
    return retval;
}

void parseFile() {
    FILE *descriptionFile;
    unsigned long oldFileLoc, oldRecLoc, oldLength, oldBufLoc, i, numTurns, numDepths, fileLength, startLoc;
    uint64_t seed;
    unsigned char c;
    char description[1000], versionString[500];
    short x, y;

    if (selectFile("Parse recording: ", "Recording.broguerec", "")) {

        oldFileLoc = positionInPlaybackFile;
        oldRecLoc = recordingLocation;
        oldBufLoc = locationInRecordingBuffer;
        oldLength = lengthOfPlaybackFile;

        positionInPlaybackFile = 0;
        locationInRecordingBuffer = 0;
        recordingLocation = 0;
        lengthOfPlaybackFile = 10000000; // hack so that the recalls don't freak out
        fillBufferFromFile();

        descriptionFile = fopen("Recording Description.txt", "w");

        for (i=0; i<16; i++) {
            versionString[i] = recallChar();
        }

        seed        = recallNumber(8);
        numTurns    = recallNumber(4);
        numDepths   = recallNumber(4);
        fileLength  = recallNumber(4);

        fprintf(descriptionFile, "Parsed file \"%s\":\n\tVersion: %s\n\tSeed: %lld\n\tNumber of turns: %lu\n\tNumber of depth changes: %lu\n\tFile length: %lu\n",
                currentFilePath,
                versionString,
                (unsigned long long)seed,
                numTurns,
                numDepths,
                fileLength);
        for (i=0; recordingLocation < fileLength; i++) {
            startLoc = recordingLocation;
            c = recallChar();
            switch (c) {
                case KEYSTROKE:
                    describeKeystroke(recallChar(), description);
                    appendModifierKeyDescription(description);
                    break;
                case MOUSE_UP:
                case MOUSE_DOWN:
                case MOUSE_ENTERED_CELL:
                    x = (short) recallChar();
                    y = (short) recallChar();
                    sprintf(description, "Mouse click: (%i, %i)", x, y);
                    appendModifierKeyDescription(description);
                    break;
                case RNG_CHECK:
                    sprintf(description, "\tRNG check: %i", (short) recallChar());
                    break;
                case SAVED_GAME_LOADED:
                    strcpy(description, "Saved game loaded");
                    break;
                default:
                    sprintf(description, "UNKNOWN EVENT TYPE: %i", (short) c);
                    break;
            }
            fprintf(descriptionFile, "\nEvent %li, loc %li, length %li:%s\t%s", i, startLoc, recordingLocation - startLoc, (i < 10 ? " " : ""), description);
        }

        fclose(descriptionFile);

        positionInPlaybackFile = oldFileLoc;
        recordingLocation = oldRecLoc;
        lengthOfPlaybackFile = oldLength;
        locationInRecordingBuffer = oldBufLoc;
        message("File parsed.", 0);
    } else {
        confirmMessages();
    }
}

void RNGLog(char *message) {
#ifdef AUDIT_RNG
    fputs(message, RNGLogFile);
#endif
}
