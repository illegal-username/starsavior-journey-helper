package helper.journey.starsavior;

import org.json.JSONException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class JourneyDatabaseUpdaterTest {
    @Test
    public void usesCanonicalPagesDatabaseUrl() {
        assertEquals(
                "https://starsavior-journey-data.pages.dev/journey_choices.json",
                JourneyDatabaseUpdater.DATABASE_URL);
        assertEquals(
                "https://starsavior-journey-data.pages.dev/journey_choices.meta.json",
                JourneyDatabaseUpdater.MANIFEST_URL);
    }

    @Test
    public void sameDatabaseRequiresMatchingNonEmptyRevisionAndSource() {
        JourneyModels.Data current = data(20, JourneyDatabaseUpdater.DATABASE_URL, "revision-1");

        assertTrue(JourneyDatabaseUpdater.sameDatabase(
                current, data(20, JourneyDatabaseUpdater.DATABASE_URL, "revision-1")));
        assertFalse(JourneyDatabaseUpdater.sameDatabase(
                current, data(20, "https://example.invalid/journey_choices.json", "revision-1")));
        assertFalse(JourneyDatabaseUpdater.sameDatabase(
                current, data(20, JourneyDatabaseUpdater.DATABASE_URL, "revision-2")));
        assertFalse(JourneyDatabaseUpdater.sameDatabase(
                data(20, JourneyDatabaseUpdater.DATABASE_URL, ""),
                data(20, JourneyDatabaseUpdater.DATABASE_URL, "")));

        JourneyModels.Data hashOne = data(
                20, JourneyDatabaseUpdater.DATABASE_URL, "revision-1", "1".repeat(64));
        JourneyModels.Data hashTwo = data(
                20, JourneyDatabaseUpdater.DATABASE_URL, "revision-1", "2".repeat(64));
        assertFalse(JourneyDatabaseUpdater.sameDatabase(hashOne, hashTwo));
    }

    @Test
    public void validatesCanonicalRemoteDatabaseAndSafetyFloor() throws Exception {
        JourneyModels.Data emptyCurrent = new JourneyModels.Data(
                4, "", "public-example", "", 0, 0, List.of());
        JourneyModels.Data candidate = data(20, JourneyDatabaseUpdater.DATABASE_URL, "revision-1");

        JourneyDatabaseUpdater.validateRemoteDatabase(emptyCurrent, candidate);

        assertThrows(JSONException.class, () -> JourneyDatabaseUpdater.validateRemoteDatabase(
                emptyCurrent, data(20, "https://example.invalid/journey_choices.json", "revision-1")));
        assertThrows(JSONException.class, () -> JourneyDatabaseUpdater.validateRemoteDatabase(
                emptyCurrent, data(20, JourneyDatabaseUpdater.DATABASE_URL, " ")));
        assertThrows(JSONException.class, () -> JourneyDatabaseUpdater.validateRemoteDatabase(
                data(80, JourneyDatabaseUpdater.DATABASE_URL, "old"), candidate));

        JourneyDatabaseManifest manifest = new JourneyDatabaseManifest(
                1, 4, "1".repeat(64), 1000, "revision-2",
                "2026-09-02T00:00:00Z", 20, 40, 36);
        JourneyDatabaseUpdater.validateManifestAgainstCurrent(emptyCurrent, manifest);
        assertThrows(JSONException.class, () ->
                JourneyDatabaseUpdater.validateManifestAgainstCurrent(
                        data(80, JourneyDatabaseUpdater.DATABASE_URL, "old"), manifest));
    }

    private static JourneyModels.Data data(int recordCount, String source, String revision) {
        return data(recordCount, source, revision, "");
    }

    private static JourneyModels.Data data(
            int recordCount, String source, String revision, String contentSha256) {
        List<JourneyModels.Event> events = new ArrayList<>();
        int choices = 0;
        for (int index = 0; index < recordCount; index++) {
            JourneyModels.Outcome outcome = new JourneyModels.Outcome(
                    "기본", "", "", "결과 " + index, "");
            List<JourneyModels.Choice> eventChoices = List.of(
                    new JourneyModels.Choice("첫 번째 선택 " + index, List.of(outcome)),
                    new JourneyModels.Choice("두 번째 선택 " + index, List.of(outcome)));
            events.add(new JourneyModels.Event("테스트 이벤트 " + index, "", eventChoices));
            choices += eventChoices.size();
        }
        return new JourneyModels.Data(
                4, "2026-09-02T00:00:00Z", source, revision,
                events.size(), choices, contentSha256, 1000, events);
    }
}
