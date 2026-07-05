package com.pineyellow.broguepe;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Transparent 3x3 Android DPAD shown at the bottom-left during gameplay
 *  when enabled from Settings. Each cell consumes its own touches and leaves
 *  the rest of the screen's touch handling alone. */
final class DPadOverlay {

    static final String PREF_ENABLED = "enable_dpad";
    static final String PREF_OFFSET_X = "dpad_offset_x";
    static final String PREF_OFFSET_Y = "dpad_offset_y";
    static final String PREF_SIZE = "dpad_size";
    static final String PREF_BUTTON_WIDTH = "dpad_button_width";

    static final float DEFAULT_SIZE = 1f;
    static final float DEFAULT_BUTTON_WIDTH = 1f;
    static final float MIN_SIZE = 0.5f;
    static final float MAX_SIZE = 2.0f;
    static final float MIN_BUTTON_WIDTH = 0.5f;
    static final float MAX_BUTTON_WIDTH = 2.0f;
    static final int SIZE_DP = 185;
    static final int MARGIN_DP = 12;
    static final float DEAD_ZONE_BASE_DP = 12.5f;
    static final float DEAD_ZONE_MIN_DP = 7.5f;
    static final float DEAD_ZONE_MAX_DP = 20f;
    private static final long HOLD_REPEAT_DELAY_MS = 300L;
    private static final long HOLD_REPEAT_INTERVAL_MS = 70L;

    // Temporary testing aid. Set false after the guard-zone size is confirmed.
    private static final boolean SHOW_DEAD_ZONE_DEBUG_TINT = true;
    private static final int DEAD_ZONE_DEBUG_COLOR = Color.argb(28, 255, 55, 55);

    private static final int GRID_LINE_COLOR = Color.argb(105, 255, 255, 255);
    private static final int GLYPH_COLOR = Color.argb(235, 255, 255, 255);
    private static final int ARROW_TEXT_SP = 18;
    private static final int CENTER_TEXT_SP = 16;
    private static final String CENTER_GLYPH = "\u25CB";

    private final BrogueActivity activity;
    private final Handler repeatHandler = new Handler(Looper.getMainLooper());
    private final LinearLayout root;
    private Runnable repeatRunnable;
    private TextView activeView;
    private char activeCommand;
    private boolean repeatArmed;
    private boolean automationSessionActive;
    private boolean continuousRepeatActive;

    DPadOverlay(BrogueActivity activity) {
        this.activity = activity;
        this.root = build();
    }

    View getView() {
        return root;
    }

    private LinearLayout build() {
        LinearLayout grid = new LinearLayout(activity);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        // Child cells handle DPAD presses. The clickable padded area around
        // them consumes near-misses so they cannot become dungeon-map taps.
        grid.setClickable(true);

        addRow(grid, 0,
            new Cell("\u2196", 'y'),
            new Cell("\u2191", 'k'),
            new Cell("\u2197", 'u'));
        addRow(grid, 1,
            new Cell("\u2190", 'h'),
            new Cell(CENTER_GLYPH, '.'),
            new Cell("\u2192", 'l'));
        addRow(grid, 2,
            new Cell("\u2199", 'b'),
            new Cell("\u2193", 'j'),
            new Cell("\u2198", 'n'));

        return grid;
    }

