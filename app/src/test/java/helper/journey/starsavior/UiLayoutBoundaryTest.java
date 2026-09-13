package helper.journey.starsavior;

import static helper.journey.starsavior.UiTestSupport.*;
import static org.junit.Assert.*;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.LocaleList;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35}, qualifiers = "w960dp-h360dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class UiLayoutBoundaryTest {
    @Before public void resetFontScale() { RuntimeEnvironment.setFontScale(1f); }

    @Test public void localizedOverlaysKeepFullTitlesAndFinalLinesReachable() {
        for (GameLanguage language : GameLanguage.values()) {
            for (float font : new float[]{1f, 1.5f, 2f}) {
                RuntimeEnvironment.setFontScale(font);
                Configuration config = new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());
                config.setLocales(new LocaleList(language.locale()));
                Context context = RuntimeEnvironment.getApplication().createConfigurationContext(config);
                for (int[] size : new int[][]{{460, 300}, {380, 240}, {320, 180}}) {
                    for (String kind : List.of("match-short", "match-long", "stamina", "menu", "error", "info")) {
                        String title = kind.equals("match-long")
                                ? language.eventHeader + " Synthetic expedition title with several exceptionally long descriptions"
                                : "Test";
                        String last = "Synthetic detail\n".repeat(15) + "Final synthetic line";
                        ViewGroup root;
                        if (kind.startsWith("match")) {
                            JourneyModels.Outcome outcome = new JourneyModels.Outcome("", "", "", last, "");
                            JourneyModels.Event event = new JourneyModels.Event(title, "", List.of(new JourneyModels.Choice("Test choice", List.of(outcome))));
                            JourneyModels.Match match = new JourneyModels.Match(event, 1, 1, 1, true, false, List.of(), List.of(), List.of(1.0));
                            root = (ViewGroup) OverlayResultView.match(context, match, () -> {});
                        } else if (kind.equals("stamina")) {
                            root = (ViewGroup) OverlayResultView.stamina(context,
                                    new StaminaGaugeDetector.Result(61, 61, StaminaGaugeDetector.Direction.NONE, null, 1), () -> {});
                            last = context.getString(R.string.stamina_hint);
                        } else if (kind.equals("menu")) {
                            root = (ViewGroup) OverlayResultView.controls(context, () -> {}, () -> {}, () -> {});
                            last = context.getString(R.string.cancel);
                        } else if (kind.equals("error")) {
                            root = (ViewGroup) OverlayResultView.error(context, context.getString(R.string.recognition_failed), last, List.of(), () -> {});
                        } else {
                            root = (ViewGroup) OverlayResultView.info(context, context.getString(R.string.update_database), last, () -> {});
                        }
                        layout(root, size[0], size[1]);
                        String description = language.tag + " font=" + font + " " + size[0] + "x" + size[1] + " " + kind;
                        assertLastLineReachable(root, text(root, last), description);
                        if (kind.equals("match-long")) {
                            TextView fullTitle = text(scroll(root).getChildAt(0), title);
                            assertNotNull(description + ": full title must be available", fullTitle);
                            assertEquals(description, View.VISIBLE, fullTitle.getVisibility());
                            assertLastLineReachable(root, fullTitle, description + " full title");
                        }
                        TextView close = text(root, "×");
                        Rect closeBounds = bounds(root, close);
                        assertTrue(description, closeBounds.top >= 0 && closeBounds.bottom <= root.getHeight());
                        assertTrue(description + ": close icon must not be cropped by font scaling",
                                close.getLayout().getHeight() <= close.getHeight());
                        assertTrue(close.performClick());
                    }
                }
            }
        }
    }

    private static void assertLastLineReachable(ViewGroup root, TextView end, String description) {
        assertNotNull(description, end);
        ScrollView viewport = scroll(root);
        Rect visible = bounds(root, viewport);
        int line = end.getLayout().getLineCount() - 1;
        Rect rect = bounds(root, end);
        int bottom = rect.top + end.getTotalPaddingTop() + end.getLayout().getLineBottom(line);
        viewport.scrollBy(0, bottom - visible.bottom);
        rect = bounds(root, end);
        int top = rect.top + end.getTotalPaddingTop() + end.getLayout().getLineTop(line);
        if (top < visible.top) viewport.scrollBy(0, top - visible.top);
        rect = bounds(root, end);
        top = rect.top + end.getTotalPaddingTop() + end.getLayout().getLineTop(line);
        bottom = rect.top + end.getTotalPaddingTop() + end.getLayout().getLineBottom(line);
        assertTrue(description + ": line " + top + ".." + bottom + " outside " + visible,
                visible.height() > 0 && top >= visible.top && bottom <= visible.bottom && visible.bottom <= root.getHeight());
    }

    @Test public void recoveryCanScrollToCopyActionAndCopyTheActualError() throws Exception {
        for (float font : new float[]{1f, 2f}) {
            RuntimeEnvironment.setFontScale(font);
            for (int repeats : new int[]{2, 60}) {
                MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
                try {
                    String message = "Synthetic startup failure details. ".repeat(repeats);
                    call(activity, "showStartupRecovery", new Class[]{Throwable.class}, new IllegalStateException(message));
                    ViewGroup content = activity.findViewById(android.R.id.content);
                    ViewGroup root = (ViewGroup) content.getChildAt(0);
                    exactLayout(root, 460, 300);
                    ScrollView scroller = scroll(root);
                    assertNotNull(scroller);
                    scroller.scrollTo(0, scroller.getChildAt(0).getHeight());
                    TextView copy = text(root, activity.getString(R.string.copy_error));
                    Rect button = bounds(root, copy);
                    assertTrue(button.height() >= copy.getLineHeight() && button.top >= 0 && button.bottom <= root.getHeight());
                    assertTrue(copy.performClick());
                    ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
                    assertTrue(clipboard.getPrimaryClip().getItemAt(0).getText().toString().contains(message));
                } finally { activity.onDestroy(); }
            }
        }
    }

    @Test @Config(sdk = 35)
    public void systemInsetsKeepBothEndsReadableAndCanChangeWithoutAccumulating() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        try {
            ViewGroup root = (ViewGroup) call(activity, "buildContent", new Class[]{});
            WindowInsets landscape = new WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 24, 0, 48))
                    .setInsets(WindowInsets.Type.displayCutout(), Insets.of(56, 0, 0, 0)).build();
            for (int repeat = 0; repeat < 2; repeat++) {
                root.dispatchApplyWindowInsets(landscape);
                exactLayout(root, 960, 360);
                ScrollView scroller = scroll(root);
                scroller.scrollTo(0, 0);
                Rect first = bounds(root, text(root, activity.getString(R.string.app_eyebrow)));
                scroller.scrollTo(0, scroller.getChildAt(0).getHeight());
                Rect last = bounds(root, text(root, activity.getString(R.string.disclaimer)));
                assertEquals(56 + 22, first.left);
                assertEquals(24 + 28, first.top);
                assertTrue(last.bottom <= 360 - 48);
            }
            WindowInsets portrait = new WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 24, 0, 24))
                    .setInsets(WindowInsets.Type.displayCutout(), Insets.of(0, 56, 0, 0)).build();
            root.dispatchApplyWindowInsets(portrait);
            exactLayout(root, 360, 800);
            scroll(root).scrollTo(0, 0);
            Rect first = bounds(root, text(root, activity.getString(R.string.app_eyebrow)));
            assertEquals(22, first.left);
            assertEquals(56 + 28, first.top);
        } finally { activity.onDestroy(); }
    }
}
