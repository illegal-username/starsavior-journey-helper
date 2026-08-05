package helper.journey.starsavior;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

final class Ui {
    static final int BG = Color.rgb(14, 13, 24);
    static final int CARD = Color.rgb(29, 27, 48);
    static final int CARD_ALT = Color.rgb(38, 35, 62);
    static final int PRIMARY = Color.rgb(137, 121, 255);
    static final int PRIMARY_DARK = Color.rgb(99, 80, 222);
    static final int TEXT = Color.rgb(246, 244, 255);
    static final int MUTED = Color.rgb(181, 176, 207);
    static final int GREEN = Color.rgb(100, 218, 162);
    static final int RED = Color.rgb(255, 129, 145);
    static final int ORANGE = Color.rgb(255, 190, 112);
    static final int BLUE = Color.rgb(142, 200, 255);

    private Ui() {}

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(Context context, int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    static GradientDrawable roundedStroke(Context context, int color, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable drawable = rounded(context, color, radiusDp);
        drawable.setStroke(dp(context, strokeDp), strokeColor);
        return drawable;
    }

    static TextView text(Context context, String value, float sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    static TextView button(Context context, String value, boolean primary) {
        TextView button = text(context, value, 16, primary ? Color.WHITE : TEXT);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(dp(context, 52));
        button.setPadding(dp(context, 18), dp(context, 12), dp(context, 18), dp(context, 12));
        button.setBackground(primary
                ? rounded(context, PRIMARY_DARK, 16)
                : roundedStroke(context, Color.TRANSPARENT, 16, Color.rgb(79, 74, 111), 1));
        button.setClickable(true);
        button.setFocusable(true);
        button.setElevation(dp(context, primary ? 3 : 0));
        return button;
    }

    static void setVisible(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
