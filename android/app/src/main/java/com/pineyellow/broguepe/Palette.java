package com.pineyellow.broguepe;

import android.graphics.Color;

/** Brogue's UI color palette — derived from the game's actual color definitions. */
final class Palette {

    private Palette() {}

    // Muted indigo chrome: darkest panels, raised rows, and brighter action
    // states share one hue while retaining clear tonal separation.
    static final int DEEP_INDIGO    = Color.argb(230, 23, 16, 39);   // interfaceBoxColor
    static final int ACTION_BUTTON_BG = Color.argb(51, 25, 17, 42);  // 20% opaque button fill
    static final int ACTION_BUTTON_TEXT = Color.argb(128, 144, 148, 190);
    static final int ACTION_BUTTON_TEXT_ACTIVE = Color.argb(128, 144, 148, 190);
    static final int DIM_WHITE_BLUE = Color.argb(255, 144, 148, 190); // Cool blue-indigo
    static final int DIM_BLUE_GRAY  = Color.argb(255, 91, 82, 120);   // Dark indigo-gray
    static final int PALE_BLUE      = Color.argb(255, 144, 148, 190); // flameTitleColor text
    static final int GHOST_WHITE    = Color.argb(255, 210, 205, 220);
    static final int VOID_BLACK     = Color.argb(240, 10, 6, 18);
    static final int SUBMENU_BG     = Color.argb(235, 16, 10, 30);
    static final int RIPPLE_GLOW    = Color.argb(80, 154, 143, 191);
    static final int BORDER_DIM     = Color.argb(120, 91, 82, 120);
    static final int BORDER_ACTIVE  = Color.argb(200, 154, 143, 191);

    static final int INVENTORY_BG   = Color.argb(245, 13, 8, 25);
    static final int ITEM_BG        = Color.argb(200, 25, 17, 42);
    static final int EQUIPPED_GLOW  = Color.argb(220, 52, 38, 63);
    static final int ACTION_BG      = Color.argb(220, 40, 28, 63);
    static final int GOOD_MAGIC     = Color.argb(255, 153, 128, 255); // Brogue {60,50,100}
    static final int BAD_MAGIC      = Color.argb(255, 255, 128, 153); // Brogue {100,50,60}

    // Good/bad magic indicator glyphs (Brogue's G_GOOD_MAGIC / G_BAD_MAGIC).
    static final String GOOD_MAGIC_GLYPH = new String(Character.toChars(0x29F3));
    static final String BAD_MAGIC_GLYPH  = new String(Character.toChars(0x29F2));

    static final int DISABLED_BG    = Color.argb(100, 18, 13, 33);
    static final int DISABLED_TEXT  = Color.argb(100, 100, 95, 110);

    /** Toggle-active background used by animateToggle's "active" state. */
    static final int TOGGLE_ACTIVE  = Color.argb(51, 50, 35, 15);
}
