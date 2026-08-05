package helper.journey.starsavior;

import android.content.Context;

final class BubbleAppearance {
    static final int MIN_PROGRESS = 0;
    static final int MAX_PROGRESS = 100;
    static final int DEFAULT_PROGRESS = MAX_PROGRESS;

    private static final String PREFERENCES = "floating_icon";
    private static final String KEY_CIRCLE_PROGRESS = "circle_progress";

    private BubbleAppearance() {}

    static int loadCircleProgress(Context context) {
        return clampProgress(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getInt(KEY_CIRCLE_PROGRESS, DEFAULT_PROGRESS));
    }

    static void saveCircleProgress(Context context, int progress) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_CIRCLE_PROGRESS, clampProgress(progress))
                .apply();
    }

    static int clampProgress(int progress) {
        return Math.max(MIN_PROGRESS, Math.min(MAX_PROGRESS, progress));
    }

    static int diameterForProgress(int minimum, int maximum, int progress) {
        if (maximum < minimum) throw new IllegalArgumentException("maximum must be >= minimum");
        int clamped = clampProgress(progress);
        return minimum + Math.round((maximum - minimum) * (clamped / 100f));
    }
}
