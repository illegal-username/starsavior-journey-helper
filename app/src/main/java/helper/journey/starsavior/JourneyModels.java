package helper.journey.starsavior;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JourneyModels {
    private JourneyModels() {}

    public static final class Data {
        public final int schema;
        public final String generatedAt;
        public final String source;
        public final String upstreamRevision;
        public final int recordCount;
        public final int choiceCount;
        public final String contentSha256;
        public final int contentLength;
        public final List<Event> events;
        public final Map<String, ArcanaImageFeature> arcanaImageFeatures;

        public Data(int schema, String generatedAt, String source, String upstreamRevision,
                    int recordCount, int choiceCount, List<Event> events) {
            this(schema, generatedAt, source, upstreamRevision, recordCount, choiceCount,
                    "", -1, events, Map.of());
        }

        public Data(int schema, String generatedAt, String source, String upstreamRevision,
                    int recordCount, int choiceCount, String contentSha256, int contentLength,
                    List<Event> events) {
            this(schema, generatedAt, source, upstreamRevision, recordCount, choiceCount,
                    contentSha256, contentLength, events, Map.of());
        }

        public Data(int schema, String generatedAt, String source, String upstreamRevision,
                    int recordCount, int choiceCount, String contentSha256, int contentLength,
                    List<Event> events, Map<String, ArcanaImageFeature> arcanaImageFeatures) {
            this.schema = schema;
            this.generatedAt = generatedAt;
            this.source = source;
            this.upstreamRevision = upstreamRevision;
            this.recordCount = recordCount;
            this.choiceCount = choiceCount;
            this.contentSha256 = contentSha256 == null ? "" : contentSha256;
            this.contentLength = contentLength;
            this.events = Collections.unmodifiableList(events);
            this.arcanaImageFeatures = Collections.unmodifiableMap(
                    new LinkedHashMap<>(arcanaImageFeatures));
        }
    }

    public static final class ArcanaImageFeature {
        public static final int HISTOGRAM_SIZE = 48;

        public final String arcanaId;
        public final int[] histogram;

        public ArcanaImageFeature(String arcanaId, int[] histogram) {
            this.arcanaId = arcanaId == null ? "" : arcanaId.trim();
            this.histogram = histogram == null ? new int[0] : histogram.clone();
        }
    }

    public static final class Event {
        public final String name;
        public final String context;
        public final List<Choice> choices;
        public final boolean sameProgress;

        public Event(String name, String context, List<Choice> choices) {
            this(name, context, choices, false);
        }

        public Event(String name, String context, List<Choice> choices, boolean sameProgress) {
            this.name = name;
            this.context = context;
            this.choices = Collections.unmodifiableList(choices);
            this.sameProgress = sameProgress;
        }
    }

    public static final class Choice {
        public final String text;
        public final List<String> aliases;
        public final List<Outcome> outcomes;

        public Choice(String text, List<Outcome> outcomes) {
            this(text, List.of(), outcomes);
        }

        public Choice(String text, List<String> aliases, List<Outcome> outcomes) {
            this.text = text;
            this.aliases = Collections.unmodifiableList(aliases);
            this.outcomes = Collections.unmodifiableList(outcomes);
        }

        public List<Outcome> outcomesForDifficulty(String difficulty) {
            if (difficulty == null || difficulty.trim().isEmpty()) return outcomes;

            List<Outcome> matching = new ArrayList<>();
            List<Outcome> generic = new ArrayList<>();
            for (Outcome outcome : outcomes) {
                if (outcome.appliesToDifficulty(difficulty)) matching.add(outcome);
                else if (outcome.difficulty.isEmpty()) generic.add(outcome);
            }
            if (!matching.isEmpty()) return Collections.unmodifiableList(matching);
            return Collections.unmodifiableList(generic);
        }

        /**
         * Applies optional visual source evidence after the normal difficulty filter.
         * Empty or stale evidence deliberately falls back to the complete choice group.
         */
        public List<Outcome> outcomesFor(String difficulty, Set<String> recognizedArcanaIds) {
            List<Outcome> difficultyMatches = outcomesForDifficulty(difficulty);
            if (recognizedArcanaIds == null || recognizedArcanaIds.isEmpty()) {
                return difficultyMatches;
            }

            List<Outcome> sourceMatches = new ArrayList<>();
            for (Outcome outcome : difficultyMatches) {
                if (outcome.belongsToAnyArcana(recognizedArcanaIds)) sourceMatches.add(outcome);
            }
            if (sourceMatches.isEmpty()) return difficultyMatches;
            return Collections.unmodifiableList(sourceMatches);
        }
    }

    public static final class Outcome {
        public final String label;
        public final String difficulty;
        public final String condition;
        public final String success;
        public final String failure;
        public final List<String> arcanaIds;

        public Outcome(String label, String difficulty, String condition, String success, String failure) {
            this(label, difficulty, condition, success, failure, List.of());
        }

        public Outcome(String label, String difficulty, String condition, String success, String failure,
                       List<String> arcanaIds) {
            this.label = label;
            this.difficulty = difficulty == null ? "" : difficulty.trim();
            this.condition = condition;
            this.success = success;
            this.failure = failure;
            Set<String> uniqueIds = new LinkedHashSet<>();
            if (arcanaIds != null) {
                for (String arcanaId : arcanaIds) {
                    String normalized = arcanaId == null ? "" : arcanaId.trim();
                    if (!normalized.isEmpty()) uniqueIds.add(normalized);
                }
            }
            this.arcanaIds = Collections.unmodifiableList(new ArrayList<>(uniqueIds));
        }

        private boolean appliesToDifficulty(String recognizedDifficulty) {
            String expected = recognizedDifficulty.trim();
            if (expected.isEmpty() || difficulty.isEmpty()) return false;
            for (String candidate : difficulty.split("[/,·\\s]+")) {
                if (candidate.equals(expected)) return true;
            }
            return false;
        }

        private boolean belongsToAnyArcana(Set<String> recognizedArcanaIds) {
            for (String arcanaId : arcanaIds) {
                if (recognizedArcanaIds.contains(arcanaId)) return true;
            }
            return false;
        }
    }

    public static final class Match {
        public final Event event;
        public final double confidence;
        public final double eventConfidence;
        public final double choiceConfidence;
        public final boolean eventNameUsed;
        public final boolean ambiguous;
        public final List<String> recognizedEventLines;
        public final List<String> recognizedLines;
        public final List<Double> choiceScores;

        public Match(Event event, double confidence, double eventConfidence, double choiceConfidence,
                     boolean eventNameUsed, boolean ambiguous, List<String> recognizedEventLines,
                     List<String> recognizedLines, List<Double> choiceScores) {
            this.event = event;
            this.confidence = confidence;
            this.eventConfidence = eventConfidence;
            this.choiceConfidence = choiceConfidence;
            this.eventNameUsed = eventNameUsed;
            this.ambiguous = ambiguous;
            this.recognizedEventLines = Collections.unmodifiableList(recognizedEventLines);
            this.recognizedLines = Collections.unmodifiableList(recognizedLines);
            this.choiceScores = Collections.unmodifiableList(choiceScores);
        }

        public boolean isConfident() {
            return event != null && choiceConfidence >= 0.58 && confidence >= 0.58 && !ambiguous;
        }
    }
}
