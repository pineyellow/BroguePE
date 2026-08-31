package com.pineyellow.broguepe;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Item description text and good/bad highlights supplied by the engine. */
final class ItemDescription {
    final String text;
    final List<Highlight> highlights;

    static final class Highlight {
        final int start;
        final int end;
        final int polarity;

        Highlight(int start, int end, int polarity) {
            this.start = start;
            this.end = end;
            this.polarity = polarity;
        }
    }

    private ItemDescription(String text, List<Highlight> highlights) {
        this.text = text;
        this.highlights = highlights;
    }

    static ItemDescription fromJson(JSONObject item) {
        String description = item.optString("desc", "");
        List<Highlight> highlights = new ArrayList<>();
        JSONArray runs = item.optJSONArray("descRuns");
        if (runs != null && runs.length() > 0) {
            StringBuilder joined = new StringBuilder();
            boolean valid = true;
            for (int i = 0; i < runs.length(); i++) {
                JSONObject run = runs.optJSONObject(i);
                if (run == null || !(run.opt("text") instanceof String)) {
                    valid = false;
                    break;
                }
                int start = joined.length();
                joined.append(run.optString("text"));
                int polarity = run.optInt("polarity", 0);
                if (polarity != 0 && joined.length() > start) {
                    highlights.add(new Highlight(start, joined.length(), polarity));
                }
            }
            if (valid) {
                description = joined.toString();
            } else {
                highlights.clear();
            }
        }

        // The inventory row already displays the name. Adjust highlights with it.
        String title = item.optString("name", "") + "\n\n";
        if (description.regionMatches(true, 0, title, 0, title.length())) {
            description = description.substring(title.length());
            List<Highlight> bodyHighlights = new ArrayList<>();
            for (Highlight highlight : highlights) {
                if (highlight.end > title.length()) {
                    bodyHighlights.add(new Highlight(
                        Math.max(0, highlight.start - title.length()),
                        highlight.end - title.length(), highlight.polarity));
                }
            }
            highlights = bodyHighlights;
        }
        return new ItemDescription(description, highlights);
    }

    CharSequence styledText() {
        if (highlights.isEmpty()) {
            return text;
        }
        SpannableString styled = new SpannableString(text);
        for (Highlight highlight : highlights) {
            styled.setSpan(new ForegroundColorSpan(highlight.polarity > 0
                    ? Palette.STAT_IMPROVEMENT : Palette.STAT_PENALTY),
                highlight.start, highlight.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return styled;
    }
}
