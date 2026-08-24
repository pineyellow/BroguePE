package com.pineyellow.broguepe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TextInputDialogTest {

    @Test
    public void normalizeEngineTextTransliteratesTurkishLetters() {
        assertEquals("guclu cig sOIi",
            TextInputDialog.normalizeEngineText("güçlü çığ şÖİı", 30));
    }

    @Test
    public void normalizeEngineTextKeepsAsciiAndNormalizesSmartPunctuation() {
        assertEquals("Potion +5! \"safe\"-it's",
            TextInputDialog.normalizeEngineText("Potion +5! “safe”—it’s", 30));
    }

    @Test
    public void normalizeEngineTextDropsUnsupportedGlyphsAndControls() {
        assertEquals("abcDEF end",
            TextInputDialog.normalizeEngineText("abc🗡️中文D\nE\tF end", 30));
    }

    @Test
    public void normalizeEngineTextAppliesLimitAfterNormalization() {
        assertEquals("eee", TextInputDialog.normalizeEngineText("éééé", 3));
        assertEquals("", TextInputDialog.normalizeEngineText("text", 0));
        assertEquals("", TextInputDialog.normalizeEngineText(null, 10));
    }
}
