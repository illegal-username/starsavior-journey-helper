package helper.journey.starsavior;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Parses only the raid block; no raid is converted into a dialogue choice. */
final class RaidRepository {
    private RaidRepository() {}

    static RaidModels.Data parse(JSONObject root) throws JSONException {
        if (integer(root, "schema", 1) != 1) throw new JSONException("Unsupported raid schema.");
        JSONObject labels = root.getJSONObject("labels");
        String rank = string(labels, "rank");
        String coin = string(labels, "coin");
        JSONArray tierJson = labels.getJSONArray("tiers");
        if (tierJson.length() != 3) throw new JSONException("Invalid raid tier labels.");
        List<String> tiers = new ArrayList<>();
        for (int i = 0; i < tierJson.length(); i++) {
            Object label = tierJson.get(i);
            if (!(label instanceof String) || ((String) label).trim().isEmpty()) {
                throw new JSONException("Empty raid tier label.");
            }
            tiers.add((String) label);
        }
        JSONArray records = root.getJSONArray("records");
        if (records.length() == 0) throw new JSONException("Empty raid records.");
        List<RaidModels.Event> events = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i);
            String name = string(record, "name");
            String difficulty = string(record, "journeyDifficulty");
            if (!seen.add(RaidMatcher.normalize(name) + "|" + difficulty)) {
                throw new JSONException("Duplicate raid event and journey difficulty.");
            }
            JSONArray choices = record.getJSONArray("options");
            if (choices.length() != 1 && choices.length() != 3) {
                throw new JSONException("Raid requires one battle or I, II and III.");
            }
            List<RaidModels.Option> options = new ArrayList<>();
            for (int j = 0; j < choices.length(); j++) {
                JSONObject option = choices.getJSONObject(j);
                int tier = integer(option, "tier", 0);
                int expectedTier = choices.length() == 1 ? 0 : j + 1;
                if (tier != expectedTier) throw new JSONException("Duplicate or unordered raid tiers.");
                options.add(new RaidModels.Option(tier, string(option, "title"),
                        integer(option, "recommendedRank", 1), integer(option, "victoryCoin", 0),
                        integer(option, "missionBonusCoin", 0), integer(option, "missionCount", 0),
                        string(option, "success"), string(option, "failure")));
            }
            events.add(new RaidModels.Event(name, difficulty, options));
        }
        return new RaidModels.Data(rank, coin, tiers, events);
    }

    private static String string(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new JSONException("Invalid raid string: " + key);
        }
        return ((String) value).trim();
    }

    private static int integer(JSONObject object, String key, int minimum) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof Number)) throw new JSONException("Invalid raid integer: " + key);
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < minimum || number > Integer.MAX_VALUE) {
            throw new JSONException("Invalid raid integer: " + key);
        }
        return (int) number;
    }
}
