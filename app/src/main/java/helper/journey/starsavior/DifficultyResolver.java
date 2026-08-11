package helper.journey.starsavior;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves a matched event's difficulty from its own per-difficulty conditions. */
final class DifficultyResolver {
    private static final Pattern NUMBER = Pattern.compile("(?<!\\d)(\\d{1,6})(?!\\d)");
    private static final Pattern DIFFICULTY_SEPARATOR = Pattern.compile("[/,·\\s]+");
    private static final double MIN_CHOICE_SIMILARITY = 0.46;
    private static final int MAX_NUMBER_DISTANCE = 2;

    private DifficultyResolver() {}

    static String fromRecognizedLines(JourneyModels.Event event, List<String> lines) {
        if (event == null || lines == null || lines.isEmpty()) return "";

        List<String> difficulties = collectDifficulties(event);
        if (difficulties.size() < 2) return "";

        List<ChoiceAnchor> anchors = findChoiceAnchors(event, lines);
        Map<String, Integer> scores = new LinkedHashMap<>();
        Map<String, Integer> exactMatches = new LinkedHashMap<>();
        for (String difficulty : difficulties) {
            scores.put(difficulty, 0);
            exactMatches.put(difficulty, 0);
        }

        for (int choiceIndex = 0; choiceIndex < event.choices.size(); choiceIndex++) {
            ChoiceAnchor anchor = anchors.get(choiceIndex);
            if (!anchor.isUsable()) continue;

            Map<String, Set<Integer>> expectedByDifficulty = conditionNumbers(
                    event.choices.get(choiceIndex), difficulties);
            if (!variesByDifficulty(expectedByDifficulty, difficulties)) continue;

            Set<Integer> observed = numbersAssignedToChoice(lines, anchors, choiceIndex);
            for (int number : observed) {
                List<String> matching = new ArrayList<>();
                for (String difficulty : difficulties) {
                    if (expectedByDifficulty.get(difficulty).contains(number)) matching.add(difficulty);
                }
                // A value shared by every difficulty carries no information. A value shared by
                // only some variants remains useful but is intentionally weaker than a unique one.
                if (matching.isEmpty() || matching.size() == difficulties.size()) continue;
                int weight = matching.size() == 1 ? 4 : 1;
                for (String difficulty : matching) {
                    scores.put(difficulty, scores.get(difficulty) + weight);
                    exactMatches.put(difficulty, exactMatches.get(difficulty) + 1);
                }
            }
        }

        String best = "";
        int bestScore = 0;
        boolean tied = false;
        for (String difficulty : difficulties) {
            int score = scores.get(difficulty);
            if (score > bestScore) {
                best = difficulty;
                bestScore = score;
                tied = false;
            } else if (score > 0 && score == bestScore) {
                tied = true;
            }
        }
        if (best.isEmpty() || tied || exactMatches.get(best) == 0) return "";
        return best;
    }

    private static List<String> collectDifficulties(JourneyModels.Event event) {
        Set<String> result = new LinkedHashSet<>();
        for (JourneyModels.Choice choice : event.choices) {
            for (JourneyModels.Outcome outcome : choice.outcomes) {
                for (String candidate : DIFFICULTY_SEPARATOR.split(outcome.difficulty)) {
                    String trimmed = candidate.trim();
                    if (!trimmed.isEmpty()) result.add(trimmed);
                }
            }
        }
        return new ArrayList<>(result);
    }

    private static Map<String, Set<Integer>> conditionNumbers(
            JourneyModels.Choice choice, List<String> difficulties) {
        Map<String, Set<Integer>> result = new LinkedHashMap<>();
        for (String difficulty : difficulties) result.put(difficulty, new LinkedHashSet<>());

        for (JourneyModels.Outcome outcome : choice.outcomes) {
            Set<Integer> numbers = numbers(outcome.condition);
            if (numbers.isEmpty()) continue;
            for (String candidate : DIFFICULTY_SEPARATOR.split(outcome.difficulty)) {
                Set<Integer> target = result.get(candidate.trim());
                if (target != null) target.addAll(numbers);
            }
        }
        return result;
    }

