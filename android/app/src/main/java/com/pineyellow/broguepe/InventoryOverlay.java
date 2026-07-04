package com.pineyellow.broguepe;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;

/** Slides the player's inventory in from the right when the engine asks for
 *  it (either to browse or to select an item for an action). The JSON blob
 *  the engine pushes over JNI describes each item; each row expands on tap
 *  to reveal the item's description + available verbs as inline buttons. */
final class InventoryOverlay {

    private static final int TILE_ATLAS_COLUMNS = 16;
    private static final int TILE_ATLAS_ROWS = 24;
    private static final int TILE_SOURCE_SAMPLE = 4;

    private final BrogueActivity activity;
    private final FrameLayout host;
    private final SparseArray<Bitmap> tileMasks = new SparseArray<>();
    private View currentlyExpandedDetail;

    InventoryOverlay(BrogueActivity activity, FrameLayout host) {
        this.activity = activity;
        this.host = host;
    }

    void show(final String json) {
        activity.runOnUiThread(() -> {
            // Cancel any in-flight hide animation so its withEndAction
            // doesn't wipe the new content (e.g. nested ring-swap prompt).
            for (int i = 0; i < host.getChildCount(); i++) {
                host.getChildAt(i).animate().cancel();
            }
            host.removeAllViews();
            currentlyExpandedDetail = null;

            try {
                JSONObject root = new JSONObject(json);
                String mode = root.optString("mode", "inventory");
                String prompt = root.optString("prompt", "");
                boolean selectMode = "select".equals(mode);
                JSONArray items = root.getJSONArray("items");

                int equippedCount = 0;
                if (items.length() > 0) {
                    equippedCount = items.getJSONObject(0).optInt("equippedCount", 0);
                }

                View backdrop = new View(activity);
                backdrop.setBackgroundColor(Color.argb(140, 0, 0, 0));
                backdrop.setOnClickListener(v -> KeyInput.sendKey(activity, KeyEvent.KEYCODE_ESCAPE));
                host.addView(backdrop, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
                backdrop.setAlpha(0f);
                backdrop.animate().alpha(1f).setDuration(280).start();

                ScrollView scrollView = new ScrollView(activity);
                scrollView.setFillViewport(false);
                scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
                scrollView.setFadingEdgeLength(activity.dpToPx(24));
                scrollView.setVerticalFadingEdgeEnabled(true);

                LinearLayout panel = new LinearLayout(activity);
                panel.setOrientation(LinearLayout.VERTICAL);
                int pad = activity.dpToPx(8);
                panel.setPadding(pad, pad, pad, pad);

                GradientDrawable panelBg = new GradientDrawable();
                panelBg.setShape(GradientDrawable.RECTANGLE);
                panelBg.setCornerRadii(new float[]{
                    activity.dpToPx(4), activity.dpToPx(4), 0, 0, 0, 0,
                    activity.dpToPx(4), activity.dpToPx(4)});
                panelBg.setColor(Palette.INVENTORY_BG);
                panelBg.setStroke(1, Palette.BORDER_DIM);
                scrollView.setBackground(panelBg);

                LinearLayout headerBar = new LinearLayout(activity);
                headerBar.setOrientation(LinearLayout.HORIZONTAL);
                headerBar.setGravity(Gravity.CENTER_VERTICAL);

                TextView header = new TextView(activity);
                // 26 = a–z inventory letter cap (MAX_PACK_ITEMS in the engine).
                String inventoryHeader = "INVENTORY ( " + items.length() + " / 26 )";
                header.setText(selectMode && !prompt.isEmpty() ? prompt
                    : selectMode ? "SELECT ITEM" : inventoryHeader);
                header.setTextColor(selectMode ? Palette.PALE_BLUE : Palette.DIM_WHITE_BLUE);
                header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                header.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                header.setLetterSpacing(0.15f);
                header.setPadding(activity.dpToPx(4), activity.dpToPx(2), 0, activity.dpToPx(4));
                headerBar.addView(header, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                // Eye icon → read-only Discovered Items (engine maps 'D' to it
                // while the inventory loop is active).
                ImageButton discoveriesBtn = makeHeaderIcon(R.drawable.ic_visibility,
                    "View discovered items");
                discoveriesBtn.setOnClickListener(v -> KeyInput.sendChar(activity, 'D'));
                headerBar.addView(discoveriesBtn);

                panel.addView(headerBar);

                View headerSep = new View(activity);
                GradientDrawable sepGrad = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{ Palette.DIM_BLUE_GRAY, Palette.DIM_WHITE_BLUE, Palette.DIM_BLUE_GRAY });
                headerSep.setBackground(sepGrad);
                LinearLayout.LayoutParams hSepP = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1);
                hSepP.setMargins(activity.dpToPx(4), 0, activity.dpToPx(4), activity.dpToPx(6));
                panel.addView(headerSep, hSepP);

                if (items.length() == 0) {
                    TextView emptyMsg = new TextView(activity);
                    emptyMsg.setText(R.string.inventory_empty);
                    emptyMsg.setTextColor(Color.argb(160, 140, 150, 190));
                    emptyMsg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    emptyMsg.setTypeface(Typeface.MONOSPACE, Typeface.ITALIC);
                    emptyMsg.setPadding(activity.dpToPx(8), activity.dpToPx(16),
                                        activity.dpToPx(8), activity.dpToPx(16));
                    emptyMsg.setGravity(Gravity.CENTER);
                    panel.addView(emptyMsg);
                }

                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);

                    if (i == equippedCount && equippedCount > 0) {
                        View sep = new View(activity);
                        sep.setBackgroundColor(Palette.BORDER_DIM);
                        LinearLayout.LayoutParams sepP = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1);
                        sepP.setMargins(activity.dpToPx(4), activity.dpToPx(4),
                                        activity.dpToPx(4), activity.dpToPx(4));
                        panel.addView(sep, sepP);
                    }

                    panel.addView(makeRow(item, scrollView, selectMode));
                }

                scrollView.addView(panel);

                int panelWidth = Math.min(activity.dpToPx(340),
                    (int)(activity.getResources().getDisplayMetrics().widthPixels * 0.75f));

                FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
                    panelWidth, FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.END);
                scrollParams.setMargins(0, activity.dpToPx(8),
                    activity.dpToPx(BrogueActivity.EDGE_SAFE_DP),
                    activity.dpToPx(BrogueActivity.EDGE_SAFE_DP));

