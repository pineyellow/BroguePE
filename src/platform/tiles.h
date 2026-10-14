#ifndef __TILES_H__
#define __TILES_H__

#include <SDL.h>

void initTiles(void);
void resizeWindow(int width, int height);
void updateTile(int row, int column, short charIndex,
    short foreRed, short foreGreen, short foreBlue,
    short backRed, short backGreen, short backBlue);
void updateScreen(void);
// Draw every frame for a moment, even ones that match the last, for when the
// surface they are drawn to may have been replaced.
void forceFrames(void);
void setLoadingProgress(unsigned long amount, unsigned long maximum);
SDL_Surface *captureScreen(void);

void invalidateTextures(void);
void resetRendererResources(void);
void shutdownTiles(void);
void requestRendererRecovery(void);
int consumeRendererRecoveryRequest(void);
void resetCameraFrameClock(void);

// Calculate the scaled dungeon rectangle and constrain its pan to the visible
// bounds. When the dungeon fits on an axis, it is centered on that axis.
void calculateDungeonViewport(int screenWidth, int screenHeight, float zoom,
    float *panX, float *panY, SDL_Rect *viewport);

// How far right the sidebar row centred at rowCenterY is drawn, in pixels:
// the fixed offset, or more to clear a rounded top-left screen corner.
int sidebarOffsetPixels(int rowCenterY, float cellWidth);

#endif
