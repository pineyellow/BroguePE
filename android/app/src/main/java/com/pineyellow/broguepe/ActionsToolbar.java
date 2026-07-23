package com.pineyellow.broguepe;

import android.annotation.SuppressLint;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;

/** Owns the bottom action toolbar: pinned icon buttons + hamburger menu with
 *  its Actions / Settings / Exit submenu, and the full Actions panel (the
 *  reorderable list of every action with a pin toggle). */
final class ActionsToolbar {

    private static final String PREFS        = "brogue_toolbar";
    private static final String PREF_PINNED  = "pinned_actions";
    private static final String PREF_ORDER   = "action_order";
    static final String PREF_BUTTON_SIZE     = "action_button_size";
    static final float DEFAULT_BUTTON_SIZE   = 1f;
    static final float MIN_BUTTON_SIZE       = 0.5f;
    static final float MAX_BUTTON_SIZE       = 2f;

    private static final float BASE_BUTTON_SIZE_DP = 44f;
    private static final float BASE_BUTTON_PADDING_DP = 10f;

    // Registered actions: {key, human label}. The pinned subset of this set
    // appears in the toolbar; the full set appears in the Actions panel.
    private static final String[][] ACTION_KEYS = {
        {"inventory",  "Inventory"},
        {"explore",    "Explore"},
        {"search",     "Search"},
        {"throw",      "Target"},
        {"click",      "Confirm Target"},
        {"show_log",   "Show Log"},
        {"wait",       "Rest One Turn"},
        {"rest",       "Rest Until Better"},
    };
    private static final java.util.Set<String> DEFAULT_PINNED =
        new java.util.HashSet<>(java.util.Arrays.asList("inventory", "click", "search", "throw"));

    private final BrogueActivity activity;
    private final FrameLayout gameOverlay;       // host for submenu-dismiss backdrop
    private final FrameLayout inventoryOverlay;  // host for the Actions panel
    private final Runnable onSettingsClicked;

    private LinearLayout toolbarContainer;
    private LinearLayout submenu;
    private View menuBtn;
    private View submenuBackdrop;
    private java.util.List<String> cachedActionOrder;
    private boolean actionReordering;
    private View draggedActionRow;
    private LinearLayout draggedActionRows;
    private ScrollView draggedActionBounds;
    private int draggedActionOriginalIndex;
    private int draggedActionTargetIndex;
    private float draggedActionTouchOffsetY;

    ActionsToolbar(BrogueActivity activity,
                   FrameLayout gameOverlay,
                   FrameLayout inventoryOverlay,
                   Runnable onSettingsClicked) {
        this.activity = activity;
        this.gameOverlay = gameOverlay;
        this.inventoryOverlay = inventoryOverlay;
        this.onSettingsClicked = onSettingsClicked;
    }

    // ---- Public surface -----------------------------------------------------

