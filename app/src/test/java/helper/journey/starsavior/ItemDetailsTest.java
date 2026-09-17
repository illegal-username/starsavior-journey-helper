package helper.journey.starsavior;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import org.junit.Test;
import static org.junit.Assert.*;

public class ItemDetailsTest {
    static JSONObject candidate(GameLanguage language) throws Exception {
        JSONObject root = new JSONObject(DatabaseTestData.json(language, "items", 20));
        root.put("schema", 7).put("raids", RaidRecognitionTest.block());
        root.put("items", new JSONArray()
                .put(item("first", "Sample", "First description", "Example effect +7"))
                .put(item("second", "Sample", "Second description", "Example effect +13"))
                .put(item("long", "Sample tonic", "Long description", "Example effect +19")));
        JSONObject outcome = outcome(root);
        outcome.put("success", "🧪 Sample · Sample tonic · Sample")
                .put("successItems", new JSONArray().put(link("long", 12, 24)).put(link("second", 27, 33)))
                .put("condition", "Sample x1").put("conditionItems", new JSONArray().put(link("first", 0, 6)))
                .put("failure", "Sample").put("failureItems", new JSONArray().put(link("second", 0, 6)));
        JSONObject option = root.getJSONObject("raids").getJSONArray("records").getJSONObject(0)
                .getJSONArray("options").getJSONObject(0);
        option.put("success", outcome.getString("success")).put("successItems", outcome.getJSONArray("successItems"))
                .put("failure", "Sample").put("failureItems", new JSONArray().put(link("first", 0, 6)));
        return root;
    }

    static JSONObject outcome(JSONObject root) throws Exception {
        return root.getJSONArray("records").getJSONObject(0).getJSONArray("choices").getJSONObject(0)
                .getJSONArray("outcomes").getJSONObject(0);
    }
    static JSONObject item(String id, String name, String description, String effect) throws Exception {
        return new JSONObject().put("id", id).put("name", name).put("description", description).put("effect", effect);
    }
    static JSONObject link(String id, int start, int end) throws Exception {
        return new JSONObject().put("itemId", id).put("start", start).put("end", end);
    }

    @Test public void explicitIdsPreserveSameNamesOverlapsAndUtf16Offsets() throws Exception {
        JourneyModels.Data data = JourneyRepository.parseValidated(candidate(GameLanguage.ENGLISH).toString(), GameLanguage.ENGLISH);
        JourneyModels.Outcome row = data.events.get(0).choices.get(0).outcomes.get(0);
        assertEquals(2, row.items.success.size());
        assertEquals("Long description", row.items.success.get(0).item.description);
        assertEquals("Second description", row.items.success.get(1).item.description);
        assertEquals("First description", row.items.condition.get(0).item.description);
        assertEquals("Second description", row.items.failure.get(0).item.description);
        assertEquals(12, row.items.success.get(0).start);
        assertEquals("first", data.raids.events.get(0).options.get(0).items.failure.get(0).item.id);
    }

    @Test public void invalidCatalogAndLinksAreRejectedBeforeUse() throws Exception {
        for (String damage : new String[] {"missing", "duplicate", "unknown", "overlap", "fraction", "range", "name", "type", "old-schema"}) {
            JSONObject root = candidate(GameLanguage.ENGLISH);
            JSONArray links = outcome(root).getJSONArray("successItems");
            switch (damage) {
                case "missing": root.remove("items"); break;
                case "duplicate": root.getJSONArray("items").put(root.getJSONArray("items").get(0)); break;
                case "unknown": links.getJSONObject(0).put("itemId", "unknown"); break;
                case "overlap": links.put(link("second", 27, 33)); break;
                case "fraction": links.getJSONObject(0).put("start", 12.5); break;
                case "range": links.getJSONObject(0).put("end", Integer.MAX_VALUE); break;
                case "name": links.getJSONObject(0).put("start", 11); break;
                case "type": root.getJSONArray("items").getJSONObject(0).put("effect", JSONObject.NULL); break;
                case "old-schema": root.put("schema", 6); break;
            }
            assertThrows(damage, JSONException.class, () -> JourneyRepository.parseValidated(root.toString(), GameLanguage.ENGLISH));
        }
    }

    @Test public void oldDatabasesRemainPlainAndItemDetailsDoNotRequireRaids() throws Exception {
        JourneyModels.Data old = JourneyRepository.parseValidated(DatabaseTestData.json(GameLanguage.ENGLISH, "old", 1), GameLanguage.ENGLISH);
        assertTrue(old.events.get(0).choices.get(0).outcomes.get(0).items.success.isEmpty());
        JSONObject root = candidate(GameLanguage.ENGLISH); root.remove("raids");
        assertTrue(JourneyRepository.parseValidated(root.toString(), GameLanguage.ENGLISH).raids.events.isEmpty());
        JourneyDatabaseManifest meta = JourneyDatabaseManifest.parse(DatabaseTestData.manifest(root.toString(), 65));
        assertFalse(meta.isCompatible(64)); assertTrue(meta.isCompatible(65));
        assertThrows(JSONException.class, () -> JourneyDatabaseManifest.parse(DatabaseTestData.manifest(root.toString(), 64)));
    }
}
