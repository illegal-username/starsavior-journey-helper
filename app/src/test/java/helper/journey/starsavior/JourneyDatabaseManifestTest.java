package helper.journey.starsavior;

import org.json.JSONException;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class JourneyDatabaseManifestTest {
    private static final String HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void parsesAndMatchesExactDatabaseIdentity() throws Exception {
        JourneyDatabaseManifest manifest = JourneyDatabaseManifest.parse(json(HASH, 120, 36));
        JourneyModels.Data data = data(HASH, 120);

        assertTrue(manifest.isCompatible(36));
        assertTrue(manifest.matches(data));
        manifest.verifyCandidate(data);
        assertTrue(JourneyDatabaseManifest.parse(manifest.toJson()).matches(data));
    }

    @Test
    public void rejectsMalformedOrMismatchedReleaseMetadata() throws Exception {
        assertThrows(JSONException.class, () ->
                JourneyDatabaseManifest.parse(json("not-a-sha", 120, 36)));
        assertThrows(JSONException.class, () ->
                JourneyDatabaseManifest.parse(
                        json(HASH, 120, 36).replace("2026-09-02T00:00:00Z", "not-a-time")));

        JourneyDatabaseManifest manifest = JourneyDatabaseManifest.parse(json(HASH, 120, 40));
        assertFalse(manifest.isCompatible(39));
        assertThrows(JSONException.class, () -> manifest.verifyCandidate(data(HASH, 121)));

        JourneyDatabaseManifest futureSchema = JourneyDatabaseManifest.parse(
                json(HASH, 120, 36).replace("\"databaseSchema\":4", "\"databaseSchema\":5"));
        assertFalse(futureSchema.isCompatible(42));
    }

    private static JourneyModels.Data data(String hash, int length) {
        return new JourneyModels.Data(
                4,
                "2026-09-02T00:00:00Z",
                JourneyDatabaseUpdater.DATABASE_URL,
                "revision-1",
                20,
                40,
                hash,
                length,
                List.of());
    }

    private static String json(String hash, int length, int minimumAppVersionCode) {
        return "{"
                + "\"manifestSchema\":1,"
                + "\"databaseSchema\":4,"
                + "\"contentSha256\":\"" + hash + "\","
                + "\"contentLength\":" + length + ","
                + "\"upstreamRevision\":\"revision-1\","
                + "\"generatedAt\":\"2026-09-02T00:00:00Z\","
                + "\"recordCount\":20,"
                + "\"choiceCount\":40,"
                + "\"minimumAppVersionCode\":" + minimumAppVersionCode
                + "}";
    }
}