    /** Builds the overlay control layer: menu anchored top-right, action bar
     *  anchored bottom-right. */
    View build() {
        submenu = new LinearLayout(activity);
        submenu.setOrientation(LinearLayout.VERTICAL);
        submenu.setBackground(makeSubmenuBackground());
        submenu.setPadding(dp(3), dp(4), dp(3), dp(3));
        submenu.setVisibility(View.GONE);
        submenu.setElevation(dp(8));

        menuBtn = makeIconBarButton(R.drawable.ic_menu);

        submenu.addView(makeSubmenuItem("Actions", v -> {
            collapseSubmenu();
            showActionsPanel();
        }), submenuItemParams());

        submenu.addView(makeSubmenuItem("Settings", v -> {
            collapseSubmenu();
            onSettingsClicked.run();
        }), submenuItemParams());

        View divider = new View(activity);
        divider.setBackgroundColor(Palette.BORDER_DIM);
        LinearLayout.LayoutParams divP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divP.setMargins(dp(4), dp(4), dp(4), dp(4));
        submenu.addView(divider, divP);

        submenu.addView(makeSubmenuItem("Exit", v -> {
            collapseSubmenu();
            KeyInput.sendChar(activity, 'S');
        }), submenuItemParams());

        menuBtn.setOnClickListener(v -> {
            if (submenu.getVisibility() == View.VISIBLE) {
                collapseSubmenu();
            } else {
                expandSubmenu();
            }
        });

        toolbarContainer = new LinearLayout(activity);
        toolbarContainer.setOrientation(LinearLayout.HORIZONTAL);
        toolbarContainer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        toolbarContainer.setBackground(makeBarBackground());
        int barPad = dp(4);
        toolbarContainer.setPadding(barPad, barPad, barPad, barPad);

        LinearLayout topGroup = new LinearLayout(activity);
        topGroup.setOrientation(LinearLayout.VERTICAL);
        topGroup.setGravity(Gravity.END);
        int buttonSize = buttonSizePx();
        topGroup.addView(menuBtn, new LinearLayout.LayoutParams(
            buttonSize, buttonSize));
        LinearLayout.LayoutParams submenuParams = new LinearLayout.LayoutParams(
            dp(170), LinearLayout.LayoutParams.WRAP_CONTENT);
        submenuParams.topMargin = dp(6);
        topGroup.addView(submenu, submenuParams);

        FrameLayout controlsLayer = new FrameLayout(activity);

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.END);
        topParams.setMargins(0, dp(8), dp(4), 0);
        controlsLayer.addView(topGroup, topParams);

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM | Gravity.END);
        bottomParams.setMargins(0, 0, dp(4), 0);
        controlsLayer.addView(toolbarContainer, bottomParams);

        rebuildToolbar();
        return controlsLayer;
    }

    /** Applies the saved size to both the pinned actions and hamburger button. */
    void applyButtonSizeSetting() {
        float scale = buttonSizeScale();

        if (menuBtn != null) {
            ViewGroup.LayoutParams params = menuBtn.getLayoutParams();
            if (params != null) {
                params.width = buttonSizePx(scale);
                params.height = buttonSizePx(scale);
                menuBtn.setLayoutParams(params);
            }
            setBarButtonPadding(menuBtn, scale);
        }

        rebuildToolbar();
    }

    /** Dismisses the submenu if open. No-op otherwise. */
    void collapseSubmenu() {
        if (submenu == null || submenu.getVisibility() != View.VISIBLE) return;
        if (submenuBackdrop != null && submenuBackdrop.getParent() != null) {
            ((ViewGroup) submenuBackdrop.getParent()).removeView(submenuBackdrop);
        }
        submenu.animate()
            .alpha(0f).translationY(dp(-6))
            .setDuration(100)
            .withEndAction(() -> submenu.setVisibility(View.GONE))
            .start();
        animateToggle(menuBtn, false);
    }

    // ---- Pinned state / action order persistence ---------------------------

    private SharedPreferences toolbarPrefs() {
        return activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }

    private java.util.Set<String> getPinned() {
        SharedPreferences prefs = toolbarPrefs();
        if (!prefs.contains(PREF_PINNED)) return new java.util.HashSet<>(DEFAULT_PINNED);
        return new java.util.HashSet<>(prefs.getStringSet(PREF_PINNED, DEFAULT_PINNED));
    }

    private void setPinned(java.util.Set<String> pinned) {
        toolbarPrefs().edit().putStringSet(PREF_PINNED, pinned).apply();
    }

    private boolean isPinned(String key) {
        return getPinned().contains(key);
    }

    private void togglePin(String key) {
        java.util.Set<String> pinned = getPinned();
        if (!pinned.remove(key)) pinned.add(key);
        setPinned(pinned);
        rebuildToolbar();
    }

    /** Master display order for the Actions panel and pinned toolbar ordering.
     *  Unknown / missing keys are appended in registry order so newly-added
     *  actions always appear. */
    private java.util.List<String> getActionOrder() {
        if (cachedActionOrder != null) return cachedActionOrder;

        String json = toolbarPrefs().getString(PREF_ORDER, null);
        java.util.List<String> list = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    String k = arr.optString(i, null);
                    if (k != null && !seen.contains(k) && isKnownAction(k)) {
                        list.add(k);
                        seen.add(k);
                    }
                }
            } catch (Exception ignored) { }
        }
        for (String[] entry : ACTION_KEYS) {
            if (!seen.contains(entry[0])) list.add(entry[0]);
        }
        cachedActionOrder = list;
        return cachedActionOrder;
    }

    private void setActionOrder(java.util.List<String> order) {
        java.util.List<String> clean = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String k : order) {
            if (k != null && !seen.contains(k) && isKnownAction(k)) {
                clean.add(k);
                seen.add(k);
            }
        }
        for (String[] entry : ACTION_KEYS) {
            if (!seen.contains(entry[0])) clean.add(entry[0]);
        }
        cachedActionOrder = clean;

        JSONArray arr = new JSONArray();
        for (String k : clean) arr.put(k);
        toolbarPrefs().edit().putString(PREF_ORDER, arr.toString()).apply();
    }

    private boolean isKnownAction(String key) {
        for (String[] entry : ACTION_KEYS) {
            if (entry[0].equals(key)) return true;
        }
        return false;
    }

    private String actionLabel(String key) {
        for (String[] entry : ACTION_KEYS) {
            if (entry[0].equals(key)) return entry[1];
        }
        return key;
    }

    private int actionIconRes(String key) {
        switch (key) {
            case "inventory": return R.drawable.ic_money_bag;
            case "throw":     return R.drawable.ic_target;
            case "click":     return R.drawable.ic_left_click;
            case "show_log":  return R.drawable.ic_show_log;
            case "search":    return R.drawable.ic_search;
            case "explore":   return R.drawable.ic_explore;
            case "wait":      return R.drawable.ic_hourglass;
            case "rest":      return R.drawable.ic_heart_check;
            case "autopilot": return R.drawable.ic_autoplay;
            default:          return R.drawable.ic_menu;
        }
    }

    // ---- Action execution --------------------------------------------------

    private void executeAction(String key) {
        if (actionReordering) return;

        switch (key) {
            case "inventory": KeyInput.sendKey(activity, KeyEvent.KEYCODE_I); break;
            case "throw":
                if (activity.nativeIsTargetingActive()) {
                    KeyInput.sendKey(activity, KeyEvent.KEYCODE_ESCAPE);
                } else {
                    activity.nativeArmQuickTargetSelection();
                    KeyInput.sendChar(activity, 't');
                }
                break;
            case "click":
                if (activity.nativeHasSelectedTarget()) {
                    KeyInput.sendKey(activity, KeyEvent.KEYCODE_ENTER);
                }
                break;
            case "show_log":  KeyInput.sendChar(activity, 'M'); break;
            case "search":    KeyInput.sendChar(activity, 's'); break;
            case "explore":   KeyInput.sendChar(activity, 'x'); break;
            case "wait":      KeyInput.sendChar(activity, 'z'); break;
            case "rest":      KeyInput.sendChar(activity, 'Z'); break;
            case "autopilot": KeyInput.sendChar(activity, 'A'); break;
        }
    }

    // ---- Toolbar rebuild ---------------------------------------------------

    private void rebuildToolbar() {
        if (toolbarContainer == null) return;
        toolbarContainer.removeAllViews();

        java.util.Set<String> pinned = getPinned();
        int btnSize = buttonSizePx();
        int btnMargin = dp(3);

        java.util.List<String> order = getActionOrder();
        for (int i = order.size() - 1; i >= 0; i--) {
            String key = order.get(i);
            if (!pinned.contains(key)) continue;

            View btn = makeIconBarButton(actionIconRes(key));
            btn.setTag(R.id.action_key_tag, key);
            btn.setContentDescription(actionLabel(key));

            btn.setOnClickListener(v -> {
                collapseSubmenu();
                executeAction(key);
                pulseButton(v);
            });

            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(btnSize, btnSize);
            p.setMargins(btnMargin, 0, btnMargin, 0);
            toolbarContainer.addView(btn, p);
        }

    }

    // ---- Actions panel -----------------------------------------------------

    private void showActionsPanel() {
        inventoryOverlay.removeAllViews();

        View backdrop = new View(activity);
        backdrop.setBackgroundColor(Color.argb(140, 0, 0, 0));
        backdrop.setOnClickListener(v -> {
            if (!actionReordering) hideActionsPanel();
        });
        inventoryOverlay.addView(backdrop, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        backdrop.setAlpha(0f);
        backdrop.animate().alpha(1f).setDuration(280).start();

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(10);
        panel.setPadding(pad, pad, pad, pad);

        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setShape(GradientDrawable.RECTANGLE);
        panelBg.setCornerRadii(new float[]{
            dp(4), dp(4), 0, 0, 0, 0, dp(4), dp(4)});
        panelBg.setColor(Palette.INVENTORY_BG);
        panelBg.setStroke(1, Palette.BORDER_DIM);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setBackground(panelBg);
        scrollView.addView(panel);

        TextView header = new TextView(activity);
        header.setText(R.string.actions_title);
        header.setTextColor(Palette.DIM_WHITE_BLUE);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.setLetterSpacing(0.15f);
        header.setPadding(dp(4), dp(2), 0, dp(4));
        panel.addView(header);

        View headerSep = new View(activity);
        GradientDrawable sepGrad = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{ Palette.DIM_BLUE_GRAY, Palette.DIM_WHITE_BLUE, Palette.DIM_BLUE_GRAY });
        headerSep.setBackground(sepGrad);
        LinearLayout.LayoutParams hSepP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        hSepP.setMargins(dp(4), 0, dp(4), dp(8));
        panel.addView(headerSep, hSepP);

        LinearLayout rowsContainer = new LinearLayout(activity);
        rowsContainer.setOrientation(LinearLayout.VERTICAL);
        panel.addView(rowsContainer, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        for (String key : getActionOrder()) {
            addActionRow(rowsContainer, scrollView, key, actionLabel(key));
        }

        int panelWidth = Math.min(dp(280),
            (int)(activity.getResources().getDisplayMetrics().widthPixels * 0.6f));

        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
            panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM | Gravity.END);
        scrollParams.setMargins(0, dp(8), dp(BrogueActivity.EDGE_SAFE_DP),
            dp(BrogueActivity.EDGE_SAFE_DP));

        inventoryOverlay.addView(scrollView, scrollParams);
        inventoryOverlay.setVisibility(View.VISIBLE);

        scrollView.setTranslationX(panelWidth);
        scrollView.animate()
            .translationX(0)
            .setDuration(280)
            .setInterpolator(new DecelerateInterpolator(1.5f))
            .start();
    }

    private void hideActionsPanel() { hideActionsPanel(null); }

    private void hideActionsPanel(Runnable onDone) {
        if (inventoryOverlay.getChildCount() < 2) {
            inventoryOverlay.setVisibility(View.GONE);
            inventoryOverlay.removeAllViews();
            if (onDone != null) onDone.run();
            return;
        }
        View backdrop = inventoryOverlay.getChildAt(0);
        View panel = inventoryOverlay.getChildAt(1);

        panel.animate()
            .translationX(panel.getWidth())
            .setDuration(200)
            .setInterpolator(new DecelerateInterpolator())
            .start();

        backdrop.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction(() -> {
                inventoryOverlay.setVisibility(View.GONE);
                inventoryOverlay.removeAllViews();
                if (onDone != null) onDone.run();
            })
            .start();
    }

    // Normal taps return false to Android's click handling; reorder gestures
    // deliberately consume touch events and must not also trigger a click.
    @SuppressLint("ClickableViewAccessibility")
    private void addActionRow(LinearLayout panel, ScrollView scrollView,
                              String key, String label) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(10), dp(12), dp(10));
        row.setMinimumHeight(dp(44));
        row.setTag(R.id.action_key_tag, key);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(3));
        bg.setColor(Palette.ITEM_BG);
        row.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.RIPPLE_GLOW), bg, null));

        final float[] rowTouchY = new float[1];
        row.setOnTouchListener((v, e) -> {
            rowTouchY[0] = e.getRawY();
            if (actionReordering && draggedActionRow == row) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_MOVE:
                        updateActionReorder(e.getRawY());
                        return true;
                    case MotionEvent.ACTION_UP:
                        finishActionReorder(true);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        finishActionReorder(false);
                        return true;
                    default:
                        return true;
                }
            }

            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(60).start();
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                    || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
            }
            return false;
        });
        row.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            beginActionReorder(row, panel, scrollView, rowTouchY[0]);
            return true;
        });

        ImageView handle = new ImageView(activity);
        handle.setImageResource(R.drawable.ic_drag_handle);
        handle.setColorFilter(Palette.BORDER_ACTIVE);
        handle.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int handleSize = dp(32);
        LinearLayout.LayoutParams handleP = new LinearLayout.LayoutParams(handleSize, handleSize);
        handleP.setMargins(0, 0, dp(4), 0);
        row.addView(handle, handleP);
        handle.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    beginActionReorder(row, panel, scrollView, e.getRawY());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    updateActionReorder(e.getRawY());
                    return true;
                case MotionEvent.ACTION_UP:
                    finishActionReorder(true);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    finishActionReorder(false);
                    return true;
                default:
                    return true;
            }
        });

        int iconSize = dp(24);
        LinearLayout.LayoutParams iconP = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconP.setMargins(0, 0, dp(10), 0);
        row.addView(makeActionListIcon(key), iconP);

        TextView labelView = new TextView(activity);
        labelView.setText(label);
        labelView.setTextColor(Palette.GHOST_WHITE);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        labelView.setTypeface(Typeface.MONOSPACE);
        row.addView(labelView, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        boolean pinned = isPinned(key);
        TextView check = new TextView(activity);
        check.setText(pinned ? "\u2713" : "");
        check.setTextColor(Palette.DIM_WHITE_BLUE);
        check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        check.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        check.setGravity(Gravity.CENTER);
        int checkSize = dp(28);

        GradientDrawable checkBg = new GradientDrawable();
        checkBg.setShape(GradientDrawable.RECTANGLE);
        checkBg.setCornerRadius(dp(3));
        checkBg.setColor(pinned ? Palette.ACTION_BG : Color.TRANSPARENT);
        checkBg.setStroke(1, Palette.BORDER_ACTIVE);
        check.setBackground(checkBg);

        row.addView(check, new LinearLayout.LayoutParams(checkSize, checkSize));

        check.setOnClickListener(v -> {
            if (actionReordering) return;
            togglePin(key);
            boolean nowPinned = isPinned(key);
            check.setText(nowPinned ? "\u2713" : "");
            GradientDrawable cbg = (GradientDrawable) check.getBackground();
            cbg.setColor(nowPinned ? Palette.ACTION_BG : Color.TRANSPARENT);
            cbg.setStroke(1, Palette.BORDER_ACTIVE);
        });

        row.setOnClickListener(v -> {
            if (!actionReordering) hideActionsPanel(() -> executeAction(key));
        });

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(2), 0, dp(2));
        panel.addView(row, p);
    }

    // ---- Drag-reorder ------------------------------------------------------

    private void beginActionReorder(View row, LinearLayout rows,
                                    ScrollView bounds, float rawY) {
        if (actionReordering) return;

        int[] rowLocation = new int[2];
        row.getLocationOnScreen(rowLocation);
        actionReordering = true;
        draggedActionRow = row;
        draggedActionRows = rows;
        draggedActionBounds = bounds;
        draggedActionOriginalIndex = rows.indexOfChild(row);
        draggedActionTargetIndex = draggedActionOriginalIndex;
        draggedActionTouchOffsetY = rawY - rowLocation[1];

        row.animate().cancel();
        row.setScaleX(1f);
        row.setScaleY(1f);
        row.setTranslationX(0f);
        row.setTranslationZ(dp(8));
        rows.requestDisallowInterceptTouchEvent(true);
    }

    private void updateActionReorder(float rawY) {
        if (!actionReordering || draggedActionRow == null
                || draggedActionRows == null || draggedActionBounds == null) return;

        int[] rowsLocation = new int[2];
        int[] boundsLocation = new int[2];
        draggedActionRows.getLocationOnScreen(rowsLocation);
        draggedActionBounds.getLocationOnScreen(boundsLocation);

        float visibleTop = Math.max(0, boundsLocation[1] - rowsLocation[1]);
        float visibleBottom = Math.min(draggedActionRows.getHeight(),
            boundsLocation[1] + draggedActionBounds.getHeight() - rowsLocation[1]);
        float maxTop = Math.max(visibleTop,
            visibleBottom - draggedActionRow.getHeight());
        float desiredTop = rawY - rowsLocation[1] - draggedActionTouchOffsetY;
        desiredTop = Math.max(visibleTop, Math.min(maxTop, desiredTop));

        draggedActionRow.setTranslationX(0f);
        draggedActionRow.setTranslationY(desiredTop - draggedActionRow.getTop());

        float draggedCenter = desiredTop + draggedActionRow.getHeight() / 2f;
        int target = 0;
        for (int i = 0; i < draggedActionRows.getChildCount(); i++) {
            View child = draggedActionRows.getChildAt(i);
            if (child == draggedActionRow) continue;
            if (draggedCenter > child.getTop() + child.getHeight() / 2f) target++;
        }
        target = Math.max(0, Math.min(draggedActionRows.getChildCount() - 1, target));
        if (target != draggedActionTargetIndex) {
            draggedActionTargetIndex = target;
            previewActionReorder();
        }
    }

    private void previewActionReorder() {
        int slot = draggedActionRow.getHeight();
        ViewGroup.LayoutParams params = draggedActionRow.getLayoutParams();
        if (params instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams linearParams = (LinearLayout.LayoutParams) params;
            slot += linearParams.topMargin + linearParams.bottomMargin;
        }

        for (int i = 0; i < draggedActionRows.getChildCount(); i++) {
            View child = draggedActionRows.getChildAt(i);
            if (child == draggedActionRow) continue;

            float shift = 0f;
            if (draggedActionTargetIndex > draggedActionOriginalIndex
                    && i > draggedActionOriginalIndex && i <= draggedActionTargetIndex) {
                shift = -slot;
            } else if (draggedActionTargetIndex < draggedActionOriginalIndex
                    && i >= draggedActionTargetIndex && i < draggedActionOriginalIndex) {
                shift = slot;
            }
            child.animate().translationY(shift).setDuration(90).start();
        }
    }

    private void finishActionReorder(boolean commit) {
        if (!actionReordering || draggedActionRow == null || draggedActionRows == null) return;

        View row = draggedActionRow;
        LinearLayout rows = draggedActionRows;
        int originalIndex = draggedActionOriginalIndex;
        int targetIndex = commit ? draggedActionTargetIndex : originalIndex;

        for (int i = 0; i < rows.getChildCount(); i++) {
            View child = rows.getChildAt(i);
            child.animate().cancel();
            child.setTranslationY(0f);
        }
        row.setTranslationX(0f);
        row.setTranslationZ(0f);

        if (targetIndex != originalIndex) {
            rows.removeView(row);
            rows.addView(row, targetIndex);
        }
        rows.requestDisallowInterceptTouchEvent(false);

        actionReordering = false;
        draggedActionRow = null;
        draggedActionRows = null;
        draggedActionBounds = null;

        if (commit) {
            persistRowOrder(rows);
            rebuildToolbar();
        }
    }

    private void persistRowOrder(LinearLayout rows) {
        java.util.List<String> order = new java.util.ArrayList<>();
        for (int i = 0; i < rows.getChildCount(); i++) {
            Object tag = rows.getChildAt(i).getTag(R.id.action_key_tag);
            if (tag instanceof String) order.add((String) tag);
        }
        setActionOrder(order);
    }

    // ---- Submenu animation -------------------------------------------------

    private void expandSubmenu() {
        if (submenuBackdrop == null) submenuBackdrop = new View(activity);
        submenuBackdrop.setOnClickListener(v -> collapseSubmenu());
        if (submenuBackdrop.getParent() != null) {
            ((ViewGroup) submenuBackdrop.getParent()).removeView(submenuBackdrop);
        }
        gameOverlay.addView(submenuBackdrop, 0, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        submenu.setAlpha(0f);
        submenu.setTranslationY(dp(-8));
        submenu.setVisibility(View.VISIBLE);
        submenu.animate()
            .alpha(1f).translationY(0)
            .setDuration(150)
            .setInterpolator(new DecelerateInterpolator())
            .start();
        animateToggle(menuBtn, true);
    }

    private void animateToggle(View v, boolean active) {
        GradientDrawable bg = extractBackground(v);
        if (bg == null) return;

        int from = active ? Palette.ACTION_BUTTON_BG : Palette.TOGGLE_ACTIVE;
        int to   = active ? Palette.TOGGLE_ACTIVE : Palette.ACTION_BUTTON_BG;
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        anim.setDuration(150);
        anim.addUpdateListener(a -> bg.setColor((int) a.getAnimatedValue()));
        anim.start();

        bg.setStroke(1, active ? Palette.BORDER_ACTIVE : Palette.BORDER_DIM);

        if (v instanceof ImageButton) {
            ((ImageButton) v).setColorFilter(active
                ? Palette.ACTION_BUTTON_TEXT_ACTIVE
                : Palette.ACTION_BUTTON_TEXT);
        } else if (v instanceof TextView) {
            ((TextView) v).setTextColor(active
                ? Palette.ACTION_BUTTON_TEXT_ACTIVE
                : Palette.ACTION_BUTTON_TEXT);
        }
    }

    private void pulseButton(View v) {
        v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(80)
            .withEndAction(() ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(120)
                    .setInterpolator(new OvershootInterpolator(3f)).start()
            ).start();
    }

    private GradientDrawable extractBackground(View v) {
        if (v.getBackground() instanceof RippleDrawable) {
            RippleDrawable rd = (RippleDrawable) v.getBackground();
            if (rd.getNumberOfLayers() > 0
                    && rd.getDrawable(0) instanceof GradientDrawable) {
                return (GradientDrawable) rd.getDrawable(0);
            }
        }
        return null;
    }

    // ---- View factories ----------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private ImageButton makeIconBarButton(int drawableResId) {
        ImageButton btn = new ImageButton(activity);
        btn.setImageResource(drawableResId);
        btn.setColorFilter(Palette.ACTION_BUTTON_TEXT);
        btn.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        setBarButtonPadding(btn, buttonSizeScale());
        btn.setStateListAnimator(null);
        btn.setElevation(0);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(2));
        bg.setColor(Palette.ACTION_BUTTON_BG);
        bg.setStroke(1, Palette.BORDER_DIM);

        btn.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.RIPPLE_GLOW), bg, null));

        btn.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(60).start();
            } else if (e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(120)
                    .setInterpolator(new OvershootInterpolator(2.5f)).start();
            }
            return false;
        });

        return btn;
    }

    private View makeActionListIcon(String key) {
        ImageView icon = new ImageView(activity);
        icon.setImageResource(actionIconRes(key));
        icon.setColorFilter(Palette.PALE_BLUE);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        return icon;
    }

    @SuppressLint("ClickableViewAccessibility")
    private View makeSubmenuItem(String label, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(6), dp(10), dp(6));
        row.setMinimumHeight(dp(40));
        row.setClickable(true);
        row.setFocusable(true);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(2));
        bg.setColor(Color.TRANSPARENT);
        row.setBackground(new RippleDrawable(
            ColorStateList.valueOf(Palette.RIPPLE_GLOW), bg, null));

        row.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().translationX(dp(2)).setDuration(60).start();
            } else if (e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().translationX(0).setDuration(100).start();
            }
            return false;
        });

        TextView labelView = new TextView(activity);
        labelView.setText(label);
        labelView.setTextColor(Palette.GHOST_WHITE);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        labelView.setTypeface(Typeface.MONOSPACE);
        row.addView(labelView, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(listener);
        return row;
    }

    private LinearLayout.LayoutParams submenuItemParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(1), 0, dp(1));
        return p;
    }

    private GradientDrawable makeSubmenuBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(3));
        bg.setColor(Palette.SUBMENU_BG);
        bg.setStroke(1, Palette.BORDER_DIM);
        return bg;
    }

    private GradientDrawable makeBarBackground() {
        GradientDrawable bg = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{ Color.TRANSPARENT, Color.TRANSPARENT, Color.TRANSPARENT });
        bg.setCornerRadius(0);
        return bg;
    }

    private float buttonSizeScale() {
        float saved = GameSettings.getFloat(
            activity, PREF_BUTTON_SIZE, DEFAULT_BUTTON_SIZE);
        float scale = Float.isFinite(saved)
            ? Math.max(MIN_BUTTON_SIZE, Math.min(MAX_BUTTON_SIZE, saved))
            : DEFAULT_BUTTON_SIZE;
        if (saved != scale) {
            GameSettings.setFloat(activity, PREF_BUTTON_SIZE, scale);
        }
        return scale;
    }

    private int buttonSizePx() {
        return buttonSizePx(buttonSizeScale());
    }

    private int buttonSizePx(float scale) {
        return dp(BASE_BUTTON_SIZE_DP * scale);
    }

    private void setBarButtonPadding(View button, float scale) {
        int padding = dp(BASE_BUTTON_PADDING_DP * scale);
        button.setPadding(padding, padding, padding, padding);
    }

    private int dp(int v) { return activity.dpToPx(v); }
    private int dp(float v) { return activity.dpToPx(v); }
}
