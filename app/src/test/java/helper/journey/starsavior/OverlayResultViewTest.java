package helper.journey.starsavior;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35}, qualifiers = "w960dp-h360dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class OverlayResultViewTest {
    @Before
    public void resetFontScale() {
        RuntimeEnvironment.setFontScale(1.0f);
    }

    @Test
    public void contentClippedByParentStillScrollsEvenBelowDisplayHeightCap() {
        ViewGroup root = result("Reward +10", "", () -> {});
        layout(root, 460, 360);
        ScrollView scroll = findScroll(root);
        assertFalse("The complete result initially fits: root=" + root.getHeight()
                + ", viewport=" + scroll.getHeight() + ", content=" + scroll.getChildAt(0).getHeight(),
                scroll.canScrollVertically(1));
        int naturalHeight = root.getHeight();

        // The window can be smaller than the display because of insets or other fixed content.
        layout(root, 460, naturalHeight - 40);

        assertTrue("Clipped results must have a scroll range", scroll.canScrollVertically(1));
        assertViewportInsidePanel(root, scroll);
        assertLastResultReachable(root, scroll);
    }

    @Test
    public void longOutcomesScrollToLastLineInShortLandscapeWindow() {
        ViewGroup root = result("A long synthetic reward description.\n".repeat(12),
                "A synthetic failure description.\n".repeat(5), () -> {});
        layout(root, 460, 300);
        ScrollView scroll = findScroll(root);

        assertTrue(scroll.canScrollVertically(1));
        assertViewportInsidePanel(root, scroll);
        swipeUp(root, scroll);
        assertTrue("Dragging the result must move the content", scroll.getScrollY() > 0);
        assertLastResultReachable(root, scroll);
    }

    @Test
    public void largeFontAndWindowResizeKeepResultsReachable() {
        RuntimeEnvironment.setFontScale(1.8f);
        ViewGroup root = result("Reward details\n".repeat(8), "Failure details\n".repeat(4), () -> {});
        layout(root, 460, 320);
        ScrollView scroll = findScroll(root);
        assertViewportInsidePanel(root, scroll);
        assertLastResultReachable(root, scroll);

        layout(root, 380, 160);
        assertViewportInsidePanel(root, scroll);
        assertLastResultReachable(root, scroll);
    }

    @Test
    public void shortResultsWrapContentAndCloseButtonRemainsUsable() {
        AtomicBoolean closed = new AtomicBoolean();
        ViewGroup root = result("Reward +10", "", () -> closed.set(true));
        layout(root, 460, 360);
        ScrollView scroll = findScroll(root);

        assertFalse(scroll.canScrollVertically(1));
        assertFalse(scroll.canScrollVertically(-1));
        assertTrue("Short results should not fill the window", root.getHeight() < 360);
        assertViewportInsidePanel(root, scroll);
        TextView close = findText(root, "×");
        Rect closeBounds = boundsInRoot(root, close);
        assertTrue("The close button must stay inside the window", closeBounds.top >= 0
                && closeBounds.bottom <= root.getHeight());
        assertTrue(close.performClick());
        assertTrue(closed.get());
    }

    @Test
    public void growingInfoMessageRemainsScrollable() {
        Context context = RuntimeEnvironment.getApplication();
        ViewGroup root = (ViewGroup) OverlayResultView.info(context, "Update", "Checking", () -> {});
        layout(root, 460, 240);
        assertTrue(OverlayResultView.updateInfo(root, "Update details\n".repeat(30) + "Finished"));
        layout(root, 460, 240);
        assertTextEndReachable(root, "Update details\n".repeat(30) + "Finished");
    }

    @Test
    public void longErrorMessageCanBeReadToTheEnd() {
        String message = "Synthetic recovery instructions\n".repeat(25) + "Last recovery step";
        ViewGroup root = (ViewGroup) OverlayResultView.error(RuntimeEnvironment.getApplication(),
                "Recovery", message, List.of(), () -> {});
        layout(root, 460, 240);
        assertTextEndReachable(root, message);
    }

    @Test
    public void menuButtonsRemainReachableWithLargeFonts() {
        RuntimeEnvironment.setFontScale(1.8f);
        Context context = RuntimeEnvironment.getApplication();
        AtomicBoolean canceled = new AtomicBoolean();
        ViewGroup root = (ViewGroup) OverlayResultView.controls(context, () -> {}, () -> {},
                () -> canceled.set(true));
        layout(root, 460, 210);
        assertTextEndReachable(root, context.getString(R.string.cancel));
        assertTrue(findText(root, context.getString(R.string.cancel)).performClick());
        assertTrue(canceled.get());
    }

    @Test
    public void sameProgressNoticeRemainsReadableInShortWindow() {
        RuntimeEnvironment.setFontScale(1.8f);
        Context context = RuntimeEnvironment.getApplication();
        JourneyModels.Event event = new JourneyModels.Event("Synthetic dialogue", "", List.of(), true);
        JourneyModels.Match match = new JourneyModels.Match(event, 1, 1, 1, true, false,
                List.of(), List.of(), List.of());
        ViewGroup root = (ViewGroup) OverlayResultView.match(context, match, () -> {});
        layout(root, 380, 180);
        assertTextEndReachable(root, context.getString(R.string.same_progress_message));
    }

    @Test
    public void staminaHintRemainsReadableInShortWindow() {
        RuntimeEnvironment.setFontScale(1.8f);
        Context context = RuntimeEnvironment.getApplication();
        StaminaGaugeDetector.Result stamina = new StaminaGaugeDetector.Result(
                61, 61, StaminaGaugeDetector.Direction.NONE, null, 1);
        ViewGroup root = (ViewGroup) OverlayResultView.stamina(context, stamina, () -> {});
        layout(root, 380, 170);
        assertTextEndReachable(root, context.getString(R.string.stamina_hint));
    }

    private static void assertTextEndReachable(ViewGroup root, String text) {
        ScrollView scroll = findScroll(root);
        assertNotNull("Every overflowing result body needs a scroll viewport", scroll);
        assertViewportInsidePanel(root, scroll);
        assertTrue("Long content must scroll", scroll.canScrollVertically(1));
        scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
        assertFalse(scroll.canScrollVertically(1));
        TextView message = findText(root, text);
        assertNotNull(message);
        Rect bounds = boundsInRoot(root, message);
        Rect viewport = boundsInRoot(root, scroll);
        int lastLine = message.getLayout().getLineCount() - 1;
        int lineTop = bounds.top + message.getTotalPaddingTop() + message.getLayout().getLineTop(lastLine);
        int lineBottom = bounds.top + message.getTotalPaddingTop() + message.getLayout().getLineBottom(lastLine);
        assertTrue("Last line must be above the viewport bottom", lineBottom <= viewport.bottom);
        assertTrue("Last line must be below the fixed header", lineTop >= viewport.top);
    }

    private static ViewGroup result(String success, String failure, Runnable close) {
        Context context = RuntimeEnvironment.getApplication();
        JourneyModels.Outcome outcome = new JourneyModels.Outcome("", "", "", success, failure);
        JourneyModels.Choice first = new JourneyModels.Choice("First synthetic choice", List.of(outcome));
        JourneyModels.Choice last = new JourneyModels.Choice("Last synthetic choice", List.of(
                new JourneyModels.Outcome("", "", "", "Final reward line", "")));
        JourneyModels.Event event = new JourneyModels.Event("Synthetic event", "", List.of(first, last));
        JourneyModels.Match match = new JourneyModels.Match(event, 1, 1, 1, true, false,
                List.of(), List.of(), List.of(1.0, 1.0));
        StaminaGaugeDetector.Result stamina = new StaminaGaugeDetector.Result(
                61, 61, StaminaGaugeDetector.Direction.NONE, null, 1);
        return (ViewGroup) OverlayResultView.match(context, match, "", stamina, close);
    }

    private static void layout(View root, int width, int maxHeight) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
    }

    private static void assertViewportInsidePanel(ViewGroup root, ScrollView scroll) {
        ViewGroup panel = (ViewGroup) scroll.getParent();
        assertTrue("Viewport extends below the visible panel: " + scroll.getBottom()
                        + " > " + (panel.getHeight() - panel.getPaddingBottom()),
                scroll.getBottom() <= panel.getHeight() - panel.getPaddingBottom());
        assertTrue(boundsInRoot(root, scroll).bottom <= root.getHeight() - root.getPaddingBottom());
    }

    private static void assertLastResultReachable(ViewGroup root, ScrollView scroll) {
        scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
        assertFalse("The scroll should have reached its end", scroll.canScrollVertically(1));
        TextView last = findText(root, "Final reward line");
        Rect lastBounds = boundsInRoot(root, last);
        Rect viewport = boundsInRoot(root, scroll);
        assertTrue("Final reward must be visible at the end", lastBounds.top >= viewport.top);
        assertTrue("Final reward must fit inside the viewport", lastBounds.bottom <= viewport.bottom);
        assertTrue("Final reward must fit inside the actual window", lastBounds.bottom <= root.getHeight());
    }

    private static void swipeUp(ViewGroup root, ScrollView scroll) {
        Rect viewport = boundsInRoot(root, scroll);
        long time = SystemClock.uptimeMillis();
        float start = viewport.top + viewport.height() * 0.85f;
        dispatch(root, time, time, MotionEvent.ACTION_DOWN, viewport.centerX(), start);
        for (int i = 1; i <= 6; i++) {
            dispatch(root, time, time + i * 20, MotionEvent.ACTION_MOVE, viewport.centerX(),
                    start - viewport.height() * 0.1f * i);
        }
        dispatch(root, time, time + 140, MotionEvent.ACTION_CANCEL, viewport.centerX(),
                start - viewport.height() * 0.6f);
    }

    private static void dispatch(View root, long downTime, long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, time, action, x, y, 0);
        try {
            assertTrue("Result window must consume its touch gestures", root.dispatchTouchEvent(event));
        } finally {
            event.recycle();
        }
    }

    private static Rect boundsInRoot(ViewGroup root, View child) {
        Rect bounds = new Rect(child.getScrollX(), child.getScrollY(),
                child.getScrollX() + child.getWidth(), child.getScrollY() + child.getHeight());
        root.offsetDescendantRectToMyCoords(child, bounds);
        return bounds;
    }

    private static ScrollView findScroll(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ScrollView scroll = findScroll(group.getChildAt(i));
                if (scroll != null) return scroll;
            }
        }
        return null;
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
