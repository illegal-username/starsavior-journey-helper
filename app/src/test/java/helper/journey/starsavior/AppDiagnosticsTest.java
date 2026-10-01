package helper.journey.starsavior;

import java.io.IOException;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppDiagnosticsTest {
    @Test public void boundedRecordsNeverCopyExceptionPayloadOrFreeTextMetadata() {
        JourneyModels.Data data = new JourneyModels.Data(7, "private-date", "private-source",
                "private-revision", 0, 0, "private-hash", 0, List.of());
        IOException error = new IOException("private-path private-screen", new Exception("private-cause"));
        for (int i = 0; i < 100; i++) AppDiagnostics.record(AppDiagnostics.Stage.DATABASE_READ, error, data);
        String details = AppDiagnostics.snapshot();
        assertTrue(details.contains("events=32")); assertTrue(details.contains("DATABASE_READ IO"));
        assertTrue(details.contains("schema=7")); assertFalse(details.contains("private-"));
        assertEquals(34, details.split("\n").length);
    }
}
