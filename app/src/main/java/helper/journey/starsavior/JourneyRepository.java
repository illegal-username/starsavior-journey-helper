package helper.journey.starsavior;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class JourneyRepository {
    private static final String ASSET_NAME = "journey_choices.json";
    private static final String EXAMPLE_ASSET_NAME = "journey_choices.example.json";
    private static final String EXAMPLE_SOURCE = "public-example";
    private static final String UPDATED_NAME = "journey_choices_updated.json";
    private static final String PREVIOUS_NAME = "journey_choices_previous.json";
    private static final String TEMP_NAME = "journey_choices_update.tmp";
    private static final Object FILE_LOCK = new Object();

    private JourneyRepository() {}

    public static JourneyModels.Data load(Context context) throws IOException, JSONException {
        synchronized (FILE_LOCK) {
            JourneyModels.Data updated = tryLoadFile(new File(context.getFilesDir(), UPDATED_NAME));
            if (updated != null) return updated;

            JourneyModels.Data previous = tryLoadFile(new File(context.getFilesDir(), PREVIOUS_NAME));
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
            File directory = context.getFilesDir();
            File current = new File(directory, UPDATED_NAME);
            File previous = new File(directory, PREVIOUS_NAME);
            File temporary = new File(directory, TEMP_NAME);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                output.write(bytes);
                output.flush();
                output.getFD().sync();
            }

            if (previous.exists() && !previous.delete()) {
                throw new IOException("이전 DB 백업을 정리하지 못했습니다.");
            }
            if (current.exists() && !current.renameTo(previous)) {
                throw new IOException("현재 DB를 백업하지 못했습니다.");
            }
            if (!temporary.renameTo(current)) {
                if (previous.exists()) previous.renameTo(current);
                throw new IOException("새 DB를 적용하지 못했습니다.");
            }
        }
        return parsed;
    }

    public static boolean hasDownloadedDatabase(Context context) {
        synchronized (FILE_LOCK) {
            return new File(context.getFilesDir(), UPDATED_NAME).isFile();
        }
    }

    static JourneyModels.Data parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray records = root.getJSONArray("records");
        List<JourneyModels.Event> events = new ArrayList<>(records.length());

        for (int eventIndex = 0; eventIndex < records.length(); eventIndex++) {
            JSONObject eventJson = records.getJSONObject(eventIndex);
            JSONArray choicesJson = eventJson.getJSONArray("choices");
            List<JourneyModels.Choice> choices = new ArrayList<>(choicesJson.length());

            for (int choiceIndex = 0; choiceIndex < choicesJson.length(); choiceIndex++) {
                JSONObject choiceJson = choicesJson.getJSONObject(choiceIndex);
                JSONArray outcomesJson = choiceJson.getJSONArray("outcomes");
                List<JourneyModels.Outcome> outcomes = new ArrayList<>(outcomesJson.length());

                for (int outcomeIndex = 0; outcomeIndex < outcomesJson.length(); outcomeIndex++) {
                    JSONObject outcomeJson = outcomesJson.getJSONObject(outcomeIndex);
                    outcomes.add(new JourneyModels.Outcome(
                            outcomeJson.optString("label"),
                            outcomeJson.optString("difficulty"),
                            outcomeJson.optString("condition"),
                            outcomeJson.optString("success"),
                            outcomeJson.optString("failure")
                    ));
                }

                choices.add(new JourneyModels.Choice(choiceJson.getString("text"), outcomes));
            }

            events.add(new JourneyModels.Event(
                    eventJson.getString("event"),
                    eventJson.optString("context"),
                    choices
            ));
        }

        return new JourneyModels.Data(
                root.optInt("schema", 1),
                root.optString("generatedAt"),
                root.optString("source"),
                root.optString("upstreamRevision"),
                root.optInt("recordCount", events.size()),
                root.optInt("choiceCount", countChoices(events)),
                events
        );
    }

    static void validate(JourneyModels.Data data) throws JSONException {
        if (data.schema != 4) throw new JSONException("지원하지 않는 DB 형식입니다.");
        if (data.events.isEmpty()) throw new JSONException("선택지 레코드가 없습니다.");
        if (data.recordCount != data.events.size()) throw new JSONException("레코드 개수가 일치하지 않습니다.");

        int actualChoices = 0;
        Set<String> signatures = new HashSet<>();
        for (JourneyModels.Event event : data.events) {
            if (event.name.trim().isEmpty()) throw new JSONException("이벤트 이름이 비어 있습니다.");
            if (event.choices.size() < 2) throw new JSONException("선택지가 두 개보다 적은 이벤트가 있습니다.");
            StringBuilder signature = new StringBuilder(JourneyMatcher.normalize(event.name));
            for (JourneyModels.Choice choice : event.choices) {
                String normalized = JourneyMatcher.normalize(choice.text);
                if (normalized.isEmpty()) throw new JSONException("선택지 문구가 비어 있습니다.");
                if (choice.outcomes.isEmpty()) throw new JSONException("선택지 결과가 비어 있습니다.");
                signature.append('|');
                signature.append(normalized);
                actualChoices++;
            }
            if (!signatures.add(signature.toString())) throw new JSONException("중복 이벤트·선택지 그룹이 있습니다.");
        }
        if (data.choiceCount != actualChoices) throw new JSONException("선택지 개수가 일치하지 않습니다.");
    }

    private static int countChoices(List<JourneyModels.Event> events) {
        int result = 0;
        for (JourneyModels.Event event : events) result += event.choices.size();
        return result;
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
