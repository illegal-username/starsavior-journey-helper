package helper.journey.starsavior;

import org.json.JSONException;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;

/** Bounded, process-local records. Never retain exceptions or their messages. */
final class AppDiagnostics {
    enum Stage { DATABASE_READ, DATABASE_LOAD, DATABASE_CHECK, DATABASE_UPDATE,
        CAPTURE_START, CAPTURE }
    private static final int LIMIT = 32;
    private static final ArrayDeque<String> RECENT = new ArrayDeque<>();

    static synchronized void record(Stage stage, Throwable error, JourneyModels.Data data) {
        String type = error instanceof JSONException ? "JSON"
                : error instanceof IOException ? "IO"
                : error instanceof SecurityException ? "SECURITY"
                : error instanceof IllegalStateException ? "STATE" : "OTHER";
        String record = Instant.now() + " " + stage + " " + type;
        if (data != null) {
            record += " origin=" + data.origin + " schema=" + data.schema;
            // A computed content hash is useful for correlation without copying free text.
            if (data.contentSha256.matches("[a-fA-F0-9]{64}")) {
                record += " db=" + data.contentSha256.substring(0, 12);
            }
        }
        if (RECENT.size() == LIMIT) RECENT.removeFirst();
        RECENT.addLast(record);
    }

    static synchronized String snapshot() {
        return "Journey Helper " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
                + "\nevents=" + RECENT.size() + "\n" + String.join("\n", RECENT);
    }

    private AppDiagnostics() {}
}