    private void addRow(LinearLayout parent, int rowIndex, Cell left, Cell center, Cell right) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);

        parent.addView(row, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        addCell(row, rowIndex, 0, left);
        addCell(row, rowIndex, 1, center);
        addCell(row, rowIndex, 2, right);
    }

    // handleTouch calls performClick on release; lint cannot follow the helper.
    @SuppressLint("ClickableViewAccessibility")
    private void addCell(LinearLayout row, int rowIndex, int colIndex, Cell cell) {
        TextView view = new TextView(activity);
        view.setText(cell.glyph);
        view.setTextColor(GLYPH_COLOR);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP,
            CENTER_GLYPH.equals(cell.glyph) ? CENTER_TEXT_SP : ARROW_TEXT_SP);
        view.setTypeface(Typeface.MONOSPACE);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        view.setBackground(new CellBorderDrawable(rowIndex == 0, colIndex == 0));
        view.setOnClickListener(v -> { });
        view.setOnTouchListener((v, event) -> handleTouch((TextView) v, event, cell.command));

        row.addView(view, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
    }

    private boolean handleTouch(TextView view, MotionEvent event, char command) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startHold(view, command);
                return true;
            case MotionEvent.ACTION_UP:
                view.performClick();
                // fall through
            case MotionEvent.ACTION_CANCEL:
                stopHold(view);
                return true;
            default:
                return true;
        }
    }

    private void startHold(TextView view, char command) {
        cancelRepeat(false);
        activeView = view;
        activeCommand = command;
        repeatArmed = true;
        automationSessionActive = isAutomationCommand(command);
        continuousRepeatActive = false;

        if (automationSessionActive) {
            activity.nativeBeginDpadAutoMove();
        }

        KeyInput.sendChar(activity, command);
        pressVisual(view);

        repeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (!repeatArmed || activeView == null) return;
                if (shouldStopForAutomation(activeCommand)) {
                    cancelRepeat(true);
                    return;
                }
                if (!continuousRepeatActive) {
                    activity.nativeSetDpadRepeatActive(true);
                    continuousRepeatActive = true;
                }
                KeyInput.sendChar(activity, activeCommand);
                repeatHandler.postDelayed(this, HOLD_REPEAT_INTERVAL_MS);
            }
        };
        repeatHandler.postDelayed(repeatRunnable, HOLD_REPEAT_DELAY_MS);
    }

    private void stopHold(TextView view) {
        if (view == activeView) {
            cancelRepeat(true);
        } else {
            releaseVisual(view);
        }
    }

    private void cancelRepeat(boolean releaseActiveVisual) {
        repeatArmed = false;
        if (repeatRunnable != null) {
            repeatHandler.removeCallbacks(repeatRunnable);
            repeatRunnable = null;
        }
        if (releaseActiveVisual && activeView != null) {
            releaseVisual(activeView);
        }
        if (continuousRepeatActive) {
            activity.nativeSetDpadRepeatActive(false);
            continuousRepeatActive = false;
        }
        if (automationSessionActive) {
            activity.nativeEndDpadAutoMove();
            automationSessionActive = false;
        }
        activeView = null;
    }

    void cancelInput() {
        cancelRepeat(true);
    }

    void setDeadZoneSize(int insetPx) {
        int inset = Math.max(0, insetPx);
        root.setPadding(inset, inset, inset, inset);
    }

    static float deadZoneDp(float sizeScale) {
        return Math.max(DEAD_ZONE_MIN_DP,
            Math.min(DEAD_ZONE_MAX_DP, DEAD_ZONE_BASE_DP * sizeScale));
    }

    private boolean shouldStopForAutomation(char command) {
        return isAutomationCommand(command) && activity.nativeShouldStopDpadAutoMove();
    }

    private static boolean isAutomationCommand(char command) {
        return true;
    }

    private void pressVisual(TextView view) {
        view.animate().alpha(0.65f).scaleX(0.94f).scaleY(0.94f)
            .setDuration(50).start();
    }

    private void releaseVisual(TextView view) {
        view.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(90).start();
    }

    private static final class Cell {
        final String glyph;
        final char command;

        Cell(String glyph, char command) {
            this.glyph = glyph;
            this.command = command;
        }
    }

    /** Draws only the grid outline while keeping the fill transparent. */
    private static final class CellBorderDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean drawTop;
        private final boolean drawLeft;

        CellBorderDrawable(boolean drawTop, boolean drawLeft) {
            this.drawTop = drawTop;
            this.drawLeft = drawLeft;
            paint.setColor(GRID_LINE_COLOR);
            paint.setStrokeWidth(1f);
            paint.setStyle(Paint.Style.STROKE);
        }

        @Override
        public void draw(Canvas canvas) {
            float left = getBounds().left;
            float top = getBounds().top;
            float right = getBounds().right;
            float bottom = getBounds().bottom;

            if (drawTop) canvas.drawLine(left, top, right, top, paint);
            if (drawLeft) canvas.drawLine(left, top, left, bottom, paint);
            canvas.drawLine(left, bottom, right, bottom, paint);
            canvas.drawLine(right, top, right, bottom, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Debug-only tint for the touch-consuming padding around the visible grid. */
    private static final class DeadZoneDrawable extends Drawable {
        private final Paint paint = new Paint();
        private int inset;

        DeadZoneDrawable() {
            paint.setColor(SHOW_DEAD_ZONE_DEBUG_TINT
                ? DEAD_ZONE_DEBUG_COLOR : Color.TRANSPARENT);
            paint.setStyle(Paint.Style.FILL);
        }

        void setInset(int inset) {
            this.inset = Math.max(0, inset);
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            if (inset <= 0 || paint.getAlpha() == 0) return;

            float left = getBounds().left;
            float top = getBounds().top;
            float right = getBounds().right;
            float bottom = getBounds().bottom;
            float guard = Math.min(inset,
                Math.min((right - left) / 2f, (bottom - top) / 2f));

            canvas.drawRect(left, top, right, top + guard, paint);
            canvas.drawRect(left, bottom - guard, right, bottom, paint);
            canvas.drawRect(left, top + guard, left + guard, bottom - guard, paint);
            canvas.drawRect(right - guard, top + guard, right, bottom - guard, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
