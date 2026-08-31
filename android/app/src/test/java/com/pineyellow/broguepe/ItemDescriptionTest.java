package com.pineyellow.broguepe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class ItemDescriptionTest {
    @Test
    public void highlightsOnlyComparisonPercentagesAndPreservesUnknownPropertiesWarning() throws Exception {
        String prefix = "Wielding the sword, assuming it has no hidden properties, "
            + "will increase your current accuracy by ";
        String between = ", and will decrease your current damage by ";
        ItemDescription description = ItemDescription.fromJson(new JSONObject()
            .put("name", "a sword")
            .put("descRuns", new JSONArray()
                .put(run("A SWORD", 0))
                .put(run("\n\n" + prefix, 0))
                .put(run("12%", 1))
                .put(run(between, 0))
                .put(run("12%", -1))
                .put(run(". It cannot be corroded by acid.", 0))));

        assertEquals(prefix + "12%" + between + "12%. It cannot be corroded by acid.",
            description.text);
        assertEquals(2, description.highlights.size());
        assertHighlight(description, 0, prefix.length(), "12%", 1);
        assertHighlight(description, 1, prefix.length() + 3 + between.length(), "12%", -1);
    }

    @Test
    public void neutralPercentagesAndOtherNumbersHaveNoHighlights() throws Exception {
        String text = "An intrinsic enchantment of +2. Accuracy increases by 0%, damage increases by 0%.";
        ItemDescription description = ItemDescription.fromJson(new JSONObject()
            .put("descRuns", new JSONArray().put(run(text, 0))));

        assertEquals(text, description.text);
        assertTrue(description.highlights.isEmpty());
        assertEquals(text, description.styledText());
    }

    @Test
    public void strengthBonusAndComparisonPercentagesKeepSeparateHighlights() throws Exception {
        String prefix = "The sword bears an intrinsic enchantment of +1, "
            + "and carries an additional bonus of ";
        String between = " because of your excess strength. "
            + "Wielding the sword will increase your current accuracy by ";
        ItemDescription description = ItemDescription.fromJson(new JSONObject()
            .put("name", "a +1 sword")
            .put("descRuns", new JSONArray()
                .put(run("A +1 SWORD\n\n" + prefix, 0))
                .put(run("+0.50", 1))
                .put(run(between, 0))
                .put(run("6%", 1))
                .put(run(", and will increase your current damage by 0%.", 0))));

        assertEquals(prefix + "+0.50" + between + "6%, and will increase your current damage by 0%.",
            description.text);
        assertEquals(2, description.highlights.size());
        assertHighlight(description, 0, prefix.length(), "+0.50", 1);
        assertHighlight(description, 1, prefix.length() + 5 + between.length(), "6%", 1);
    }

    @Test
    public void intrinsicEnchantmentsAndPenaltiesPreserveTheirColorCues() throws Exception {
        String enchantmentPrefix = "The sword bears an intrinsic enchantment of ";
        ItemDescription enchanted = ItemDescription.fromJson(new JSONObject()
            .put("descRuns", new JSONArray()
                .put(run(enchantmentPrefix, 0))
                .put(run("+2", 1))
                .put(run(".", 0))));

        assertEquals(enchantmentPrefix + "+2.", enchanted.text);
        assertHighlight(enchanted, 0, enchantmentPrefix.length(), "+2", 1);

        String penaltyPrefix = "The axe bears an intrinsic penalty of ";
        ItemDescription penalized = ItemDescription.fromJson(new JSONObject()
            .put("descRuns", new JSONArray()
                .put(run(penaltyPrefix, 0))
                .put(run("-1", -1))
                .put(run(".", 0))));

        assertEquals(penaltyPrefix + "-1.", penalized.text);
        assertHighlight(penalized, 0, penaltyPrefix.length(), "-1", -1);
    }

    @Test
    public void preservesBrogueColorBoundariesForProse() throws Exception {
        String prefix = "You can feel an ";
        String aura = "aura of benevolent magic";
        String between = " radiating from the sword. ";
        String protection = "The sword cannot be corroded by acid.";
        String beforeCurse = "\n\n";
        String curse = "You can feel a malevolent magic lurking within the sword.";
        ItemDescription description = ItemDescription.fromJson(new JSONObject()
            .put("descRuns", new JSONArray()
                .put(run(prefix, 0))
                .put(run(aura, 1))
                .put(run(between, 0))
                .put(run(protection, 1))
                .put(run(beforeCurse, 0))
                .put(run(curse, -1))));

        assertEquals(prefix + aura + between + protection + beforeCurse + curse,
            description.text);
        assertEquals(3, description.highlights.size());
        assertHighlight(description, 0, prefix.length(), aura, 1);
        assertHighlight(description, 1, prefix.length() + aura.length() + between.length(),
            protection, 1);
        assertHighlight(description, 2, prefix.length() + aura.length() + between.length()
            + protection.length() + beforeCurse.length(), curse, -1);
    }

    @Test
    public void nonEquipmentDescriptionsPreserveBrogueColorRuns() throws Exception {
        String prefix = "It will increase your maximum health by ";
        ItemDescription description = ItemDescription.fromJson(new JSONObject()
            .put("category", "potion")
            .put("descRuns", new JSONArray()
                .put(run(prefix, 0))
                .put(run("4%", 1))
                .put(run(".", 0))));

        assertEquals(prefix + "4%.", description.text);
        assertEquals(1, description.highlights.size());
        assertHighlight(description, 0, prefix.length(), "4%", 1);
    }

    @Test
    public void equippedArmorShowsStrengthPenaltyWithoutColoringTheExplanation() throws Exception {
        String prefix = "The chain mail carries a penalty of ";
        String suffix = " because of your inadequate strength. "
            + "It will reveal its secrets if worn for 1000 turns.";
        ItemDescription description = ItemDescription.fromJson(new JSONObject()
            .put("name", "a chain mail")
            .put("equipped", true)
            .put("descRuns", new JSONArray()
                .put(run("A CHAIN MAIL\n\n" + prefix, 0))
                .put(run("-12.50", -1))
                .put(run(suffix, 0))));

        assertEquals(prefix + "-12.50" + suffix, description.text);
        assertEquals(1, description.highlights.size());
        assertHighlight(description, 0, prefix.length(), "-12.50", -1);
    }

    @Test
    public void betterProjectedArmorRatingHighlightsOnlyTheRating() throws Exception {
        String prefix = "Wearing the plate armor, assuming it has no hidden properties, "
            + "will result in an armor rating of ";
        ItemDescription description = ItemDescription.fromJson(new JSONObject()
            .put("name", "a plate armor")
            .put("descRuns", new JSONArray()
                .put(run("A PLATE ARMOR\n\n" + prefix, 0))
                .put(run("12", 1))
                .put(run(".", 0))));

        assertEquals(prefix + "12.", description.text);
        assertEquals(1, description.highlights.size());
        assertHighlight(description, 0, prefix.length(), "12", 1);
    }

    @Test
    public void worseProjectedArmorRatingHighlightsOnlyTheRating() throws Exception {
        String prefix = "Wearing the leather armor will result in an armor rating of ";
        ItemDescription description = ItemDescription.fromJson(new JSONObject()
            .put("name", "a leather armor")
            .put("descRuns", new JSONArray()
                .put(run("A LEATHER ARMOR\n\n" + prefix, 0))
                .put(run("2", -1))
                .put(run(".", 0))));

        assertEquals(prefix + "2.", description.text);
        assertEquals(1, description.highlights.size());
        assertHighlight(description, 0, prefix.length(), "2", -1);
    }

    @Test
    public void equalProjectedArmorRatingRemainsNeutral() throws Exception {
        String text = "Wearing the scale mail will result in an armor rating of 5.";
        ItemDescription description = ItemDescription.fromJson(new JSONObject()
            .put("descRuns", new JSONArray().put(run(text, 0))));

        assertEquals(text, description.text);
        assertTrue(description.highlights.isEmpty());
    }

    @Test
    public void stealthRangeIncreaseExplainsDetectionRadiusAndHighlightsOnlyTheNumber() throws Exception {
        String prefix = "Equipping the banded mail will increase your stealth range "
            + "(the radius enemies can detect you) by ";
        ItemDescription description = ItemDescription.fromJson(new JSONObject()
            .put("name", "a banded mail")
            .put("descRuns", new JSONArray()
                .put(run("A BANDED MAIL\n\n" + prefix, 0))
                .put(run("3", -1))
                .put(run(".", 0))));

        assertEquals(prefix + "3.", description.text);
        assertEquals(1, description.highlights.size());
        assertHighlight(description, 0, prefix.length(), "3", -1);
    }

    @Test
    public void stealthRangeDecreaseHighlightsTheNumberAsBeneficial() throws Exception {
        String prefix = "Equipping the scale mail will decrease your stealth range "
            + "(the radius enemies can detect you) by ";
        ItemDescription description = ItemDescription.fromJson(new JSONObject()
            .put("name", "a scale mail")
            .put("descRuns", new JSONArray()
                .put(run("A SCALE MAIL\n\n" + prefix, 0))
                .put(run("3", 1))
                .put(run(".", 0))));

        assertEquals(prefix + "3.", description.text);
        assertEquals(1, description.highlights.size());
        assertHighlight(description, 0, prefix.length(), "3", 1);
    }

    @Test
    public void highlightOffsetsUseJavaCharactersAfterRemovingUnicodeTitle() throws Exception {
        String name = "sword \uD83D\uDDE1";
        String prefix = "A blade called \"\u00E9clair\" \uD83D\uDDE1. Accuracy increases by ";
        ItemDescription description = ItemDescription.fromJson(new JSONObject()
            .put("name", name)
            .put("descRuns", new JSONArray()
                .put(run(name + "\n\n" + prefix, 0))
                .put(run("7%", 1))));

        assertEquals(prefix + "7%", description.text);
        assertEquals(1, description.highlights.size());
        assertHighlight(description, 0, prefix.length(), "7%", 1);
    }

    @Test
    public void plainDescriptionsStillRemoveTheDuplicateTitle() throws Exception {
        ItemDescription description = ItemDescription.fromJson(new JSONObject()
            .put("name", "a dagger")
            .put("desc", "A DAGGER\n\nA simple iron dagger."));

        assertEquals("A simple iron dagger.", description.text);
        assertTrue(description.highlights.isEmpty());
    }

    @Test
    public void malformedRunsFallBackToPlainDescriptionWithoutStaleHighlights() throws Exception {
        ItemDescription description = ItemDescription.fromJson(new JSONObject()
            .put("name", "a sword")
            .put("desc", "A SWORD\n\nFallback description.")
            .put("descRuns", new JSONArray()
                .put(run("12%", 1))
                .put(new JSONObject().put("text", JSONObject.NULL))));

        assertEquals("Fallback description.", description.text);
        assertTrue(description.highlights.isEmpty());
    }

    @Test
    public void descriptionsWithoutRepeatedTitlesAreNotTrimmed() throws Exception {
        ItemDescription description = ItemDescription.fromJson(new JSONObject()
            .put("name", "a sword")
            .put("descRuns", new JSONArray().put(run("5%", -1)).put(run(" less damage.", 0))));

        assertEquals("5% less damage.", description.text);
        assertHighlight(description, 0, 0, "5%", -1);
    }

    @Test
    public void selectionRowsWithoutDescriptionsRemainEmpty() {
        ItemDescription description = ItemDescription.fromJson(new JSONObject());

        assertEquals("", description.text);
        assertTrue(description.highlights.isEmpty());
    }

    private static JSONObject run(String text, int polarity) throws Exception {
        return new JSONObject().put("text", text).put("polarity", polarity);
    }

    private static void assertHighlight(ItemDescription description, int index, int start,
                                        String text, int polarity) {
        ItemDescription.Highlight highlight = description.highlights.get(index);
        assertEquals(start, highlight.start);
        assertEquals(text, description.text.substring(highlight.start, highlight.end));
        assertEquals(polarity, highlight.polarity);
    }
}
