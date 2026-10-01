package helper.journey.starsavior;

import android.content.Context;
import android.graphics.Point;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BubblePositionTest {
    @Test public void restoreUsesAvailableTravelAndKeepsOrientationsIndependent() {
        Context context = RuntimeEnvironment.getApplication();
        BubblePosition.save(context, 1000, 600, 100, 450, 500);
        BubblePosition.save(context, 600, 1000, 100, 100, 450);
        assertEquals(new Point(900, 900), BubblePosition.load(context, 1900, 1000, 100));
        assertEquals(new Point(180, 900), BubblePosition.load(context, 1000, 1900, 100));
        assertEquals(new Point(450, 500), BubblePosition.load(context, 1000, 600, 100));
    }

    @Test public void tinyScreensAndInvalidStoredCoordinatesStayInsideDisplay() {
        Context context = RuntimeEnvironment.getApplication();
        BubblePosition.save(context, 1000, 600, 100, -100, 3000);
        assertEquals(new Point(0, 500), BubblePosition.load(context, 1000, 600, 100));
        assertEquals(new Point(0, 0), BubblePosition.load(context, 40, 30, 100));
        context.getSharedPreferences("floating_icon", Context.MODE_PRIVATE).edit()
                .putFloat("landscape_x", Float.NaN).putFloat("landscape_y", Float.POSITIVE_INFINITY).apply();
        Point point = BubblePosition.load(context, 400, 300, 60);
        assertTrue(point.x >= 0 && point.x <= 340); assertTrue(point.y >= 0 && point.y <= 240);
    }
}
