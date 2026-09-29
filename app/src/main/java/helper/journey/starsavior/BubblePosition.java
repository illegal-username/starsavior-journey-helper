package helper.journey.starsavior;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;

/** Fractions of the available travel, independently for portrait and landscape. */
final class BubblePosition {
    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("floating_icon", Context.MODE_PRIVATE);
    }

    private static String key(int width, int height) {
        return width > height ? "landscape_" : "portrait_";
    }

    static void save(Context context, int width, int height, int size, int x, int y) {
        if (width <= 0 || height <= 0) return;
        String key = key(width, height);
        preferences(context).edit()
                .putFloat(key + "x", fraction(x, width - size))
                .putFloat(key + "y", fraction(y, height - size)).apply();
    }

    static Point load(Context context, int width, int height, int size) {
        String key = key(width, height);
        SharedPreferences saved = preferences(context);
        int maxX = Math.max(0, width - size), maxY = Math.max(0, height - size);
        return new Point(coordinate(saved, key + "x", maxX, Ui.dp(context, 12)),
                coordinate(saved, key + "y", maxY, Ui.dp(context, 110)));
    }

    private static float fraction(int value, int maximum) {
        return maximum <= 0 ? 0 : Math.max(0, Math.min(1, value / (float) maximum));
    }

    private static int coordinate(SharedPreferences saved, String key, int maximum, int fallback) {
        if (!saved.contains(key)) return Math.min(maximum, fallback);
        float ratio = saved.getFloat(key, 0);
        if (Float.isNaN(ratio) || Float.isInfinite(ratio)) return Math.min(maximum, fallback);
        return Math.round(Math.max(0, Math.min(1, ratio)) * maximum);
    }

    private BubblePosition() {}
}
