package helper.journey.starsavior;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;
import java.util.Set;

final class OverlayResultView {
    private static final String INFO_MESSAGE_TAG = "journey_overlay_info_message";

    private OverlayResultView() {}

    static View match(Context context, JourneyModels.Match match, Runnable closeAction) {
        return match(context, match, "", null, closeAction);
    }

    static View match(Context context, JourneyModels.Match match, String difficulty,
                      StaminaGaugeDetector.Result stamina, Runnable closeAction) {
        return match(context, match, difficulty, stamina, Set.of(), closeAction);
    }

    static View match(Context context, JourneyModels.Match match, String difficulty,
                      StaminaGaugeDetector.Result stamina, Set<String> recognizedArcanaIds,
                      Runnable closeAction) {
        LinearLayout panel = panel(context);
        addHeader(context, panel, match.event.name, closeAction);

        addJourneyStatus(context, panel, stamina);

        ArcanaRecognitionStatus arcanaStatus = ArcanaRecognitionStatus.from(
                match.event, difficulty, recognizedArcanaIds);
        boolean showArcanaStatus = !match.event.sameProgress && arcanaStatus.applicable;

        String detail = match.eventNameUsed
                ? String.format(AppLanguage.of(context).locale(), context.getString(R.string.match_confidence),
                        match.eventConfidence * 100, match.choiceConfidence * 100)
                : String.format(AppLanguage.of(context).locale(), context.getString(R.string.match_no_title),
                        match.choiceConfidence * 100);
        if (!match.event.sameProgress && !showArcanaStatus && !match.event.context.isEmpty()) {
            detail += " · " + match.event.context;
        }
        TextView subtitle = Ui.text(context, detail, 12, Ui.MUTED);
        panel.addView(subtitle, margins(
                context, -1, -2, 0, 2, 0, showArcanaStatus ? 6 : 10));

        if (match.event.sameProgress) {
            addSameProgressNotice(context, panel);
            return wrap(context, panel);
        }

        if (showArcanaStatus) addArcanaStatus(context, panel, arcanaStatus);

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);

        for (int index = 0; index < match.event.choices.size(); index++) {
            JourneyModels.Choice choice = match.event.choices.get(index);
            List<JourneyModels.Outcome> visibleOutcomes = choice.outcomesFor(
                    difficulty, recognizedArcanaIds);
            LinearLayout choiceCard = new LinearLayout(context);
            choiceCard.setOrientation(LinearLayout.VERTICAL);
            choiceCard.setPadding(Ui.dp(context, 13), Ui.dp(context, 11), Ui.dp(context, 13), Ui.dp(context, 11));
            choiceCard.setBackground(Ui.rounded(context, Ui.CARD_ALT, 14));

            String displayedText = JourneyMatcher.displayChoiceText(choice, match.recognizedLines);
            TextView choiceTitle = Ui.text(context, (index + 1) + ". " + displayedText, 14, Ui.TEXT);
            choiceTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            choiceTitle.setLineSpacing(0, 1.08f);
            choiceCard.addView(choiceTitle, new LinearLayout.LayoutParams(-1, -2));

            if (visibleOutcomes.isEmpty()) {
                TextView unavailable = Ui.text(context, context.getString(R.string.no_difficulty_result), 12, Ui.MUTED);
                choiceCard.addView(unavailable, margins(context, -1, -2, 0, 7, 0, 0));
            }

            for (int outcomeIndex = 0; outcomeIndex < visibleOutcomes.size(); outcomeIndex++) {
                JourneyModels.Outcome outcome = visibleOutcomes.get(outcomeIndex);
                if (visibleOutcomes.size() > 1) {
                    String variantText = difficulty == null || difficulty.isEmpty()
                            ? outcome.label.isEmpty() ? context.getString(R.string.possible_result, outcomeIndex + 1) : outcome.label
                            : context.getString(R.string.possible_result, outcomeIndex + 1);
                    TextView variant = Ui.text(context,
                            variantText,
                            11, Ui.ORANGE);
                    variant.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    choiceCard.addView(variant, margins(context, -1, -2, 0, 8, 0, 2));
                }
                if (!outcome.condition.isEmpty()) addEffect(context, choiceCard, context.getString(R.string.condition_label), outcome.condition, Ui.ORANGE);
                addEffect(context, choiceCard, outcome.failure.isEmpty() ? context.getString(R.string.effect_label) : context.getString(R.string.success_label), outcome.success, Ui.GREEN);
                if (!outcome.failure.isEmpty()) addEffect(context, choiceCard, context.getString(R.string.failure_label), outcome.failure, Ui.RED);
            }

            list.addView(choiceCard, margins(context, -1, -2, 0, 0, 0, index == match.event.choices.size() - 1 ? 0 : 8));
        }

