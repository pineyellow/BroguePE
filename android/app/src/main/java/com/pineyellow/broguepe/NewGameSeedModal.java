package com.pineyellow.broguepe;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;

/** Main-menu "New Game" entry. Picks a random seed client-side and lets the
 *  player tap the seed, its pill or its label to override it via the soft
 *  keyboard. */
final class NewGameSeedModal extends SeedDetailsModal {

    // Seeds are unsigned 64-bit, as in Brogue CE. Java has no unsigned long, so
    // seeds above Long.MAX_VALUE are held as negative longs with the same bits
    // and must be parsed and shown with the Long *Unsigned* methods.
    private static final int MAX_SEED_DIGITS = 20;
    private static final long MAX_SEED = -1L; // 18446744073709551615 unsigned
    // Client-picked random seeds match the shape of Brogue's clock-based
    // seeds: `(uint64_t) time(NULL) - 1352700000` at Math.c:186 produces
    // ~9-digit values at the current date range. A 9-digit random keeps
    // the header visually consistent with what players see in-engine.
    private static final long RANDOM_SEED_MIN = 100_000_000L;
    private static final long RANDOM_SEED_MAX = 999_999_999L;
    private static final String[] VARIANT_LABELS = {
        "Game Mode: Classic",
        "Game Mode: Rapid Brogue",
        "Game Mode: Bullet Brogue"
    };
    private static final String[] DIFFICULTY_LABELS = {
        "Difficulty: Default",
        "Difficulty: Easy"
    };

    private final Random random = new Random();
    private EditText seedEdit;
    private int selectedVariant = StartMenu.VARIANT_BROGUE;
    private int selectedDifficulty = StartMenu.DIFFICULTY_DEFAULT;
    private TextView modeLabelView;
    private TextView modeHintView;
    private TextView difficultyLabelView;
    private TextView difficultyHintView;

    NewGameSeedModal(BrogueActivity activity) { super(activity); }

    @Override protected String getTitleUpper() { return "NEW GAME"; }

    @Override protected int getContentTopGapDp() { return 2; }

    void show() {
        show(pickRandomSeed(),
            GameSettings.getInt(activity, GameSettings.PREF_NEW_GAME_VARIANT,
                StartMenu.VARIANT_BROGUE),
            GameSettings.getInt(activity, GameSettings.PREF_NEW_GAME_DIFFICULTY,
                StartMenu.DIFFICULTY_DEFAULT));
    }

    void show(long seed, int variant) {
        show(seed, variant, StartMenu.DIFFICULTY_DEFAULT);
    }

    void show(long seed, int variant, int difficulty) {
        selectedVariant = boundedVariant(variant);
        selectedDifficulty = boundedDifficulty(difficulty);
        seedEdit = null;
        modeLabelView = null;
        modeHintView = null;
        difficultyLabelView = null;
        difficultyHintView = null;
        super.show(seed);
    }

