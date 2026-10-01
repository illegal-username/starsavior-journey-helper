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

    @Test public void recognitionFooterKeepsItsOriginalUntilResultCloses() throws Exception {
        OverlayCaptureService service = service();
        CaptureSessionStateMachine session = (CaptureSessionStateMachine)get(service, "captureSession");
        set(service, "mediaProjection", Shadow.newInstanceOf(MediaProjection.class));
        set(service, "captureActive", true);
        session.activate(); session.beginCapture();
        set(service, "reportGeneration", session.generation());
        CaptureJob job = (CaptureJob)call(service, "newCaptureJob", new Class[]{int.class}, session.generation());
        Bitmap full = job.own(Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888));
        call(service, "retainReportCapture", new Class[]{CaptureJob.class, Bitmap.class}, job, full);
        job.finish();
        try {
            call(service, "showStamina", new Class[]{int.class, StaminaGaugeDetector.Result.class},
                    session.generation(), new StaminaGaugeDetector.Result(61, 61, StaminaGaugeDetector.Direction.NONE, null, 1));
            View result = (View)get(service, "resultView");
            assertNotNull(result);
            View report = result.findViewWithTag("report_error");
            assertNotNull(report);
            android.view.ViewGroup body = (android.view.ViewGroup)report.getParent();
            assertSame(report, body.getChildAt(body.getChildCount() - 1));
            assertFalse(full.isRecycled());
            java.io.File directory = ReportAttachmentProvider.directory(service);
            int before = directory.list() == null ? 0 : directory.list().length;
            report.performClick();
            android.app.AlertDialog dialog = (android.app.AlertDialog)get(service, "reportDialog");
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(get(service, "reportDialog"));
            assertFalse(full.isRecycled());
            assertEquals(before, directory.list() == null ? 0 : directory.list().length);
            call(service, "dismissResult", new Class[]{});
            assertTrue(full.isRecycled());
            report.performClick();
            assertNull("Stale report action must not open a dialog", get(service, "reportDialog"));
        } finally { service.onDestroy(); }
    }

    @Test public void lateFrameCannotBeRetainedAfterItsResultIsDiscarded() throws Exception {
        OverlayCaptureService service = service();
        CaptureSessionStateMachine session = (CaptureSessionStateMachine)get(service, "captureSession");
        set(service, "mediaProjection", Shadow.newInstanceOf(MediaProjection.class));
        set(service, "captureActive", true);
        session.activate(); session.beginCapture();
        set(service, "reportGeneration", session.generation());
        CaptureJob job = (CaptureJob)call(service, "newCaptureJob", new Class[]{int.class}, session.generation());
        Bitmap full = job.own(Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888));
        try {
            call(service, "dismissResult", new Class[]{});
            call(service, "retainReportCapture", new Class[]{CaptureJob.class, Bitmap.class}, job, full);
            job.finish();
            assertTrue(full.isRecycled());
            assertNull(get(service, "reportCapture"));
        } finally { service.onDestroy(); }
    }

    @Test public void ocrErrorHasReportFooterAndOriginalButCaptureFailureHasNoStaleImage() throws Exception {
        OverlayCaptureService service = service();
        CaptureSessionStateMachine session = (CaptureSessionStateMachine)get(service, "captureSession");
        set(service, "mediaProjection", Shadow.newInstanceOf(MediaProjection.class));
        set(service, "captureActive", true);
        session.activate(); session.beginCapture();
        set(service, "reportGeneration", session.generation());
        CaptureJob job = (CaptureJob)call(service, "newCaptureJob", new Class[]{int.class}, session.generation());
        Bitmap full = job.own(Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888));
        call(service, "retainReportCapture", new Class[]{CaptureJob.class, Bitmap.class}, job, full);
        job.finish();
        try {
            call(service, "captureFailed", new Class[]{int.class, String.class, List.class}, session.generation(), "OCR failed", List.of());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(((View)get(service, "resultView")).findViewWithTag("report_error"));
            assertFalse(full.isRecycled());
            RecognitionDiagnostics previous = (RecognitionDiagnostics)get(service, "recognitionDiagnostics");
            assertEquals(40, previous.snapshot().getInt("width"));
            call(service, "dismissResult", new Class[]{});
            assertTrue(session.beginCapture()); session.finishCapture();
            call(service, "captureFailed", new Class[]{int.class, String.class, List.class}, session.generation(), "No frame", List.of());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(((View)get(service, "resultView")).findViewWithTag("report_error"));
            assertNull(get(service, "reportCapture"));
            RecognitionDiagnostics current = (RecognitionDiagnostics)get(service, "recognitionDiagnostics");
            assertNotSame(previous, current);
            assertFalse(current.snapshot().getBoolean("captureAvailable"));
            assertFalse(current.snapshot().has("width"));
        } finally { service.onDestroy(); }
    }

    @Test public void confirmedReportOpensEmailWithOriginalAttachmentAndEditableBody() throws Exception {
        completeReport(false);
    }

    @Test public void stopDuringEmailPreparationReleasesImageAndDoesNotLaunchOrLeaveAttachment() throws Exception {
        completeReport(true);
    }

    private void completeReport(boolean stop) throws Exception {
        OverlayCaptureService service = service();
        CaptureSessionStateMachine session = (CaptureSessionStateMachine)get(service, "captureSession");
        set(service, "mediaProjection", Shadow.newInstanceOf(MediaProjection.class));
        set(service, "captureActive", true);
        session.activate(); session.beginCapture();
        set(service, "reportGeneration", session.generation());
        CaptureJob job = (CaptureJob)call(service, "newCaptureJob", new Class[]{int.class}, session.generation());
        Bitmap full = job.own(Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888));
        full.eraseColor(android.graphics.Color.GREEN);
        call(service, "retainReportCapture", new Class[]{CaptureJob.class, Bitmap.class}, job, full);
        job.finish();
        org.robolectric.shadows.ShadowPackageManager manager = Shadows.shadowOf(service.getPackageManager());
        android.content.pm.ResolveInfo mail = new android.content.pm.ResolveInfo();
        mail.activityInfo = new android.content.pm.ActivityInfo();
        mail.activityInfo.packageName = "test.email"; mail.activityInfo.name = "Compose";
        mail.activityInfo.exported = true; mail.activityInfo.enabled = true;
        manager.addResolveInfoForIntent(new Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:")), mail);
        manager.addResolveInfoForIntent(new Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").setPackage("test.email"), mail);
        manager.addResolveInfoForIntent(new Intent(Intent.ACTION_SEND).setType("application/json").setPackage("test.email"), mail);
        ExecutorService emailWorker = (ExecutorService)get(service, "reportWorker");
        java.util.concurrent.CountDownLatch unblock = new java.util.concurrent.CountDownLatch(1);
        emailWorker.submit(() -> { try { unblock.await(5, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
        java.io.File directory = ReportAttachmentProvider.directory(service);
        int before = directory.list() == null ? 0 : directory.list().length;
        try {
            call(service, "showStamina", new Class[]{int.class, StaminaGaugeDetector.Result.class},
                    session.generation(), new StaminaGaugeDetector.Result(61, 61, StaminaGaugeDetector.Direction.NONE, null, 1));
            View result = (View)get(service, "resultView");
            result.findViewWithTag("report_error").performClick();
            android.app.AlertDialog dialog = (android.app.AlertDialog)get(service, "reportDialog");
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (stop) service.onDestroy();
            unblock.countDown();
            if (stop) assertTrue(emailWorker.awaitTermination(5, TimeUnit.SECONDS));
            else emailWorker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Intent launched = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
            assertTrue(full.isRecycled());
            assertNull(get(service, "resultView"));
            if (stop) {
                assertNull(launched);
                assertEquals(before, directory.list() == null ? 0 : directory.list().length);
            } else {
                assertNotNull(launched);
                assertEquals(Intent.ACTION_CHOOSER, launched.getAction());
                Intent message = launched.getParcelableExtra(Intent.EXTRA_INTENT);
                assertArrayEquals(new String[]{service.getString(R.string.report_email_address)}, message.getStringArrayExtra(Intent.EXTRA_EMAIL));
                assertTrue(message.getStringExtra(Intent.EXTRA_TEXT).contains(service.getString(R.string.report_body_prompt)));
                java.util.ArrayList<android.net.Uri> uris = message.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                assertEquals(2, uris.size());
                android.net.Uri uri = uris.get(0);
                java.io.File attachment = new java.io.File(directory, uri.getLastPathSegment());
                assertTrue("File must survive the result closing for the email app", attachment.isFile());
                Bitmap decoded = android.graphics.BitmapFactory.decodeFile(attachment.getPath());
                assertEquals(android.graphics.Color.GREEN, decoded.getPixel(1, 1));
                java.io.File jsonFile = new java.io.File(directory, uris.get(1).getLastPathSegment());
                org.json.JSONObject json = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(jsonFile.toPath()), java.nio.charset.StandardCharsets.UTF_8));
                assertEquals("STAMINA", json.getJSONObject("recognition").getString("result"));
                assertEquals(40, json.getJSONObject("recognition").getInt("width"));
                assertFalse(message.getStringExtra(Intent.EXTRA_TEXT).contains("androidApi"));
                decoded.recycle(); attachment.delete(); jsonFile.delete();
            }
        } finally {
            unblock.countDown();
            if (!stop) service.onDestroy();
        }
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
        CaptureJob job = (CaptureJob) call(service, "newCaptureJob", new Class[]{int.class}, session.generation());
        job.own(event); job.own(choices); job.own(full);
        call(service, "recognizeRegions", new Class[]{Bitmap.class, Bitmap.class, Bitmap.class, CaptureJob.class, StaminaGaugeDetector.Result.class},
                event, choices, full, job,
                new StaminaGaugeDetector.Result(61, 61, StaminaGaugeDetector.Direction.NONE, null, 1));
        if (!completion.equals("close-race")) assertFalse("Pending OCR still owns its input", choices.isRecycled());
        if (stop && !completion.equals("close-race")) service.onDestroy();
        if (!completion.equals("close-race")) assertFalse("Shutdown must wait for OCR completion", choices.isRecycled());
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
