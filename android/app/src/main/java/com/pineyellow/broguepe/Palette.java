package com.pineyellow.broguepe;

import android.graphics.Color;

/** Brogue's UI color palette — derived from the game's actual color definitions. */
final class Palette {

    private Palette() {}

    static final int DEEP_INDIGO    = Color.argb(230, 18, 15, 38);   // interfaceBoxColor
    static final int ACTION_BUTTON_BG = Color.argb(51, 18, 15, 38);  // 20% opaque button fill
    static final int ACTION_BUTTON_TEXT = Color.argb(128, 140, 150, 190);
    static final int ACTION_BUTTON_TEXT_ACTIVE = Color.argb(128, 140, 150, 190);
    static final int DIM_WHITE_BLUE = Color.argb(255, 140, 150, 190); // Dim white-blue
    static final int DIM_BLUE_GRAY  = Color.argb(255, 80, 95, 120);   // Darker dim blue-gray
    static final int PALE_BLUE      = Color.argb(255, 140, 150, 190);// flameTitleColor text
    static final int GHOST_WHITE    = Color.argb(255, 210, 205, 220);
    static final int VOID_BLACK     = Color.argb(240, 8, 6, 16);
    static final int SUBMENU_BG     = Color.argb(235, 12, 10, 25);
    static final int RIPPLE_GLOW    = Color.argb(80, 140, 150, 190);
    static final int BORDER_DIM     = Color.argb(120, 80, 95, 120);
    static final int BORDER_ACTIVE  = Color.argb(200, 140, 150, 190);

    static final int INVENTORY_BG   = Color.argb(245, 10, 8, 22);
    static final int ITEM_BG        = Color.argb(200, 20, 17, 42);
    static final int EQUIPPED_GLOW  = Color.argb(220, 45, 35, 55);
    static final int ACTION_BG      = Color.argb(220, 30, 25, 55);
    static final int GOOD_MAGIC     = Color.argb(255, 153, 128, 255); // Brogue {60,50,100}
    static final int BAD_MAGIC      = Color.argb(255, 255, 128, 153); // Brogue {100,50,60}

    // Good/bad magic indicator glyphs (Brogue's G_GOOD_MAGIC / G_BAD_MAGIC).
    static final String GOOD_MAGIC_GLYPH = new String(Character.toChars(0x29F3));
    static final String BAD_MAGIC_GLYPH  = new String(Character.toChars(0x29F2));

    static final int DISABLED_BG    = Color.argb(100, 15, 13, 30);
    static final int DISABLED_TEXT  = Color.argb(100, 100, 95, 110);

    /** Toggle-active background used by animateToggle's "active" state. */
    static final int TOGGLE_ACTIVE  = Color.argb(51, 50, 35, 15);
}