    private static boolean variesByDifficulty(
            Map<String, Set<Integer>> values, List<String> difficulties) {
        Set<Integer> first = values.get(difficulties.get(0));
        for (int index = 1; index < difficulties.size(); index++) {
            if (!first.equals(values.get(difficulties.get(index)))) return true;
        }
        return false;
    }

    private static List<ChoiceAnchor> findChoiceAnchors(
            JourneyModels.Event event, List<String> lines) {
        List<ChoiceAnchor> result = new ArrayList<>(event.choices.size());
        for (JourneyModels.Choice choice : event.choices) {
            String expected = JourneyMatcher.normalize(choice.text);
            ChoiceAnchor best = ChoiceAnchor.NONE;
            for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
                String one = choiceText(lines.get(lineIndex));
                double oneScore = JourneyMatcher.similarity(expected, one);
                if (oneScore > best.score) best = new ChoiceAnchor(lineIndex, lineIndex, oneScore);

                if (lineIndex + 1 < lines.size()) {
                    String next = choiceText(lines.get(lineIndex + 1));
                    // Never make a two-line choice anchor through a number-only cost line.
                    // Otherwise that number can be attached to both adjacent choices.
                    if (!one.isEmpty() && !next.isEmpty()) {
                        double pairScore = JourneyMatcher.similarity(expected, one + next);
                        if (pairScore > best.score) {
                            best = new ChoiceAnchor(lineIndex, lineIndex + 1, pairScore);
                        }
                    }
                }
            }
            result.add(best);
        }
        return result;
    }

    private static Set<Integer> numbersAssignedToChoice(
            List<String> lines, List<ChoiceAnchor> anchors, int expectedChoice) {
        Set<Integer> result = new LinkedHashSet<>();
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            Set<Integer> found = numbers(lines.get(lineIndex));
            if (found.isEmpty()) continue;
            int assigned = nearestChoice(lineIndex, anchors);
            if (assigned == expectedChoice) result.addAll(found);
        }
        return result;
    }

    private static int nearestChoice(int lineIndex, List<ChoiceAnchor> anchors) {
        int bestChoice = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int choiceIndex = 0; choiceIndex < anchors.size(); choiceIndex++) {
            ChoiceAnchor anchor = anchors.get(choiceIndex);
            if (!anchor.isUsable()) continue;
            int distance;
            if (lineIndex < anchor.start) distance = anchor.start - lineIndex;
            else if (lineIndex > anchor.end) distance = lineIndex - anchor.end;
            else distance = 0;

            // OCR lines on the same row are ordered left-to-right, so on an equal distance
            // prefer the preceding choice text over the following row's choice text.
            boolean followsAnchor = lineIndex >= anchor.start;
            boolean followsBest = bestChoice >= 0 && lineIndex >= anchors.get(bestChoice).start;
            if (distance < bestDistance
                    || (distance == bestDistance && followsAnchor && !followsBest)
                    || (distance == bestDistance && followsAnchor == followsBest
                    && anchor.score > anchors.get(bestChoice).score)) {
                bestChoice = choiceIndex;
                bestDistance = distance;
            }
        }
        return bestDistance <= MAX_NUMBER_DISTANCE ? bestChoice : -1;
    }

    private static String choiceText(String value) {
        return JourneyMatcher.normalize(value).replaceAll("\\d", "");
    }

    private static Set<Integer> numbers(String value) {
        Set<Integer> result = new LinkedHashSet<>();
        if (value == null || value.isEmpty()) return result;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).replace(",", "");
        Matcher matcher = NUMBER.matcher(normalized);
        while (matcher.find()) result.add(Integer.parseInt(matcher.group(1)));
        return result;
    }

    private static final class ChoiceAnchor {
        static final ChoiceAnchor NONE = new ChoiceAnchor(-1, -1, 0.0);

        final int start;
        final int end;
        final double score;

        ChoiceAnchor(int start, int end, double score) {
            this.start = start;
            this.end = end;
            this.score = score;
        }

        boolean isUsable() {
            return start >= 0 && score >= MIN_CHOICE_SIMILARITY;
        }
    }
}