    @Override
    protected void onSeedViewBuilt(TextView seedView) {
        LinearLayout panel = (LinearLayout) seedView.getParent();
        int seedIndex = panel.indexOfChild(seedView);
        panel.removeView(seedView);

        seedEdit = makeSeedEditText();
        TextView label = makeSeedLabel();
        View pill = makeSeedPill();

        panel.addView(new SeedRow(activity, label, pill, seedEdit, makePencilIcon()),
            seedIndex, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // The pencil is not clickable, so a tap on it reaches the pill.
        pill.setOnClickListener(v -> focusEditor());
        label.setOnClickListener(v -> focusEditor());
        seedEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitEdit();
                return true;
            }
            return false;
        });

        seedEdit.setText(Long.toUnsignedString(seed));
    }

    @Override
    protected void onBeforeActionButtons(LinearLayout panel) {
        modeHintView = makeHintView();
        updateModeHint();
        addHintView(panel, modeHintView);

        View modeRow = StartMenu.addButton(panel, variantLabel(), true, v -> cycleVariant());
        modeLabelView = labelFromRow(modeRow);

        View difficultyRow = StartMenu.addButton(panel, difficultyLabel(), true,
            v -> cycleDifficulty());
        difficultyLabelView = labelFromRow(difficultyRow);

        difficultyHintView = makeHintView();
        difficultyHintView.setText("-50% damage taken");
        updateDifficultyHint();
        addHintView(panel, difficultyHintView);

        panel.addView(ModalChrome.makeDimSeparator(activity),
            ModalChrome.separatorParams(activity, 8, 8, 2, 12));
    }

    @Override
    protected int getLaunchVariant() {
        return selectedVariant;
    }

    @Override
    protected int getLaunchDifficulty() {
        return selectedDifficulty;
    }

    @Override
    protected void onBeforeLaunch() {
        commitEdit();
    }

    private void cycleVariant() {
        selectedVariant = (selectedVariant + 1) % VARIANT_LABELS.length;
        GameSettings.setInt(activity, GameSettings.PREF_NEW_GAME_VARIANT,
            selectedVariant);
        if (modeLabelView != null) {
            modeLabelView.setText(variantLabel());
        }
        updateModeHint();
    }

    private String variantLabel() {
        return VARIANT_LABELS[selectedVariant];
    }

    private void updateModeHint() {
        if (modeHintView == null) return;

        if (selectedVariant == StartMenu.VARIANT_RAPID) {
            modeHintView.setText("6 fast-paced levels");
            modeHintView.setVisibility(View.VISIBLE);
        } else if (selectedVariant == StartMenu.VARIANT_BULLET) {
            modeHintView.setText("3 chaotic levels");
            modeHintView.setVisibility(View.VISIBLE);
        } else {
            // Keep one line reserved so cycling modes does not resize the modal.
            modeHintView.setText("6 fast-paced levels");
            modeHintView.setVisibility(View.INVISIBLE);
        }
    }

    private void cycleDifficulty() {
        selectedDifficulty = (selectedDifficulty + 1) % DIFFICULTY_LABELS.length;
        GameSettings.setInt(activity, GameSettings.PREF_NEW_GAME_DIFFICULTY,
            selectedDifficulty);
        if (difficultyLabelView != null) {
            difficultyLabelView.setText(difficultyLabel());
        }
        updateDifficultyHint();
    }

    private String difficultyLabel() {
        return DIFFICULTY_LABELS[selectedDifficulty];
    }

    private void updateDifficultyHint() {
        if (difficultyHintView != null) {
            difficultyHintView.setVisibility(selectedDifficulty == StartMenu.DIFFICULTY_EASY
                ? View.VISIBLE
                : View.INVISIBLE);
        }
    }

    private TextView makeHintView() {
        TextView view = new TextView(activity);
        view.setTextColor(Palette.ACTION_BUTTON_TEXT);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        view.setTypeface(Typeface.MONOSPACE);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        return view;
    }

    private void addHintView(LinearLayout panel, TextView hintView) {
        panel.addView(hintView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private int boundedVariant(int variant) {
        return variant >= 0 && variant < VARIANT_LABELS.length
            ? variant
            : StartMenu.VARIANT_BROGUE;
    }

    private int boundedDifficulty(int difficulty) {
        return difficulty >= 0 && difficulty < DIFFICULTY_LABELS.length
            ? difficulty
            : StartMenu.DIFFICULTY_DEFAULT;
    }

    private TextView labelFromRow(View row) {
        if (!(row instanceof LinearLayout)) return null;
        LinearLayout ll = (LinearLayout) row;
        View first = ll.getChildCount() > 0 ? ll.getChildAt(0) : null;
        return first instanceof TextView ? (TextView) first : null;
    }

    private EditText makeSeedEditText() {
        EditText v = new EditText(activity) {
            @Override
            public boolean onKeyPreIme(int keyCode, KeyEvent event) {
                boolean handled = super.onKeyPreIme(keyCode, event);
                if (keyCode == KeyEvent.KEYCODE_BACK
                        && event.getAction() == KeyEvent.ACTION_UP) {
                    // The IME consumes Back before the Activity sees it. Commit
                    // after that dispatch so a dismissed empty editor becomes 1.
                    post(NewGameSeedModal.this::commitEdit);
                }
                return handled;
            }
        };
        v.setTextColor(Palette.GHOST_WHITE);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, activity.dpToPx(3), 0, activity.dpToPx(3));
        v.setBackground(null);
        // focusEditor selects the whole seed; tint that in the buttons' press
        // color instead of the system blue.
        v.setHighlightColor(Palette.RIPPLE_GLOW);
        // Preserve WRAP_CONTENT's responsive sizing while preventing the empty,
        // backgroundless field from collapsing to zero width.
        v.setMinEms(1);
        v.setInputType(InputType.TYPE_CLASS_NUMBER);
        v.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(MAX_SEED_DIGITS) });
        v.setImeOptions(EditorInfo.IME_ACTION_DONE);
        v.setSingleLine(true);
        v.setCursorVisible(false);
        v.setOnClickListener(ignored -> v.setCursorVisible(true));
        v.setOnFocusChangeListener((ignored, hasFocus) -> {
            if (!hasFocus) v.setCursorVisible(false);
        });
        return v;
    }

    private TextView makeSeedLabel() {
        TextView v = new TextView(activity);
        v.setText("Seed:");
        v.setTextColor(Palette.PALE_BLUE);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        v.setTypeface(Typeface.MONOSPACE);
        return v;
    }

    /** Borderless pill. GradientDrawable caps the corner radius at half the
     *  height, so a large one rounds the ends fully. */
    private View makeSeedPill() {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(activity.dpToPx(100));
        bg.setColor(Palette.ACTION_BG);
        View v = new View(activity);
        v.setBackground(bg);
        return v;
    }

    private ImageView makePencilIcon() {
        ImageView v = new ImageView(activity);
        v.setImageResource(R.drawable.ic_edit);
        v.setImageTintList(ColorStateList.valueOf(Palette.PALE_BLUE));
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return v;
    }

    /** "Seed:" and a pill holding the seed and the pencil, centered as a group
     *  for a 9-digit seed like the random ones. A longer seed grows the pill
     *  to the right until it reaches the edge, then pushes the group left, and
     *  its text shrinks only when that is not enough. */
    private static final class SeedRow extends ViewGroup {
        private static final String REFERENCE_SEED = "000000000";

        private final TextView label;
        private final View pill;
        private final EditText seed;
        private final View pencil;
        private final int labelGap;
        private final int pillStart;
        private final int pencilGap;
        private final int pillEnd;
        private final float seedTextPx;
        private final int referenceSeedWidth;

        SeedRow(BrogueActivity activity, TextView label, View pill, EditText seed,
                View pencil) {
            super(activity);
            this.label = label;
            this.pill = pill;
            this.seed = seed;
            this.pencil = pencil;
            labelGap = activity.dpToPx(8);
            pillStart = activity.dpToPx(14);
            pencilGap = activity.dpToPx(7);
            pillEnd = activity.dpToPx(10);
            seedTextPx = seed.getTextSize();
            referenceSeedWidth = (int) Math.ceil(seed.getPaint().measureText(REFERENCE_SEED));
            setPadding(0, activity.dpToPx(8), 0, activity.dpToPx(4));

            // The pill goes first so it draws behind the seed and the pencil.
            addView(pill);
            addView(seed);
            int pencilPx = activity.dpToPx(16);
            addView(pencil, new LayoutParams(pencilPx, pencilPx));
            addView(label);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            measureChild(label, widthSpec, heightSpec);
            measureChild(pencil, widthSpec, heightSpec);
            fitSeedText(width - getPaddingLeft() - getPaddingRight() - sideWidth());
            measureChild(seed, widthSpec, heightSpec);
            pill.measure(
                MeasureSpec.makeMeasureSpec(pillStart + seed.getMeasuredWidth() + pencilGap
                    + pencil.getMeasuredWidth() + pillEnd, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(seed.getMeasuredHeight(), MeasureSpec.EXACTLY));
            int height = Math.max(label.getMeasuredHeight(), pill.getMeasuredHeight());
            setMeasuredDimension(width, getPaddingTop() + height + getPaddingBottom());
        }

        /** The group's width apart from the seed's own text. */
        private int sideWidth() {
            return label.getMeasuredWidth() + labelGap + pillStart + pencilGap
                + pencil.getMeasuredWidth() + pillEnd;
        }

        /** The size is set only when it changes, so a measure pass does not
         *  keep requesting another one. */
        private void fitSeedText(int room) {
            TextPaint paint = new TextPaint(seed.getPaint());
            paint.setTextSize(seedTextPx);
            float textWidth = (float) Math.ceil(paint.measureText(seed.getText().toString()));
            float size = room <= 0 || textWidth <= room
                ? seedTextPx
                : (float) Math.floor(seedTextPx * room / textWidth);
            if (size != seed.getTextSize()) {
                seed.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            }
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l - getPaddingLeft() - getPaddingRight();
            int groupWidth = sideWidth() + seed.getMeasuredWidth();
            int referenceWidth = sideWidth() + referenceSeedWidth;
            int x = groupWidth <= referenceWidth
                ? (width - groupWidth) / 2
                : Math.min((width - referenceWidth) / 2, width - groupWidth);
            x = getPaddingLeft() + Math.max(0, x);
            int centerY = getPaddingTop()
                + Math.max(label.getMeasuredHeight(), pill.getMeasuredHeight()) / 2;

            x = place(label, x, centerY) + labelGap;
            place(pill, x, centerY);
            x = place(seed, x + pillStart, centerY) + pencilGap;
            place(pencil, x, centerY);
        }

        /** Lays {@code v} out from {@code left}, centered on {@code centerY},
         *  and returns its right edge. */
        private static int place(View v, int left, int centerY) {
            int top = centerY - v.getMeasuredHeight() / 2;
            v.layout(left, top, left + v.getMeasuredWidth(), top + v.getMeasuredHeight());
            return left + v.getMeasuredWidth();
        }
    }

    private void focusEditor() {
        seedEdit.setText(Long.toUnsignedString(seed));
        seedEdit.requestFocus();
        seedEdit.setCursorVisible(true);
        seedEdit.selectAll();
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(seedEdit, 0);
    }

    /** Empty input becomes seed 1, and a number too large becomes the largest
     *  seed, as in Brogue CE. Invalid input or 0 retains the prior seed. The
     *  display is rewritten from {@code seed} so it matches what's committed.
     *  Called by IME dismissal, Done, and Play. */
    private void commitEdit() {
        if (seedEdit == null || seedEdit.getWindowToken() == null) return;

        String trimmed = seedEdit.getText().toString().trim();
        long parsed = trimmed.isEmpty() ? 1L : 0L;
        if (!trimmed.isEmpty()) {
            try { parsed = Long.parseUnsignedLong(trimmed); }
            catch (NumberFormatException ignored) {
                if (trimmed.matches("[0-9]+")) parsed = MAX_SEED;
            }
        }
        if (parsed != 0L && parsed != seed) {
            seed = parsed;
        }
        seedEdit.setText(Long.toUnsignedString(seed));

        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(seedEdit.getWindowToken(), 0);
        seedEdit.clearFocus();
        seedEdit.setCursorVisible(false);
    }



    /** 9-digit random seed matching Brogue's clock-based seed shape at
     *  the current date range. See RANDOM_SEED_MIN/MAX for rationale. */
    private long pickRandomSeed() {
        long span = RANDOM_SEED_MAX - RANDOM_SEED_MIN + 1;
        // floorMod, unlike Math.abs, is never negative, even for Long.MIN_VALUE.
        return RANDOM_SEED_MIN + Math.floorMod(random.nextLong(), span);
    }
}
