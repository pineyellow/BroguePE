package com.pineyellow.broguepe;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.widget.ImageView;

/** Builds small colored monster icons from the selected part of the atlas. */
final class MonsterTileIcon {

    private static final int TILE_ICON_SIZE_DP = 14;
    private static final int TEXT_ICON_SIZE_DP = 24;

    private MonsterTileIcon() { }

    static ImageView makeView(BrogueActivity activity, String monsterName) {
        int[] info = activity.nativeMonsterTileInfo(monsterName);
        if (info == null || info.length < 5) return null;

        boolean useTileGlyph = GameSettings.useTileCreatureAndItemGlyphs(activity);
        return makeGlyphView(activity, useTileGlyph ? info[0] : info[4], useTileGlyph,
            Color.rgb(percentToByte(info[1]), percentToByte(info[2]), percentToByte(info[3])));
    }

    /** One glyph from the atlas in one color, sized like a monster icon. */
    static ImageView makeGlyphView(BrogueActivity activity, int atlasIndex,
                                   boolean useTileGlyph, int color) {
        return makeGlyphView(activity, atlasIndex, useTileGlyph, sizeDp(useTileGlyph), color);
    }

    /** The same in a box of the given size. A trimmed glyph fills the box, and an
     *  untrimmed one keeps its whole cell, lifted a little to sit with text. */
    static ImageView makeGlyphView(BrogueActivity activity, int atlasIndex,
                                   boolean trimArtwork, int sizeDp, int color) {
        int iconSize = activity.dpToPx(sizeDp);
        Bitmap mask = TileAtlasMask.get(
            activity, atlasIndex, iconSize, iconSize, trimArtwork);
        if (mask == null) return null;

        ImageView icon = new ImageView(activity);
        BitmapDrawable drawable = new BitmapDrawable(activity.getResources(), mask);
        // The cached mask is already rendered at the final physical pixel size.
        // Nearest placement here avoids applying a second softening filter.
        drawable.setFilterBitmap(false);
        icon.setImageDrawable(drawable);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        if (!trimArtwork) icon.setTranslationY(-activity.dpToPx(2));
        icon.setImportantForAccessibility(ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    /** Tile artwork is trimmed to its outline, and a text glyph keeps its
     *  whole cell, so a text icon needs the bigger box. */
    static int sizeDp(boolean useTileGlyph) {
        return useTileGlyph ? TILE_ICON_SIZE_DP : TEXT_ICON_SIZE_DP;
    }

    /** A color component from the game's 0 to 100 scale. */
    static int percentToByte(int percent) {
        return Math.max(0, Math.min(100, percent)) * 255 / 100;
    }

}
