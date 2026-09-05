package helper.journey.starsavior;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Matches the tilted ArcanaImg card beside the OCR event title using non-reversible color features. */
final class ArcanaImageRecognizer {
    private static final int HUE_BINS = 12;
    private static final int SATURATION_BINS = 4;
    private static final int SAMPLE_COLUMNS = 24;
    private static final int SAMPLE_ROWS = 44;
    private static final double CARD_ASPECT = 704.0 / 1314.0;
    private static final double MAX_DISTANCE = 0.28;
    private static final double MIN_MARGIN = 0.10;

    static final class Anchor {
        final int titleLeft;
        final int titleTop;
        final int titleHeight;

        Anchor(int titleLeft, int titleTop, int titleHeight) {
            this.titleLeft = titleLeft;
            this.titleTop = titleTop;
            this.titleHeight = titleHeight;
        }
    }

    static final class Region {
        final int left;
        final int top;
        final int width;
        final int height;

        Region(int left, int top, int width, int height) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }
    }

    static final class Result {
        final Set<String> recognizedArcanaIds;
        final double bestDistance;
        final double margin;

        Result(Set<String> recognizedArcanaIds, double bestDistance, double margin) {
            this.recognizedArcanaIds = Collections.unmodifiableSet(
                    new LinkedHashSet<>(recognizedArcanaIds));
            this.bestDistance = bestDistance;
            this.margin = margin;
        }

        boolean isConfident() {
            return !recognizedArcanaIds.isEmpty();
        }
    }

    private ArcanaImageRecognizer() {}

    static Set<String> candidateIds(JourneyModels.Event event, String difficulty) {
        if (event == null) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        List<Set<String>> memberships = new ArrayList<>();
        for (JourneyModels.Choice choice : event.choices) {
            for (JourneyModels.Outcome outcome : choice.outcomesForDifficulty(difficulty)) {
                result.addAll(outcome.arcanaIds);
                if (!outcome.arcanaIds.isEmpty()) {
                    memberships.add(new LinkedHashSet<>(outcome.arcanaIds));
                }
            }
        }
        if (result.size() < 2) return Set.of();
        boolean distinguishable = false;
        for (Set<String> membership : memberships) {
            if (!membership.equals(result)) {
                distinguishable = true;
                break;
            }
        }
        if (!distinguishable) return Set.of();
        return Collections.unmodifiableSet(result);
    }

    static Region scanRegion(int screenWidth, int screenHeight, Anchor anchor) {
        if (anchor == null || anchor.titleHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) {
            return new Region(0, 0, 0, 0);
        }
        double unit = anchor.titleHeight;
        int left = clamp((int) Math.floor(anchor.titleLeft - unit * 7.0), 0, screenWidth);
        int top = clamp((int) Math.floor(anchor.titleTop - unit * 4.0), 0, screenHeight);
        int right = clamp((int) Math.ceil(anchor.titleLeft - unit * 0.1), left, screenWidth);
        int bottom = clamp((int) Math.ceil(anchor.titleTop + unit * 6.5), top, screenHeight);
        return new Region(left, top, right - left, bottom - top);
    }

    static Result recognize(
            Region region,
            int[] pixels,
            Anchor anchor,
            Map<String, JourneyModels.ArcanaImageFeature> features,
            Set<String> candidateIds) {
        if (anchor == null || region == null || pixels == null || features == null
                || candidateIds == null || candidateIds.size() < 2
                || region.width <= 0 || region.height <= 0
                || pixels.length != region.width * region.height) {
            return noMatch();
        }
        List<String> candidates = new ArrayList<>(candidateIds);
        for (String candidate : candidates) {
            JourneyModels.ArcanaImageFeature feature = features.get(candidate);
            if (feature == null
                    || feature.histogram.length != JourneyModels.ArcanaImageFeature.HISTOGRAM_SIZE) {
                return noMatch();
            }
        }

        double[] bestByCandidate = new double[candidates.size()];
        java.util.Arrays.fill(bestByCandidate, Double.POSITIVE_INFINITY);
        double unit = anchor.titleHeight;
        double[] heightFactors = {6.8, 7.3, 7.8, 8.3, 8.8};
        double[] leftFactors = {3.8, 4.2, 4.6};
        double[] topFactors = {0.8, 1.2, 1.6};
        double[] angles = {-14.0, -10.0, -6.0};
        int[] histogram = new int[JourneyModels.ArcanaImageFeature.HISTOGRAM_SIZE];
        for (double heightFactor : heightFactors) {
            double cardHeight = unit * heightFactor;
            double cardWidth = cardHeight * CARD_ASPECT;
            for (double leftFactor : leftFactors) {
                double centerX = anchor.titleLeft - unit * leftFactor;
                for (double topFactor : topFactors) {
                    double centerY = anchor.titleTop + unit * topFactor;
                    for (double angle : angles) {
                        if (!sampleHistogram(
                                region, pixels, centerX, centerY, cardWidth, cardHeight,
                                Math.toRadians(angle), histogram)) {
                            continue;
                        }
                        for (int index = 0; index < candidates.size(); index++) {
                            double distance = histogramDistance(
                                    histogram, features.get(candidates.get(index)).histogram);
                            if (distance < bestByCandidate[index]) bestByCandidate[index] = distance;
                        }
                    }
                }
            }
        }

        int bestIndex = -1;
        double best = Double.POSITIVE_INFINITY;
        double second = Double.POSITIVE_INFINITY;
        for (int index = 0; index < bestByCandidate.length; index++) {
            double distance = bestByCandidate[index];
            if (distance < best) {
                second = best;
                best = distance;
                bestIndex = index;
            } else if (distance < second) {
                second = distance;
            }
        }
        double margin = second - best;
        if (bestIndex < 0 || best > MAX_DISTANCE || margin < MIN_MARGIN) {
            return new Result(Set.of(), best, margin);
        }
        return new Result(Set.of(candidates.get(bestIndex)), best, margin);
    }

    private static boolean sampleHistogram(
            Region region, int[] pixels, double centerX, double centerY,
            double cardWidth, double cardHeight, double radians, int[] histogram) {
        java.util.Arrays.fill(histogram, 0);
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        double innerWidth = cardWidth * 0.89;
        double innerHeight = cardHeight * 0.89;
        for (int row = 0; row < SAMPLE_ROWS; row++) {
            double localY = ((row + 0.5) / SAMPLE_ROWS - 0.5) * innerHeight;
            for (int column = 0; column < SAMPLE_COLUMNS; column++) {
                double localX = ((column + 0.5) / SAMPLE_COLUMNS - 0.5) * innerWidth;
                int x = (int) Math.round(centerX + cosine * localX - sine * localY);
                int y = (int) Math.round(centerY + sine * localX + cosine * localY);
                int relativeX = x - region.left;
                int relativeY = y - region.top;
                if (relativeX < 0 || relativeY < 0
                        || relativeX >= region.width || relativeY >= region.height) {
                    return false;
                }
                int color = pixels[relativeY * region.width + relativeX];
                histogram[hsvBin(color)]++;
            }
        }
        return true;
    }

    private static int hsvBin(int color) {
        int red = (color >> 16) & 0xff;
        int green = (color >> 8) & 0xff;
        int blue = color & 0xff;
        int maximum = Math.max(red, Math.max(green, blue));
        int minimum = Math.min(red, Math.min(green, blue));
        int delta = maximum - minimum;
        int saturationBin = maximum == 0
                ? 0
                : Math.min(SATURATION_BINS - 1, delta * SATURATION_BINS / maximum);
        double hue;
        if (delta == 0) hue = 0.0;
        else if (maximum == red) hue = ((green - blue) / (double) delta + 6.0) % 6.0;
        else if (maximum == green) hue = (blue - red) / (double) delta + 2.0;
        else hue = (red - green) / (double) delta + 4.0;
        int hueBin = Math.min(HUE_BINS - 1, (int) (hue * HUE_BINS / 6.0));
        return hueBin * SATURATION_BINS + saturationBin;
    }

    private static double histogramDistance(int[] observed, int[] reference) {
        long observedTotal = 0;
        long referenceTotal = 0;
        for (int value : observed) observedTotal += value;
        for (int value : reference) referenceTotal += value;
        if (observedTotal == 0 || referenceTotal == 0) return Double.POSITIVE_INFINITY;
        double difference = 0.0;
        for (int index = 0; index < observed.length; index++) {
            difference += Math.abs(
                    observed[index] / (double) observedTotal
                            - reference[index] / (double) referenceTotal);
        }
        return difference * 0.5;
    }

    private static Result noMatch() {
        return new Result(Set.of(), Double.POSITIVE_INFINITY, 0.0);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
