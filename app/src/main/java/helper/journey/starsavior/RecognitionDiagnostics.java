package helper.journey.starsavior;

import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.time.Instant;

/** One capture's numeric/enum trace. Never stores OCR strings, exception payloads or model objects. */
final class RecognitionDiagnostics {
    enum Region { CHOICES, EVENT, FULL }
    enum Result { JOURNEY, SAME_PROGRESS, RAID, STAMINA, ERROR }
    enum Failure { NO_FRAME, CAPTURE_ENDED, RESIZED, CAPTURE_EXCEPTION, OCR_CANCELLED }
    final int generation;
    private final long started = SystemClock.elapsedRealtime();
    private final JSONObject trace = new JSONObject();
    private final JSONArray ocr = new JSONArray();
    private final JSONArray decisions = new JSONArray();
    private final JSONArray failures = new JSONArray();
    private final long[] ocrStarted = new long[Region.values().length];
    private final JSONObject[] ocrEntries = new JSONObject[Region.values().length];
    private int width, height;
    private boolean finished;

    RecognitionDiagnostics(int generation, JourneyModels.Data data) {
        this.generation = generation;
        put(trace, "startedAt", Instant.now().toString());
        put(trace, "captureAvailable", false);
        put(trace, "database", database(data));
        put(trace, "ocr", ocr);
        put(trace, "decisions", decisions);
        put(trace, "failures", failures);
    }

    static JSONObject database(JourneyModels.Data data) {
        JSONObject value = new JSONObject();
        if (data == null) return value;
        put(value, "origin", data.origin.name());
        put(value, "schema", data.schema);
        if (data.contentSha256.matches("[a-fA-F0-9]{64}")) put(value, "sha256", data.contentSha256);
        put(value, "eventCount", data.events.size());
        put(value, "raidEventCount", data.raids.events.size());
        return value;
    }

    synchronized void frame(int width, int height, String timestamp) {
        this.width = width; this.height = height;
        put(trace, "captureAvailable", true);
        put(trace, "capturedAt", timestamp);
        put(trace, "width", width); put(trace, "height", height);
    }

    synchronized void ocrStart(Region region, int inputWidth, int inputHeight) {
        JSONObject value = new JSONObject();
        put(value, "region", region.name());
        CaptureRegionPlanner.Region bounds = region == Region.EVENT ? CaptureRegionPlanner.event(width, height)
                : region == Region.CHOICES ? CaptureRegionPlanner.choice(width, height)
                : new CaptureRegionPlanner.Region(0, 0, width, height);
        put(value, "sourceRect", new JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom));
        put(value, "inputWidth", inputWidth); put(value, "inputHeight", inputHeight);
        put(value, "status", "RUNNING");
        ocrStarted[region.ordinal()] = SystemClock.elapsedRealtime();
        ocrEntries[region.ordinal()] = value;
        if (ocr.length() < 3) ocr.put(value);
    }

    synchronized void ocrEnd(Region region, boolean success, boolean cancelled, int lineCount, Throwable error) {
        JSONObject value = ocrEntries[region.ordinal()];
        if (value == null) return;
        put(value, "elapsedMs", Math.max(0, SystemClock.elapsedRealtime() - ocrStarted[region.ordinal()]));
        put(value, "status", cancelled ? "CANCELLED" : success ? "SUCCESS" : "ERROR");
        put(value, "lineCount", lineCount);
        if (error != null) put(value, "errorType", AppDiagnostics.errorType(error));
    }

    synchronized void decision(Region region, JourneyRecognitionCoordinator.Decision decision, JourneyModels.Data data) {
        JSONObject value = new JSONObject();
        put(value, "region", region.name());
        put(value, "action", decision.action.name());
        put(value, "difficultyResolved", !decision.difficulty.isEmpty());
        put(value, "database", database(data));
        JourneyModels.Match match = decision.match;
        if (match != null) {
            int eventIndex = data == null ? -1 : data.events.indexOf(match.event);
            put(value, "databaseMatchesMatch", eventIndex >= 0);
            put(value, "bestEventIndex", eventIndex);
            put(value, "confident", match.isConfident());
            put(value, "ambiguous", match.ambiguous);
            put(value, "eventNameUsed", match.eventNameUsed);
            put(value, "confidence", match.confidence);
            put(value, "eventScore", match.eventConfidence);
            put(value, "choiceScore", match.choiceConfidence);
            JSONArray choices = new JSONArray();
            for (int i = 0; i < Math.min(32, match.choiceScores.size()); i++) choices.put(match.choiceScores.get(i));
            put(value, "choiceScores", choices);
            JSONArray candidates = new JSONArray();
            for (JourneyModels.MatchCandidate candidate : match.candidates) {
                // A database reload may race recognition. Never label old indices with the new DB.
                if (eventIndex < 0) break;
                JSONObject item = new JSONObject();
                put(item, "eventIndex", candidate.eventIndex);
                put(item, "rankScore", candidate.rankScore);
                put(item, "confidence", candidate.confidence);
                put(item, "eventScore", candidate.eventScore);
                put(item, "choiceScore", candidate.choiceScore);
                candidates.put(item);
                if (candidates.length() == 3) break;
            }
            put(value, "topCandidates", candidates);
        }
        if (decisions.length() < 2) decisions.put(value);
    }

    synchronized void raid(RaidModels.Match match, RaidModels.Data data) {
        JSONObject value = new JSONObject();
        put(value, "raidScreen", match.raidScreen);
        put(value, "difficultyResolved", match.difficultyResolved);
        put(value, "rankMismatch", match.rankMismatch);
        put(value, "selectedTier", match.selectedTier);
        put(value, "recommendedRank", match.recommendedRank);
        put(value, "candidateCount", match.events.size());
        JSONArray indices = new JSONArray();
        for (RaidModels.Event event : match.events) {
            indices.put(data.events.indexOf(event));
            if (indices.length() == 16) break;
        }
        put(value, "eventIndices", indices);
        put(trace, "raid", value);
    }

    synchronized void stamina(StaminaGaugeDetector.Result result) {
        JSONObject value = new JSONObject();
        if (width > 0 && height > 0) {
            StaminaGaugeDetector.Region region = StaminaGaugeDetector.scanRegion(width, height);
            put(value, "scanRect", new JSONArray().put(region.left).put(region.top)
                    .put(region.left + region.width).put(region.top + region.height));
        }
        put(value, "detected", result != null);
        if (result != null) {
            put(value, "current", result.current); put(value, "after", result.after);
            put(value, "direction", result.direction.name()); put(value, "confidence", result.confidence);
        }
        put(trace, "stamina", value);
    }

    synchronized void arcana(int candidates, int recognized) {
        JSONObject value = new JSONObject();
        put(value, "candidateCount", candidates); put(value, "recognizedCount", recognized);
        put(trace, "arcana", value);
    }

    synchronized void failure(Failure reason) { if (failures.length() < 8) failures.put(reason.name()); }

    synchronized void finish(Result result) {
        if (finished) return;
        finished = true;
        put(trace, "result", result.name());
        put(trace, "elapsedMs", Math.max(0, SystemClock.elapsedRealtime() - started));
    }

    synchronized JSONObject snapshot() {
        try { return new JSONObject(trace.toString()); }
        catch (JSONException error) { throw new IllegalStateException("Cannot encode diagnostics", error); }
    }

    static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); }
        catch (JSONException error) { throw new IllegalArgumentException("Invalid diagnostic field", error); }
    }
}
