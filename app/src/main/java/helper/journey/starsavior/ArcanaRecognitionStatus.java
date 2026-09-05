package helper.journey.starsavior;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds a user-facing explanation for optional Arcana image filtering. */
final class ArcanaRecognitionStatus {
    private static final String LABEL_SEPARATOR = " · ";

    final boolean applicable;
    final boolean recognized;
    final String detectedSource;

    private ArcanaRecognitionStatus(
            boolean applicable, boolean recognized, String detectedSource) {
        this.applicable = applicable;
        this.recognized = recognized;
        this.detectedSource = detectedSource;
    }

    static ArcanaRecognitionStatus from(
            JourneyModels.Event event, String difficulty, Set<String> recognizedArcanaIds) {
        Set<String> candidates = ArcanaImageRecognizer.candidateIds(event, difficulty);
        if (candidates.isEmpty()) return new ArcanaRecognitionStatus(false, false, "");

        Set<String> recognizedCandidates = new LinkedHashSet<>();
        if (recognizedArcanaIds != null) {
            for (String candidate : candidates) {
                if (recognizedArcanaIds.contains(candidate)) recognizedCandidates.add(candidate);
            }
        }
        if (recognizedCandidates.isEmpty()) {
            return new ArcanaRecognitionStatus(true, false, "");
        }

        Set<String> sourceLabels = new LinkedHashSet<>();
        for (JourneyModels.Choice choice : event.choices) {
            for (JourneyModels.Outcome outcome : choice.outcomesForDifficulty(difficulty)) {
                if (!containsAny(outcome.arcanaIds, recognizedCandidates)) continue;
                String source = sourceFromOutcomeLabel(event.name, outcome.label);
                if (!source.isEmpty()) sourceLabels.add(source);
            }
        }
        String detectedSource = sourceLabels.isEmpty()
                ? "ID " + String.join(", ", recognizedCandidates)
                : String.join(" / ", sourceLabels);
        return new ArcanaRecognitionStatus(true, true, detectedSource);
    }

    String message() {
        if (!applicable) return "";
        if (!recognized) return "아르카나 이미지 판별 실패 · 전체 결과 표시";
        return "감지된 아르카나: " + detectedSource;
    }

    private static boolean containsAny(List<String> values, Set<String> expected) {
        for (String value : values) {
            if (expected.contains(value)) return true;
        }
        return false;
    }

    private static String sourceFromOutcomeLabel(String eventName, String outcomeLabel) {
        String label = outcomeLabel == null ? "" : outcomeLabel.trim();
        String normalizedEventName = eventName == null ? "" : eventName.trim();
        String prefix = normalizedEventName + LABEL_SEPARATOR;
        if (!normalizedEventName.isEmpty() && label.startsWith(prefix)) {
            return label.substring(prefix.length()).trim();
        }
        return label;
    }
}
