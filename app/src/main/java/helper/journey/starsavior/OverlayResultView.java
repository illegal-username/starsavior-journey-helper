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

final class OverlayResultView {
    private static final String INFO_MESSAGE_TAG = "journey_overlay_info_message";

    private OverlayResultView() {}

    static View match(Context context, JourneyModels.Match match, Runnable closeAction) {
        return match(context, match, "", null, closeAction);
    }

    static View match(Context context, JourneyModels.Match match, String difficulty,
                      StaminaGaugeDetector.Result stamina, Runnable closeAction) {
        LinearLayout panel = panel(context);
        addHeader(context, panel, match.event.name, closeAction);

        addJourneyStatus(context, panel, stamina);

        String detail = match.eventNameUsed
                ? String.format(Locale.KOREA, "이벤트 %.0f%% · 선택지 %.0f%%",
                        match.eventConfidence * 100, match.choiceConfidence * 100)
                : String.format(Locale.KOREA, "선택지 %.0f%% · 이벤트명 미확인",
                        match.choiceConfidence * 100);
        if (!match.event.context.isEmpty()) detail += " · " + match.event.context;
        TextView subtitle = Ui.text(context, detail, 12, Ui.MUTED);
        panel.addView(subtitle, margins(context, -1, -2, 0, 2, 0, 10));

        MaxHeightScrollView scroll = new MaxHeightScrollView(context, maxScrollHeight(context));
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));

        for (int index = 0; index < match.event.choices.size(); index++) {
            JourneyModels.Choice choice = match.event.choices.get(index);
            List<JourneyModels.Outcome> visibleOutcomes = choice.outcomesForDifficulty(difficulty);
            LinearLayout choiceCard = new LinearLayout(context);
            choiceCard.setOrientation(LinearLayout.VERTICAL);
            choiceCard.setPadding(Ui.dp(context, 13), Ui.dp(context, 11), Ui.dp(context, 13), Ui.dp(context, 11));
            choiceCard.setBackground(Ui.rounded(context, Ui.CARD_ALT, 14));

            TextView choiceTitle = Ui.text(context, (index + 1) + ". " + choice.text, 14, Ui.TEXT);
            choiceTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            choiceTitle.setLineSpacing(0, 1.08f);
            choiceCard.addView(choiceTitle, new LinearLayout.LayoutParams(-1, -2));

            if (visibleOutcomes.isEmpty()) {
                TextView unavailable = Ui.text(context, "감지한 난이도의 결과 정보가 없습니다.", 12, Ui.MUTED);
                choiceCard.addView(unavailable, margins(context, -1, -2, 0, 7, 0, 0));
            }

            for (int outcomeIndex = 0; outcomeIndex < visibleOutcomes.size(); outcomeIndex++) {
                JourneyModels.Outcome outcome = visibleOutcomes.get(outcomeIndex);
                if (visibleOutcomes.size() > 1) {
                    String variantText = difficulty == null || difficulty.isEmpty()
                            ? outcome.label.isEmpty() ? "가능 결과 " + (outcomeIndex + 1) : outcome.label
                            : "가능 결과 " + (outcomeIndex + 1);
                    TextView variant = Ui.text(context,
                            variantText,
                            11, Ui.ORANGE);
                    variant.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    choiceCard.addView(variant, margins(context, -1, -2, 0, 8, 0, 2));
                }
                if (!outcome.condition.isEmpty()) addEffect(context, choiceCard, "조건", outcome.condition, Ui.ORANGE);
                addEffect(context, choiceCard, outcome.failure.isEmpty() ? "효과" : "성공", outcome.success, Ui.GREEN);
                if (!outcome.failure.isEmpty()) addEffect(context, choiceCard, "실패", outcome.failure, Ui.RED);
            }

            list.addView(choiceCard, margins(context, -1, -2, 0, 0, 0, index == match.event.choices.size() - 1 ? 0 : 8));
        }

        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        return wrap(context, panel);
    }

    static View stamina(Context context, StaminaGaugeDetector.Result stamina, Runnable closeAction) {
        LinearLayout panel = panel(context);
        addHeader(context, panel, "스태미나", closeAction);
        addJourneyStatus(context, panel, stamina);
        TextView hint = Ui.text(context,
                "상단 게이지의 색 구간을 기준으로 계산한 추정값입니다.", 11, Ui.MUTED);
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
            TextView rawTitle = Ui.text(context, "읽힌 글자", 11, Ui.ORANGE);
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
        addHeader(context, panel, "도우미 메뉴", closeAction);

        TextView description = Ui.text(context,
                "DB를 최신화하거나 화면 공유와 플로팅 아이콘을 함께 종료할 수 있습니다.", 13, Ui.MUTED);
        description.setLineSpacing(0, 1.2f);
        panel.addView(description, margins(context, -1, -2, 0, 5, 0, 13));

        TextView update = Ui.button(context, "DB 업데이트", true);
        update.setContentDescription("선택지 DB 업데이트");
        update.setOnClickListener(view -> updateAction.run());
        panel.addView(update, margins(context, -1, Ui.dp(context, 48), 0, 0, 0, 8));

        TextView stop = Ui.button(context, "도우미 종료", false);
        stop.setTextColor(Ui.RED);
        stop.setBackground(Ui.roundedStroke(context, Color.argb(35, 255, 129, 145), 16, Ui.RED, 1));
        stop.setContentDescription("도우미 완전히 종료");
        stop.setOnClickListener(view -> stopAction.run());
        panel.addView(stop, margins(context, -1, Ui.dp(context, 48), 0, 0, 0, 8));

        TextView cancel = Ui.button(context, "취소", false);
        cancel.setOnClickListener(view -> closeAction.run());
        panel.addView(cancel, margins(context, -1, Ui.dp(context, 46), 0, 0, 0, 0));
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

    private static FrameLayout wrap(Context context, View content) {
        FrameLayout wrapper = new FrameLayout(context);
        wrapper.setPadding(Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4));
        wrapper.addView(content, new FrameLayout.LayoutParams(-1, -2));
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
        close.setContentDescription("결과 닫기");
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
        row.addView(labelView, new LinearLayout.LayoutParams(Ui.dp(context, 42), Ui.dp(context, 24)));

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
                text.append("행동 후 스태미나 : ")
                        .append(stamina.current).append(" → ").append(stamina.after);
            } else {
                text.append("현재 스태미나 : ").append(stamina.current);
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
            int limitedHeight = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
            super.onMeasure(widthMeasureSpec, limitedHeight);
        }
    }
}
