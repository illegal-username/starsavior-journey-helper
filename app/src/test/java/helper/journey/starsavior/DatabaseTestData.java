package helper.journey.starsavior;

import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic records only; no publication data or game captures. */
final class DatabaseTestData {
    static String json(GameLanguage language, String revision, int records) throws Exception {
        JSONArray events = new JSONArray();
        for (int i = 0; i < records; i++) {
            JSONArray choices = new JSONArray();
            for (int j = 0; j < 2; j++) {
                choices.put(new JSONObject().put("text", "Synthetic choice " + i + " " + j)
                        .put("outcomes", new JSONArray().put(new JSONObject()
                                .put("difficulty", "").put("condition", "")
                                .put("success", "Synthetic reward " + revision).put("failure", ""))));
            }
            events.put(new JSONObject().put("event", "Synthetic event " + i)
                    .put("context", "").put("choices", choices));
        }
        String json = new JSONObject().put("schema", 5).put("language", language.tag)
                .put("source", language.databaseUrl()).put("upstreamRevision", revision)
                .put("generatedAt", "2026-09-12T00:00:00Z")
                .put("recordCount", records).put("choiceCount", records * 2)
                .put("records", events).toString();
        JourneyRepository.parseValidated(json, language);
        return json;
    }

    static String manifest(String json, int minimum) throws Exception {
        JourneyModels.Data data = JourneyRepository.parse(json);
        return new JourneyDatabaseManifest(2, data.schema, data.contentSha256, data.contentLength,
                data.upstreamRevision, data.generatedAt, data.recordCount, data.choiceCount,
                minimum, data.language).toJson();
    }
}
