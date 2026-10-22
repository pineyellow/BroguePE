package com.pineyellow.broguepe;

/** Shared dimensions for visually related app UI components. */
final class UiStyle {

    static final int PANEL_CORNER_RADIUS_DP = 8;
    static final int MENU_ITEM_CORNER_RADIUS_DP = 8;
    // Overlay title text (Inventory, Discovered Items).
    static final int HEADER_TITLE_TEXT_SP = 14;
    // Title-bar icon button: a 48x28dp box, a little taller than the title
    // text, drawn inside a 36dp-tall touch area, with its right edge on the
    // rows' right edge.
    static final int HEADER_ICON_BUTTON_HEIGHT_DP = 36;
    static final int HEADER_ICON_BOX_WIDTH_DP = 48;
    static final int HEADER_ICON_BOX_HEIGHT_DP = 28;
    static final int HEADER_ICON_GLYPH_DP = 20;

    private UiStyle() {}
}