        panel.addView(list, new LinearLayout.LayoutParams(-1, -2));
        return wrap(context, panel);
    }

    static View stamina(Context context, StaminaGaugeDetector.Result stamina, Runnable closeAction) {
        LinearLayout panel = panel(context);
        addHeader(context, panel, context.getString(R.string.stamina_label), closeAction);
        addJourneyStatus(context, panel, stamina);
        TextView hint = Ui.text(context,
                context.getString(R.string.stamina_hint), 11, Ui.MUTED);
        panel.addView(hint, margins(context, -1, -2, 0, 3, 0, 0));
        return wrap(context, panel);
    }

    static View error(Context context, String title, String message, List<String> recognizedLines, Runnable closeAction) {
        LinearLayout panel = panel(context);
        addHeader(context, panel, title, closeAction);
        TextView body = Ui.text(context, message, 13, Ui.MUTED);
        body.setLineSpacing(0, 1.2f);
        panel.addView(body, margins(context, -1, -2, 0, 4, 0, 10));

        if (recognizedLines != null && !recognizedLines.isEmpty()) {
            TextView rawTitle = Ui.text(context, context.getString(R.string.recognized_text), 11, Ui.ORANGE);
            rawTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            panel.addView(rawTitle, margins(context, -1, -2, 0, 0, 0, 3));
            String raw = String.join("  /  ", recognizedLines);
            TextView rawText = Ui.text(context, raw, 11, Color.rgb(163, 158, 188));
            rawText.setMaxLines(4);
            rawText.setLineSpacing(0, 1.15f);
            panel.addView(rawText);
        }
        return wrap(context, panel);
    }

    static View controls(Context context, Runnable updateAction, Runnable stopAction, Runnable closeAction) {
        LinearLayout panel = panel(context);
        addHeader(context, panel, context.getString(R.string.helper_menu), closeAction);

        TextView description = Ui.text(context,
                context.getString(R.string.menu_help), 13, Ui.MUTED);
        description.setLineSpacing(0, 1.2f);
        panel.addView(description, margins(context, -1, -2, 0, 5, 0, 13));

        TextView update = Ui.button(context, context.getString(R.string.update_database), true);
        update.setContentDescription(context.getString(R.string.update_accessibility));
        update.setOnClickListener(view -> updateAction.run());
        panel.addView(update, margins(context, -1, -2, 0, 0, 0, 8));

        TextView stop = Ui.button(context, context.getString(R.string.stop_helper), false);
        stop.setTextColor(Ui.RED);
        stop.setBackground(Ui.roundedStroke(context, Color.argb(35, 255, 129, 145), 16, Ui.RED, 1));
        stop.setContentDescription(context.getString(R.string.stop_accessibility));
        stop.setOnClickListener(view -> stopAction.run());
        panel.addView(stop, margins(context, -1, -2, 0, 0, 0, 8));

        TextView cancel = Ui.button(context, context.getString(R.string.cancel), false);
        cancel.setOnClickListener(view -> closeAction.run());
        panel.addView(cancel, margins(context, -1, -2, 0, 0, 0, 0));
        return wrap(context, panel);
    }

    static View info(Context context, String title, String message, Runnable closeAction) {
        LinearLayout panel = panel(context);
        addHeader(context, panel, title, closeAction);
        TextView body = Ui.text(context, message, 13, Ui.MUTED);
        body.setTag(INFO_MESSAGE_TAG);
        body.setLineSpacing(0, 1.2f);
        panel.addView(body, margins(context, -1, -2, 0, 5, 0, 2));
        return wrap(context, panel);
    }

    static boolean updateInfo(View root, String message) {
        if (root == null) return false;
        View tagged = root.findViewWithTag(INFO_MESSAGE_TAG);
        if (!(tagged instanceof TextView)) return false;
        ((TextView) tagged).setText(message);
        return true;
    }

    private static FrameLayout wrap(Context context, LinearLayout panel) {
        // Keep the title and close action visible while the entire body can scroll.
        // Status messages must share the viewport so they cannot crowd out the results.
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        while (panel.getChildCount() > 1) {
            View child = panel.getChildAt(1);
            panel.removeViewAt(1);
            body.addView(child);
        }
        MaxHeightScrollView scroll = new MaxHeightScrollView(context, maxScrollHeight(context));
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout wrapper = new FrameLayout(context);
        wrapper.setPadding(Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4));
        wrapper.addView(panel, new FrameLayout.LayoutParams(-1, -2));
        wrapper.setElevation(Ui.dp(context, 12));
        return wrapper;
    }

    @SuppressWarnings("deprecation")
    private static int maxScrollHeight(Context context) {
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        int screenHeight;
        if (Build.VERSION.SDK_INT >= 30) {
            Rect bounds = manager.getMaximumWindowMetrics().getBounds();
            screenHeight = bounds.height();
        } else {
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            manager.getDefaultDisplay().getRealMetrics(metrics);
            screenHeight = metrics.heightPixels;
        }
        return Math.min(Ui.dp(context, 480), Math.round(screenHeight * 0.66f));
    }

    private static LinearLayout panel(Context context) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(context, 16), Ui.dp(context, 14), Ui.dp(context, 16), Ui.dp(context, 15));
        panel.setBackground(Ui.roundedStroke(context, Color.argb(239, 27, 25, 45), 18, Color.rgb(91, 83, 137), 1));
        return panel;
    }

    private static void addHeader(Context context, LinearLayout panel, String title, Runnable closeAction) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = Ui.text(context, title, 18, Ui.TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

        TextView close = Ui.text(context, "×", 26, Ui.MUTED);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription(context.getString(R.string.close_result));
        close.setBackground(Ui.rounded(context, Color.rgb(50, 47, 75), 12));
        close.setOnClickListener(v -> closeAction.run());
        header.addView(close, new LinearLayout.LayoutParams(Ui.dp(context, 38), Ui.dp(context, 38)));
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));
    }

    private static void addEffect(Context context, LinearLayout parent, String label, String value, int color) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);

        TextView labelView = Ui.text(context, label, 11, color);
        labelView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        labelView.setGravity(Gravity.CENTER);
        labelView.setBackground(Ui.rounded(context, Color.argb(45, Color.red(color), Color.green(color), Color.blue(color)), 7));
        labelView.setMinWidth(Ui.dp(context, 42));
        labelView.setMinHeight(Ui.dp(context, 24));
        labelView.setMaxWidth(Ui.dp(context, 95));
        labelView.setPadding(Ui.dp(context, 5), Ui.dp(context, 3), Ui.dp(context, 5), Ui.dp(context, 3));
        row.addView(labelView, new LinearLayout.LayoutParams(-2, -2));

        TextView valueView = Ui.text(context, value, 12, Ui.TEXT);
        valueView.setLineSpacing(0, 1.13f);
        row.addView(valueView, margins(context, 0, -2, 8, 1, 0, 0, 1));
        parent.addView(row, margins(context, -1, -2, 0, 6, 0, 0));
    }

    private static void addJourneyStatus(Context context, LinearLayout panel,
                                         StaminaGaugeDetector.Result stamina) {
        StringBuilder text = new StringBuilder();
        if (stamina != null) {
            if (stamina.hasPreview()) {
                text.append(context.getString(R.string.stamina_after, stamina.current, stamina.after));
            } else {
                text.append(context.getString(R.string.stamina_current, stamina.current));
            }
        }
        if (text.length() == 0) return;

        TextView status = Ui.text(context, text.toString(), 13, Ui.GREEN);
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        status.setLineSpacing(0, 1.18f);
        status.setPadding(Ui.dp(context, 11), Ui.dp(context, 9), Ui.dp(context, 11), Ui.dp(context, 9));
        status.setBackground(Ui.roundedStroke(context, Color.argb(42, 86, 219, 171), 10,
                Color.argb(105, 86, 219, 171), 1));
        panel.addView(status, margins(context, -1, -2, 0, 3, 0, 9));
    }

    private static void addArcanaStatus(
            Context context, LinearLayout panel, ArcanaRecognitionStatus arcanaStatus) {
        int color = arcanaStatus.recognized ? Ui.GREEN : Ui.ORANGE;
        TextView status = Ui.text(context, arcanaStatus.recognized
                ? context.getString(R.string.arcana_detected, arcanaStatus.detectedSource)
                : context.getString(R.string.arcana_unknown), 12, color);
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        status.setLineSpacing(0, 1.15f);
        status.setPadding(Ui.dp(context, 10), Ui.dp(context, 7), Ui.dp(context, 10), Ui.dp(context, 7));
        status.setBackground(Ui.roundedStroke(
                context,
                Color.argb(35, Color.red(color), Color.green(color), Color.blue(color)),
                9,
                Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)),
                1));
        panel.addView(status, margins(context, -1, -2, 0, 0, 0, 10));
    }

    private static void addSameProgressNotice(Context context, LinearLayout panel) {
        LinearLayout notice = new LinearLayout(context);
        notice.setOrientation(LinearLayout.VERTICAL);
        notice.setPadding(Ui.dp(context, 13), Ui.dp(context, 11), Ui.dp(context, 13), Ui.dp(context, 11));
        notice.setBackground(Ui.roundedStroke(
                context, Color.argb(42, 86, 219, 171), 12,
                Color.argb(105, 86, 219, 171), 1));

        TextView title = Ui.text(context, context.getString(R.string.same_progress_title), 14, Ui.GREEN);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        notice.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView message = Ui.text(context, context.getString(R.string.same_progress_message), 12, Ui.TEXT);
        message.setLineSpacing(0, 1.15f);
        notice.addView(message, margins(context, -1, -2, 0, 6, 0, 0));
        panel.addView(notice, margins(context, -1, -2, 0, 0, 0, 0));
    }

    private static LinearLayout.LayoutParams margins(Context context, int width, int height,
                                                       int left, int top, int right, int bottom) {
        return margins(context, width, height, left, top, right, bottom, 0);
    }

    private static LinearLayout.LayoutParams margins(Context context, int width, int height,
                                                       int left, int top, int right, int bottom, float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height, weight);
        params.setMargins(Ui.dp(context, left), Ui.dp(context, top), Ui.dp(context, right), Ui.dp(context, bottom));
        return params;
    }

    private static final class MaxHeightScrollView extends ScrollView {
        private final int maxHeight;

        MaxHeightScrollView(Context context, int maxHeight) {
            super(context);
            this.maxHeight = maxHeight;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int mode = MeasureSpec.getMode(heightMeasureSpec);
            if (mode != MeasureSpec.EXACTLY) {
                // The parent has already reserved space for the header and padding.
                // Enlarging that space clips the viewport and can hide its scroll range.
                int height = mode == MeasureSpec.UNSPECIFIED
                        ? maxHeight : Math.min(maxHeight, MeasureSpec.getSize(heightMeasureSpec));
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}
