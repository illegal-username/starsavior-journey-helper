package helper.journey.starsavior;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class JourneyModels {
    private JourneyModels() {}

    public static final class Data {
        public final int schema;
        public final String generatedAt;
        public final String source;
        public final String upstreamRevision;
        public final int recordCount;
        public final int choiceCount;
        public final List<Event> events;

        public Data(int schema, String generatedAt, String source, String upstreamRevision,
                    int recordCount, int choiceCount, List<Event> events) {
            this.schema = schema;
            this.generatedAt = generatedAt;
            this.source = source;
            this.upstreamRevision = upstreamRevision;
            this.recordCount = recordCount;
            this.choiceCount = choiceCount;
            this.events = Collections.unmodifiableList(events);
        }
    }

    public static final class Event {
        public final String name;
        public final String context;
        public final List<Choice> choices;

        public Event(String name, String context, List<Choice> choices) {
            this.name = name;
            this.context = context;
            this.choices = Collections.unmodifiableList(choices);
        }
    }

    public static final class Choice {
        public final String text;
        public final List<Outcome> outcomes;

        public Choice(String text, List<Outcome> outcomes) {
            this.text = text;
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
    }

    public static final class Outcome {
        public final String label;
        public final String difficulty;
        public final String condition;
        public final String success;
        public final String failure;

        public Outcome(String label, String difficulty, String condition, String success, String failure) {
            this.label = label;
            this.difficulty = difficulty == null ? "" : difficulty.trim();
            this.condition = condition;
            this.success = success;
            this.failure = failure;
        }

        private boolean appliesToDifficulty(String recognizedDifficulty) {
            String expected = recognizedDifficulty.trim();
            if (expected.isEmpty() || difficulty.isEmpty()) return false;
            for (String candidate : difficulty.split("[/,·\\s]+")) {
                if (candidate.equals(expected)) return true;
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