                host.addView(scrollView, scrollParams);
                host.setVisibility(View.VISIBLE);

                scrollView.setTranslationX(panelWidth);
                scrollView.animate()
                    .translationX(0)
                    .setDuration(280)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .start();

            } catch (Exception e) {
                KeyInput.sendKey(activity, KeyEvent.KEYCODE_ESCAPE);
            }
        });
    }

    void hide() {
        activity.runOnUiThread(() -> {
            currentlyExpandedDetail = null;
            if (host.getChildCount() < 2) {
                host.setVisibility(View.GONE);
                host.removeAllViews();
                return;
            }
            View backdrop = host.getChildAt(0);
            View scrollView = host.getChildAt(1);

            scrollView.animate()
                .translationX(scrollView.getWidth())
                .setDuration(200)
                .setInterpolator(new DecelerateInterpolator())
                .start();

            backdrop.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction(() -> {
                    host.setVisibility(View.GONE);
                    host.removeAllViews();
                })
                .start();
        });
    }

    // Animation listeners return false, leaving performClick to the views'
    // existing OnClickListeners without double-firing inventory actions.
    @SuppressLint("ClickableViewAccessibility")
    private View makeRow(JSONObject item, ScrollView scrollView, boolean selectMode) {
        boolean equipped = item.optBoolean("equipped", false);
        boolean selectable = item.optBoolean("selectable", true);
        char letter = item.optString("letter", "?").charAt(0);
        String name = item.optString("name", "???");
        String desc = item.optString("desc", "");
        String nameUpper = name.toUpperCase(java.util.Locale.US) + "\n\n";
        if (desc.toUpperCase(java.util.Locale.US).startsWith(nameUpper)) {
            desc = desc.substring(nameUpper.length());
        }
        String actions = item.optString("actions", "");
        int magicPolarity = item.optInt("magicPolarity", 0);
        String displayName = name;

        LinearLayout headerRow = new LinearLayout(activity);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(activity.dpToPx(8), activity.dpToPx(8),
                             activity.dpToPx(8), activity.dpToPx(8));
        headerRow.setMinimumHeight(activity.dpToPx(44));
        headerRow.setContentDescription(displayName);

        GradientDrawable rowBg = new GradientDrawable();
        rowBg.setShape(GradientDrawable.RECTANGLE);
        rowBg.setCornerRadius(activity.dpToPx(3));
        if (selectMode) {
            rowBg.setColor(selectable ? Palette.ITEM_BG : Palette.DISABLED_BG);
        } else {
            rowBg.setColor(equipped ? Palette.EQUIPPED_GLOW : Palette.ITEM_BG);
            if (equipped) rowBg.setStroke(activity.dpToPx(1), Palette.DIM_BLUE_GRAY);
        }
        headerRow.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.RIPPLE_GLOW), rowBg, null));

        headerRow.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(60).start();
            } else if (e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
            }
            return false;
        });

        addItemIcon(headerRow, item, selectable);
        addMagicIndicator(headerRow, magicPolarity);

        TextView nameView = new TextView(activity);
        nameView.setText(displayName);
        nameView.setTextColor(selectable ? Palette.GHOST_WHITE
            : (selectMode ? Palette.DISABLED_TEXT : Color.argb(150, 120, 115, 130)));
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        nameView.setTypeface(Typeface.MONOSPACE);
        nameView.setPadding(activity.dpToPx(6), 0, 0, 0);
        nameView.setMaxLines(1);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        headerRow.addView(nameView, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Select mode: tap sends just the letter, no expandable detail.
        if (selectMode) {
            if (selectable) {
                headerRow.setOnClickListener(v -> KeyInput.sendChar(activity, letter));
            } else {
                headerRow.setClickable(false);
            }

            LinearLayout.LayoutParams rowP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            rowP.setMargins(0, activity.dpToPx(2), 0, activity.dpToPx(2));
            headerRow.setLayoutParams(rowP);
            return headerRow;
        }

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);

        TextView chevron = new TextView(activity);
        chevron.setText("\u25BE"); // ▾
        chevron.setTextColor(Color.argb(120, 140, 150, 190));
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        chevron.setPadding(activity.dpToPx(4), 0, 0, 0);
        headerRow.addView(chevron);

        row.addView(headerRow);

        LinearLayout detailSection = new LinearLayout(activity);
        detailSection.setOrientation(LinearLayout.VERTICAL);
        detailSection.setVisibility(View.GONE);
        detailSection.setPadding(activity.dpToPx(12), activity.dpToPx(4),
                                 activity.dpToPx(8), activity.dpToPx(8));

        GradientDrawable detailBg = new GradientDrawable();
        detailBg.setShape(GradientDrawable.RECTANGLE);
        detailBg.setCornerRadii(new float[]{
            0, 0, 0, 0, activity.dpToPx(3), activity.dpToPx(3),
            activity.dpToPx(3), activity.dpToPx(3)});
        detailBg.setColor(Color.argb(180, 15, 12, 32));
        detailSection.setBackground(detailBg);

        if (!desc.isEmpty()) {
            TextView descView = new TextView(activity);
            descView.setText(desc);
            descView.setTextColor(Palette.PALE_BLUE);
            descView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            descView.setTypeface(Typeface.MONOSPACE);
            descView.setLineSpacing(0, 1.3f);
            descView.setPadding(0, activity.dpToPx(4), 0, activity.dpToPx(8));
            detailSection.addView(descView);
        }

        if (!actions.isEmpty()) {
            LinearLayout actionRow = new LinearLayout(activity);
            actionRow.setOrientation(LinearLayout.HORIZONTAL);
            actionRow.setGravity(Gravity.START);

            int actionButtonIndex = 0;
            for (String ak : actions.split(",")) {
                if (ak.isEmpty()) continue;
                char actionChar = ak.charAt(0);
                String label = actionVerbLabel(actionChar);

                Button actionBtn = new Button(activity);
                actionBtn.setText(label);
                actionBtn.setTextColor(Palette.GHOST_WHITE);
                actionBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                actionBtn.setTypeface(Typeface.MONOSPACE);
                actionBtn.setAllCaps(true);
                actionBtn.setStateListAnimator(null);
                actionBtn.setElevation(0);
                actionBtn.setPadding(activity.dpToPx(12), activity.dpToPx(8),
                                     activity.dpToPx(12), activity.dpToPx(8));
                actionBtn.setMinWidth(0);
                actionBtn.setMinimumWidth(0);
                actionBtn.setMinHeight(activity.dpToPx(36));
                actionBtn.setMinimumHeight(activity.dpToPx(36));
                actionBtn.setContentDescription(label + " " + name);

                GradientDrawable abg = new GradientDrawable();
                abg.setShape(GradientDrawable.RECTANGLE);
                abg.setCornerRadius(activity.dpToPx(3));
                abg.setColor(Palette.ACTION_BG);
                abg.setStroke(1, Palette.BORDER_DIM);
                actionBtn.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(Palette.RIPPLE_GLOW), abg, null));

                actionBtn.setOnTouchListener((v, e) -> {
                    if (e.getAction() == MotionEvent.ACTION_DOWN) {
                        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(60).start();
                    } else if (e.getAction() == MotionEvent.ACTION_UP
                            || e.getAction() == MotionEvent.ACTION_CANCEL) {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(100)
                            .setInterpolator(new OvershootInterpolator(2f)).start();
                    }
                    return false;
                });

                final char actionKey = actionChar;
                actionBtn.setOnClickListener(v -> {
                    KeyInput.sendChar(activity, letter);
                    v.postDelayed(() -> KeyInput.sendChar(activity, actionKey), 50);
                });

                LinearLayout.LayoutParams btnP = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                btnP.setMargins(actionButtonIndex == 0 ? 0 : activity.dpToPx(4),
                    0, 0, 0);
                actionRow.addView(actionBtn, btnP);
                actionButtonIndex++;
            }
            detailSection.addView(actionRow);
        }

        row.addView(detailSection);

        headerRow.setOnClickListener(v -> {
            if (detailSection.getVisibility() == View.GONE) {
                if (currentlyExpandedDetail != null
                        && currentlyExpandedDetail != detailSection) {
                    View prev = currentlyExpandedDetail;
                    prev.animate().alpha(0f).setDuration(120)
                        .withEndAction(() -> prev.setVisibility(View.GONE)).start();
                }
                currentlyExpandedDetail = detailSection;
                detailSection.setVisibility(View.VISIBLE);
                detailSection.setAlpha(0f);
                detailSection.animate().alpha(1f).setDuration(150).start();
                chevron.setText("\u25B4"); // ▴
                row.post(() -> {
                    int rowBottom = row.getTop() + row.getHeight();
                    int visibleBottom = scrollView.getScrollY() + scrollView.getHeight();
                    if (rowBottom > visibleBottom) {
                        scrollView.smoothScrollTo(0, rowBottom - scrollView.getHeight());
                    }
                });
            } else {
                detailSection.animate().alpha(0f).setDuration(120)
                    .withEndAction(() -> detailSection.setVisibility(View.GONE)).start();
                currentlyExpandedDetail = null;
                chevron.setText("\u25BE"); // ▾
            }
        });

        LinearLayout.LayoutParams rowP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        rowP.setMargins(0, activity.dpToPx(2), 0, activity.dpToPx(2));
        row.setLayoutParams(rowP);

        return row;
    }

    @SuppressLint("ClickableViewAccessibility")
    private ImageButton makeHeaderIcon(int drawableRes, String contentDesc) {
        ImageButton btn = new ImageButton(activity);
        btn.setImageResource(drawableRes);
        btn.setColorFilter(Palette.PALE_BLUE);
        btn.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        btn.setBackground(null);
        btn.setStateListAnimator(null);
        btn.setElevation(0);
        btn.setContentDescription(contentDesc);
        int size = activity.dpToPx(36);
        btn.setMinimumWidth(size);
        btn.setMinimumHeight(size);
        int p = activity.dpToPx(6);
        btn.setPadding(p, p, p, p);
        btn.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(60).start();
            } else if (e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
            }
            return false;
        });
        return btn;
    }

    private void addItemIcon(LinearLayout row, JSONObject item, boolean enabled) {
        Bitmap mask = getTileMask(item.optInt("tileGlyph", -1));
        if (mask == null) return;

        ImageView icon = new ImageView(activity);
        icon.setImageBitmap(mask);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setColorFilter(Color.rgb(
            item.optInt("iconRed", 255),
            item.optInt("iconGreen", 242),
            item.optInt("iconBlue", 0)));
        icon.setAlpha(enabled ? 1f : 0.4f);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
            activity.dpToPx(28), activity.dpToPx(32));
        iconParams.setMargins(0, 0, activity.dpToPx(2), 0);
        row.addView(icon, iconParams);
    }

    private Bitmap getTileMask(int glyph) {
        if (glyph < 0 || glyph >= TILE_ATLAS_COLUMNS * TILE_ATLAS_ROWS) return null;

        Bitmap cached = tileMasks.get(glyph);
        if (cached != null) return cached;

        Bitmap source = null;
        BitmapRegionDecoder decoder = null;
        try (InputStream stream = activity.getAssets().open("tiles.png")) {
            decoder = BitmapRegionDecoder.newInstance(stream, false);
            int tileWidth = decoder.getWidth() / TILE_ATLAS_COLUMNS;
            int tileHeight = decoder.getHeight() / TILE_ATLAS_ROWS;
            int left = (glyph % TILE_ATLAS_COLUMNS) * tileWidth;
            int top = (glyph / TILE_ATLAS_COLUMNS) * tileHeight;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = TILE_SOURCE_SAMPLE;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            source = decoder.decodeRegion(
                new Rect(left, top, left + tileWidth, top + tileHeight), options);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (decoder != null) decoder.recycle();
        }
        if (source == null) return null;

        int tileWidth = source.getWidth();
        int tileHeight = source.getHeight();
        int[] pixels = new int[tileWidth * tileHeight];
        source.getPixels(pixels, 0, tileWidth, 0, 0, tileWidth, tileHeight);

        // The atlas uses white artwork on opaque black. Convert luminance to
        // alpha so Android can tint the tile without drawing a black box.
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int alpha = Math.max(Color.red(pixel),
                Math.max(Color.green(pixel), Color.blue(pixel)));
            pixels[i] = Color.argb(alpha, 255, 255, 255);
        }

        Bitmap mask = Bitmap.createBitmap(tileWidth, tileHeight, Bitmap.Config.ARGB_8888);
        mask.setPixels(pixels, 0, tileWidth, 0, 0, tileWidth, tileHeight);
        source.recycle();
        tileMasks.put(glyph, mask);
        return mask;
    }

    private void addMagicIndicator(LinearLayout row, int magicPolarity) {
        if (magicPolarity == 0) return;
        TextView v = new TextView(activity);
        v.setText(magicPolarity > 0 ? Palette.GOOD_MAGIC_GLYPH : Palette.BAD_MAGIC_GLYPH);
        v.setTextColor(magicPolarity > 0 ? Palette.GOOD_MAGIC : Palette.BAD_MAGIC);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        v.setTypeface(Typeface.MONOSPACE);
        v.setPadding(activity.dpToPx(4), 0, 0, 0);
        row.addView(v);
    }

    /** Human-readable label for an inventory verb key (a/e/r/c/d/t). */
    private static String actionVerbLabel(char key) {
        switch (key) {
            case 'a': return "Apply";
            case 'e': return "Equip";
            case 'r': return "Remove";
            case 'c': return "Call";
            case 'd': return "Drop";
            case 't': return "Throw";
            default:  return String.valueOf(key);
        }
    }
}
