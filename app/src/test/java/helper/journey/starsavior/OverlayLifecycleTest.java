package helper.journey.starsavior;

import static helper.journey.starsavior.UiTestSupport.*;
import static org.junit.Assert.*;
import android.app.Activity;
import android.graphics.Bitmap;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowSettings;
import org.robolectric.shadow.api.Shadow;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.CancellationTokenSource;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognizer;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35}, qualifiers = "w960dp-h360dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class OverlayLifecycleTest {
    private final List<View> added = new ArrayList<>();
    private OverlayCaptureService service() throws Exception {
        ShadowSettings.setCanDrawOverlays(true);
        OverlayCaptureService service = Robolectric.buildService(OverlayCaptureService.class).get();
        WindowManager real = service.getSystemService(WindowManager.class);
        WindowManager tracked = (WindowManager)Proxy.newProxyInstance(WindowManager.class.getClassLoader(),
                new Class[]{WindowManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("addView")) { added.add((View)args[0]); return null; }
                    if (method.getName().equals("removeView") || method.getName().equals("removeViewImmediate")) {
                        added.remove(args[0]); return null;
                    }
                    return method.invoke(real, args);
                });
        set(service, "windowManager", tracked);
        return service;
    }

    @Test public void pendingBubbleMustNotReappearAfterServiceStop() throws Exception {
        OverlayCaptureService service = service();
        call(service, "showBubble", new Class[]{});
        service.onDestroy();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("Stopped service added " + added.size() + " orphan overlay window(s)", added.isEmpty());
    }

    @Test public void pendingControlMenuDoesNotReappearAfterServiceStop() throws Exception {
        OverlayCaptureService service = service();
        call(service, "showControlMenu", new Class[]{});
        service.onDestroy();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(added.isEmpty());
    }

    @Test public void revokedOverlayPermissionPreventsQueuedBubble() throws Exception {
        OverlayCaptureService service = service();
        call(service, "showBubble", new Class[]{});
        ShadowSettings.setCanDrawOverlays(false);
        try {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(added.isEmpty());
        } finally { service.onDestroy(); }
    }

    @Test public void oldLanguageInfoIsDiscardedBeforeRendering() throws Exception {
        OverlayCaptureService service = service();
        call(service, "showInfo", new Class[]{String.class, String.class}, "Old language", "Synthetic update");
        GameLanguage current = (GameLanguage)get(service, "language");
        set(service, "language", current == GameLanguage.JAPANESE ? GameLanguage.ENGLISH : GameLanguage.JAPANESE);
        try {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(get(service, "resultView"));
        } finally { service.onDestroy(); }
    }

    @Test public void invalidatedStaminaResultIsDiscardedBeforeRendering() throws Exception {
        OverlayCaptureService service = service();
        CaptureSessionStateMachine session = (CaptureSessionStateMachine)get(service, "captureSession");
        set(service, "mediaProjection", Shadow.newInstanceOf(MediaProjection.class));
        set(service, "captureActive", true);
        session.activate();
        int old = session.generation();
        session.waitForPermission();
        try {
            call(service, "showStamina", new Class[]{int.class, StaminaGaugeDetector.Result.class}, old,
                    new StaminaGaugeDetector.Result(61, 61, StaminaGaugeDetector.Direction.NONE, null, 1));
            assertNull(get(service, "resultView"));
        } finally { service.onDestroy(); }
    }

    @Test public void captureErrorMustNotReappearAfterProjectionSessionEnds() throws Exception {
        OverlayCaptureService service = service();
        CaptureSessionStateMachine session = (CaptureSessionStateMachine)get(service, "captureSession");
        // Only its non-null identity is used in this UI-session test; no capture binder is invoked.
        MediaProjection projection = Shadow.newInstanceOf(MediaProjection.class);
        assertNotNull(projection);
        set(service, "mediaProjection", projection);
        set(service, "captureActive", true);
        session.activate();
        assertEquals(Boolean.TRUE, call(service, "isProjectionSessionActive", new Class[]{int.class}, session.generation()));
        call(service, "captureFailed", new Class[]{int.class, String.class, List.class},
                session.generation(), "Synthetic old capture error", List.of());
        // Model the projection-stop callback queued between error validation and rendering.
        new Handler(Looper.getMainLooper()).post(() -> {
            session.waitForPermission();
            try {
                set(service, "captureActive", false);
                set(service, "mediaProjection", null);
                call(service, "dismissResult", new Class[]{});
            } catch (Exception error) { throw new AssertionError(error); }
        });
        try {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull("Old error window appeared after the projection was invalidated", get(service, "resultView"));
        } finally { service.onDestroy(); }
    }

    @Test public void captureGrantMustNotLaunchUiFromDestroyedActivity() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        call(activity, "buildContent", new Class[]{});
        call(activity, "onActivityResult", new Class[]{int.class, int.class, Intent.class}, 1002, Activity.RESULT_OK, new Intent());
        activity.onDestroy();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(650));
        assertNull("Destroyed activity displayed the game-launch dialog", ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test public void settingsReturnMustNotRequestCaptureFromDestroyedActivity() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        call(activity, "buildContent", new Class[]{});
        ShadowSettings.setCanDrawOverlays(true);
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        set(activity, "continueAfterOverlaySettings", true);
        activity.onResume();
        activity.onDestroy();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertNull("Destroyed activity opened a new screen-capture permission request",
                Shadows.shadowOf(activity).getNextStartedActivityForResult());
    }

    @Test public void lateRecognitionSuccessMustNotCrashAfterServiceStop() throws Exception {
        completeRecognition("success", true);
    }

    @Test public void lateRecognitionFailureMustNotCrashAfterServiceStop() throws Exception {
        completeRecognition("failure", true);
    }

    @Test public void canceledRecognitionReleasesImagesAfterStop() throws Exception {
        completeRecognition("cancel", true);
    }

    @Test public void engineCloseDuringProcessUsesFailureCleanup() throws Exception {
        completeRecognition("close-race", true);
    }

    @Test public void activeRecognitionFailureStillShowsStaminaAndReleasesImages() throws Exception {
        completeRecognition("failure", false);
    }

    @Test public void activeCancellationEndsTheCaptureAndShowsRecovery() throws Exception {
        completeRecognition("cancel", false);
    }

    @Test public void activeCaptureGrantStillStartsTheServiceAndGameFlow() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        try {
            call(activity, "buildContent", new Class[]{});
            call(activity, "onActivityResult", new Class[]{int.class, int.class, Intent.class},
                    1002, Activity.RESULT_OK, new Intent());
            Intent started = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
            assertNotNull(started);
            assertEquals(OverlayCaptureService.ACTION_START, started.getAction());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(650));
            if (BuildConfig.BUNDLED_TEST_DATABASE) {
                assertTrue(Shadows.shadowOf(activity).isTaskMovedToBack());
                assertNull(ShadowAlertDialog.getLatestAlertDialog());
            } else {
                assertFalse(Shadows.shadowOf(activity).isTaskMovedToBack());
                assertNotNull(ShadowAlertDialog.getLatestAlertDialog());
            }
        } finally { activity.onDestroy(); }
    }

    private void completeRecognition(String completion, boolean stop) throws Exception {
        com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(RuntimeEnvironment.getApplication());
        OverlayCaptureService service = service();
        CaptureSessionStateMachine session = (CaptureSessionStateMachine)get(service, "captureSession");
        set(service, "mediaProjection", Shadow.newInstanceOf(MediaProjection.class));
        set(service, "captureActive", true);
        session.activate();
        assertTrue(session.beginCapture());
        CancellationTokenSource cancellation = new CancellationTokenSource();
        TaskCompletionSource<Text> pending = new TaskCompletionSource<>(cancellation.getToken());
        TextRecognizer recognizer = (TextRecognizer)Proxy.newProxyInstance(TextRecognizer.class.getClassLoader(),
                new Class[]{TextRecognizer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("process")) {
                        if (completion.equals("close-race")) {
                            service.onDestroy();
                            throw new IllegalStateException("Synthetic engine closed during process");
                        }
                        return pending.getTask();
                    }
                    if (method.getName().equals("close")) return null;
                    throw new UnsupportedOperationException(method.toString());
                });
        set(service, "recognizer", recognizer);
        Bitmap event = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888);
        Bitmap choices = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888);
        Bitmap full = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
        call(service, "recognizeRegions", new Class[]{Bitmap.class, Bitmap.class, Bitmap.class, int.class, StaminaGaugeDetector.Result.class},
                event, choices, full, session.generation(),
                new StaminaGaugeDetector.Result(61, 61, StaminaGaugeDetector.Direction.NONE, null, 1));
        if (stop && !completion.equals("close-race")) service.onDestroy();
        try {
            // Actual Google Tasks listener dispatch, with only the OCR engine replaced by a deferred task.
            if (completion.equals("success")) pending.setResult(null);
            else if (completion.equals("failure")) pending.setException(new IllegalStateException("Synthetic delayed OCR failure"));
            else if (completion.equals("cancel")) cancellation.cancel();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (!stop) {
                ((ExecutorService) get(service, "worker")).submit(() -> {}).get(5, TimeUnit.SECONDS);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertNotNull("Active session must still display a result or recovery", get(service, "resultView"));
                assertEquals(CaptureSessionStateMachine.Phase.ACTIVE, session.phase());
            }
            assertTrue("Late completion must release the event bitmap", event.isRecycled());
            assertTrue("Late completion must release the choices bitmap", choices.isRecycled());
            assertTrue("Late completion must release the full bitmap", full.isRecycled());
        } finally {
            if (!stop) service.onDestroy();
            event.recycle(); choices.recycle(); full.recycle();
        }
    }
}
