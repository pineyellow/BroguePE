package com.pineyellow.broguepe;

import android.graphics.Bitmap;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.IOException;
import java.io.InputStream;

/** Extracts tintable graphical glyphs at their final physical pixel size. */
final class TileAtlasMask {

    private static final int ATLAS_COLUMNS = 16;
    private static final int ATLAS_ROWS = 24;
    private static final Object DECODER_LOCK = new Object();

    private static final LruCache<CacheKey, Bitmap> MASK_CACHE =
        new LruCache<CacheKey, Bitmap>(2048) {
            @Override
            protected int sizeOf(CacheKey key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };

    private static BitmapRegionDecoder atlasDecoder;
    private static boolean decoderReleasePosted;

    private TileAtlasMask() { }

    static Bitmap get(BrogueActivity activity, int tileIndex,
                      int maxWidthPx, int maxHeightPx, boolean trimArtwork) {
        if (tileIndex < 0 || tileIndex >= ATLAS_COLUMNS * ATLAS_ROWS
                || maxWidthPx <= 0 || maxHeightPx <= 0) {
            return null;
        }

        CacheKey key = new CacheKey(tileIndex, maxWidthPx, maxHeightPx, trimArtwork);
        Bitmap cached = MASK_CACHE.get(key);
        if (cached != null) return cached;

        synchronized (DECODER_LOCK) {
            cached = MASK_CACHE.get(key);
            if (cached != null) return cached;

            BitmapRegionDecoder decoder = obtainDecoder(activity);
            if (decoder == null) return null;

            int tileWidth = decoder.getWidth() / ATLAS_COLUMNS;
            int tileHeight = decoder.getHeight() / ATLAS_ROWS;
            int column = tileIndex % ATLAS_COLUMNS;
            int row = tileIndex / ATLAS_COLUMNS;

            Bitmap tile;
            try {
                tile = decoder.decodeRegion(new Rect(
                    column * tileWidth, row * tileHeight,
                    (column + 1) * tileWidth, (row + 1) * tileHeight), null);
            } catch (RuntimeException ignored) {
                return null;
            }
            if (tile == null) return null;

            int[] source = new int[tileWidth * tileHeight];
            tile.getPixels(source, 0, tileWidth, 0, 0, tileWidth, tileHeight);
            tile.recycle();

            int left = 0;
            int top = 0;
            int right = tileWidth - 1;
            int bottom = tileHeight - 1;
            if (trimArtwork) {
                left = tileWidth;
                top = tileHeight;
                right = -1;
                bottom = -1;
                for (int y = 0; y < tileHeight; y++) {
                    for (int x = 0; x < tileWidth; x++) {
                        if (intensity(source[y * tileWidth + x]) == 0) continue;
                        left = Math.min(left, x);
                        top = Math.min(top, y);
                        right = Math.max(right, x);
                        bottom = Math.max(bottom, y);
                    }
                }
                if (right < left || bottom < top) return null;
            }

            int padding = trimArtwork ? 2 : 0;
            int artworkWidth = right - left + 1;
            int artworkHeight = bottom - top + 1;
            int sourceWidth = artworkWidth + padding * 2;
            int sourceHeight = artworkHeight + padding * 2;
            int[] maskPixels = new int[sourceWidth * sourceHeight];
            for (int y = 0; y < artworkHeight; y++) {
                for (int x = 0; x < artworkWidth; x++) {
                    int alpha = intensity(source[(top + y) * tileWidth + left + x]);
                    maskPixels[(y + padding) * sourceWidth + x + padding] =
                        Color.argb(alpha, 255, 255, 255);
                }
            }

            Bitmap fullSizeMask = Bitmap.createBitmap(
                maskPixels, sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888);
            float scale = Math.min((float) maxWidthPx / sourceWidth,
                                   (float) maxHeightPx / sourceHeight);
            int renderedWidth = Math.max(1, Math.round(sourceWidth * scale));
            int renderedHeight = Math.max(1, Math.round(sourceHeight * scale));
            Bitmap mask = Bitmap.createScaledBitmap(
                fullSizeMask, renderedWidth, renderedHeight, true);
            if (mask != fullSizeMask) fullSizeMask.recycle();

            MASK_CACHE.put(key, mask);
            return mask;
        }
    }

    private static int intensity(int pixel) {
        return Math.max(Color.red(pixel),
            Math.max(Color.green(pixel), Color.blue(pixel)));
    }

    private static BitmapRegionDecoder obtainDecoder(BrogueActivity activity) {
        if (atlasDecoder != null && !atlasDecoder.isRecycled()) {
            return atlasDecoder;
        }

        try (InputStream input = activity.getAssets().open("tiles.png")) {
            atlasDecoder = BitmapRegionDecoder.newInstance(input, false);
        } catch (IOException ignored) {
            atlasDecoder = null;
        }

        if (atlasDecoder != null && !decoderReleasePosted) {
            decoderReleasePosted = true;
            new Handler(Looper.getMainLooper()).post(() -> {
                synchronized (DECODER_LOCK) {
                    if (atlasDecoder != null) {
                        atlasDecoder.recycle();
                        atlasDecoder = null;
                    }
                    decoderReleasePosted = false;
                }
            });
        }
        return atlasDecoder;
    }

    private static final class CacheKey {
        final int tileIndex;
        final int width;
        final int height;
        final boolean trimmed;

        CacheKey(int tileIndex, int width, int height, boolean trimmed) {
            this.tileIndex = tileIndex;
            this.width = width;
            this.height = height;
            this.trimmed = trimmed;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof CacheKey)) return false;
            CacheKey that = (CacheKey) other;
            return tileIndex == that.tileIndex && width == that.width
                && height == that.height && trimmed == that.trimmed;
        }

        @Override
        public int hashCode() {
            int result = tileIndex;
            result = 31 * result + width;
            result = 31 * result + height;
            return 31 * result + (trimmed ? 1 : 0);
        }
    }
}
