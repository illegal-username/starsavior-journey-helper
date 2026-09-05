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
        synchronized (FILE_LOCK) {
            if (BuildConfig.BUNDLED_TEST_DATABASE) {
                JourneyModels.Data bundledTest = tryLoadAsset(context, ASSET_NAME);
                if (bundledTest != null) return bundledTest;
                throw new IOException("테스트 APK의 내장 선택지 DB를 읽지 못했습니다.");
            }

            JourneyModels.Data updated = tryLoadFile(
                    JourneyDatabaseFileStore.updated(context.getFilesDir()));
            if (updated != null) return updated;

            JourneyModels.Data previous = tryLoadFile(
                    JourneyDatabaseFileStore.previous(context.getFilesDir()));
            if (previous != null) return previous;

            JourneyModels.Data bundled = tryLoadAsset(context, ASSET_NAME);
            if (bundled != null) return bundled;

            JourneyModels.Data example = tryLoadAsset(context, EXAMPLE_ASSET_NAME);
            if (example != null) return example;
            throw new IOException("내장 또는 예제 선택지 DB를 읽지 못했습니다.");
        }
    }

    public static boolean isExampleDatabase(JourneyModels.Data data) {
        return data != null && EXAMPLE_SOURCE.equals(data.source);
    }

    public static JourneyModels.Data installUpdated(Context context, String json) throws IOException, JSONException {
        JourneyModels.Data parsed = parse(json);
        validate(parsed);

        synchronized (FILE_LOCK) {
            JourneyDatabaseFileStore.install(context.getFilesDir(), json);
        }
        return parsed;
    }

    public static boolean hasDownloadedDatabase(Context context) {
        synchronized (FILE_LOCK) {
            return JourneyDatabaseFileStore.updated(context.getFilesDir()).isFile();
        }
    }

    static JourneyModels.Data parse(String json) throws JSONException {
        byte[] content = json.getBytes(StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(json);
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
                                throw new JSONException("아르카나 출처 ID가 비어 있습니다.");
                            }
                            if (!seenArcanaIds.add(arcanaId)) {
                                throw new JSONException("아르카나 출처 ID가 중복되었습니다.");
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
                arcanaImageFeatures
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
        if (data.schema != 4) throw new JSONException("지원하지 않는 DB 형식입니다.");
        if (data.events.isEmpty()) throw new JSONException("선택지 레코드가 없습니다.");
        if (data.recordCount != data.events.size()) throw new JSONException("레코드 개수가 일치하지 않습니다.");

        int actualChoices = 0;
        Set<String> signatures = new HashSet<>();
        Set<String> ambiguousArcanaIds = new HashSet<>();
        for (JourneyModels.Event event : data.events) {
            if (event.name.trim().isEmpty()) throw new JSONException("이벤트 이름이 비어 있습니다.");
            if (event.choices.size() < 2) throw new JSONException("선택지가 두 개보다 적은 이벤트가 있습니다.");
            StringBuilder signature = new StringBuilder(event.sameProgress
                    ? "same-progress|"
                    : "choice-results|");
            signature.append(JourneyMatcher.normalize(event.name));
            Set<String> eventArcanaIds = new HashSet<>();
            for (JourneyModels.Choice choice : event.choices) {
                String normalized = JourneyMatcher.normalize(choice.text);
                if (normalized.isEmpty()) throw new JSONException("선택지 문구가 비어 있습니다.");
                if (choice.outcomes.isEmpty()) throw new JSONException("선택지 결과가 비어 있습니다.");
                Set<String> choiceTexts = new HashSet<>();
                choiceTexts.add(normalized);
                for (String alias : choice.aliases) {
                    String normalizedAlias = JourneyMatcher.normalize(alias);
                    if (normalizedAlias.isEmpty()) throw new JSONException("선택지 별칭이 비어 있습니다.");
                    if (!choiceTexts.add(normalizedAlias)) {
                        throw new JSONException("선택지 별칭이 중복되었습니다.");
                    }
                }
                for (JourneyModels.Outcome outcome : choice.outcomes) {
                    Set<String> arcanaIds = new HashSet<>();
                    eventArcanaIds.addAll(outcome.arcanaIds);
                    for (String arcanaId : outcome.arcanaIds) {
                        if (arcanaId.trim().isEmpty()) {
                            throw new JSONException("아르카나 출처 ID가 비어 있습니다.");
                        }
                        if (!arcanaIds.add(arcanaId)) {
                            throw new JSONException("아르카나 출처 ID가 중복되었습니다.");
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
            if (!signatures.add(signature.toString())) throw new JSONException("중복 이벤트·선택지 그룹이 있습니다.");
        }
        if (data.choiceCount != actualChoices) throw new JSONException("선택지 개수가 일치하지 않습니다.");
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
            throw new JSONException(name + " 값은 boolean이어야 합니다.");
        }
        return (Boolean) raw;
    }

    private static JourneyModels.Data tryLoadFile(File file) {
        if (!file.isFile()) return null;
        try (InputStream input = new FileInputStream(file)) {
            JourneyModels.Data data = parse(readUtf8(input));
            validate(data);
            return data;
        } catch (IOException | JSONException ignored) {
            return null;
        }
    }

    private static JourneyModels.Data tryLoadAsset(Context context, String name) {
        try (InputStream input = context.getAssets().open(name)) {
            JourneyModels.Data data = parse(readUtf8(input));
            validate(data);
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
