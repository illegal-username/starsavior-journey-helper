package helper.journey.starsavior;

import android.media.projection.MediaProjection;
import android.view.View;
import android.view.WindowManager;
import android.widget.ScrollView;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowSettings;
import static helper.journey.starsavior.UiTestSupport.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35}, qualifiers = "w960dp-h360dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ItemEventSearchLifecycleTest {
    private final List<View> windows = new ArrayList<>();
    private boolean failAdd;

    private OverlayCaptureService service() throws Exception {
        ShadowSettings.setCanDrawOverlays(true);
        OverlayCaptureService service = Robolectric.buildService(OverlayCaptureService.class).get();
        WindowManager real = service.getSystemService(WindowManager.class);
        WindowManager tracked = (WindowManager) Proxy.newProxyInstance(WindowManager.class.getClassLoader(), new Class[]{WindowManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addView")) {
                        if (failAdd) throw new IllegalStateException("Synthetic denied overlay");
                        windows.add((View) args[0]); return null;
                    }
                    if (method.getName().equals("removeView")) { windows.remove(args[0]); return null; }
                    return method.invoke(real, args);
                });
        set(service, "windowManager", tracked);
        set(service, "captureActive", true);
        set(service, "mediaProjection", Shadow.newInstanceOf(MediaProjection.class));
        CaptureSessionStateMachine session = (CaptureSessionStateMachine) get(service, "captureSession");
        session.activate();
        JourneyModels.Data data = ItemEventSearchTest.data();
        ((JourneyMatcherStore) get(service, "matcherStore")).reload(() -> data);
        call(service, "showMatch", new Class[]{int.class, JourneyModels.Match.class, String.class, StaminaGaugeDetector.Result.class, Set.class},
                session.generation(), ItemEventSearchTest.match(data), "", null, Set.of("a"));
        ItemEventSearchViewTest.tapItem(text((View) get(service, "resultView"), ItemEventSearchTest.ITEM.name));
        return service;
    }

    @Test public void searchCloseRestoresOriginalViewDetailsAndScrollThenWholeResultClosesTogether() throws Exception {
        OverlayCaptureService service = service();
        try {
            View original = (View) get(service, "resultView");
            layout(original, 400, 260);
            ScrollView scroll = scroll(original);
            scroll.scrollTo(0, 35);
            int position = scroll.getScrollY();
            text(original, service.getString(R.string.item_search_events)).performClick();
            View search = (View) get(service, "itemSearchView");
            assertNotNull(search);
            assertEquals(2, windows.size());
            assertEquals(View.GONE, original.getVisibility());
            text(original, service.getString(R.string.item_search_events)).performClick();
            assertSame(search, get(service, "itemSearchView"));
            text(search, "×").performClick();
            assertNull(get(service, "itemSearchView"));
            assertSame(original, get(service, "resultView"));
            assertEquals(View.VISIBLE, original.getVisibility());
            assertEquals(position, scroll.getScrollY());
            assertNotNull(text(original, ItemEventSearchTest.ITEM.description));
            assertEquals(1, windows.size());

            text(original, service.getString(R.string.item_search_events)).performClick();
            assertNotNull(get(service, "itemSearchView"));
            call(service, "dismissResult", new Class[]{});
            assertNull(get(service, "resultView"));
            assertNull(get(service, "itemSearchView"));
            assertTrue(windows.isEmpty());
        } finally { service.onDestroy(); }
    }

    @Test public void failedOrRevokedWindowOpenKeepsOriginalAndServiceStopRemovesBoth() throws Exception {
        OverlayCaptureService service = service();
        View original = (View) get(service, "resultView");
        try {
            for (boolean permission : new boolean[]{false, true}) {
                ShadowSettings.setCanDrawOverlays(permission);
                failAdd = permission;
                text(original, service.getString(R.string.item_search_events)).performClick();
                assertNull(get(service, "itemSearchView"));
                assertEquals(View.VISIBLE, original.getVisibility());
                assertEquals(1, windows.size());
            }
            failAdd = false;
            text(original, service.getString(R.string.item_search_events)).performClick();
            assertEquals(2, windows.size());
        } finally { service.onDestroy(); }
        assertNull(get(service, "resultView"));
        assertNull(get(service, "itemSearchView"));
        assertTrue(windows.isEmpty());
        text(original, service.getString(R.string.item_search_events)).performClick();
        assertTrue(windows.isEmpty());
    }

    @Test public void oldGenerationOrReplacedDatabaseCannotOpenStaleLookup() throws Exception {
        OverlayCaptureService service = service();
        try {
            View original = (View) get(service, "resultView");
            CaptureSessionStateMachine session = (CaptureSessionStateMachine) get(service, "captureSession");
            session.waitForPermission();
            text(original, service.getString(R.string.item_search_events)).performClick();
            assertNull(get(service, "itemSearchView"));
            session.activate();
            JourneyMatcherStore store = (JourneyMatcherStore) get(service, "matcherStore");
            JourneyModels.Data data = store.currentData();
            call(service, "showMatch", new Class[]{int.class, JourneyModels.Match.class, String.class, StaminaGaugeDetector.Result.class, Set.class},
                    session.generation(), ItemEventSearchTest.match(data), "", null, Set.of("a"));
            original = (View) get(service, "resultView");
            ItemEventSearchViewTest.tapItem(text(original, ItemEventSearchTest.ITEM.name));
            store.reload(ItemEventSearchTest::data);
            text(original, service.getString(R.string.item_search_events)).performClick();
            assertNull(get(service, "itemSearchView"));
            assertEquals(1, windows.size());
            // A database can also change between matching and posting the result to the UI.
            call(service, "showMatch", new Class[]{int.class, JourneyModels.Match.class, String.class, StaminaGaugeDetector.Result.class, Set.class},
                    session.generation(), ItemEventSearchTest.match(data), "", null, Set.of("a"));
            View stale = (View) get(service, "resultView");
            ItemEventSearchViewTest.tapItem(text(stale, ItemEventSearchTest.ITEM.name));
            assertNull(text(stale, service.getString(R.string.item_search_events)));
        } finally { service.onDestroy(); }
    }
}
