package helper.journey.starsavior;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public class JourneyRepositoryArcanaFeatureTest {
    @Test
    public void parsesAndValidatesFeaturesForThreeSources() throws Exception {
        JSONObject root = database();

        JourneyModels.Data data = JourneyRepository.parse(root.toString());
        JourneyRepository.validate(data);

        assertEquals(3, data.arcanaImageFeatures.size());
        assertEquals(48, data.arcanaImageFeatures.get("three").histogram.length);
    }

    @Test
    public void rejectsIncompleteFeatureSetInsteadOfGuessing() throws Exception {
        JSONObject root = database();
        root.getJSONObject("arcanaImageFeatures").getJSONArray("items").remove(2);
        JourneyModels.Data data = JourneyRepository.parse(root.toString());

        assertThrows(JSONException.class, () -> JourneyRepository.validate(data));
    }

    private static JSONObject database() throws JSONException {
        JSONArray outcomes = new JSONArray()
                .put(outcome("first", "one"))
                .put(outcome("second", "two"))
                .put(outcome("third", "three"));
        JSONArray choices = new JSONArray()
                .put(new JSONObject().put("text", "choice one").put("outcomes", outcomes))
                .put(new JSONObject().put("text", "choice two").put("outcomes", outcomes));
        JSONArray featureItems = new JSONArray()
                .put(feature("one", 1))
                .put(feature("two", 2))
                .put(feature("three", 3));
        return new JSONObject()
                .put("schema", 4)
                .put("recordCount", 1)
                .put("choiceCount", 2)
                .put("records", new JSONArray().put(
                        new JSONObject().put("event", "event").put("choices", choices)))
                .put("arcanaImageFeatures", new JSONObject()
                        .put("schema", 1)
                        .put("kind", "hsv-h12-s4")
                        .put("items", featureItems));
    }

    private static JSONObject outcome(String success, String arcanaId) throws JSONException {
        return new JSONObject()
                .put("success", success)
                .put("arcanaIds", new JSONArray().put(arcanaId));
    }

    private static JSONObject feature(String arcanaId, int value) throws JSONException {
        JSONArray histogram = new JSONArray();
        for (int index = 0; index < 48; index++) histogram.put(value);
        return new JSONObject().put("arcanaId", arcanaId).put("histogram", histogram);
    }
}
