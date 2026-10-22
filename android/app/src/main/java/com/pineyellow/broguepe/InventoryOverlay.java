package com.pineyellow.broguepe;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
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

import java.util.ArrayList;
import java.util.List;

/** Slides the player's inventory in from the right when the engine asks for
 *  it (either to browse or to select an item for an action). The JSON blob
 *  the engine pushes over JNI describes each item; each row expands on tap
 *  to reveal the item's description + available verbs as inline buttons. */
final class InventoryOverlay {

    private final BrogueActivity activity;
    private final FrameLayout host;
    private View currentlyExpandedDetail;
    private ImageView currentlyExpandedChevron;
    private final List<View> browseDetailSections = new ArrayList<>();
    private ScrollView browseScrollView;
    private View browseContent;
    private float browseScrollFraction;
    private boolean hasBrowseScrollPosition;
    // True only while the engine is waiting on this panel. Item letters double
    // as game commands, so a tap that lands after the panel has already
    // answered would be played as a move in the dungeon instead.
    private boolean acceptingInput;

    InventoryOverlay(BrogueActivity activity, FrameLayout host) {
        this.activity = activity;
        this.host = host;
    }

    /** Claims the panel's single answer to the engine. Every later tap gets
     *  false until the engine shows the panel again. */
    private boolean claimInput() {
        if (!acceptingInput) return false;
        acceptingInput = false;
        return true;
    }

    /** Android Back closes the panel, the same as a tap outside it. Returns
     *  false when the engine is not waiting on this panel. */
    boolean handleBack() {
        if (host.getVisibility() != View.VISIBLE || !claimInput()) return false;
        KeyInput.sendKey(activity, KeyEvent.KEYCODE_ESCAPE);
        return true;
    }

