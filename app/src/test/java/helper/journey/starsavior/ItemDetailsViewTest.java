package helper.journey.starsavior;

import android.content.Context;
import android.graphics.Rect;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35}, qualifiers = "w960dp-h360dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ItemDetailsViewTest {
    @Test public void tappingMarkedNamesExpandsReplacesAndCollapsesInsideRewardColumn() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        JourneyModels.Data data = JourneyRepository.parseValidated(ItemDetailsTest.candidate(GameLanguage.ENGLISH).toString(), GameLanguage.ENGLISH);
        JourneyModels.Event event = data.events.get(0);
        JourneyModels.Match match = new JourneyModels.Match(event, 1, 1, 1, true, false, List.of(), List.of(), List.of());
        boolean[] dismissed = {false};
        FrameLayout root = (FrameLayout) OverlayResultView.match(context, match, () -> dismissed[0] = true);
        UiTestSupport.layout(root, 400, 250);
        TextView reward = UiTestSupport.text(root, event.choices.get(0).outcomes.get(0).success);
        ViewGroup column = (ViewGroup) reward.getParent();
        Spanned text = (Spanned) reward.getText();
        ClickableSpan[] spans = text.getSpans(0, text.length(), ClickableSpan.class);
        assertEquals(2, spans.length);
        assertEquals(0, text.getSpans(3, 9, ClickableSpan.class).length);
        ScrollView scroll = UiTestSupport.scroll(root);
        scroll.scrollTo(0, 40);
        int position = scroll.getScrollY();

        tap(reward, text.getSpanStart(spans[1]));
        UiTestSupport.layout(root, 400, 250);
        assertEquals(1, root.getChildCount());
        assertSame(scroll, UiTestSupport.scroll(root));
        assertEquals(position, scroll.getScrollY());
        assertEquals(2, column.getChildCount());
        assertNotNull(UiTestSupport.text(column, "Second description"));
        assertTrue(column.getChildAt(1).getTop() >= reward.getBottom());
        assertAncestorsVisible(reward);

        // A different item replaces only this row's details; the same item toggles them off.
        tap(reward, text.getSpanStart(spans[0]));
        assertEquals(2, column.getChildCount());
        assertNull(UiTestSupport.text(column, "Second description"));
        assertNotNull(UiTestSupport.text(column, "Long description"));
        tap(reward, text.getSpanStart(spans[0]));
        assertEquals(1, column.getChildCount());
        tap(reward, text.getSpanStart(spans[1]));

        // Conditions and rewards may stay expanded independently, including identical names.
        TextView condition = UiTestSupport.text(root, event.choices.get(0).outcomes.get(0).condition);
        firstLink(condition).onClick(condition);
        ViewGroup conditionColumn = (ViewGroup) condition.getParent();
        assertNotNull(UiTestSupport.text(conditionColumn, "First description"));
        assertNotNull(UiTestSupport.text(column, "Second description"));
        TextView close = UiTestSupport.text(column, "×");
        assertEquals(context.getString(R.string.item_close_details), close.getContentDescription());
        close.performClick();
        assertEquals(1, column.getChildCount());
        assertNotNull(UiTestSupport.text(conditionColumn, "First description"));
        UiTestSupport.text(conditionColumn, "×").performClick();
        UiTestSupport.layout(root, 400, 250);
        assertEquals(position, scroll.getScrollY());
        assertFalse(dismissed[0]);
    }

    @Test public void raidCoinPrefixKeepsSuccessAndFailureLinksOnTheirItemNames() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        JourneyModels.Data data = JourneyRepository.parseValidated(ItemDetailsTest.candidate(GameLanguage.ENGLISH).toString(), GameLanguage.ENGLISH);
        RaidModels.Event event = data.raids.events.get(0);
        FrameLayout root = (FrameLayout) RaidResultView.render(context, data.raids,
                new RaidModels.Match(true, List.of(event), 1, 0, true, false), null, () -> {});
        String reward = data.raids.coinLabel + " +" + event.options.get(0).victoryCoin + " · " + event.options.get(0).success;
        TextView view = UiTestSupport.text(root, reward);
        Spanned text = (Spanned) view.getText();
        ClickableSpan[] spans = text.getSpans(0, text.length(), ClickableSpan.class);
        assertEquals("Sample tonic", text.subSequence(text.getSpanStart(spans[0]), text.getSpanEnd(spans[0])).toString());
        spans[0].onClick(view);
        ViewGroup column = (ViewGroup) view.getParent();
        assertNotNull(UiTestSupport.text(column, "Long description"));
        TextView failure = UiTestSupport.text(root, "Sample");
        firstLink(failure).onClick(failure);
        assertNotNull(UiTestSupport.text((View) failure.getParent(), "First description"));
        assertNotNull(UiTestSupport.text(column, "Long description"));
        assertAncestorsVisible(view);
        assertEquals(1, root.getChildCount());
        UiTestSupport.text(column, "×").performClick();
        assertNotNull(UiTestSupport.text((View) failure.getParent(), "First description"));
    }

    @Test public void longDetailsAtLargeFontUseExistingScrollWithoutClippingFollowingRewards() {
        RuntimeEnvironment.setFontScale(1.8f);
        try {
            Context context = RuntimeEnvironment.getApplication();
            LinearLayout panel = OverlayResultView.panel(context);
            OverlayResultView.addHeader(context, panel, "Synthetic rewards", () -> {});
            ItemDetails.Item item = new ItemDetails.Item("x", "Long sample item", "Long description. ".repeat(150), "Last effect +7");
            OverlayResultView.addEffect(context, panel, "Reward", item.name, Ui.GREEN,
                    List.of(new ItemDetails.Link(item, 0, item.name.length())), 0);
            OverlayResultView.addEffect(context, panel, "Next", "Following reward +3", Ui.GREEN);
            FrameLayout root = OverlayResultView.wrap(context, panel);
            TextView reward = UiTestSupport.text(root, item.name);
            ScrollView scroll = UiTestSupport.scroll(root);
            firstLink(reward).onClick(reward);
            ViewGroup column = (ViewGroup) reward.getParent();
            View detail = column.getChildAt(1);
            assertNull(UiTestSupport.scroll(detail));
            TextView description = UiTestSupport.text(detail, item.description);
            TextView effect = UiTestSupport.text(detail, item.effect);
            TextView following = UiTestSupport.text(root, "Following reward +3");
            assertTrue(description.getTextSize() < reward.getTextSize());
            for (int width : new int[]{380, 320, 460}) {
                UiTestSupport.layout(root, width, 300);
                assertSame(scroll, UiTestSupport.scroll(root));
                assertAncestorsVisible(reward);
                assertTrue(scroll.canScrollVertically(-1) || scroll.canScrollVertically(1));
                assertTrue(UiTestSupport.bounds(root, detail).bottom <= UiTestSupport.bounds(root, following).top);
                assertTrue(detail.getRight() <= column.getWidth());
                assertTrue(UiTestSupport.text(detail, "×").getWidth() > 0);
                for (TextView field : List.of(description, effect, following)) {
                    int last = field.getLineCount() - 1;
                    assertEquals(field.length(), field.getLayout().getLineEnd(last));
                    assertEquals(0, field.getLayout().getEllipsisCount(last));
                    int lineBottom = field.getTotalPaddingTop() + field.getLayout().getLineBottom(last);
                    assertTrue(lineBottom <= field.getHeight() - field.getPaddingBottom());
                    Rect viewport = UiTestSupport.bounds(root, scroll);
                    int bottom = UiTestSupport.bounds(root, field).top + lineBottom;
                    scroll.scrollBy(0, bottom - viewport.bottom);
                    Rect bounds = UiTestSupport.bounds(root, field);
                    assertTrue(bounds.top + field.getTotalPaddingTop() + field.getLayout().getLineTop(last) >= viewport.top);
                    assertTrue(bounds.top + lineBottom <= viewport.bottom);
                }
            }
            firstLink(reward).onClick(reward);
            UiTestSupport.layout(root, 380, 300);
            assertEquals(1, column.getChildCount());
            assertAncestorsVisible(following);
        } finally { RuntimeEnvironment.setFontScale(1.0f); }
    }

    @Test public void absentEffectAndAbsentDetailsStayCompactAndCanBeClosed() {
        Context context = RuntimeEnvironment.getApplication();
        for (String description : List.of("Description only", "")) {
            LinearLayout panel = new LinearLayout(context);
            ItemDetails.Item item = new ItemDetails.Item("x", "Sample", description, "");
            OverlayResultView.addEffect(context, panel, "Reward", item.name, Ui.GREEN,
                    List.of(new ItemDetails.Link(item, 0, item.name.length())), 0);
            TextView reward = UiTestSupport.text(panel, item.name);
            firstLink(reward).onClick(reward);
            ViewGroup column = (ViewGroup) reward.getParent();
            assertNull(UiTestSupport.text(column, context.getString(R.string.item_effect)));
            assertNotNull(UiTestSupport.text(column, description.isEmpty()
                    ? context.getString(R.string.item_details_unavailable) : description));
            UiTestSupport.text(column, "×").performClick();
            assertEquals(1, column.getChildCount());
        }
    }

    private static ClickableSpan firstLink(TextView view) {
        Spanned text = (Spanned) view.getText();
        return text.getSpans(0, text.length(), ClickableSpan.class)[0];
    }

    private static void assertAncestorsVisible(View view) {
        assertEquals(View.VISIBLE, view.getVisibility());
        if (view.getParent() instanceof View) assertAncestorsVisible((View) view.getParent());
    }

    private static void tap(TextView view, int offset) {
        int line = view.getLayout().getLineForOffset(offset);
        float x = view.getTotalPaddingLeft() + view.getLayout().getPrimaryHorizontal(offset) + 2;
        float y = view.getTotalPaddingTop() + (view.getLayout().getLineTop(line) + view.getLayout().getLineBottom(line)) / 2f;
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, x, y, 0);
        try { view.dispatchTouchEvent(down); view.dispatchTouchEvent(up); }
        finally { down.recycle(); up.recycle(); }
    }
}
