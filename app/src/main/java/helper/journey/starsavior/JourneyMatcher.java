package helper.journey.starsavior;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class JourneyMatcher {
    private static final Pattern NOISE = Pattern.compile("[^가-힣A-Za-z0-9]");
    private static final String EVENT_HEADER = normalize("여정 이벤트");
    private static final double MIN_CHOICE_CONFIDENCE = 0.58;
    private static final double MIN_EVENT_SIGNAL = 0.50;
    private static final double STRONG_EVENT_SIGNAL = 0.58;
    private static final double CLOSE_CHOICE_MARGIN = 0.04;
    private static final double EVENT_SEPARATION_MARGIN = 0.06;
    private static final double COMBINED_SEPARATION_MARGIN = 0.025;
    private final List<JourneyModels.Event> events;

    public JourneyMatcher(List<JourneyModels.Event> events) {
        this.events = events;
    }

    public JourneyModels.Match match(List<String> recognizedLines) {
        return match(List.of(), recognizedLines);
    }

    /** Fast pre-check used to avoid event/full-screen OCR on stamina-only screens. */
    public boolean hasPlausibleChoiceSignal(List<String> recognizedLines) {
        List<Candidate> candidates = makeCandidates(recognizedLines);
        for (JourneyModels.Event event : events) {
            if (scoreChoices(event, candidates).score >= 0.40) return true;
        }
        return false;
    }

    public JourneyModels.Match match(List<String> recognizedEventLines, List<String> recognizedChoiceLines) {
        List<Candidate> eventCandidates = makeEventCandidates(recognizedEventLines);
        List<Candidate> choiceCandidates = makeCandidates(recognizedChoiceLines);
        List<ScoredEvent> ranked = new ArrayList<>(events.size());

        for (JourneyModels.Event event : events) {
            ChoiceEvaluation choiceEvaluation = scoreChoices(event, choiceCandidates);
            double eventScore = scoreEventName(event.name, eventCandidates);
            boolean hasEventSignal = eventScore >= MIN_EVENT_SIGNAL;
            // Choices remain the primary signal. The title bonus only separates
            // events whose choices are equal or nearly equal, so a noisy title
            // cannot overpower an otherwise clear choice group.
            double rankScore = choiceEvaluation.score + eventScore * 0.12;
            double confidence = hasEventSignal
                    ? choiceEvaluation.score * 0.75 + eventScore * 0.25
                    : choiceEvaluation.score;
            ranked.add(new ScoredEvent(event, rankScore, Math.min(1.0, confidence), eventScore,
                    choiceEvaluation.score, choiceEvaluation.scores));
        }

        ranked.sort(Comparator.comparingDouble((ScoredEvent value) -> value.combined).reversed());
        ScoredEvent best = ranked.isEmpty() ? null : ranked.get(0);
        ScoredEvent second = ranked.size() < 2 ? null : ranked.get(1);

        if (best == null) {
            return new JourneyModels.Match(null, 0.0, 0.0, 0.0,
                    false, false, new ArrayList<>(recognizedEventLines),
                    new ArrayList<>(recognizedChoiceLines), List.of());
        }

        boolean closeChoiceCompetitor = second != null
                && second.choice >= MIN_CHOICE_CONFIDENCE
                && best.choice - second.choice < CLOSE_CHOICE_MARGIN;
        boolean eventSeparates = second == null
                || best.event - second.event >= EVENT_SEPARATION_MARGIN;
        boolean combinedSeparates = second == null
                || best.combined - second.combined >= COMBINED_SEPARATION_MARGIN;
        boolean strongEventEvidence = best.event >= STRONG_EVENT_SIGNAL
                && eventSeparates && combinedSeparates;
        boolean ambiguous = closeChoiceCompetitor && !strongEventEvidence;
        boolean eventNameUsed = !eventCandidates.isEmpty() && best.event >= MIN_EVENT_SIGNAL;

        return new JourneyModels.Match(best.eventData, best.confidence, best.event, best.choice,
                eventNameUsed, ambiguous, new ArrayList<>(recognizedEventLines),
                new ArrayList<>(recognizedChoiceLines), best.choiceScores);
    }

    private ChoiceEvaluation scoreChoices(JourneyModels.Event event, List<Candidate> candidates) {
            List<Double> scores = new ArrayList<>(event.choices.size());
            List<Integer> bestIndices = new ArrayList<>(event.choices.size());
            int covered = 0;
            double total = 0.0;

            for (JourneyModels.Choice choice : event.choices) {
                String expected = normalize(choice.text);
                double choiceBest = 0.0;
                int choiceIndex = -1;
                for (Candidate candidate : candidates) {
                    double score = similarity(expected, candidate.normalized);
                    if (score > choiceBest) {
                        choiceBest = score;
                        choiceIndex = candidate.lineIndex;
                    }
                }
                scores.add(choiceBest);
                bestIndices.add(choiceIndex);
                total += choiceBest;
                if (choiceBest >= 0.55) covered++;
            }

            if (event.choices.isEmpty()) return new ChoiceEvaluation(0.0, scores);
            double average = total / event.choices.size();
            double coverage = covered / (double) event.choices.size();
            double orderBonus = isMostlyInOrder(bestIndices) ? 0.025 : 0.0;
            double score = Math.min(1.0, average * 0.78 + coverage * 0.22 + orderBonus);
            return new ChoiceEvaluation(score, scores);
    }

    private static double scoreEventName(String eventName, List<Candidate> candidates) {
        String expected = normalize(eventName);
        double best = 0.0;
        for (Candidate candidate : candidates) {
            best = Math.max(best, similarity(expected, candidate.normalized));
        }
        return best;
    }

    static String normalize(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        return NOISE.matcher(normalized).replaceAll("").toLowerCase(Locale.ROOT);
    }

    static double similarity(String expected, String actual) {
        if (expected.isEmpty() || actual.isEmpty()) return 0.0;
        if (expected.equals(actual)) return 1.0;

        int maxLength = Math.max(expected.length(), actual.length());
        double edit = 1.0 - levenshtein(expected, actual) / (double) maxLength;
        double dice = dice(expected, actual);
        double containment = 0.0;
        if (expected.contains(actual) || actual.contains(expected)) {
            containment = Math.min(expected.length(), actual.length()) / (double) maxLength;
        }
        return Math.max(edit, Math.max(dice, containment));
    }

    private static List<Candidate> makeCandidates(List<String> lines) {
        List<Candidate> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String one = normalize(lines.get(i));
            if (one.length() >= 2) result.add(new Candidate(one, i));

            if (i + 1 < lines.size()) {
                String pair = normalize(lines.get(i) + lines.get(i + 1));
                if (pair.length() >= 3) result.add(new Candidate(pair, i));
            }
        }
        return result;
    }

    private static List<Candidate> makeEventCandidates(List<String> lines) {
        List<String> cleaned = new ArrayList<>(lines.size());
        for (String line : lines) {
            String normalized = normalize(line);
            if (normalized.equals(EVENT_HEADER)) continue;
            if (normalized.startsWith(EVENT_HEADER)) normalized = normalized.substring(EVENT_HEADER.length());
            if (!normalized.isEmpty()) cleaned.add(normalized);
        }
        return makeCandidates(cleaned);
    }

    private static boolean isMostlyInOrder(List<Integer> indices) {
        int previous = -1;
        int comparisons = 0;
        int ordered = 0;
        for (int index : indices) {
            if (index < 0) continue;
            if (previous >= 0) {
                comparisons++;
                if (index >= previous) ordered++;
            }
            previous = index;
        }
        return comparisons > 0 && ordered >= Math.ceil(comparisons * 0.7);
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;

        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private static double dice(String left, String right) {
        if (left.length() < 2 || right.length() < 2) return left.equals(right) ? 1.0 : 0.0;
        Map<String, Integer> leftPairs = bigrams(left);
        Map<String, Integer> rightPairs = bigrams(right);
        int intersection = 0;
        int leftCount = 0;
        int rightCount = 0;

        for (int count : leftPairs.values()) leftCount += count;
        for (int count : rightPairs.values()) rightCount += count;
        Set<String> keys = new HashSet<>(leftPairs.keySet());
        keys.retainAll(rightPairs.keySet());
        for (String key : keys) intersection += Math.min(leftPairs.get(key), rightPairs.get(key));
        return (2.0 * intersection) / (leftCount + rightCount);
    }

    private static Map<String, Integer> bigrams(String value) {
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < value.length() - 1; i++) {
            String pair = value.substring(i, i + 2);
            result.put(pair, result.getOrDefault(pair, 0) + 1);
        }
        return result;
    }

    private static final class Candidate {
        final String normalized;
        final int lineIndex;

        Candidate(String normalized, int lineIndex) {
            this.normalized = normalized;
            this.lineIndex = lineIndex;
        }
    }

    private static final class ChoiceEvaluation {
        final double score;
        final List<Double> scores;

        ChoiceEvaluation(double score, List<Double> scores) {
            this.score = score;
            this.scores = scores;
        }
    }

    private static final class ScoredEvent {
        final JourneyModels.Event eventData;
        final double combined;
        final double confidence;
        final double event;
        final double choice;
        final List<Double> choiceScores;

        ScoredEvent(JourneyModels.Event eventData, double combined, double confidence, double event,
                    double choice, List<Double> choiceScores) {
            this.eventData = eventData;
            this.combined = combined;
            this.confidence = confidence;
            this.event = event;
            this.choice = choice;
            this.choiceScores = choiceScores;
        }
    }
}