    void show(final String json) {
        activity.runOnUiThread(() -> {
            // Item letters overlap the D-pad's movement keys, so a press that
            // is still held must not keep repeating into the inventory.
            activity.cancelDpadInput();
            acceptingInput = false;

            rememberBrowseScrollPosition();
            clearBrowseViewReferences();

            // Cancel any in-flight hide animation so its withEndAction
            // doesn't wipe the new content (e.g. nested ring-swap prompt).
            for (int i = 0; i < host.getChildCount(); i++) {
                host.getChildAt(i).animate().cancel();
            }
            host.removeAllViews();
            currentlyExpandedDetail = null;
            currentlyExpandedChevron = null;

            try {
                JSONObject root = new JSONObject(json);
                String mode = root.optString("mode", "inventory");
                String prompt = root.optString("prompt", "");
                boolean selectMode = "select".equals(mode);
                boolean showCallButton = GameSettings.getBool(activity,
                    GameSettings.PREF_SHOW_CALL_BUTTON, false);
                JSONArray items = root.getJSONArray("items");

                int equippedCount = 0;
                for (int i = 0; i < items.length(); i++) {
                    if (items.getJSONObject(i).optBoolean("equipped", false)) {
                        equippedCount++;
                    }
                }

                View backdrop = new View(activity);
                backdrop.setBackgroundColor(Color.argb(140, 0, 0, 0));
                backdrop.setOnClickListener(v -> {
                    if (claimInput()) KeyInput.sendKey(activity, KeyEvent.KEYCODE_ESCAPE);
                });
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
                    activity.dpToPx(UiStyle.PANEL_CORNER_RADIUS_DP),
                    activity.dpToPx(UiStyle.PANEL_CORNER_RADIUS_DP), 0, 0, 0, 0,
                    activity.dpToPx(UiStyle.PANEL_CORNER_RADIUS_DP),
                    activity.dpToPx(UiStyle.PANEL_CORNER_RADIUS_DP)});
                panelBg.setColor(Palette.INVENTORY_BG);
                panelBg.setStroke(1, Palette.BORDER_DIM);
                scrollView.setBackground(panelBg);

                LinearLayout headerBar = new LinearLayout(activity);
                headerBar.setOrientation(LinearLayout.HORIZONTAL);
                headerBar.setGravity(Gravity.CENTER_VERTICAL);

                TextView header = new TextView(activity);
                int packCount = root.optInt("packCount", items.length());
                int packCapacity = root.optInt("packCapacity", 26);
                header.setText(selectMode && !prompt.isEmpty() ? prompt
                    : selectMode ? "SELECT ITEM" : "INVENTORY");
                header.setTextColor(selectMode ? Palette.PALE_BLUE : Palette.DIM_WHITE_BLUE);
                styleHeaderText(header);
                header.setPadding(activity.dpToPx(4), activity.dpToPx(2), 0, activity.dpToPx(4));
                headerBar.addView(header, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                // The pack count sits on the right in a regular weight, like the
                // New Game "Seed:" label, and turns red once the pack is full.
                if (!selectMode) {
                    TextView packCountView = new TextView(activity);
                    packCountView.setText("(" + packCount + "/" + packCapacity + ")");
                    packCountView.setTextColor(packCount >= packCapacity
                        ? Palette.PACK_FULL : Palette.PALE_BLUE);
                    styleHeaderText(packCountView);
                    packCountView.setTypeface(Typeface.MONOSPACE);
                    packCountView.setPadding(0, activity.dpToPx(2),
                        activity.dpToPx(8), activity.dpToPx(4));
                    headerBar.addView(packCountView);
                }

                // Eye icon → read-only Discovered Items (engine maps 'D' to it
                // while the inventory loop is active).
                ImageButton discoveriesBtn = makeHeaderIcon(R.drawable.ic_visibility,
                    "View discovered items");
                discoveriesBtn.setOnClickListener(v -> {
                    if (claimInput()) KeyInput.sendChar(activity, 'D');
                });
                int headerButtonWidth = activity.dpToPx(UiStyle.HEADER_ICON_BOX_WIDTH_DP);
                int headerButtonHeight = activity.dpToPx(UiStyle.HEADER_ICON_BUTTON_HEIGHT_DP);
                headerBar.addView(discoveriesBtn, new LinearLayout.LayoutParams(
                    headerButtonWidth, headerButtonHeight));

                // The button's touch area reaches above its box, so pull the
                // row up by that much: the box then sits the panel's padding
                // from the top edge, as it does from the right.
                LinearLayout.LayoutParams headerBarParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
                headerBarParams.topMargin = -activity.dpToPx(
                    (UiStyle.HEADER_ICON_BUTTON_HEIGHT_DP - UiStyle.HEADER_ICON_BOX_HEIGHT_DP) / 2f);
                panel.addView(headerBar, headerBarParams);

                View headerSep = new View(activity);
                headerSep.setBackgroundColor(Palette.BORDER_DIM);
                LinearLayout.LayoutParams hSepP = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1);
                // Even space between the header button's box, the divider and
                // the first row (which adds its own 2dp margin).
                hSepP.setMargins(activity.dpToPx(4), activity.dpToPx(4),
                                 activity.dpToPx(4), activity.dpToPx(6));
                panel.addView(headerSep, hSepP);

                if (items.length() == 0) {
                    TextView emptyMsg = new TextView(activity);
                    emptyMsg.setText(R.string.inventory_empty);
                    emptyMsg.setTextColor(Color.argb(160, 144, 148, 190));
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

                    panel.addView(makeRow(item, scrollView, selectMode, showCallButton));
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
                acceptingInput = true;

                if (!selectMode) {
                    browseScrollView = scrollView;
                    browseContent = panel;
                    restoreBrowseScrollPosition(scrollView, panel);
                }

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
            // The panel stays on screen while it slides out; the engine has
            // stopped listening to it by then.
            acceptingInput = false;
            rememberBrowseScrollPosition();
            clearBrowseViewReferences();
            currentlyExpandedDetail = null;
            currentlyExpandedChevron = null;
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

    void resetScrollMemory() {
        browseScrollFraction = 0f;
        hasBrowseScrollPosition = false;
    }

    private void rememberBrowseScrollPosition() {
        if (browseScrollView == null || browseContent == null
                || browseScrollView.getHeight() <= 0 || browseContent.getHeight() <= 0) {
            return;
        }

        int scrollY = browseScrollView.getScrollY();
        int collapsedScrollY = scrollY;
        int collapsedContentHeight = browseContent.getHeight();

        // Descriptions are rebuilt collapsed, so remove their contribution
        // from both the saved offset and the scrollable content height.
        for (View detail : browseDetailSections) {
            if (detail.getVisibility() != View.VISIBLE || detail.getHeight() <= 0) {
                continue;
            }

            int detailTop = descendantTopWithin(detail, browseContent);
            if (detailTop < 0) {
                continue;
            }

            int detailHeight = detail.getHeight();
            collapsedContentHeight -= detailHeight;
            if (scrollY > detailTop) {
                collapsedScrollY -= Math.min(detailHeight, scrollY - detailTop);
            }
        }

        int maxScroll = Math.max(0,
            collapsedContentHeight - browseScrollView.getHeight());
        collapsedScrollY = Math.max(0, Math.min(collapsedScrollY, maxScroll));
        browseScrollFraction = maxScroll == 0
            ? 0f : (float) collapsedScrollY / maxScroll;
        hasBrowseScrollPosition = true;
    }

    private void restoreBrowseScrollPosition(ScrollView scrollView, View content) {
        if (!hasBrowseScrollPosition) {
            return;
        }

        scrollView.post(() -> {
            if (scrollView != browseScrollView || content != browseContent
                    || !hasBrowseScrollPosition) {
                return;
            }
            int maxScroll = Math.max(0, content.getHeight() - scrollView.getHeight());
            scrollView.scrollTo(0, Math.round(browseScrollFraction * maxScroll));
        });
    }

    private void clearBrowseViewReferences() {
        browseScrollView = null;
        browseContent = null;
        browseDetailSections.clear();
    }

    private static int descendantTopWithin(View descendant, View ancestor) {
        int top = 0;
        View current = descendant;
        while (current != ancestor) {
            top += current.getTop();
            android.view.ViewParent parent = current.getParent();
            if (!(parent instanceof View)) {
                return -1;
            }
            current = (View) parent;
        }
        return top;
    }

    // Animation listeners return false, leaving performClick to the views'
    // existing OnClickListeners without double-firing inventory actions.
    @SuppressLint("ClickableViewAccessibility")
    private View makeRow(JSONObject item, ScrollView scrollView, boolean selectMode,
                         boolean showCallButton) {
        boolean equipped = item.optBoolean("equipped", false);
        boolean selectable = item.optBoolean("selectable", true);
        char letter = item.optString("letter", "?").charAt(0);
        String name = item.optString("name", "???");
        ItemDescription description = ItemDescription.fromJson(item);
        String actions = item.optString("actions", "");
        int category = item.optInt("category", 0);
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
        rowBg.setCornerRadius(activity.dpToPx(UiStyle.MENU_ITEM_CORNER_RADIUS_DP));
        rowBg.setStroke(1, Palette.BORDER_DIM);
        if (selectMode) {
            rowBg.setColor(selectable ? Palette.ITEM_BG : Palette.DISABLED_BG);
        } else {
            rowBg.setColor(equipped ? Palette.EQUIPPED_GLOW : Palette.ITEM_BG);
            if (equipped) rowBg.setStroke(activity.dpToPx(1), Palette.DIM_BLUE_GRAY);
        }
        headerRow.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.RIPPLE_GLOW), rowBg, null));

        if (!selectMode || selectable) {
            headerRow.setOnTouchListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(60).start();
                } else if (e.getAction() == MotionEvent.ACTION_UP
                        || e.getAction() == MotionEvent.ACTION_CANCEL) {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                }
                return false;
            });
        }

        addItemIcon(activity, headerRow, item, selectable);
        addMagicIndicator(activity, headerRow, magicPolarity);

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
                headerRow.setOnClickListener(v -> {
                    if (claimInput()) KeyInput.sendChar(activity, letter);
                });
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

        // Points right when collapsed and is turned down when expanded.
        ImageView chevron = new ImageView(activity);
        chevron.setImageResource(R.drawable.ic_chevron);
        // SRC_IN keeps the colour's transparency; the default mode would
        // blend it over the white icon and draw it opaque and too bright.
        chevron.setColorFilter(Color.argb(120, 144, 148, 190), PorterDuff.Mode.SRC_IN);
        int chevronSize = activity.dpToPx(12);
        LinearLayout.LayoutParams chevronParams =
            new LinearLayout.LayoutParams(chevronSize, chevronSize);
        chevronParams.leftMargin = activity.dpToPx(4);
        headerRow.addView(chevron, chevronParams);

        row.addView(headerRow);

        LinearLayout detailSection = new LinearLayout(activity);
        detailSection.setOrientation(LinearLayout.VERTICAL);
        detailSection.setVisibility(View.GONE);
        detailSection.setPadding(activity.dpToPx(12), activity.dpToPx(4),
                                 activity.dpToPx(8), activity.dpToPx(8));
        browseDetailSections.add(detailSection);

        GradientDrawable detailBg = new GradientDrawable();
        detailBg.setShape(GradientDrawable.RECTANGLE);
        detailBg.setCornerRadii(new float[]{
            0, 0, 0, 0,
            activity.dpToPx(UiStyle.MENU_ITEM_CORNER_RADIUS_DP),
            activity.dpToPx(UiStyle.MENU_ITEM_CORNER_RADIUS_DP),
            activity.dpToPx(UiStyle.MENU_ITEM_CORNER_RADIUS_DP),
            activity.dpToPx(UiStyle.MENU_ITEM_CORNER_RADIUS_DP)});
        detailBg.setColor(Color.argb(180, 20, 13, 34));
        detailSection.setBackground(detailBg);

        if (!description.text.isEmpty()) {
            TextView descView = new TextView(activity);
            descView.setText(description.styledText());
            descView.setTextColor(Palette.GHOST_WHITE);
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
                if (actionChar == 'c' && !showCallButton) continue;
                String label = actionVerbLabel(actionChar, category);

                Button actionBtn = new Button(activity);
                actionBtn.setText(label);
                actionBtn.setTextColor(Palette.GHOST_WHITE);
                actionBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                actionBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
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
                abg.setCornerRadius(
                    activity.dpToPx(UiStyle.MENU_ITEM_CORNER_RADIUS_DP));
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
                    if (!claimInput()) return;
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
                    // Do not let the outgoing detail occupy space while the
                    // new row is measured for its smooth-scroll target.
                    prev.animate().cancel();
                    prev.setVisibility(View.GONE);
                    prev.setAlpha(1f);
                    if (currentlyExpandedChevron != null) {
                        currentlyExpandedChevron.setRotation(0f);
                    }
                }
                currentlyExpandedDetail = detailSection;
                currentlyExpandedChevron = chevron;
                detailSection.animate().cancel();
                detailSection.setVisibility(View.VISIBLE);
                detailSection.setAlpha(0f);
                detailSection.animate().alpha(1f).setDuration(150).start();
                chevron.setRotation(90f);
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
                // A second tap during the fade must not forget another item
                // that was opened in the meantime.
                if (currentlyExpandedDetail == detailSection) {
                    currentlyExpandedDetail = null;
                    currentlyExpandedChevron = null;
                }
                chevron.setRotation(0f);
            }
        });

        LinearLayout.LayoutParams rowP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        rowP.setMargins(0, activity.dpToPx(2), 0, activity.dpToPx(2));
        row.setLayoutParams(rowP);

        return row;
    }

    private static void styleHeaderText(TextView view) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        view.setLetterSpacing(0.15f);
    }

    @SuppressLint("ClickableViewAccessibility")
    private ImageButton makeHeaderIcon(int drawableRes, String contentDesc) {
        ImageButton btn = new ImageButton(activity);
        btn.setImageResource(drawableRes);
        btn.setColorFilter(Palette.PALE_BLUE);
        btn.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(activity.dpToPx(UiStyle.MENU_ITEM_CORNER_RADIUS_DP));
        bg.setColor(Palette.ITEM_BG);
        bg.setStroke(1, Palette.BORDER_DIM);
        // The box is drawn shorter than the button, so the touch area is taller
        // than what shows. The ripple stays inside the box.
        int width = activity.dpToPx(UiStyle.HEADER_ICON_BOX_WIDTH_DP);
        int height = activity.dpToPx(UiStyle.HEADER_ICON_BUTTON_HEIGHT_DP);
        int insetY = (height - activity.dpToPx(UiStyle.HEADER_ICON_BOX_HEIGHT_DP)) / 2;
        btn.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.RIPPLE_GLOW),
            new InsetDrawable(bg, 0, insetY, 0, insetY), null));
        btn.setStateListAnimator(null);
        btn.setElevation(0);
        btn.setContentDescription(contentDesc);
        btn.setMinimumWidth(width);
        btn.setMinimumHeight(height);
        int glyphPadX = activity.dpToPx(
            (UiStyle.HEADER_ICON_BOX_WIDTH_DP - UiStyle.HEADER_ICON_GLYPH_DP) / 2f);
        int glyphPadY = insetY + activity.dpToPx(
            (UiStyle.HEADER_ICON_BOX_HEIGHT_DP - UiStyle.HEADER_ICON_GLYPH_DP) / 2f);
        btn.setPadding(glyphPadX, glyphPadY, glyphPadX, glyphPadY);
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

    static void addItemIcon(BrogueActivity activity, LinearLayout row, JSONObject item,
                            boolean enabled) {
        addItemIcon(activity, row, item, enabled, 28, 32);
    }

    static void addItemIcon(BrogueActivity activity, LinearLayout row, JSONObject item,
                            boolean enabled, int widthDp, int heightDp) {
        int iconWidth = activity.dpToPx(widthDp);
        int iconHeight = activity.dpToPx(heightDp);
        String glyphKey = GameSettings.useTileCreatureAndItemGlyphs(activity)
            ? "tileGlyph" : "textGlyph";
        Bitmap mask = TileAtlasMask.get(activity,
            item.optInt(glyphKey, -1), iconWidth, iconHeight, false);
        if (mask == null) return;

        ImageView icon = new ImageView(activity);
        BitmapDrawable drawable = new BitmapDrawable(activity.getResources(), mask);
        drawable.setFilterBitmap(false);
        icon.setImageDrawable(drawable);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setColorFilter(Color.rgb(
            item.optInt("iconRed", 255),
            item.optInt("iconGreen", 242),
            item.optInt("iconBlue", 0)));
        icon.setAlpha(enabled ? 1f : 0.4f);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
            iconWidth, iconHeight);
        iconParams.setMargins(0, 0, activity.dpToPx(2), 0);
        row.addView(icon, iconParams);
    }

    static void addMagicIndicator(BrogueActivity activity, LinearLayout row, int magicPolarity) {
        if (magicPolarity == 0) return;
        TextView v = new TextView(activity);
        v.setText(magicPolarity > 0 ? Palette.GOOD_MAGIC_GLYPH : Palette.BAD_MAGIC_GLYPH);
        v.setTextColor(magicPolarity > 0 ? Palette.GOOD_MAGIC : Palette.BAD_MAGIC);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        v.setTypeface(Typeface.MONOSPACE);
        v.setPadding(activity.dpToPx(4), 0, 0, 0);
        row.addView(v);
    }

    // Item category bits, matching the itemCategory enum in Rogue.h.
    private static final int CATEGORY_FOOD   = 1 << 0;
    private static final int CATEGORY_POTION = 1 << 3;
    private static final int CATEGORY_SCROLL = 1 << 4;

    /** Human-readable label for an inventory verb key (a/e/r/c/d/t). */
    private static String actionVerbLabel(char key, int category) {
        switch (key) {
            case 'a':
                // The engine's one apply key, named for what it does to this item.
                if ((category & CATEGORY_FOOD) != 0)   return "Eat";
                if ((category & CATEGORY_POTION) != 0) return "Drink";
                if ((category & CATEGORY_SCROLL) != 0) return "Read";
                return "Use";
            case 'e': return "Equip";
            case 'r': return "Unequip";
            case 'c': return "Call";
            case 'd': return "Drop";
            case 't': return "Throw";
            default:  return String.valueOf(key);
        }
    }
}
