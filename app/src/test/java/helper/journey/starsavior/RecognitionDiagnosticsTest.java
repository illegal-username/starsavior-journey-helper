package helper.journey.starsavior;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class RecognitionDiagnosticsTest {
    private JourneyModels.Event event(String name) {
        return new JourneyModels.Event(name, "private-context", List.of(
                new JourneyModels.Choice("private-choice-a", List.of()),
                new JourneyModels.Choice("private-choice-b", List.of())), false);
    }

    @Test public void ambiguousCandidatesKeepActualRankingWithoutOcrOrDatabaseText() throws Exception {
        List<JourneyModels.Event> events = List.of(event("private-title-a"), event("private-title-b"),
                event("private-title-c"), event("private-title-d"));
        JourneyModels.Data data = new JourneyModels.Data(7, "private-date", "private-source", "private-revision", 4, 8,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 10, events);
        JourneyRecognitionCoordinator.Decision decision = JourneyRecognitionCoordinator.evaluateFull(
                new JourneyMatcher(events), List.of("private-choice-a", "private-choice-b"), List.of());
        assertEquals(JourneyRecognitionCoordinator.Action.AMBIGUOUS, decision.action);
        RecognitionDiagnostics trace = new RecognitionDiagnostics(5, data);
        trace.frame(3120, 1440, "2026-10-01T01:00:00Z");
        trace.decision(RecognitionDiagnostics.Region.FULL, decision, data);
        trace.finish(RecognitionDiagnostics.Result.ERROR);
        JSONObject snapshot = trace.snapshot();
        JSONObject logged = snapshot.getJSONArray("decisions").getJSONObject(0);
        assertTrue(logged.getBoolean("ambiguous"));
        assertEquals(events.indexOf(decision.match.event), logged.getInt("bestEventIndex"));
        JSONArray candidates = logged.getJSONArray("topCandidates");
        assertEquals(3, candidates.length());
        assertEquals(0, candidates.getJSONObject(0).getInt("eventIndex"));
        assertEquals(1, candidates.getJSONObject(1).getInt("eventIndex"));
        assertEquals(decision.match.confidence, candidates.getJSONObject(0).getDouble("confidence"), 0.000001);
        assertFalse(snapshot.toString().contains("private-"));
        assertEquals(data.contentSha256, snapshot.getJSONObject("database").getString("sha256"));

        JourneyModels.Data replacement = new JourneyModels.Data(7, "", "", "", 1, 2, List.of(event("replacement")));
        trace.decision(RecognitionDiagnostics.Region.FULL, decision, replacement);
        JSONObject reloaded = trace.snapshot().getJSONArray("decisions").getJSONObject(1);
        assertFalse(reloaded.getBoolean("databaseMatchesMatch"));
        assertEquals(-1, reloaded.getInt("bestEventIndex"));
        assertEquals(0, reloaded.getJSONArray("topCandidates").length());
    }

    @Test public void actualOcrInputGeometryTimingAndFailureAreRetainedWithoutMessages() throws Exception {
        RecognitionDiagnostics trace = new RecognitionDiagnostics(7, null);
        trace.frame(1080, 720, "timestamp");
        CaptureRegionPlanner.Region bounds = CaptureRegionPlanner.event(1080, 720);
        trace.ocrStart(RecognitionDiagnostics.Region.EVENT, bounds.width() * 2, bounds.height() * 2);
        ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(125));
        trace.ocrEnd(RecognitionDiagnostics.Region.EVENT, false, false, 0, new IllegalStateException("private OCR payload"));
        trace.ocrStart(RecognitionDiagnostics.Region.FULL, 1080, 720);
        ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(50));
        trace.ocrEnd(RecognitionDiagnostics.Region.FULL, true, false, 6, null);
        trace.finish(RecognitionDiagnostics.Result.STAMINA);
        JSONObject frozen = trace.snapshot();
        JSONObject event = frozen.getJSONArray("ocr").getJSONObject(0);
        assertEquals(bounds.left, event.getJSONArray("sourceRect").getInt(0));
        assertEquals(bounds.right, event.getJSONArray("sourceRect").getInt(2));
        assertEquals(bounds.width() * 2, event.getInt("inputWidth"));
        assertEquals(125, event.getLong("elapsedMs"));
        assertEquals("STATE", event.getString("errorType"));
        assertEquals(6, frozen.getJSONArray("ocr").getJSONObject(1).getInt("lineCount"));
        assertFalse(frozen.toString().contains("private"));
        long duration = frozen.getLong("elapsedMs");
        ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(20));
        trace.finish(RecognitionDiagnostics.Result.ERROR);
        trace.failure(RecognitionDiagnostics.Failure.OCR_CANCELLED);
        assertEquals(duration, trace.snapshot().getLong("elapsedMs"));
        assertEquals("STAMINA", trace.snapshot().getString("result"));
        assertEquals(0, frozen.getJSONArray("failures").length());
    }

    @Test public void missingFrameIsExplicitAndFailuresRemainBounded() throws Exception {
        RecognitionDiagnostics trace = new RecognitionDiagnostics(9, null);
        for (int i = 0; i < 100; i++) trace.failure(RecognitionDiagnostics.Failure.NO_FRAME);
        trace.finish(RecognitionDiagnostics.Result.ERROR);
        JSONObject log = trace.snapshot();
        assertFalse(log.getBoolean("captureAvailable"));
        assertFalse(log.has("width"));
        assertEquals(0, log.getJSONArray("ocr").length());
        assertEquals(8, log.getJSONArray("failures").length());
    }
}
