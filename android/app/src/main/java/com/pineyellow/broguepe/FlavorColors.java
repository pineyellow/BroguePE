package com.pineyellow.broguepe;

import android.graphics.Color;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Colors for the flavor words of unidentified items and for names given with
 *  Call. Potions and rings get their own color per word, matching
 *  itemColorsRef and itemGemsRef in Globals.c; the game itself has no colors
 *  for them, and the dark ones are lifted so they still read on the dark
 *  inventory rows. Scroll titles and all called names share Brogue's purple,
 *  lightened. Staff woods and wand metals stay plain; instead the "staff" or
 *  "wand" noun gets one color each, identified or not, so the two are easy
 *  to tell apart. */
final class FlavorColors {

    // Item category bits, matching the itemCategory enum in Rogue.h.
    private static final int CATEGORY_POTION = 1 << 3;
    private static final int CATEGORY_STAFF  = 1 << 5;
    private static final int CATEGORY_WAND   = 1 << 6;
    private static final int CATEGORY_RING   = 1 << 7;

    private static final int STAFF_WOOD = Color.rgb(148, 108, 72);  // Tree bark
    private static final int WAND_METAL = Color.rgb(150, 180, 215); // Steel blue
    private static final int ORCHID     = Color.rgb(165, 105, 205); // Brogue's purple

    // Wood and metal names never contain these, and called names and
    // inscriptions come after the noun, so the first match is the noun.
    private static final Pattern STAFF_NOUN =
        Pattern.compile("\\bstaffs?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WAND_NOUN =
        Pattern.compile("\\bwands?\\b", Pattern.CASE_INSENSITIVE);

    private static final Map<String, Integer> POTIONS = new HashMap<>();
    private static final Map<String, Integer> GEMS = new HashMap<>();

    static {
        POTIONS.put("crimson",    Color.rgb(230, 50, 80));
        POTIONS.put("scarlet",    Color.rgb(255, 70, 50));
        POTIONS.put("orange",     Color.rgb(255, 145, 40));
        POTIONS.put("yellow",     Color.rgb(255, 230, 60));
        POTIONS.put("green",      Color.rgb(80, 220, 80));
        POTIONS.put("blue",       Color.rgb(70, 120, 255));
        POTIONS.put("indigo",     Color.rgb(110, 90, 255));
        POTIONS.put("violet",     Color.rgb(180, 90, 255));
        POTIONS.put("puce",       Color.rgb(205, 110, 140));
        POTIONS.put("mauve",      Color.rgb(215, 140, 215));
        POTIONS.put("burgundy",   Color.rgb(200, 50, 95));
        POTIONS.put("turquoise",  Color.rgb(40, 220, 200));
        POTIONS.put("aquamarine", Color.rgb(100, 245, 210));
        POTIONS.put("gray",       Color.rgb(170, 170, 175));
        POTIONS.put("pink",       Color.rgb(255, 140, 190));
        POTIONS.put("white",      Color.rgb(245, 245, 250));
        POTIONS.put("lavender",   Color.rgb(195, 165, 255));
        POTIONS.put("tan",        Color.rgb(225, 185, 125));
        POTIONS.put("brown",      Color.rgb(190, 120, 60));
        POTIONS.put("cyan",       Color.rgb(40, 225, 245));
        POTIONS.put("black",      Color.rgb(100, 95, 110));

        GEMS.put("diamond",     Color.rgb(210, 245, 255));
        GEMS.put("opal",        Color.rgb(235, 205, 240));
        GEMS.put("garnet",      Color.rgb(210, 45, 70));
        GEMS.put("ruby",        Color.rgb(245, 40, 95));
        GEMS.put("amethyst",    Color.rgb(175, 90, 240));
        GEMS.put("topaz",       Color.rgb(255, 185, 50));
        GEMS.put("onyx",        Color.rgb(110, 108, 115));
        GEMS.put("tourmaline",  Color.rgb(245, 95, 165));
        GEMS.put("sapphire",    Color.rgb(60, 100, 255));
        GEMS.put("obsidian",    Color.rgb(100, 85, 135));
        GEMS.put("malachite",   Color.rgb(30, 200, 120));
        GEMS.put("aquamarine",  Color.rgb(100, 230, 230));
        GEMS.put("emerald",     Color.rgb(40, 210, 110));
        GEMS.put("jade",        Color.rgb(100, 200, 130));
        GEMS.put("alexandrite", Color.rgb(60, 180, 170));
        GEMS.put("agate",       Color.rgb(215, 140, 80));
        GEMS.put("bloodstone",  Color.rgb(70, 140, 90));
        GEMS.put("jasper",      Color.rgb(205, 85, 55));
    }

    private FlavorColors() { }

    /** The color for an item's flavor word, or for the name it was given with
     *  Call when called is true; 0 leaves it plain. */
    static int colorFor(int category, String word, boolean called) {
        if (called) return ORCHID;
        if ((category & (CATEGORY_STAFF | CATEGORY_WAND)) != 0) return 0;
        Map<String, Integer> table = (category & CATEGORY_POTION) != 0 ? POTIONS
            : (category & CATEGORY_RING) != 0 ? GEMS : null;
        Integer color = table == null ? null
            : table.get(word.trim().toLowerCase(Locale.ROOT));
        return color != null ? color : ORCHID;
    }

    /** {start, end, color} of the "staff(s)" or "wand(s)" noun in a staff's
     *  or wand's name, or null for other items or if it isn't found. */
    static int[] nounSpan(int category, String name) {
        Pattern noun;
        int color;
        if ((category & CATEGORY_STAFF) != 0) {
            noun = STAFF_NOUN;
            color = STAFF_WOOD;
        } else if ((category & CATEGORY_WAND) != 0) {
            noun = WAND_NOUN;
            color = WAND_METAL;
        } else {
            return null;
        }
        Matcher m = noun.matcher(name);
        return m.find() ? new int[]{m.start(), m.end(), color} : null;
    }
}
