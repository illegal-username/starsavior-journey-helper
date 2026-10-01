package helper.journey.starsavior;

import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;

final class DisplayBounds {
    @SuppressWarnings("deprecation")
    static Rect of(WindowManager manager) {
        if (Build.VERSION.SDK_INT >= 30) return manager.getMaximumWindowMetrics().getBounds();
        DisplayMetrics metrics = new DisplayMetrics();
        manager.getDefaultDisplay().getRealMetrics(metrics);
        return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
    }

    private DisplayBounds() {}
}
