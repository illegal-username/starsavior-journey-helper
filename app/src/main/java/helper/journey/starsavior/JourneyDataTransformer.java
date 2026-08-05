package helper.journey.starsavior;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Converts the public Star Savior DB documents into the compact matcher database. */
final class JourneyDataTransformer {
    static final String SOURCE = "https://star-savior-arcana-db.pages.dev/journey";
    static final List<String> FILES = List.of(
            "journeys.json",
            "journey_items.json",
            "potentials.json",
            "stat_potentials.json",
            "journey_buffs.json",
            "arcanas.json"
    );

    private static final String KO = "ko-KR";
    private static final Pattern COLOR_OPEN = Pattern.compile("<color=[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLOR_CLOSE = Pattern.compile("</color>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BREAK = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern NON_MATCH_TEXT = Pattern.compile("[^가-힣A-Za-z0-9]");

    private static final Map<String, String> STAT_NAMES = Map.of(
            "JST_POWER", "힘",
            "JST_HEALTH", "체력",
            "JST_ENDURANCE", "인내",
            "JST_FOCUS", "집중",
            "JST_PROTECT", "보호"
    );

    private static final Map<String, String> ITEM_STAT_NAMES = Map.ofEntries(
            Map.entry("UT_JST_POWER", "힘"),
            Map.entry("UT_JST_HEALTH", "체력"),
            Map.entry("UT_JST_ENDURANCE", "인내"),
            Map.entry("UT_JST_FOCUS", "집중"),
            Map.entry("UT_JST_PROTECT", "보호"),
            Map.entry("UT_PP", "잠재력 포인트"),
            Map.entry("UT_STAMINA", "스태미나"),
            Map.entry("UT_CONDITION", "컨디션"),
            Map.entry("UT_TRAINING_EXP_POWER", "힘 훈련 경험치"),
            Map.entry("UT_TRAINING_EXP_HEALTH", "체력 훈련 경험치"),
            Map.entry("UT_TRAINING_EXP_ENDURANCE", "인내 훈련 경험치"),
            Map.entry("UT_TRAINING_EXP_FOCUS", "집중 훈련 경험치"),
            Map.entry("UT_TRAINING_EXP_PROTECT", "보호 훈련 경험치")
    );

    private JourneyDataTransformer() {}

    static String transform(Map<String, String> documents, String upstreamRevision,
                            String generatedAt) throws JSONException {
        for (String file : FILES) {
            if (!documents.containsKey(file)) throw new JSONException(file + " 파일이 없습니다.");
        }

        JSONObject journeys = new JSONObject(documents.get("journeys.json"));
        JSONArray items = new JSONArray(documents.get("journey_items.json"));
        JSONArray potentials = new JSONArray(documents.get("potentials.json"));
        JSONArray statPotentials = new JSONArray(documents.get("stat_potentials.json"));
        JSONArray buffs = new JSONArray(documents.get("journey_buffs.json"));
        JSONArray arcanas = new JSONArray(documents.get("arcanas.json"));
        Formatter formatter = new Formatter(items, potentials, statPotentials, buffs);

        Map<String, List<RecordSource>> grouped = new LinkedHashMap<>();
        Iterator<String> journeyKeys = journeys.keys();
        while (journeyKeys.hasNext()) {
            String eventKey = journeyKeys.next();
            JSONArray variants = journeys.optJSONArray(eventKey);
            if (variants == null) continue;
            for (int variantIndex = 0; variantIndex < variants.length(); variantIndex++) {
                JSONObject variant = variants.optJSONObject(variantIndex);
                if (variant == null) continue;
                JSONArray rawChoices = variant.optJSONArray("choices");
                List<String> choiceTexts = choiceTexts(rawChoices);
                String event = local(variant.opt("name"));
                if (event.isEmpty()) event = eventKey;

                String difficulty = localizedList(variant.optJSONArray("difficulties"));
                String hint = !difficulty.isEmpty()
                        ? difficulty
                        : variants.length() > 1 ? "경우 " + (variantIndex + 1) : "";
                String label = hint.isEmpty() ? event : event + " · " + hint;
                addRecord(grouped, new RecordSource(
                        event, "", choiceTexts, formatter.outcomes(rawChoices, label)));
            }
        }

        for (int arcanaIndex = 0; arcanaIndex < arcanas.length(); arcanaIndex++) {
            JSONObject arcana = arcanas.optJSONObject(arcanaIndex);
            if (arcana == null) continue;
            JSONArray events = arcana.optJSONArray("events");
            if (events == null) continue;
            for (int eventIndex = 0; eventIndex < events.length(); eventIndex++) {
                JSONObject eventData = events.optJSONObject(eventIndex);
                if (eventData == null) continue;
                JSONArray rawChoices = eventData.optJSONArray("choices");
                List<String> choiceTexts = choiceTexts(rawChoices);
                String event = local(eventData.opt("name"));
                String context = local(arcana.opt("char_name")) + " · " + local(arcana.opt("name"));
                String label = event + " · " + context;
                addRecord(grouped, new RecordSource(
                        event, context, choiceTexts, formatter.outcomes(rawChoices, label)));
            }
        }

        List<JSONObject> records = new ArrayList<>();
        int choiceCount = 0;
        for (List<RecordSource> sources : grouped.values()) {
            if (sources.isEmpty()) continue;
            Set<String> contexts = new LinkedHashSet<>();
            for (RecordSource source : sources) {
                if (!source.context.isEmpty()) contexts.add(source.context);
            }

            JSONArray choices = new JSONArray();
            RecordSource first = sources.get(0);
            for (int choiceIndex = 0; choiceIndex < first.choiceTexts.size(); choiceIndex++) {
                Map<String, OutcomeData> unique = new LinkedHashMap<>();
                for (RecordSource source : sources) {
                    if (choiceIndex >= source.outcomes.size()) continue;
                    OutcomeData outcome = source.outcomes.get(choiceIndex);
                    unique.putIfAbsent(outcome.signature(), outcome);
                }
                JSONArray outcomes = new JSONArray();
                boolean single = unique.size() == 1;
                for (OutcomeData outcome : unique.values()) outcomes.put(outcome.toJson(single ? "" : outcome.label));
                choices.put(new JSONObject()
                        .put("text", first.choiceTexts.get(choiceIndex))
                        .put("outcomes", outcomes));
                choiceCount++;
            }

            records.add(new JSONObject()
                    .put("event", first.event)
                    .put("context", summarized(contexts, 2, ""))
                    .put("choices", choices));
        }

        Collator korean = Collator.getInstance(Locale.KOREAN);
        korean.setStrength(Collator.TERTIARY);
        records.sort(Comparator.comparing(record -> record.optString("event"), korean));
        JSONArray recordArray = new JSONArray();
        for (JSONObject record : records) recordArray.put(record);

        JSONObject result = new JSONObject()
                .put("schema", 2)
                .put("generatedAt", generatedAt == null ? "" : generatedAt)
                .put("source", SOURCE)
                .put("upstreamRevision", upstreamRevision == null ? "" : upstreamRevision)
                .put("notice", "비영리 팬 데이터베이스의 선택지/보상 정보를 가공했습니다. 게임 및 원자료의 권리는 각 권리자에게 있습니다.")
                .put("recordCount", records.size())
                .put("choiceCount", choiceCount)
                .put("records", recordArray);
        return result.toString() + "\n";
    }

    private static void addRecord(Map<String, List<RecordSource>> grouped, RecordSource source) {
        if (source.choiceTexts.size() < 2 || source.choiceTexts.size() != source.outcomes.size()) return;
        List<String> normalized = new ArrayList<>(source.choiceTexts.size());
        for (String choice : source.choiceTexts) {
            String value = normalize(choice);
            if (value.isEmpty()) return;
            normalized.add(value);
        }
        String signature = normalize(source.event) + "|" + String.join("|", normalized);
        grouped.computeIfAbsent(signature, ignored -> new ArrayList<>()).add(source);
    }

    private static List<String> choiceTexts(JSONArray choices) {
        if (choices == null) return Collections.emptyList();
        List<String> result = new ArrayList<>(choices.length());
        for (int index = 0; index < choices.length(); index++) {
            JSONObject choice = choices.optJSONObject(index);
            result.add(choice == null ? "" : local(choice.opt("name")));
        }
        return result;
    }

    private static String localizedList(JSONArray values) {
        if (values == null) return "";
        List<String> result = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            String value = local(values.opt(index));
            if (!value.isEmpty()) result.add(value);
        }
        return String.join("/", result);
    }

