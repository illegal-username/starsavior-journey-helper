package helper.journey.starsavior;

import java.util.List;

/** Owns OCR-to-event decisions independently from capture and Android view lifecycle code. */
final class JourneyRecognitionCoordinator {
    enum Action {
        SHOW_MATCH,
        SCAN_FULL_SCREEN,
        AMBIGUOUS,
        NOT_RECOGNIZED,
        DATA_UNAVAILABLE
    }

    static final class Decision {
        final Action action;
        final JourneyModels.Match match;
        final String difficulty;

        Decision(Action action, JourneyModels.Match match, String difficulty) {
            this.action = action;
            this.match = match;
            this.difficulty = difficulty;
        }
    }

    private JourneyRecognitionCoordinator() {}

    static Decision evaluateRegional(
            JourneyMatcher matcher, List<String> eventLines, List<String> choiceLines) {
        if (matcher == null) return new Decision(Action.DATA_UNAVAILABLE, null, "");
        JourneyModels.Match match = matcher.match(eventLines, choiceLines);
        if (!match.isConfident()) return new Decision(Action.SCAN_FULL_SCREEN, match, "");
        return new Decision(
                Action.SHOW_MATCH,
                match,
                DifficultyResolver.fromRecognizedLines(match.event, choiceLines));
    }

    static Decision evaluateFull(
            JourneyMatcher matcher, List<String> fullLines, List<String> regionalChoiceLines) {
        if (matcher == null) return new Decision(Action.DATA_UNAVAILABLE, null, "");
        JourneyModels.Match match = matcher.match(fullLines, fullLines);
        if (!match.isConfident()) {
            return new Decision(
                    match.ambiguous ? Action.AMBIGUOUS : Action.NOT_RECOGNIZED,
                    match,
                    "");
        }

        String difficulty = DifficultyResolver.fromRecognizedLines(
                match.event, regionalChoiceLines);
        if (difficulty.isEmpty()) {
            difficulty = DifficultyResolver.fromRecognizedLines(match.event, fullLines);
        }
        return new Decision(Action.SHOW_MATCH, match, difficulty);
    }
}
