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
        int iconSize = activity.dpToPx(useTileGlyph
            ? TILE_ICON_SIZE_DP : TEXT_ICON_SIZE_DP);
        int atlasIndex = useTileGlyph ? info[0] : info[4];
        Bitmap mask = TileAtlasMask.get(
            activity, atlasIndex, iconSize, iconSize, useTileGlyph);
        if (mask == null) return null;

        ImageView icon = new ImageView(activity);
        BitmapDrawable drawable = new BitmapDrawable(activity.getResources(), mask);
        // The cached mask is already rendered at the final physical pixel size.
        // Nearest placement here avoids applying a second softening filter.
        drawable.setFilterBitmap(false);
        icon.setImageDrawable(drawable);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setColorFilter(Color.rgb(
            percentToByte(info[1]), percentToByte(info[2]), percentToByte(info[3])),
            PorterDuff.Mode.SRC_IN);
        if (!useTileGlyph) icon.setTranslationY(-activity.dpToPx(2));
        icon.setImportantForAccessibility(ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    static int sizeDp(BrogueActivity activity) {
        return GameSettings.useTileCreatureAndItemGlyphs(activity)
            ? TILE_ICON_SIZE_DP : TEXT_ICON_SIZE_DP;
    }

    private static int percentToByte(int percent) {
        return Math.max(0, Math.min(100, percent)) * 255 / 100;
    }

}