    private static String summarized(Set<String> values, int fullLimit, String noun) {
        if (values.isEmpty()) return "";
        List<String> list = new ArrayList<>(values);
        if (list.size() <= fullLimit) return String.join(" / ", list);
        return list.get(0) + " 외 " + (list.size() - 1) + "개" + (noun.isEmpty() ? "" : " " + noun);
    }

    private static String local(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof String) return (String) value;
        if (value instanceof JSONObject) return ((JSONObject) value).optString(KO, "");
        return "";
    }

    private static String clean(Object value) {
        String result = value == null || value == JSONObject.NULL ? "" : String.valueOf(value);
        result = COLOR_OPEN.matcher(result).replaceAll("");
        result = COLOR_CLOSE.matcher(result).replaceAll("");
        result = BREAK.matcher(result).replaceAll(" ");
        result = TAG.matcher(result).replaceAll("");
        return SPACE.matcher(result).replaceAll(" ").trim();
    }

    private static String normalize(String value) {
        return NON_MATCH_TEXT.matcher(Normalizer.normalize(clean(value), Normalizer.Form.NFKC))
                .replaceAll("").toLowerCase(Locale.ROOT);
    }

    private static String signed(Object minimum, Object maximum) {
        if (!(minimum instanceof Number)) return "";
        String min = signedNumber((Number) minimum);
        if (!(maximum instanceof Number) || numbersEqual((Number) minimum, (Number) maximum)) return min;
        return min + "~" + signedNumber((Number) maximum);
    }

    private static String signedNumber(Number value) {
        double number = value.doubleValue();
        String plain = BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
        return number > 0 ? "+" + plain : plain;
    }

    private static boolean numbersEqual(Number first, Number second) {
        return Double.compare(first.doubleValue(), second.doubleValue()) == 0;
    }

    private static String number(Object value) {
        if (!(value instanceof Number)) return String.valueOf(value == null ? "" : value);
        return BigDecimal.valueOf(((Number) value).doubleValue()).stripTrailingZeros().toPlainString();
    }

    private static final class Formatter {
        private final Map<Long, JSONObject> items;
        private final Map<Long, JSONObject> potentials;
        private final Map<Long, JSONObject> statPotentials;
        private final Map<Long, JSONObject> buffs;

        Formatter(JSONArray items, JSONArray potentials, JSONArray statPotentials, JSONArray buffs) {
            this.items = byId(items);
            this.potentials = byId(potentials);
            this.statPotentials = byId(statPotentials);
            this.buffs = byId(buffs);
        }

        List<OutcomeData> outcomes(JSONArray choices, String label) {
            if (choices == null) return Collections.emptyList();
            List<OutcomeData> result = new ArrayList<>(choices.length());
            for (int index = 0; index < choices.length(); index++) {
                JSONObject choice = choices.optJSONObject(index);
                if (choice == null) {
                    result.add(new OutcomeData(label, "", "효과 없음", ""));
                    continue;
                }
                JSONArray failure = choice.optJSONArray("failure_rewards");
                result.add(new OutcomeData(
                        label,
                        formatCondition(choice.optJSONObject("condition")),
                        formatRewardGroups(choice.optJSONArray("success_rewards")),
                        failure == null || failure.length() == 0 ? "" : formatRewardGroups(failure)
                ));
            }
            return result;
        }

        private String formatRewardGroups(JSONArray groups) {
            if (groups == null || groups.length() == 0) return "효과 없음";
            List<String> parts = new ArrayList<>();
            for (int index = 0; index < groups.length(); index++) {
                Object group = groups.opt(index);
                if (group instanceof JSONArray) {
                    JSONArray alternatives = (JSONArray) group;
                    List<String> choices = new ArrayList<>();
                    for (int altIndex = 0; altIndex < alternatives.length(); altIndex++) {
                        JSONObject reward = alternatives.optJSONObject(altIndex);
                        if (reward != null) choices.add(formatReward(reward));
                    }
                    if (!choices.isEmpty()) parts.add(String.join(" 또는 ", choices));
                } else if (group instanceof JSONObject) {
                    parts.add(formatReward((JSONObject) group));
                }
            }
            return parts.isEmpty() ? "효과 없음" : String.join(" · ", parts);
        }

        private String formatReward(JSONObject reward) {
            String type = reward.optString("type");
            String value = signed(reward.opt("min"), reward.opt("max"));
            switch (type) {
                case "RT_STAT":
                    return STAT_NAMES.getOrDefault(reward.optString("reward_stat"), reward.optString("reward_stat")) + " " + value;
                case "RT_STAMINA": return "스태미나 " + value;
                case "RT_CONDITION": return "컨디션 " + value;
                case "RT_COIN": return "오래된 동전 " + value;
                case "RT_POTEN_POINT": return "잠재력 포인트 " + value;
                case "RT_JOURNEY_BUFF": return formatBuff(reward.optLong("reward_id"));
                case "RT_JOURNEY_BUFF_REMOVE_NEG": return "해로운 여정 버프 제거";
                case "RT_JOURNEY_BUFF_REMOVE_POS": return "이로운 여정 버프 제거";
                case "RT_JOURNEY_ITEM": return formatItem(reward.optLong("reward_id"));
                case "RT_SE_POTEN": return formatPotential(potentials, reward.optLong("reward_id"));
                case "RT_STAT_POTEN": return formatPotential(statPotentials, reward.optLong("reward_id"));
                case "SELECTABLE_CHARM": return "여정 부적 선택";
                default: return type.isEmpty() ? "알 수 없는 효과" : type + (value.isEmpty() ? "" : " " + value);
            }
        }

        private String formatCondition(JSONObject condition) {
            if (condition == null) return "";
            String type = condition.optString("type");
            String value = number(condition.opt("value"));
            switch (type) {
                case "RR_COIN_USE": return "오래된 동전 -" + value;
                case "RR_STAMINA_USE": return "스태미나 -" + value;
                case "RR_PP_USE": return "잠재력 포인트 -" + value;
                case "RR_STAT":
                    return STAT_NAMES.getOrDefault(condition.optString("target"), condition.optString("target"))
                            + " " + value + " 필요";
                case "RR_ITEM_USE":
                    JSONObject item = items.get(condition.optLong("target"));
                    String name = item == null ? "여정 아이템" : local(item.opt("name"));
                    String amount = condition.has("value") && !condition.isNull("value") ? value : "1";
                    return name + " " + amount + "개 소모";
                default: return type;
            }
        }

        private String formatItem(long id) {
            JSONObject item = items.get(id);
            if (item == null) return "여정 아이템 획득";
            String name = local(item.opt("name"));
            JSONArray stats = item.optJSONArray("stats");
            if (stats == null || stats.length() == 0) return name;
            List<String> parts = new ArrayList<>();
            for (int index = 0; index < stats.length(); index++) {
                JSONObject stat = stats.optJSONObject(index);
                if (stat == null) continue;
                String type = stat.optString("type");
                if (ITEM_STAT_NAMES.containsKey(type)) {
                    parts.add(ITEM_STAT_NAMES.get(type) + " " + signed(stat.opt("value"), stat.opt("value")));
                } else if ("UT_BUFF_DELETE_NEGATIVE".equals(type)) {
                    parts.add("해로운 여정 버프 제거");
                } else if ("UT_BUFF_ADD".equals(type)) {
                    JSONObject buff = buffs.get(stat.optLong("value"));
                    parts.add(buff == null ? "여정 버프 획득" : "버프: " + local(buff.opt("name")));
                } else if (!type.isEmpty()) {
                    parts.add(type);
                }
            }
            String statsText = String.join(" · ", parts);
            return statsText.isEmpty() ? name : name + " (" + statsText + ")";
        }

        private String formatBuff(long id) {
            JSONObject buff = buffs.get(id);
            if (buff == null) return "여정 버프 획득";
            String description = conciseDescription(local(buff.opt("desc")));
            String turn = buff.optInt("turn") > 0 ? buff.optInt("turn") + "턴" : "";
            String detail = joinNonEmpty(description, turn);
            String name = local(buff.opt("name"));
            return detail.isEmpty() ? name : name + " (" + detail + ")";
        }

        private String formatPotential(Map<Long, JSONObject> map, long id) {
            JSONObject potential = map.get(id);
            if (potential == null) return "잠재력 획득";
            String name = local(potential.opt("name"));
            String description = clean(local(potential.opt("desc")));
            return description.isEmpty() ? name : name + " (" + description + ")";
        }

        private static Map<Long, JSONObject> byId(JSONArray values) {
            Map<Long, JSONObject> result = new LinkedHashMap<>();
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value != null) result.put(value.optLong("id"), value);
            }
            return result;
        }

        private static String conciseDescription(String value) {
            String description = clean(value);
            String[] sentences = description.split("(?<=\\.)\\s+");
            if (sentences.length <= 1) return description;
            List<String> rest = new ArrayList<>();
            for (int index = 1; index < sentences.length; index++) rest.add(sentences[index]);
            return String.join(" ", rest);
        }

        private static String joinNonEmpty(String first, String second) {
            if (first.isEmpty()) return second;
            if (second.isEmpty()) return first;
            return first + ", " + second;
        }
    }

    private static final class RecordSource {
        final String event;
        final String context;
        final List<String> choiceTexts;
        final List<OutcomeData> outcomes;

        RecordSource(String event, String context, List<String> choiceTexts, List<OutcomeData> outcomes) {
            this.event = event;
            this.context = context;
            this.choiceTexts = choiceTexts;
            this.outcomes = outcomes;
        }
    }

    private static final class OutcomeData {
        final String label;
        final String condition;
        final String success;
        final String failure;

        OutcomeData(String label, String condition, String success, String failure) {
            this.label = label;
            this.condition = condition;
            this.success = success;
            this.failure = failure;
        }

        String signature() {
            return condition + "|" + success + "|" + failure;
        }

        JSONObject toJson(String outputLabel) throws JSONException {
            return new JSONObject()
                    .put("label", outputLabel)
                    .put("condition", condition)
                    .put("success", success)
                    .put("failure", failure);
        }
    }
}
