package helper.journey.starsavior;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Uses the selected card's title and labeled recommended rank, never the player's rank. */
final class RaidMatcher {
    private static final Pattern NUMBER = Pattern.compile("(?<![0-9])[0-9]{1,5}(?![0-9])");
    private static final Pattern SUFFIX = Pattern.compile("[iIlL1|]{1,3}$");
    private final RaidModels.Data data;
    private final String rankLabel;
    private final List<String> tierLabels;

    RaidMatcher(RaidModels.Data data, String fallbackRank, List<String> fallbackTiers) {
        this.data = data;
        rankLabel = normalize(data.rankLabel.isEmpty() ? fallbackRank : data.rankLabel);
        tierLabels = new ArrayList<>();
        for (String label : data.tierLabels.isEmpty() ? fallbackTiers : data.tierLabels) {
            tierLabels.add(normalize(label));
        }
    }

    boolean hasRegionalSignal(List<String> lines) {
        for (String line : lines) {
            String value = normalize(line);
            if (similar(value, rankLabel) >= 0.80) return true;
            for (String tier : tierLabels) if (similar(value, tier) >= 0.86) return true;
            for (RaidModels.Event event : data.events) {
                if (eventTitleScore(line, event) >= 0.84) return true;
            }
        }
        // A localized emergency title may wrap over neighboring OCR lines.
        for (int start = 0; start < lines.size(); start++) {
            String joined = lines.get(start);
            for (int end = start + 1; end < Math.min(lines.size(), start + 3); end++) {
                joined += " " + lines.get(end);
                for (RaidModels.Event event : data.events) {
                    if (eventTitleScore(joined, event) >= 0.84) return true;
                }
            }
        }
        return false;
    }

    RaidModels.Match match(List<RaidModels.Line> lines) {
        List<RaidModels.Line> anchors = new ArrayList<>();
        Set<String> visibleTiers = new HashSet<>();
        for (RaidModels.Line line : lines) {
            String value = normalize(line.text);
            if (similar(value, rankLabel) >= 0.80) anchors.add(line);
            for (String tier : tierLabels) if (similar(value, tier) >= 0.86) visibleTiers.add(tier);
        }
        boolean screen = !anchors.isEmpty() || visibleTiers.size() >= 2;
        if (!screen) return empty(false);
        if (data.events.isEmpty()) return empty(!anchors.isEmpty());

        RaidModels.Line anchor = anchors.size() == 1 ? anchors.get(0) : null;
        // A title above and horizontally overlapping the rank label is on the detail card.
        // Sidebar entries are outside that column even when their tier is different.
        List<RaidModels.Line> titleLines = new ArrayList<>();
        if (anchor != null) {
            for (RaidModels.Line line : lines) {
                if (line.bottom <= anchor.top && line.overlapsX(anchor)) titleLines.add(line);
            }
        }
        titleLines = withWrappedTitles(titleLines);
        boolean cardTitle = !titleLines.isEmpty();
        Map<String, Double> scores = scoreNames(cardTitle ? titleLines : lines);
        String family = uniqueBest(scores);
        if (family == null && cardTitle) {
            family = uniqueBest(scoreNames(withWrappedTitles(lines)));
            cardTitle = false;
        }
        if (family == null) return empty(!anchors.isEmpty() && visibleTiers.size() >= 2);

        int selected = 0;
        boolean selectable = false;
        for (RaidModels.Event event : data.events) {
            if (normalize(event.name).equals(normalize(family)) && !event.isSingleBattle()) selectable = true;
        }
        if (cardTitle && selectable) {
            Set<Integer> observed = new HashSet<>();
            double largestHeight = 0;
            for (RaidModels.Line line : titleLines) {
                if (familyTitleScore(line.text, family) < 0.84) continue;
                int tier = tierSuffix(line.text);
                if (tier == 0) continue;
                if (line.height() > largestHeight * 1.15) {
                    observed.clear(); largestHeight = line.height();
                }
                if (line.height() >= largestHeight / 1.15) observed.add(tier);
            }
            if (observed.size() == 1) selected = observed.iterator().next();
        }
        int rank = recommendedRank(anchor, lines);
        List<RaidModels.Event> variants = new ArrayList<>();
        List<RaidModels.Event> compatible = new ArrayList<>();
        Set<Integer> matchedTiers = new HashSet<>();
        for (RaidModels.Event event : data.events) {
            if (!normalize(event.name).equals(normalize(family))) continue;
            variants.add(event);
            boolean matching = false;
            for (RaidModels.Option option : event.options) {
                if (rank > 0 && option.recommendedRank == rank
                        && (selected == 0 || selected == option.tier)) {
                    matching = true; matchedTiers.add(option.tier);
                }
            }
            if (matching) compatible.add(event);
        }
        boolean mismatch = rank > 0 && compatible.isEmpty();
        if (!compatible.isEmpty()) variants = compatible;
        if (selected == 0 && compatible.size() == 1 && matchedTiers.size() == 1) {
            selected = matchedTiers.iterator().next();
        }
        // Even a family currently exclusive to one journey needs a matching rank as evidence.
        return new RaidModels.Match(true, variants, selected, rank,
                compatible.size() == 1, mismatch);
    }

