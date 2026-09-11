package helper.journey.starsavior;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JourneyRepository {
    private static final String ASSET_NAME = "journey_choices.json";
    private static final String EXAMPLE_ASSET_NAME = "journey_choices.example.json";
    private static final String EXAMPLE_SOURCE = "public-example";
    private static final Object FILE_LOCK = new Object();

    private JourneyRepository() {}

    public static JourneyModels.Data load(Context context) throws IOException, JSONException {
        GameLanguage language = AppLanguage.of(context);
        synchronized (FILE_LOCK) {
            if (BuildConfig.BUNDLED_TEST_DATABASE) {
                JourneyModels.Data bundledTest = tryLoadAsset(context, language.tag + "/" + ASSET_NAME, language);
                if (bundledTest != null) return bundledTest;
                throw new IOException("Cannot load the bundled database for this language.");
            }

            JourneyModels.Data updated = tryLoadFile(
                    JourneyDatabaseFileStore.updated(databaseDirectory(context)), language);
            if (updated != null) return updated;

            JourneyModels.Data previous = tryLoadFile(
                    JourneyDatabaseFileStore.previous(databaseDirectory(context)), language);
            if (previous != null) return previous;

            if (language == GameLanguage.KOREAN) {
                JourneyModels.Data legacy = tryLoadFile(JourneyDatabaseFileStore.updated(context.getFilesDir()), language);
                if (legacy == null) legacy = tryLoadFile(JourneyDatabaseFileStore.previous(context.getFilesDir()), language);
                if (legacy != null) return legacy;
            }

            JourneyModels.Data bundled = tryLoadAsset(context, language.tag + "/" + ASSET_NAME, language);
            if (bundled != null) return bundled;

            JourneyModels.Data example = tryLoadAsset(context, language.tag + "/" + EXAMPLE_ASSET_NAME, language);
            if (example != null) return example;
            throw new IOException("Cannot load the bundled or example database.");
        }
    }

    public static boolean isExampleDatabase(JourneyModels.Data data) {
        return data != null && EXAMPLE_SOURCE.equals(data.source);
    }

    public static JourneyModels.Data installUpdated(Context context, String json) throws IOException, JSONException {
        JourneyModels.Data parsed = parse(json);
        validate(parsed);
        requireLanguage(parsed, AppLanguage.of(context));

        synchronized (FILE_LOCK) {
            JourneyDatabaseFileStore.install(databaseDirectory(context), json);
        }
        return parsed;
    }

    public static boolean hasDownloadedDatabase(Context context) {
        synchronized (FILE_LOCK) {
            return JourneyDatabaseFileStore.updated(databaseDirectory(context)).isFile()
                    || (AppLanguage.of(context) == GameLanguage.KOREAN
                    && JourneyDatabaseFileStore.updated(context.getFilesDir()).isFile());
        }
    }

    static File databaseDirectory(Context context) {
        return JourneyDatabaseFileStore.directory(context.getFilesDir(), AppLanguage.of(context));
    }

    static void requireLanguage(JourneyModels.Data data, GameLanguage language) throws JSONException {
        if (!language.tag.equals(data.language)) throw new JSONException("Database language mismatch.");
    }

    static JourneyModels.Data parse(String json) throws JSONException {
        byte[] content = json.getBytes(StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(json);
        if (root.optInt("schema") == 4 && root.has("language")
                && !"ko-KR".equals(root.optString("language"))) {
            throw new JSONException("Legacy DB cannot declare another language.");
        }
        Map<String, JourneyModels.ArcanaImageFeature> arcanaImageFeatures =
                parseArcanaImageFeatures(root.optJSONObject("arcanaImageFeatures"));
        JSONArray records = root.getJSONArray("records");
        List<JourneyModels.Event> events = new ArrayList<>(records.length());

        for (int eventIndex = 0; eventIndex < records.length(); eventIndex++) {
            JSONObject eventJson = records.getJSONObject(eventIndex);
            JSONArray choicesJson = eventJson.getJSONArray("choices");
            List<JourneyModels.Choice> choices = new ArrayList<>(choicesJson.length());

            for (int choiceIndex = 0; choiceIndex < choicesJson.length(); choiceIndex++) {
                JSONObject choiceJson = choicesJson.getJSONObject(choiceIndex);
                JSONArray outcomesJson = choiceJson.getJSONArray("outcomes");
                JSONArray aliasesJson = choiceJson.optJSONArray("aliases");
                List<String> aliases = new ArrayList<>();
                if (aliasesJson != null) {
                    for (int aliasIndex = 0; aliasIndex < aliasesJson.length(); aliasIndex++) {
                        aliases.add(aliasesJson.getString(aliasIndex));
                    }
                }
                List<JourneyModels.Outcome> outcomes = new ArrayList<>(outcomesJson.length());

                for (int outcomeIndex = 0; outcomeIndex < outcomesJson.length(); outcomeIndex++) {
                    JSONObject outcomeJson = outcomesJson.getJSONObject(outcomeIndex);
                    JSONArray arcanaIdsJson = outcomeJson.optJSONArray("arcanaIds");
                    List<String> arcanaIds = new ArrayList<>();
                    Set<String> seenArcanaIds = new HashSet<>();
                    if (arcanaIdsJson != null) {
                        for (int arcanaIndex = 0; arcanaIndex < arcanaIdsJson.length(); arcanaIndex++) {
                            String arcanaId = arcanaIdsJson.getString(arcanaIndex).trim();
                            if (arcanaId.isEmpty()) {
                                throw new JSONException("Arcana source ID is empty.");
                            }
                            if (!seenArcanaIds.add(arcanaId)) {
                                throw new JSONException("Duplicate Arcana source ID.");
                            }
                            arcanaIds.add(arcanaId);
                        }
                    }
                    outcomes.add(new JourneyModels.Outcome(
                            outcomeJson.optString("label"),
                            outcomeJson.optString("difficulty"),
                            outcomeJson.optString("condition"),
                            outcomeJson.optString("success"),
                            outcomeJson.optString("failure"),
                            arcanaIds
                    ));
                }

                choices.add(new JourneyModels.Choice(
                        choiceJson.getString("text"), aliases, outcomes));
            }

            events.add(new JourneyModels.Event(
                    eventJson.getString("event"),
                    eventJson.optString("context"),
                    choices,
                    optionalBoolean(eventJson, "sameProgress")
            ));
        }

        return new JourneyModels.Data(
                root.optInt("schema", 1),
                root.optString("generatedAt"),
                root.optString("source"),
                root.optString("upstreamRevision"),
                root.optInt("recordCount", events.size()),
                root.optInt("choiceCount", countChoices(events)),
                sha256(content),
                content.length,
                events,
                arcanaImageFeatures,
                root.optInt("schema", 1) == 4 ? "ko-KR" : root.getString("language")
        );
    }

    private static Map<String, JourneyModels.ArcanaImageFeature> parseArcanaImageFeatures(
            JSONObject featureRoot) throws JSONException {
        if (featureRoot == null) return Map.of();
        if (featureRoot.optInt("schema", -1) != 1
                || !"hsv-h12-s4".equals(featureRoot.optString("kind"))) {
            throw new JSONException("Unsupported Arcana image feature format.");
        }
        JSONArray items = featureRoot.getJSONArray("items");
        Map<String, JourneyModels.ArcanaImageFeature> result = new LinkedHashMap<>();
        for (int itemIndex = 0; itemIndex < items.length(); itemIndex++) {
            JSONObject item = items.getJSONObject(itemIndex);
            String arcanaId = item.getString("arcanaId").trim();
            JSONArray values = item.getJSONArray("histogram");
            if (arcanaId.isEmpty() || result.containsKey(arcanaId)
                    || values.length() != JourneyModels.ArcanaImageFeature.HISTOGRAM_SIZE) {
                throw new JSONException("Invalid Arcana image feature item.");
            }
            int[] histogram = new int[values.length()];
            for (int valueIndex = 0; valueIndex < values.length(); valueIndex++) {
                histogram[valueIndex] = values.getInt(valueIndex);
            }
            result.put(arcanaId, new JourneyModels.ArcanaImageFeature(arcanaId, histogram));
        }
        return result;
    }

    static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    static void validate(JourneyModels.Data data) throws JSONException {
        if (data.schema != 4 && data.schema != 5) throw new JSONException("Unsupported database schema.");
        try { GameLanguage.require(data.language); }
        catch (IllegalArgumentException error) { throw new JSONException(error.getMessage()); }
        if (data.schema == 4 && !"ko-KR".equals(data.language)) throw new JSONException("Legacy DB must be Korean.");
        if (data.events.isEmpty()) throw new JSONException("No choice records.");
        if (data.recordCount != data.events.size()) throw new JSONException("Record count mismatch.");

        int actualChoices = 0;
        Set<String> signatures = new HashSet<>();
        Set<String> ambiguousArcanaIds = new HashSet<>();
        for (JourneyModels.Event event : data.events) {
            if (event.name.trim().isEmpty()) throw new JSONException("Empty event name.");
            if (event.choices.size() < 2) throw new JSONException("An event contains fewer than two choices.");
            StringBuilder signature = new StringBuilder(event.sameProgress
                    ? "same-progress|"
                    : "choice-results|");
            signature.append(JourneyMatcher.normalize(event.name));
            Set<String> eventArcanaIds = new HashSet<>();
            for (JourneyModels.Choice choice : event.choices) {
                String normalized = JourneyMatcher.normalize(choice.text);
                if (normalized.isEmpty()) throw new JSONException("Empty choice text.");
                if (choice.outcomes.isEmpty()) throw new JSONException("Empty choice outcomes.");
                Set<String> choiceTexts = new HashSet<>();
                choiceTexts.add(normalized);
                for (String alias : choice.aliases) {
                    String normalizedAlias = JourneyMatcher.normalize(alias);
                    if (normalizedAlias.isEmpty()) throw new JSONException("Empty choice alias.");
                    if (!choiceTexts.add(normalizedAlias)) {
                        throw new JSONException("Duplicate choice alias.");
                    }
                }
                for (JourneyModels.Outcome outcome : choice.outcomes) {
                    Set<String> arcanaIds = new HashSet<>();
                    eventArcanaIds.addAll(outcome.arcanaIds);
                    for (String arcanaId : outcome.arcanaIds) {
                        if (arcanaId.trim().isEmpty()) {
                            throw new JSONException("Arcana source ID is empty.");
                        }
                        if (!arcanaIds.add(arcanaId)) {
                            throw new JSONException("Duplicate Arcana source ID.");
                        }
                    }
                }
                signature.append('|');
                signature.append(normalized);
                actualChoices++;
            }
            boolean distinguishableArcanaSources = false;
            for (JourneyModels.Choice choice : event.choices) {
                for (JourneyModels.Outcome outcome : choice.outcomes) {
                    if (!outcome.arcanaIds.isEmpty()
                            && !new HashSet<>(outcome.arcanaIds).equals(eventArcanaIds)) {
                        distinguishableArcanaSources = true;
                    }
                }
            }
            if (eventArcanaIds.size() > 1 && distinguishableArcanaSources) {
                ambiguousArcanaIds.addAll(eventArcanaIds);
            }
            if (!signatures.add(signature.toString())) throw new JSONException("Duplicate event-choice group.");
        }
        if (data.choiceCount != actualChoices) throw new JSONException("Choice count mismatch.");
        for (Map.Entry<String, JourneyModels.ArcanaImageFeature> entry
                : data.arcanaImageFeatures.entrySet()) {
            JourneyModels.ArcanaImageFeature feature = entry.getValue();
            if (!entry.getKey().equals(feature.arcanaId)
                    || feature.histogram.length != JourneyModels.ArcanaImageFeature.HISTOGRAM_SIZE) {
                throw new JSONException("Invalid Arcana image feature item.");
            }
            long histogramTotal = 0;
            for (int value : feature.histogram) {
                if (value < 0 || value > 65535) {
                    throw new JSONException("Arcana image feature value is out of range.");
                }
                histogramTotal += value;
            }
            if (histogramTotal <= 0) {
                throw new JSONException("Arcana image feature histogram is empty.");
            }
        }
        if (!data.arcanaImageFeatures.isEmpty()
                && !data.arcanaImageFeatures.keySet().equals(ambiguousArcanaIds)) {
            throw new JSONException("Arcana image features do not match ambiguous source IDs.");
        }
    }

    private static int countChoices(List<JourneyModels.Event> events) {
        int result = 0;
        for (JourneyModels.Event event : events) result += event.choices.size();
        return result;
    }

    private static boolean optionalBoolean(JSONObject value, String name) throws JSONException {
        if (!value.has(name) || value.isNull(name)) return false;
        Object raw = value.get(name);
        if (!(raw instanceof Boolean)) {
            throw new JSONException(name + " must be a boolean.");
        }
        return (Boolean) raw;
    }

    private static JourneyModels.Data tryLoadFile(File file, GameLanguage language) {
        if (!file.isFile()) return null;
        try (InputStream input = new FileInputStream(file)) {
            JourneyModels.Data data = parse(readUtf8(input));
            validate(data);
            requireLanguage(data, language);
            return data;
        } catch (IOException | JSONException ignored) {
            return null;
        }
    }

    private static JourneyModels.Data tryLoadAsset(Context context, String name, GameLanguage language) {
        try (InputStream input = context.getAssets().open(name)) {
            JourneyModels.Data data = parse(readUtf8(input));
            validate(data);
            requireLanguage(data, language);
            return data;
        } catch (IOException | JSONException ignored) {
            return null;
        }
    }

    private static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toString(StandardCharsets.UTF_8.name());
    }
}
