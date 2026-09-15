package helper.journey.starsavior;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class RaidRecognitionTest {
    static RaidModels.Event event(String difficulty, int... ranks) {
        List<RaidModels.Option> options = new ArrayList<>();
        for (int i = 0; i < 3; i++) options.add(new RaidModels.Option(i + 1,
                "Example raid " + "I".repeat(i + 1), ranks[i], 13 + i * 11, 7, 3,
                "Success " + difficulty + i, "Failure " + difficulty + i));
        return new RaidModels.Event("Example raid", difficulty, options);
    }

    static RaidModels.Data data() {
        return new RaidModels.Data("Recommended overall rank", "Example coin",
                List.of("Basic quest", "Intermediate quest", "Advanced quest"), List.of(
                event("Easy", 12, 29, 46), event("Normal", 31, 37, 64), event("Hard", 37, 83, 101)));
    }

    static RaidMatcher matcher(RaidModels.Data data) {
        return new RaidMatcher(data, "Recommended overall rank",
                List.of("Basic quest", "Intermediate quest", "Advanced quest"));
    }

    static List<RaidModels.Line> screen(String title, String rank) {
        return new ArrayList<>(List.of(
                new RaidModels.Line("RANK 31", 20, 70, 120, 90),
                new RaidModels.Line(title, 320, 170, 580, 203),
                new RaidModels.Line("Recommended overall rank", 310, 420, 590, 437),
                new RaidModels.Line(rank, 310, 448, 410, 469),
                new RaidModels.Line("Example raid I", 700, 120, 830, 137),
                new RaidModels.Line("Basic quest", 700, 140, 800, 157),
                new RaidModels.Line("Example raid II", 700, 230, 850, 247),
                new RaidModels.Line("Intermediate quest", 700, 250, 890, 267),
                new RaidModels.Line("Example raid III", 700, 340, 870, 357),
                new RaidModels.Line("Advanced quest", 700, 360, 860, 377)));
    }

    @Test public void recommendedRankAndSelectedTierDistinguishTheTwoDifficulties() {
        RaidModels.Match hard = matcher(data()).match(screen("Example raid Ⅰ", "RANK 37"));
        assertTrue(hard.difficultyResolved);
        assertEquals("Hard", hard.events.get(0).journeyDifficulty);
        assertEquals(1, hard.selectedTier);
        RaidModels.Match normal = matcher(data()).match(screen("Example raid II", "RANK 37"));
        assertTrue(normal.difficultyResolved);
        assertEquals("Normal", normal.events.get(0).journeyDifficulty);
        assertEquals(2, normal.selectedTier);
        assertEquals(3, normal.events.get(0).options.size());
    }

    @Test public void rankGeometryWorksAcrossSizesOffsetsAndOcrRomanVariants() {
        for (double scale : new double[]{0.45, 1, 2.8}) {
            for (double shift : new double[]{-80, 0, 317}) {
                for (String roman : List.of("III", "Ⅲ", "lll", "111")) {
                    List<RaidModels.Line> transformed = new ArrayList<>();
                    for (RaidModels.Line line : screen("Example raid " + roman, "RANK 101")) {
                        transformed.add(new RaidModels.Line(line.text, line.left * scale + 63,
                                line.top * scale + shift, line.right * scale + 63, line.bottom * scale + shift));
                    }
                    RaidModels.Match match = matcher(data()).match(transformed);
                    assertTrue(match.difficultyResolved);
                    assertEquals("Hard", match.events.get(0).journeyDifficulty);
                    assertEquals(3, match.selectedTier);
                }
            }
        }
    }

    @Test public void unreadableOrUnregisteredRankKeepsCandidatesAndIgnoresPlayerRank() {
        for (String rank : List.of("RANK ?", "RANK 777")) {
            RaidModels.Match match = matcher(data()).match(screen("Example raid II", rank));
            assertTrue(match.raidScreen);
            assertFalse(match.difficultyResolved);
            assertEquals(3, match.events.size());
        }
    }

    @Test public void missingSelectedTierDoesNotChooseBetweenCollidingRanks() {
        RaidModels.Match match = matcher(data()).match(screen("Example raid", "RANK 37"));
        assertFalse(match.difficultyResolved);
        assertEquals(2, match.events.size());
        assertEquals(0, match.selectedTier);
    }

    @Test public void collidingRankWithinTheSameTierStaysAmbiguous() {
        RaidModels.Data data = new RaidModels.Data("Recommended overall rank", "coin", List.of(),
                List.of(event("A", 157, 89, 211), event("B", 157, 61, 333)));
        RaidModels.Match match = matcher(data).match(screen("Example raid I", "RANK 157"));
        assertFalse(match.difficultyResolved);
        assertEquals(2, match.events.size());
    }

    @Test public void eachRegisteredTierTitleIsRecognitionEvidence() {
        RaidModels.Event original = event("A", 157, 89, 211);
        List<RaidModels.Option> options = new ArrayList<>(original.options);
        options.set(2, new RaidModels.Option(3, "Alternate wording III", 211, 3, 1, 1, "reward", "failure"));
        RaidModels.Data data = new RaidModels.Data("Recommended overall rank", "coin", List.of(),
                List.of(new RaidModels.Event(original.name, "A", options)));
        RaidModels.Match match = matcher(data).match(screen("Alternate wording Ⅲ", "RANK 211"));
        assertTrue(match.difficultyResolved);
        assertEquals(3, match.selectedTier);
        assertEquals("Example raid", match.events.get(0).name);
    }

    @Test public void dialogueMentionsDoNotBecomeRaidsAndOldDatabasesReportMissingData() {
        assertFalse(matcher(data()).match(List.of(new RaidModels.Line(
                "Example raid I", 300, 100, 500, 130))).raidScreen);
        RaidModels.Match missing = matcher(RaidModels.Data.EMPTY).match(screen("Example raid I", "RANK 37"));
        assertTrue(missing.raidScreen);
        assertTrue(missing.events.isEmpty());
        assertTrue(matcher(RaidModels.Data.EMPTY).hasRegionalSignal(List.of("Basic quest")));
        List<RaidModels.Line> support = List.of(
                new RaidModels.Line("Basic quest", 500, 120, 620, 140),
                new RaidModels.Line("Intermediate quest", 500, 240, 710, 260));
        assertFalse(matcher(data()).match(support).raidScreen);
        assertFalse(matcher(RaidModels.Data.EMPTY).match(support).raidScreen);
    }

    static RaidModels.Event single(String difficulty, int rank, String failure) {
        return new RaidModels.Event("Emergency Basil", difficulty, List.of(new RaidModels.Option(
                0, "Emergency Basil", rank, 23, 7, 3, "Emergency victory", failure)));
    }

    @Test public void singleEmergencyNeedsNoRomanTierOrThreeMenuEntries() {
        RaidModels.Data data = new RaidModels.Data("Recommended overall rank", "coin", List.of(),
                List.of(single("A", 131, "defeat"), single("B", 239, "defeat"), single("C", 361, "defeat")));
        List<RaidModels.Line> lines = screen("Emergency Basil", "RANK 239");
        lines.removeIf(line -> line.left >= 700);
        RaidModels.Match match = matcher(data).match(lines);
        assertTrue(match.raidScreen);
        assertTrue(match.difficultyResolved);
        assertEquals("B", match.events.get(0).journeyDifficulty);
        assertEquals(0, match.selectedTier);
        assertTrue(match.events.get(0).isSingleBattle());
        assertTrue(matcher(data).hasRegionalSignal(List.of("Emergency Basil")));
        lines.set(3, new RaidModels.Line("RANK ?", 310, 448, 410, 469));
        RaidModels.Match missing = matcher(data).match(lines);
        assertTrue(missing.raidScreen);
        assertFalse(missing.difficultyResolved);
        assertEquals(3, missing.events.size());
        assertEquals(0, missing.selectedTier);
        assertFalse(matcher(data).match(List.of(lines.get(1))).raidScreen);
    }

    @Test public void wrappedEmergencyTitleUsesAdjacentLinesInTheSameColumn() {
        RaidModels.Data data = new RaidModels.Data("Recommended overall rank", "coin", List.of(),
                List.of(single("A", 131, "defeat")));
        List<RaidModels.Line> lines = screen("Emergency", "RANK 131");
        lines.removeIf(line -> line.left >= 700);
        lines.add(new RaidModels.Line("Basil", 320, 208, 540, 241));
        RaidModels.Match match = matcher(data).match(lines);
        assertTrue(match.difficultyResolved);
        assertEquals("Emergency Basil", match.events.get(0).name);
        assertEquals(0, match.selectedTier);
        assertTrue(matcher(data).hasRegionalSignal(List.of("Emergency", "Basil")));
        lines.set(lines.size()-1, new RaidModels.Line("Basil", 710, 208, 930, 241));
        assertTrue(matcher(data).match(lines).events.isEmpty());
    }

    @Test public void parsesUnnumberedSingleBattleButRejectsIncompleteSelectableRaids() throws Exception {
        JSONObject block = block();
        JSONObject record = block.getJSONArray("records").getJSONObject(0);
        JSONObject single = record.getJSONArray("options").getJSONObject(0).put("tier", 0);
        record.put("options", new JSONArray().put(single));
        assertTrue(RaidRepository.parse(block).events.get(0).isSingleBattle());
        single.put("tier", 1);
        assertThrows(org.json.JSONException.class, () -> RaidRepository.parse(block));
        record.put("options", new JSONArray().put(single).put(new JSONObject(single.toString()).put("tier",2)));
        assertThrows(org.json.JSONException.class, () -> RaidRepository.parse(block));
    }

    static JSONObject block() throws Exception {
        JSONArray records = new JSONArray();
        for (RaidModels.Event event : data().events) {
            JSONArray options = new JSONArray();
            for (RaidModels.Option option : event.options) options.put(new JSONObject()
                    .put("tier", option.tier).put("title", option.title)
                    .put("recommendedRank", option.recommendedRank).put("victoryCoin", option.victoryCoin)
                    .put("missionBonusCoin", option.missionBonusCoin).put("missionCount", option.missionCount)
                    .put("success", option.success).put("failure", option.failure));
            records.put(new JSONObject().put("name", event.name).put("journeyDifficulty", event.journeyDifficulty)
                    .put("options", options));
        }
        return new JSONObject().put("schema", 1).put("labels", new JSONObject()
                .put("rank", "Recommended overall rank").put("coin", "Example coin")
                .put("tiers", new JSONArray(List.of("Basic quest", "Intermediate quest", "Advanced quest"))))
                .put("records", records);
    }

    @Test public void schemaSixLoadsRaidsWithoutAddingAnyChoiceRecords() throws Exception {
        JSONObject json = new JSONObject(DatabaseTestData.json(GameLanguage.ENGLISH, "raid-review", 2));
        json.put("schema", 6).put("raids", block());
        JourneyModels.Data data = JourneyRepository.parseValidated(json.toString(), GameLanguage.ENGLISH);
        assertEquals(2, data.events.size());
        assertEquals(4, data.choiceCount);
        assertEquals(3, data.raids.events.size());
        JourneyDatabaseManifest manifest = JourneyDatabaseManifest.parse(DatabaseTestData.manifest(json.toString(), 57));
        assertTrue(manifest.isCompatible(57));
        assertFalse(manifest.isCompatible(56));
        manifest.verifyCandidate(data);
        assertThrows(org.json.JSONException.class, () -> JourneyDatabaseManifest.parse(
                DatabaseTestData.manifest(json.toString(), 51)));
    }

    @Test public void partialOrMislabeledRaidBlocksAreRejected() throws Exception {
        JSONObject old = new JSONObject(DatabaseTestData.json(GameLanguage.ENGLISH, "old", 1)).put("raids", block());
        assertThrows(org.json.JSONException.class, () -> JourneyRepository.parseValidated(old.toString(), GameLanguage.ENGLISH));
        JSONObject missing = new JSONObject(DatabaseTestData.json(GameLanguage.ENGLISH, "missing", 1)).put("schema", 6);
        assertThrows(org.json.JSONException.class, () -> JourneyRepository.parseValidated(missing.toString(), GameLanguage.ENGLISH));
        for (Object bad : List.of(-1, 2.5, "37")) {
            JSONObject block = block();
            block.getJSONArray("records").getJSONObject(0).getJSONArray("options").getJSONObject(0)
                    .put("recommendedRank", bad);
            assertThrows(org.json.JSONException.class, () -> RaidRepository.parse(block));
        }
        JSONObject duplicate = block();
        duplicate.getJSONArray("records").put(duplicate.getJSONArray("records").getJSONObject(0));
        assertThrows(org.json.JSONException.class, () -> RaidRepository.parse(duplicate));
    }
}