    private static List<RaidModels.Line> withWrappedTitles(List<RaidModels.Line> lines) {
        List<RaidModels.Line> ordered = new ArrayList<>(lines);
        ordered.sort(Comparator.comparingDouble(line -> line.top));
        List<RaidModels.Line> result = new ArrayList<>(lines);
        for (int start = 0; start < ordered.size(); start++) {
            RaidModels.Line joined = ordered.get(start);
            RaidModels.Line previous = joined;
            for (int end = start + 1; end < Math.min(ordered.size(), start + 3); end++) {
                RaidModels.Line next = ordered.get(end);
                double height = Math.max(previous.height(), next.height());
                if (!previous.overlapsX(next) || next.top < previous.bottom - height * 0.2
                        || next.top - previous.bottom > height * 0.75
                        || height > Math.min(previous.height(), next.height()) * 1.35) break;
                joined = new RaidModels.Line(joined.text + " " + next.text,
                        Math.min(joined.left, next.left), joined.top,
                        Math.max(joined.right, next.right), next.bottom);
                result.add(joined);
                previous = next;
            }
        }
        return result;
    }

    private Map<String, Double> scoreNames(List<RaidModels.Line> lines) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (RaidModels.Event event : data.events) {
            double best = 0;
            for (RaidModels.Line line : lines) best = Math.max(best, eventTitleScore(line.text, event));
            result.merge(event.name, best, Math::max);
        }
        return result;
    }

    private double familyTitleScore(String text, String family) {
        double best = 0;
        for (RaidModels.Event event : data.events) {
            if (normalize(event.name).equals(normalize(family))) {
                best = Math.max(best, eventTitleScore(text, event));
            }
        }
        return best;
    }

    private static double eventTitleScore(String text, RaidModels.Event event) {
        if (event.isSingleBattle()) {
            return Math.max(similar(normalize(text), normalize(event.name)),
                    similar(normalize(text), normalize(event.options.get(0).title)));
        }
        double best = titleScore(text, event.name);
        for (RaidModels.Option option : event.options) {
            best = Math.max(best, titleScore(text, withoutTier(option.title)));
        }
        return best;
    }

    private static String uniqueBest(Map<String, Double> scores) {
        String best = null;
        double first = 0, second = 0;
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            if (entry.getValue() > first) {
                second = first; first = entry.getValue(); best = entry.getKey();
            } else second = Math.max(second, entry.getValue());
        }
        return first >= 0.84 && first - second >= 0.06 ? best : null;
    }

    private static int recommendedRank(RaidModels.Line anchor, List<RaidModels.Line> lines) {
        if (anchor == null) return -1;
        Set<Integer> found = new LinkedHashSet<>();
        for (RaidModels.Line line : lines) {
            if (line == anchor || (line.top >= anchor.bottom - anchor.height() * 0.25
                    && line.top - anchor.bottom <= anchor.height() * 3.5
                    && line.overlapsX(anchor))) {
                String raw = Normalizer.normalize(line.text, Normalizer.Form.NFKC);
                // The label supplies the meaning; do not collect arbitrary screen numbers.
                Matcher matcher = NUMBER.matcher(raw);
                while (matcher.find()) found.add(Integer.parseInt(matcher.group()));
            }
        }
        return found.size() == 1 && found.iterator().next() > 0 ? found.iterator().next() : -1;
    }

    private static double titleScore(String text, String name) {
        return similar(normalize(withoutTier(text)), normalize(name));
    }

    private static String withoutTier(String text) {
        String plain = Normalizer.normalize(text.trim(), Normalizer.Form.NFKC);
        Matcher suffix = SUFFIX.matcher(plain);
        if (suffix.find()) plain = plain.substring(0, suffix.start());
        return plain.trim();
    }

    private static int tierSuffix(String text) {
        Matcher suffix = SUFFIX.matcher(Normalizer.normalize(text.trim(), Normalizer.Form.NFKC));
        return suffix.find() ? suffix.group().length() : 0;
    }

    private static RaidModels.Match empty(boolean screen) {
        return new RaidModels.Match(screen, List.of(), 0, -1, false, false);
    }

    static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}\\p{Nd}]", "");
    }

    private static double similar(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        if (left.equals(right)) return 1;
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j < previous.length; j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1]; current[0] = i;
            for (int j = 1; j <= right.length(); j++) current[j] = Math.min(
                    Math.min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
            previous = current;
        }
        return 1.0 - previous[right.length()] / (double) Math.max(left.length(), right.length());
    }
}
