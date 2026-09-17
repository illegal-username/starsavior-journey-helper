package helper.journey.starsavior;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static helper.journey.starsavior.UiTestSupport.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35}, qualifiers = "w960dp-h360dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ItemEventSearchViewTest {
    static void tapItem(TextView view) {
        Spanned text = (Spanned) view.getText();
        text.getSpans(0, text.length(), ClickableSpan.class)[0].onClick(view);
    }

    @Test public void primaryItemActionsSearchTheSelectedItemAndLeaveDetailsOpen() {
        Context context = RuntimeEnvironment.getApplication();
        JourneyModels.Data data = ItemEventSearchTest.data();
        ItemDetails.Item[] searched = {null};
        View root = OverlayResultView.match(context, ItemEventSearchTest.match(data), "", null, Set.of("a"), () -> {},
                item -> searched[0] = item);
        tapItem(text(root, ItemEventSearchTest.ITEM.name));
        text(root, context.getString(R.string.item_search_events)).performClick();
        assertSame(ItemEventSearchTest.ITEM, searched[0]);
        assertNotNull(text(root, ItemEventSearchTest.ITEM.description));
        View raid = RaidResultView.render(context, data.raids,
                new RaidModels.Match(true, data.raids.events, 0, 0, true, false), null, () -> {}, item -> searched[0] = item);
        searched[0] = null;
        tapItem(text(raid, ItemEventSearchTest.ITEM.name));
        text(raid, context.getString(R.string.item_search_events)).performClick();
        assertSame(ItemEventSearchTest.ITEM, searched[0]);
    }

    @Test public void allJourneyAndRaidResultsStartExpandedAndCollapseIndependently() {
        Context context = RuntimeEnvironment.getApplication();
        JourneyModels.Data data = ItemEventSearchTest.data();
        boolean[] closed = {false};
        View root = ItemEventSearchView.render(context, ItemEventSearchTest.ITEM,
                ItemEventSearch.forItem(data, ItemEventSearchTest.ITEM.id), data.raids, () -> closed[0] = true);
        assertShown(text(root, "1. Take the keepsake"));
        assertShown(text(root, "1. Choose"));
        assertShown(text(root, "Coin +5 · Reward +3"));
        View laterCard = (View) text(root, "▾ Later event").getParent();
        TextView condition = text(laterCard, ItemEventSearchTest.ITEM.name);
        tapItem(condition);
        assertShown(text(laterCard, ItemEventSearchTest.ITEM.description));
        assertNull(text(root, context.getString(R.string.item_search_events)));
        assertEquals(1, scrollCount(root));
        text(root, "▾ Later event").performClick();
        assertEquals(View.GONE, ((View) text(root, "1. Choose").getParent()).getVisibility());
        assertShown(text(root, "1. Take the keepsake"));
        assertShown(text(root, "Coin +5 · Reward +3"));
        text(root, "▸ Later event").performClick();
        assertShown(text(laterCard, ItemEventSearchTest.ITEM.description));
        tapItem(condition);
        assertNull(text(laterCard, ItemEventSearchTest.ITEM.description));
        text(root, "×").performClick();
        assertTrue(closed[0]);

        View empty = ItemEventSearchView.render(context, ItemEventSearchTest.ITEM,
                ItemEventSearch.forItem(data, "absent"), data.raids, () -> {});
        assertNotNull(text(empty, context.getString(R.string.event_search_empty)));
    }

    @Test public void allExpandedResultsRemainReachableAtLargeFontAndNarrowWidthsInEveryLanguage() {
        JourneyModels.Data data = ItemEventSearchTest.data();
        try {
            RuntimeEnvironment.setFontScale(1.8f);
            for (GameLanguage language : GameLanguage.values()) {
                Context context = localized(language);
                ViewGroup root = (ViewGroup) ItemEventSearchView.render(context, ItemEventSearchTest.ITEM,
                        ItemEventSearch.forItem(data, ItemEventSearchTest.ITEM.id), data.raids, () -> {});
                View laterCard = (View) text(root, "▾ Later event").getParent();
                tapItem(text(laterCard, ItemEventSearchTest.ITEM.name));
                for (int width : new int[]{320, 460}) {
                    layout(root, width, 300);
                    ScrollView scroll = scroll(root);
                    assertTrue(scroll.canScrollVertically(1) || scroll.canScrollVertically(-1));
                    TextView last = text(root, context.getString(R.string.raid_mission_bonus, "Coin", 2, 3));
                    scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
                    assertTrue(bounds(root, last).bottom <= bounds(root, scroll).bottom);
                    assertTrue(bounds(root, last).top >= bounds(root, scroll).top);
                    assertTrue(bounds(root, text(root, "×")).bottom <= root.getHeight());
                    TextView description = text(laterCard, ItemEventSearchTest.ITEM.description);
                    int finalLine = description.getLineCount() - 1;
                    assertEquals(description.length(), description.getLayout().getLineEnd(finalLine));
                    assertEquals(0, description.getLayout().getEllipsisCount(finalLine));
                }
            }
        } finally { RuntimeEnvironment.setFontScale(1f); }
    }

    @Test public void renderReviewImagesWhenRequested() throws Exception {
        String directory = System.getenv("JOURNEY_SEARCH_SCREENSHOTS");
        if (directory == null || RuntimeEnvironment.getApiLevel() != 35) return;
        Context context = localized(GameLanguage.KOREAN);
        JourneyModels.Data data = ItemEventSearchTest.data();
        View source = OverlayResultView.match(context, ItemEventSearchTest.match(data), "", null, Set.of("a"), () -> {}, item -> {});
        tapItem(text(source, ItemEventSearchTest.ITEM.name));
        save(source, new File(directory, "item-search-entry.png"));
        scroll(source).scrollTo(0, 180);
        save(source, new File(directory, "item-search-action.png"));
        ViewGroup results = (ViewGroup) ItemEventSearchView.render(context, ItemEventSearchTest.ITEM,
                ItemEventSearch.forItem(data, ItemEventSearchTest.ITEM.id), data.raids, () -> {});
        save(results, new File(directory, "item-search-expanded-top.png"));
        scroll(results).scrollTo(0, scroll(results).getChildAt(0).getHeight());
        save(results, new File(directory, "item-search-expanded-bottom.png"));
        View laterCard = (View) text(results, "▾ Later event").getParent();
        TextView condition = text(laterCard, ItemEventSearchTest.ITEM.name);
        tapItem(condition);
        layout(results, 460, 520);
        scroll(results).scrollBy(0, bounds(results, condition).top - bounds(results, scroll(results)).top);
        save(results, new File(directory, "item-search-details.png"));
    }

    private static void assertShown(View view) {
        assertNotNull(view);
        assertEquals(View.VISIBLE, view.getVisibility());
        if (view.getParent() instanceof View) assertShown((View) view.getParent());
    }

    private static void save(View root, File file) throws Exception {
        layout(root, 460, 520);
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        file.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        finally { bitmap.recycle(); }
    }

    private static Context localized(GameLanguage language) {
        Context base = RuntimeEnvironment.getApplication();
        android.content.res.Configuration configuration = new android.content.res.Configuration(base.getResources().getConfiguration());
        configuration.setLocale(language.locale());
        return base.createConfigurationContext(configuration);
    }

    private static int scrollCount(View view) {
        int count = view instanceof ScrollView ? 1 : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) count += scrollCount(group.getChildAt(i));
        }
        return count;
    }
}
