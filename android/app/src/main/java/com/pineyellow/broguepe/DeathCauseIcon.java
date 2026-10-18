package com.pineyellow.broguepe;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.widget.ImageView;

/** Short labels and icons for the deaths the game words as a sentence of its
 *  own, like "Killed by a cloud of caustic gas", instead of a monster's name.
 *  The table is in android-stats.c. Gas, poison, lava and the fall's chasm
 *  are cell icons: a cell as the game draws one, its background color with
 *  its glyph on top. Gas and poison have no glyph, so they look the same in
 *  every graphics mode. */
final class DeathCauseIcon {

    /** The game's grid, COLS by ROWS in Rogue.h. It stretches its tiles to the
     *  shape of one screen cell, which on a phone is wider than an atlas cell. */
    private static final int GAME_COLS = 100;
    private static final int GAME_ROWS = 34;
    /** The shape of an atlas cell, 128 by 232 pixels, for before the window
     *  has a size. */
    private static final float ATLAS_CELL_ASPECT = 128f / 232f;
    /** Taller than a tile icon, whose sprite is trimmed to its artwork, so a
     *  monster's sprite would still fit inside the cell. */
    private static final int CELL_HEIGHT_DP = 18;
    private static final String KILLED_BY = "Killed by ";
    private static final String[] ARTICLES = { "a ", "an ", "the " };

    /** Whether the icon sits in a tile-sized box, which its pill pads differently. */
    final boolean tileSized;
    /** Whether its glyph is drawn from the tile sheet. A cell keeps the box of
     *  the monster icons beside it, whichever sheet its glyph comes from. */
    private final boolean tileGlyph;
    /** Its glyph in the atlas, or -1 for none. */
    private final int atlasIndex;
    private final int glyphColor;
    final boolean cell;
    private final int cellColor;

    private DeathCauseIcon(boolean tileSized, boolean tileGlyph, int atlasIndex,
                           int glyphColor, boolean cell, int cellColor) {
        this.tileSized = tileSized;
        this.tileGlyph = tileGlyph;
        this.atlasIndex = atlasIndex;
        this.glyphColor = glyphColor;
        this.cell = cell;
        this.cellColor = cellColor;
    }

    /** The label a saved death cause shows under. A monster's name stays as
     *  it is. A sentence the table doesn't know, from a newer version, loses
     *  its "Killed by a" and shows without an icon. */
    static String label(BrogueActivity activity, String cause) {
        String label = activity.nativeDeathCauseLabel(cause);
        if (label != null) return label;
        if (!cause.startsWith(KILLED_BY)) return cause;
        String rest = cause.substring(KILLED_BY.length());
        for (String article : ARTICLES) {
            if (rest.startsWith(article)) return rest.substring(article.length());
        }
        return rest;
    }

    /** The icon for a label from label(), or null if it has none. */
    static DeathCauseIcon find(BrogueActivity activity, String label) {
        int[] info = activity.nativeDeathCauseIcon(label);
        if (info == null || info.length < 10) return null;
        boolean tileGlyph = GameSettings.useTileGlyph(activity, info[2] != 0);
        int atlasIndex = info[0] < 0 ? -1 : tileGlyph ? info[0] : info[1];
        boolean cell = info[6] != 0;
        boolean tileSized = cell
            ? GameSettings.useTileCreatureAndItemGlyphs(activity) : tileGlyph;
        return new DeathCauseIcon(tileSized, tileGlyph, atlasIndex,
            rgb(info, 3), cell, rgb(info, 7));
    }

    ImageView makeView(BrogueActivity activity) {
        if (!cell) {
            return MonsterTileIcon.makeGlyphView(activity, atlasIndex, tileGlyph, glyphColor);
        }
        int height = activity.dpToPx(CELL_HEIGHT_DP);
        int width = Math.max(1, Math.round(height * cellAspect(activity)));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(cellColor);
        if (atlasIndex >= 0) {
            // The glyph's whole cell, untrimmed and stretched to the cell's shape, as
            // the game draws it.
            Bitmap mask = TileAtlasMask.get(activity, atlasIndex, width, height, false);
            if (mask != null) {
                Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
                paint.setColorFilter(new PorterDuffColorFilter(glyphColor, PorterDuff.Mode.SRC_IN));
                canvas.drawBitmap(mask, null, new Rect(0, 0, width, height), paint);
            }
        }

        ImageView icon = new ImageView(activity);
        BitmapDrawable drawable = new BitmapDrawable(activity.getResources(), bitmap);
        // Drawn at its final pixel size; see MonsterTileIcon.
        drawable.setFilterBitmap(false);
        icon.setImageDrawable(drawable);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    /** Width over height of one cell on this screen. The game is in landscape,
     *  so the long side holds its columns. */
    private static float cellAspect(BrogueActivity activity) {
        View root = activity.getWindow().getDecorView();
        int longSide = Math.max(root.getWidth(), root.getHeight());
        int shortSide = Math.min(root.getWidth(), root.getHeight());
        if (shortSide <= 0) return ATLAS_CELL_ASPECT;
        return (float) (longSide * GAME_ROWS) / (shortSide * GAME_COLS);
    }

    private static int rgb(int[] info, int start) {
        return Color.rgb(MonsterTileIcon.percentToByte(info[start]),
            MonsterTileIcon.percentToByte(info[start + 1]),
            MonsterTileIcon.percentToByte(info[start + 2]));
    }
}
