package helper.journey.starsavior;

import static helper.journey.starsavior.UiTestSupport.*;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.LocaleList;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35}, qualifiers = "w960dp-h360dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class OverlayEffectRowLayoutTest {
    @Before public void resetFontScale() { RuntimeEnvironment.setFontScale(1f); }

    @Test public void twoLineEffectsFitInsideTheirRows() { assertExplicitLinesFit(2); }

    @Test public void threeLineEffectsFitInsideTheirRows() { assertExplicitLinesFit(3); }

    @Test public void manyLineEffectsFitInsideTheirRowsAndScroll() { assertExplicitLinesFit(12); }

    private static void assertExplicitLinesFit(int lines) {
        Context context = RuntimeEnvironment.getApplication();
        String condition = "Cost\n".repeat(lines - 1) + "Cost end";
        String success = "Reward\n".repeat(lines - 1) + "Reward end";
        String failure = "Penalty\n".repeat(lines - 1) + "Penalty end";
        ViewGroup root = result(context, condition, success, failure);
        layout(root, 380, 240);
        for (String value : List.of(condition, success, failure)) {
            TextView field = text(root, value);
            assertEquals("Explicit line count must be preserved", lines, field.getLineCount());
            assertTextFitsRow(root, field, "lines=" + lines);
            assertEndReachable(root, field);
        }
    }

    @Test public void wrappedEffectsFitForLocalizedBadgesLargeFontsAndResizes() {
        List<String> failures = new ArrayList<>();
        for (GameLanguage language : GameLanguage.values()) {
            for (float font : new float[]{1f, 1.5f, 2f}) {
                RuntimeEnvironment.setFontScale(font);
                Configuration config = new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());
                config.setLocales(new LocaleList(language.locale()));
                Context context = RuntimeEnvironment.getApplication().createConfigurationContext(config);
                // Synthetic multilingual text exercises automatic wrapping and fallback font metrics.
                String detail = "Synthetic reward description · 합성 보상 설명 · 合成の説明 · "
                        + "合成說明 · Điều kiện thử nghiệm · ";
                String condition = detail + "Cost -17";
                String success = detail.repeat(2) + "Reward +31";
                String failure = detail.repeat(3) + "Penalty -9";
                ViewGroup root = result(context, condition, success, failure);
                for (int width : new int[]{460, 320, 380}) {
                    layout(root, width, 240);
                    String description = language.tag + " font=" + font + " width=" + width;
                    for (String value : List.of(condition, success, failure)) {
                        TextView field = text(root, value);
                        try {
                            assertTrue(description + ": test text must wrap", field.getLineCount() >= 2);
                            assertTextFitsRow(root, field, description);
                            assertEndReachable(root, field);
                        } catch (AssertionError error) {
                            failures.add(error.getMessage());
                        }
                    }
                }
            }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    private static void assertTextFitsRow(ViewGroup root, TextView field, String description) {
        assertNotNull(description, field);
        int last = field.getLineCount() - 1;
        assertEquals(description + ": all text must be laid out", field.length(), field.getLayout().getLineEnd(last));
        assertEquals(description + ": no last-line ellipsis", 0, field.getLayout().getEllipsisCount(last));
        int lineBottom = field.getTotalPaddingTop() + field.getLayout().getLineBottom(last);
        assertTrue(description + ": text exceeds its own view", lineBottom <= field.getHeight() - field.getPaddingBottom());
        // A line inside the TextView may still be clipped by an undersized effect row.
        ViewGroup row = (ViewGroup) field.getParent();
        int bottom = field.getTop() + lineBottom;
        assertTrue(description + ": line bottom=" + bottom + " exceeds row height=" + row.getHeight()
                        + " (text top=" + field.getTop() + ", text height=" + field.getHeight() + ")",
                field.getTop() >= row.getPaddingTop() && bottom <= row.getHeight() - row.getPaddingBottom());
        for (int i = 0; i < row.getChildCount(); i++) {
            assertTrue(description + ": row child exceeds its parent",
                    row.getChildAt(i).getBottom() <= row.getHeight() - row.getPaddingBottom());
        }
        ViewGroup card = (ViewGroup) row.getParent();
        Rect insideCard = bounds(card, field);
        assertTrue(description + ": text exceeds its card",
                insideCard.top + lineBottom <= card.getHeight() - card.getPaddingBottom());
        int index = card.indexOfChild(row);
        if (index + 1 < card.getChildCount()) {
            assertTrue(description + ": text overlaps the next row",
                    insideCard.top + lineBottom <= card.getChildAt(index + 1).getTop());
        }
    }

    private static void assertEndReachable(ViewGroup root, TextView field) {
        ScrollView viewport = scroll(root);
        Rect visible = bounds(root, viewport);
        int last = field.getLineCount() - 1;
        int bottom = bounds(root, field).top + field.getTotalPaddingTop() + field.getLayout().getLineBottom(last);
        viewport.scrollBy(0, bottom - visible.bottom);
        int top = bounds(root, field).top + field.getTotalPaddingTop() + field.getLayout().getLineTop(last);
        bottom = bounds(root, field).top + field.getTotalPaddingTop() + field.getLayout().getLineBottom(last);
        assertTrue("Every effect's final line must be reachable by scrolling", top >= visible.top && bottom <= visible.bottom);
    }

    private static ViewGroup result(Context context, String condition, String success, String failure) {
        JourneyModels.Outcome outcome = new JourneyModels.Outcome("", "", condition, success, failure);
        JourneyModels.Choice choice = new JourneyModels.Choice("Synthetic choice", List.of(outcome));
        JourneyModels.Event event = new JourneyModels.Event("Synthetic event", "", List.of(choice));
        JourneyModels.Match match = new JourneyModels.Match(event, 1, 1, 1, true, false,
                List.of(), List.of(), List.of(1.0));
        return (ViewGroup) OverlayResultView.match(context, match, () -> {});
    }
}
