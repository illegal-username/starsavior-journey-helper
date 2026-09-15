package helper.journey.starsavior;

import static helper.journey.starsavior.UiTestSupport.*;
import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.projection.MediaProjection;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognizer;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowSettings;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35}, qualifiers = "w960dp-h360dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class OverlayRaidStaminaTest {
    @Test public void regionalAndFullRaidRecognitionKeepCurrentAndPreviewStamina() throws Exception {
        for (StaminaGaugeDetector.Direction direction : StaminaGaugeDetector.Direction.values()) {
            OverlayCaptureService service = service();
            try {
                int after = direction == StaminaGaugeDetector.Direction.GAIN ? 78
                        : direction == StaminaGaugeDetector.Direction.LOSS ? 45 : 61;
                Recognition result = recognize(service, gauge(61, after, direction), false, false);
                assertNotNull(result.stamina);
                assertEquals(direction, result.stamina.direction);
                assertNotNull(result.view.findViewWithTag("raid_result"));
                String expected = result.stamina.hasPreview()
                        ? service.getString(R.string.stamina_after, result.stamina.current, result.stamina.after)
                        : service.getString(R.string.stamina_current, result.stamina.current);
                assertNotNull("Raid results lost the detected " + direction + " stamina", text(result.view, expected));
                assertNotNull(text(result.view, service.getString(R.string.raid_journey, "Normal")));
                assertNotNull(text(result.view, "Example coin +24 · Success Normal1"));
            } finally { service.onDestroy(); }
        }
    }

    @Test public void raidWithoutVisibleGaugeDoesNotReusePreviousCaptureStamina() throws Exception {
        OverlayCaptureService service = service();
        try {
            Recognition first = recognize(service, gauge(61, 61, StaminaGaugeDetector.Direction.NONE), false, false);
            assertNotNull(first.stamina);
            String old = service.getString(R.string.stamina_current, first.stamina.current);
            assertNotNull(text(first.view, old));
            Recognition second = recognize(service,
                    Bitmap.createBitmap(2340, 1080, Bitmap.Config.ARGB_8888), false, false);
            assertNull(second.stamina);
            assertNotNull(second.view.findViewWithTag("raid_result"));
            assertNull("A previous capture must not supply a hidden gauge", text(second.view, old));
            assertNotNull(text(second.view, "Example coin +24 · Success Normal1"));
        } finally { service.onDestroy(); }
    }

    @Test public void unknownRaidKeepsStaminaFallbackAndShowsErrorOnlyWithoutGauge() throws Exception {
        for (boolean missingData : new boolean[]{false, true}) {
            OverlayCaptureService service = service();
            try {
                Recognition detected = recognize(service,
                        gauge(61, 45, StaminaGaugeDetector.Direction.LOSS), true, missingData);
                assertNotNull(detected.stamina);
                assertNull(detected.view.findViewWithTag("raid_result"));
                assertNotNull(text(detected.view, service.getString(R.string.stamina_after,
                        detected.stamina.current, detected.stamina.after)));
                assertNotNull(text(detected.view, service.getString(R.string.stamina_hint)));
                Recognition hidden = recognize(service,
                        Bitmap.createBitmap(2340, 1080, Bitmap.Config.ARGB_8888), true, missingData);
                assertNull(hidden.stamina);
                assertNotNull(text(hidden.view, service.getString(missingData
                        ? R.string.raid_data_unavailable : R.string.raid_unreadable)));
            } finally { service.onDestroy(); }
        }
    }

    private static OverlayCaptureService service() throws Exception {
        com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(RuntimeEnvironment.getApplication());
        ShadowSettings.setCanDrawOverlays(true);
        OverlayCaptureService service = Robolectric.buildService(OverlayCaptureService.class).get();
        WindowManager real = service.getSystemService(WindowManager.class);
        WindowManager tracked = (WindowManager) Proxy.newProxyInstance(WindowManager.class.getClassLoader(),
                new Class[]{WindowManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("addView") || method.getName().equals("removeView")
                            || method.getName().equals("removeViewImmediate")) return null;
                    return method.invoke(real, args);
                });
        set(service, "windowManager", tracked);
        set(service, "mediaProjection", Shadow.newInstanceOf(MediaProjection.class));
        set(service, "captureActive", true);
        ((CaptureSessionStateMachine) get(service, "captureSession")).activate();
        return service;
    }

    private static Bitmap gauge(int current, int after, StaminaGaugeDetector.Direction direction) {
        StaminaGaugeDetectorTest.Fixture fixture = StaminaGaugeDetectorTest.Fixture.create(
                2340, 1080, 0.385f, current, after, direction);
        Bitmap bitmap = Bitmap.createBitmap(fixture.width, fixture.height, Bitmap.Config.ARGB_8888);
        StaminaGaugeDetector.Region region = fixture.region;
        bitmap.setPixels(fixture.pixels, 0, region.width, region.left, region.top, region.width, region.height);
        return bitmap;
    }

    private static Recognition recognize(OverlayCaptureService service, Bitmap full,
                                         boolean unknownTitle, boolean missingData) throws Exception {
        RaidModels.Data raids = RaidRecognitionTest.data();
        if (missingData) raids = new RaidModels.Data(service.getString(R.string.raid_screen_rank),
                raids.coinLabel, raids.tierLabels, List.of());
        RaidModels.Data snapshot = raids;
        ((JourneyMatcherStore) get(service, "matcherStore")).reload(() -> new JourneyModels.Data(
                6, "", "synthetic", "test", 0, 0, "", 0, List.of(), Map.of(),
                ((GameLanguage) get(service, "language")).tag, snapshot));
        List<RaidModels.Line> lines = RaidRecognitionTest.screen("Example raid II", "RANK 37");
        List<Text.Line> ocrLines = new ArrayList<>();
        for (RaidModels.Line line : lines) {
            String label = line.text.replace("Recommended overall rank", raids.rankLabel);
            if (unknownTitle) label = label.replace("Example raid", "Unlisted monster");
            Rect box = new Rect((int) line.left, (int) line.top, (int) line.right, (int) line.bottom);
            List<Point> corners = List.of(new Point(box.left, box.top), new Point(box.right, box.top),
                    new Point(box.right, box.bottom), new Point(box.left, box.bottom));
            ocrLines.add(new Text.Line(label, box, corners, "en", null, List.of(), 1, 0));
        }
        Text recognized = new Text("Synthetic raid OCR", List.of(new Text.TextBlock("Synthetic raid OCR",
                new Rect(0, 0, full.getWidth(), full.getHeight()), List.of(), "en", null, ocrLines)));
        List<TaskCompletionSource<Text>> pending = new ArrayList<>();
        TextRecognizer recognizer = (TextRecognizer) Proxy.newProxyInstance(TextRecognizer.class.getClassLoader(),
                new Class[]{TextRecognizer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("process")) {
                        TaskCompletionSource<Text> task = new TaskCompletionSource<>();
                        pending.add(task);
                        return task.getTask();
                    }
                    if (method.getName().equals("close")) return null;
                    throw new UnsupportedOperationException(method.toString());
                });
        set(service, "recognizer", recognizer);
        CaptureSessionStateMachine session = (CaptureSessionStateMachine) get(service, "captureSession");
        assertTrue(session.beginCapture());
        Bitmap event = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888);
        Bitmap choices = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888);
        try {
            StaminaGaugeDetector.Result stamina = (StaminaGaugeDetector.Result) call(service, "detectStamina",
                    new Class[]{Bitmap.class}, full);
            call(service, "recognizeRegions", new Class[]{Bitmap.class, Bitmap.class, Bitmap.class,
                    int.class, StaminaGaugeDetector.Result.class}, event, choices, full, session.generation(), stamina);
            assertEquals(1, pending.size());
            pending.get(0).setResult(recognized);
            drainWorker(service);
            assertEquals("Raid evidence must reach full OCR even when stamina was detected", 2, pending.size());
            pending.get(1).setResult(recognized);
            drainWorker(service);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            View view = (View) get(service, "resultView");
            assertNotNull("An active raid capture must produce a result", view);
            assertEquals(CaptureSessionStateMachine.Phase.ACTIVE, session.phase());
            assertTrue(event.isRecycled());
            assertTrue(choices.isRecycled());
            assertTrue(full.isRecycled());
            return new Recognition(view, stamina);
        } finally { event.recycle(); choices.recycle(); full.recycle(); }
    }

    private static void drainWorker(OverlayCaptureService service) throws Exception {
        ((ExecutorService) get(service, "worker")).submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    private static final class Recognition {
        final View view;
        final StaminaGaugeDetector.Result stamina;
        Recognition(View view, StaminaGaugeDetector.Result stamina) {
            this.view = view;
            this.stamina = stamina;
        }
    }
}
