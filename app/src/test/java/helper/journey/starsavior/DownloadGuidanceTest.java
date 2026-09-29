package helper.journey.starsavior;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import static helper.journey.starsavior.UiTestSupport.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "en-rUS-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DownloadGuidanceTest {
    @Test public void promptsAreIndependentPerLanguageAndResetOnlyAfterInstallation() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        JourneyModels.Data english = JourneyRepository.load(context, GameLanguage.ENGLISH);
        JourneyModels.Data japanese = JourneyRepository.load(context, GameLanguage.JAPANESE);
        assertTrue(DatabaseDownloadPrompt.shouldShow(context, english));
        DatabaseDownloadPrompt.shown(context, english);
        assertFalse(DatabaseDownloadPrompt.shouldShow(context, english));
        assertTrue(DatabaseDownloadPrompt.shouldShow(context, japanese));
        DatabaseDownloadPrompt.installed(context, english);
        assertFalse(DatabaseDownloadPrompt.shouldShow(context, english));
        DatabaseDownloadPrompt.installed(context, english.withOrigin(JourneyModels.DatabaseOrigin.DOWNLOADED));
        assertTrue(DatabaseDownloadPrompt.shouldShow(context, english));
    }

    @Test public void rejectingPromptSurvivesRecreationAndInstalledDataDismissesIt() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class, new Intent()).get();
        MainActivity recreated = null;
        try {
            call(activity, "buildContent", new Class[]{});
            JourneyModels.Data example = JourneyRepository.load(activity, GameLanguage.ENGLISH);
            JourneyDatabaseManifest manifest = JourneyDatabaseManifest.parse(DatabaseTestData.manifest(
                    DatabaseTestData.json(GameLanguage.ENGLISH, "prompt", 20), 1));
            JourneyDatabaseUpdater.CheckResult available = JourneyDatabaseUpdater.CheckResult.classify(example, manifest, true);
            call(activity, "showDataSummary", new Class[]{JourneyModels.Data.class}, example);
            call(activity, "showUpdateCheckResult", new Class[]{JourneyDatabaseUpdater.CheckResult.class}, available);
            AlertDialog prompt = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(prompt); assertTrue(prompt.isShowing());
            prompt.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertFalse(prompt.isShowing());
            recreated = Robolectric.buildActivity(MainActivity.class, new Intent()).get();
            call(recreated, "buildContent", new Class[]{});
            call(recreated, "showDataSummary", new Class[]{JourneyModels.Data.class}, example);
            call(recreated, "showUpdateCheckResult", new Class[]{JourneyDatabaseUpdater.CheckResult.class}, available);
            assertSame(prompt, ShadowAlertDialog.getLatestAlertDialog());
            DatabaseDownloadPrompt.installed(activity, example.withOrigin(JourneyModels.DatabaseOrigin.DOWNLOADED));
            call(activity, "showUpdateCheckResult", new Class[]{JourneyDatabaseUpdater.CheckResult.class}, available);
            AlertDialog second = ShadowAlertDialog.getLatestAlertDialog(); assertTrue(second.isShowing());
            call(activity, "showDataSummary", new Class[]{JourneyModels.Data.class},
                    example.withOrigin(JourneyModels.DatabaseOrigin.DOWNLOADED));
            assertFalse(second.isShowing());
        } finally {
            activity.onDestroy(); if (recreated != null) recreated.onDestroy();
        }
    }

    @Test public void dismissedOrReplacedOverlayCannotStartDownloadFromOldButton() throws Exception {
        OverlayCaptureService service = Robolectric.buildService(OverlayCaptureService.class).get();
        try {
            // Any accidental scheduling from an obsolete button is a test failure, never a network request.
            ((java.util.concurrent.ExecutorService) get(service, "worker")).shutdown();
            JourneyModels.Data example = JourneyRepository.load(service, GameLanguage.ENGLISH);
            JourneyMatcherStore store = (JourneyMatcherStore) get(service, "matcherStore");
            store.reload(() -> example);
            set(service, "resultView", new View(service));
            Runnable old = (Runnable) call(service, "databaseDownloadAction", new Class[]{});
            assertNotNull(old);
            call(service, "dismissResult", new Class[]{});
            View replacement = new View(service);
            set(service, "resultView", replacement);
            Runnable current = (Runnable) call(service, "databaseDownloadAction", new Class[]{});
            old.run();
            assertSame(replacement, get(service, "resultView"));
            store.reload(() -> example.withOrigin(JourneyModels.DatabaseOrigin.DOWNLOADED));
            current.run();
            assertSame(replacement, get(service, "resultView"));
            assertFalse(((java.util.concurrent.atomic.AtomicBoolean) get(service, "databaseUpdating")).get());
        } finally { service.onDestroy(); }
    }

    @Test public void staminaAndFailureViewsKeepContentAndOfferExplicitDownload() {
        Context context = RuntimeEnvironment.getApplication();
        AtomicInteger downloads = new AtomicInteger();
        StaminaGaugeDetector.Result stamina = new StaminaGaugeDetector.Result(
                61, 61, StaminaGaugeDetector.Direction.NONE, null, 1);
        View view = OverlayResultView.stamina(context, stamina, () -> {}, downloads::incrementAndGet);
        assertNotNull(text(view, context.getString(R.string.stamina_label)));
        assertNotNull(text(view, context.getString(R.string.database_download_help)));
        assertEquals(0, downloads.get());
        text(view, context.getString(R.string.get_new_db)).performClick(); assertEquals(1, downloads.get());
        View error = OverlayResultView.error(context, "Synthetic", "No data", List.of(), () -> {}, downloads::incrementAndGet);
        assertNotNull(text(error, "No data"));
        text(error, context.getString(R.string.get_new_db)).performClick(); assertEquals(2, downloads.get());
        assertNull(text(OverlayResultView.stamina(context, stamina, () -> {}), context.getString(R.string.get_new_db)));
        layout(view, 320, 240); assertNotNull(scroll(view));
    }
}
