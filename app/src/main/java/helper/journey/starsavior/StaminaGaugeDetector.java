package helper.journey.starsavior;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds and reads the journey stamina gauge without fixed device-pixel coordinates.
 *
 * <p>The detector deliberately does not infer the bar width from the capture width.
 * Gallery zoom, display compatibility scaling, letterboxing and foldable layouts can
 * all render the same HUD at a different pixel size.  Instead it searches a small top
 * strip for the rectangular green fill, measures that rectangle's own height, follows
 * the neutral tail to the physical right edge, and only then divides the measured span
 * into 100 units.  All expensive pixel access is confined to the copied top strip.</p>
 */
final class StaminaGaugeDetector {
    private static final double TRACK_ASPECT = 12.8;

    enum Direction { NONE, GAIN, LOSS }

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

    static final class Anchor {
        final float leftRatio;
        final float centerYRatio;

        Anchor(float leftRatio, float centerYRatio) {
            this.leftRatio = leftRatio;
            this.centerYRatio = centerYRatio;
        }
    }

    static final class Result {
        final int current;
        final int after;
        final Direction direction;
        final Anchor anchor;
        final float confidence;

        Result(int current, int after, Direction direction, Anchor anchor, float confidence) {
            this.current = clampValue(current);
            this.after = clampValue(after);
            this.direction = direction;
            this.anchor = anchor;
            this.confidence = confidence;
        }

        boolean hasPreview() {
            return direction != Direction.NONE && current != after;
        }

        Result stabilize(Result previous) {
            if (previous == null || previous.direction != direction) return this;
            if (Math.abs(previous.current - current) > 2) return this;
            int stableAfter = direction == Direction.NONE ? previous.current : after;
            return new Result(previous.current, stableAfter, direction, anchor,
                    Math.max(confidence, previous.confidence));
        }

        private static int clampValue(int value) {
            return Math.max(0, Math.min(100, value));
        }
    }

    private StaminaGaugeDetector() {}

    static Region scanRegion(int screenWidth, int screenHeight) {
        int left = clamp((int) Math.floor(screenWidth * 0.14), 0, screenWidth - 1);
        int right = clamp((int) Math.ceil(screenWidth * 0.62), left + 1, screenWidth);
        int bottom = clamp(Math.max(64, (int) Math.ceil(screenWidth * 0.060)),
                1, screenHeight);
        return new Region(left, 0, right - left, bottom);
    }

    static Result detect(int screenWidth, int screenHeight, Region region, int[] pixels,
                         Anchor previousAnchor) {
        if (screenWidth < 320 || screenHeight < 200 || region == null || pixels == null
                || region.width <= 0 || region.height <= 0
                || pixels.length < region.width * region.height) return null;

        PixelSource source = new PixelSource(region, pixels);
        Candidate best = findBestCandidate(source, screenWidth, previousAnchor, MaskKind.GAUGE);
        if (best == null) best = findBestCandidate(source, screenWidth, previousAnchor,
                MaskKind.NEUTRAL);
        if (best == null) return null;

        int trackHeight = best.bottom - best.top;
        int endpointInset = best.empty ? 0 : Math.max(1,
                (int) Math.round(trackHeight * 0.30));
        int left = best.left + endpointInset;
        int right = best.right + endpointInset;
        int centerY = (best.top + best.bottom - 1) / 2;
        right = Math.min(right, region.left + region.width - 1);
        int trackWidth = right - left;
        if (trackWidth < Math.max(24, trackHeight * 6)
                || !source.contains(left, centerY)
                || !source.contains(right - 1, centerY)) return null;
        return analyzeProfile(source, screenWidth, left, centerY, trackWidth, trackHeight);
    }

    private static Candidate findBestCandidate(PixelSource source, int screenWidth,
                                               Anchor previousAnchor, MaskKind kind) {
        int maxGap = Math.max(1, (int) Math.round(screenWidth * 0.0012));
        int minRun = Math.max(6, (int) Math.round(screenWidth * 0.004));
        int minHeight = Math.max(4, (int) Math.round(screenWidth * 0.0035));
        int maxHeight = Math.max(minHeight + 1, (int) Math.round(screenWidth * 0.030));
        int yStep = Math.max(1, (int) Math.round(screenWidth / 1800.0));

        @SuppressWarnings("unchecked")
        List<Run>[] rows = new List[source.region.height];
        for (int localY = 0; localY < source.region.height; localY++) {
            rows[localY] = findRuns(source, source.region.top + localY,
                    source.region.left, source.region.left + source.region.width,
                    maxGap, minRun, kind);
        }

        Candidate best = null;
        Set<Long> seen = new HashSet<>();
        for (int localY = 0; localY < rows.length; localY += yStep) {
            for (Run seed : rows[localY]) {
                StableBand band = stableBand(rows, localY, seed);
                int height = band.bottom - band.top;
                if (height < minHeight || height > maxHeight
                        || band.starts.length < height * 0.55) continue;

                int left = median(band.starts);
                int colorEnd = median(band.ends);
                int measuredEnd = colorEnd;
                int colorWidth = colorEnd - left;
                double colorAspect = colorWidth / (double) height;
                if (colorAspect < 0.9 || colorAspect > 17.0) continue;
                long key = (((long) (left / 2)) << 42)
                        ^ (((long) (colorEnd / 2)) << 20)
                        ^ ((long) band.top << 10) ^ band.bottom;
                if (!seen.add(key)) continue;

                double density = maskDensity(source, left, colorEnd, band.top, band.bottom, kind);
                if (density < (kind == MaskKind.GAUGE ? 0.48 : 0.72)) continue;

                Tail tail = kind == MaskKind.GAUGE
                        ? findNeutralTail(source, left, colorEnd, band.top, band.bottom)
                        : null;
                int right;
                int tailStart;
                double aspect;
                double tailBonus;
                boolean empty = kind == MaskKind.NEUTRAL;
                if (tail != null) {
                    right = tail.end;
                    tailStart = tail.start;
                    aspect = (right - left) / (double) height;
                    tailBonus = 36.0;
                } else if (kind == MaskKind.GAUGE && colorAspect >= 8.0) {
                    right = colorEnd;
                    tailStart = -1;
                    aspect = colorAspect;
                    tailBonus = 12.0;
                } else if (kind == MaskKind.NEUTRAL && colorAspect >= 7.5) {
                    right = colorEnd;
                    colorEnd = left;
                    tailStart = left;
                    aspect = colorAspect;
                    tailBonus = 20.0;
                } else {
                    continue;
                }
                if (aspect < 7.5 || aspect > 17.0) continue;

                double flatness = endpointFlatness(band.starts, band.ends, left, measuredEnd,
                        height, empty);
                double edge = edgeContrast(source, left, right, band.top, band.bottom);
                double location = (band.top + band.bottom) * 0.5 / screenWidth;
                double anchorPenalty = Math.min(Math.abs(location - 0.032) * 80.0, 4.0);
                if (previousAnchor != null) {
                    int remembered = Math.round(previousAnchor.leftRatio * screenWidth);
                    anchorPenalty += Math.min(Math.abs(left - remembered) * 2.0
                            / Math.max(1, right - left), 2.0);
                }
                double score = density * 55.0 + flatness * 22.0 + tailBonus
                        + Math.min(edge, 45.0) * 0.45
                        - Math.abs(aspect - TRACK_ASPECT) * 4.0 - anchorPenalty;
                Candidate candidate = new Candidate(score, left, colorEnd, right,
                        band.top + source.region.top, band.bottom + source.region.top, empty);
                if (best == null || candidate.score > best.score) best = candidate;
            }
        }
        if (best == null) return null;
        double minimumScore = kind == MaskKind.GAUGE ? 92.0 : 105.0;
        return best.score >= minimumScore ? best : null;
    }

    private static List<Run> findRuns(PixelSource source, int y, int firstX, int lastX,
                                      int maxGap, int minimum, MaskKind kind) {
        List<Run> result = new ArrayList<>();
        int start = -1;
        int previous = -1;
        for (int x = firstX; x < lastX; x++) {
            boolean match = matches(source.get(x, y), kind);
            if (match) {
                if (start < 0) start = x;
                previous = x;
            }
            if (start >= 0 && (!match && x - previous > maxGap)) {
                if (previous + 1 - start >= minimum) result.add(new Run(start, previous + 1));
                start = -1;
                previous = -1;
            }
        }
        if (start >= 0 && previous + 1 - start >= minimum) {
            result.add(new Run(start, previous + 1));
        }
        return result;
    }

    private static StableBand stableBand(List<Run>[] rows, int target, Run seed) {
        int initialLength = seed.end - seed.start;
        int tolerance = Math.max(4, (int) Math.round(initialLength * 0.16));
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        List<Integer> rowNumbers = new ArrayList<>();
        starts.add(seed.start);
        ends.add(seed.end);
        rowNumbers.add(target);
        int top = target;
        int bottom = target + 1;
        for (int direction : new int[] {-1, 1}) {
            int row = target + direction;
            int misses = 0;
            Run reference = seed;
            while (row >= 0 && row < rows.length && misses <= 1) {
                Run compatible = compatibleRun(rows[row], reference, seed,
                        initialLength, tolerance);
                if (compatible == null) {
                    misses++;
                } else {
                    starts.add(compatible.start);
                    ends.add(compatible.end);
                    rowNumbers.add(row);
                    reference = compatible;
                    top = Math.min(top, row);
                    bottom = Math.max(bottom, row + 1);
                    misses = 0;
                }
                row += direction;
            }
        }
        // A translucent background can make one adjacent scenery row look green.
        // Keep the rectangular core and discard endpoint outliers before using
        // the band height as our scale ruler.
        int medianStart = median(toArray(starts));
        int medianEnd = median(toArray(ends));
        int tightTolerance = Math.max(2, (int) Math.round(initialLength * 0.07));
        List<Integer> coreStarts = new ArrayList<>();
        List<Integer> coreEnds = new ArrayList<>();
        int coreTop = rows.length;
        int coreBottom = 0;
        for (int index = 0; index < starts.size(); index++) {
            if (Math.abs(starts.get(index) - medianStart) <= tightTolerance
                    && Math.abs(ends.get(index) - medianEnd) <= tightTolerance) {
                coreStarts.add(starts.get(index));
                coreEnds.add(ends.get(index));
                coreTop = Math.min(coreTop, rowNumbers.get(index));
                coreBottom = Math.max(coreBottom, rowNumbers.get(index) + 1);
            }
        }
        if (coreStarts.size() >= 3) {
            return new StableBand(coreTop, coreBottom, toArray(coreStarts), toArray(coreEnds));
        }
        return new StableBand(top, bottom, toArray(starts), toArray(ends));
    }

    private static Run compatibleRun(List<Run> choices, Run reference, Run initial,
                                     int initialLength, int tolerance) {
        Run best = null;
        int bestError = Integer.MAX_VALUE;
        for (Run choice : choices) {
            int overlap = Math.min(reference.end, choice.end)
                    - Math.max(reference.start, choice.start);
            int length = choice.end - choice.start;
            int referenceLength = reference.end - reference.start;
            if (overlap <= 0 || overlap < Math.min(initialLength, length) * 0.62
                    || Math.abs(choice.start - initial.start) > tolerance
                    || Math.abs(choice.end - initial.end) > tolerance
                    || length < initialLength * 0.58 || length > initialLength * 1.38) continue;
            int error = Math.abs(choice.start - reference.start)
                    + Math.abs(choice.end - reference.end)
                    + Math.abs(length - referenceLength);
            if (error < bestError) {
                bestError = error;
                best = choice;
            }
        }
        return best;
    }

    private static Tail findNeutralTail(PixelSource source, int left, int colorEnd,
                                        int localTop, int localBottom) {
        int top = localTop + source.region.top;
        int bottom = localBottom + source.region.top;
        int height = bottom - top;
        int maximum = Math.min(source.region.left + source.region.width,
                left + (int) Math.round(height * 17.0));
        int minimum = Math.max(left, colorEnd - (int) Math.round(height * 0.25));
        int maxGap = Math.max(1, (int) Math.round(height * 0.14));
        int minRun = Math.max(4, (int) Math.round(height * 0.55));
        int inset = Math.max(1, (int) Math.round(height * 0.22));
        int firstY = Math.min(bottom - 1, top + inset);
        int lastY = Math.max(firstY + 1, bottom - inset);
        int[] reds = new int[Math.max(1, lastY - firstY)];
        int[] greens = new int[reds.length];
        int[] blues = new int[reds.length];
        int profileLength = Math.max(0, maximum - minimum);
        int[] profile = new int[profileLength];
        boolean[] neutralProfile = new boolean[profileLength];
        for (int offset = 0; offset < profileLength; offset++) {
            int color = medianColor(source, minimum + offset, firstY, lastY,
                    reds, greens, blues);
            profile[offset] = color;
            neutralProfile[offset] = isNeutralColor(color);
        }

        Tail best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        int runStart = -1;
        int previous = -1;
        for (int x = minimum; x <= maximum + maxGap; x++) {
            boolean neutral = x < maximum && neutralProfile[x - minimum];
            if (neutral) {
                if (runStart < 0) runStart = x;
                previous = x;
            }
            if (runStart >= 0 && (!neutral && x - previous > maxGap)) {
                int end = previous + 1;
                if (end - runStart >= minRun && end > colorEnd) {
                    end = refineNeutralEnd(profile, runStart - minimum, end - minimum,
                            minRun, height) + minimum;
                    double aspect = (end - left) / (double) height;
                    if (aspect >= 7.5 && aspect <= 17.0) {
                        double startGap = Math.abs(runStart - colorEnd) / (double) height;
                        double cost = Math.abs(aspect - TRACK_ASPECT)
                                + Math.min(startGap, 3.0) * 0.12;
                        if (cost < bestCost) {
                            bestCost = cost;
                            best = new Tail(runStart, end);
                        }
                    }
                }
                runStart = -1;
                previous = -1;
            }
        }
        return best;
    }

    private static int medianColor(PixelSource source, int x, int firstY, int lastY,
                                   int[] reds, int[] greens, int[] blues) {
        int count = 0;
        for (int y = firstY; y < lastY; y++) {
            int color = source.get(x, y);
            reds[count] = red(color);
            greens[count] = green(color);
            blues[count] = blue(color);
            count++;
        }
        Arrays.sort(reds, 0, count);
        Arrays.sort(greens, 0, count);
        Arrays.sort(blues, 0, count);
        int r = reds[count / 2];
        int g = greens[count / 2];
        int b = blues[count / 2];
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private static int refineNeutralEnd(int[] profile, int start, int end,
                                        int minimumTail, int height) {
        int window = Math.max(2, (int) Math.round(height * 0.15));
        int first = Math.max(start + minimumTail, start + window);
        int last = Math.min(end - window, profile.length - window);
        Edge best = new Edge(-1e9, end);
        for (int boundary = first; boundary <= last; boundary++) {
            double leftR = meanChannel(profile, boundary - window, boundary, 16);
            double leftG = meanChannel(profile, boundary - window, boundary, 8);
            double leftB = meanChannel(profile, boundary - window, boundary, 0);
            double rightR = meanChannel(profile, boundary, boundary + window, 16);
            double rightG = meanChannel(profile, boundary, boundary + window, 8);
            double rightB = meanChannel(profile, boundary, boundary + window, 0);
            double drop = (leftR + leftG + leftB - rightR - rightG - rightB) / 3.0;
            double distance = vectorDistance(leftR, leftG, leftB, rightR, rightG, rightB);
            double score = drop + distance * 0.20;
            if (drop >= 18.0 && score > best.score) best = new Edge(score, boundary);
        }
        return best.score >= 24.0 ? best.position : end;
    }

    private static double meanChannel(int[] colors, int start, int end, int shift) {
        double sum = 0.0;
        for (int index = start; index < end; index++) sum += (colors[index] >>> shift) & 0xff;
        return sum / Math.max(1, end - start);
    }

    private static double maskDensity(PixelSource source, int left, int right,
                                      int localTop, int localBottom, MaskKind kind) {
        int top = localTop + source.region.top;
        int bottom = localBottom + source.region.top;
        int matches = 0;
        int total = Math.max(1, (right - left) * (bottom - top));
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) if (matches(source.get(x, y), kind)) matches++;
        }
        return matches / (double) total;
    }

    private static double endpointFlatness(int[] starts, int[] ends, int left, int right,
                                           int height, boolean empty) {
        double startMad = medianAbsoluteDeviation(starts, left) / Math.max(1.0, height);
        double endMad = medianAbsoluteDeviation(ends, right) / Math.max(1.0, height);
        return Math.max(0.0, 1.0 - (startMad + endMad) * 0.55);
    }

    private static double edgeContrast(PixelSource source, int left, int right,
                                       int localTop, int localBottom) {
        int top = localTop + source.region.top;
        int bottom = localBottom + source.region.top;
        if (top < source.region.top + 2 || bottom + 1 >= source.region.top + source.region.height) {
            return 0.0;
        }
        double[] values = new double[5];
        double[] fractions = {0.18, 0.35, 0.55, 0.75, 0.90};
        for (int index = 0; index < fractions.length; index++) {
            int x = clamp(left + (int) Math.round((right - left) * fractions[index]),
                    source.region.left, source.region.left + source.region.width - 1);
            int inside = source.get(x, (top + bottom - 1) / 2);
            values[index] = (colorDistance(inside, source.get(x, top - 2))
                    + colorDistance(inside, source.get(x, bottom + 1))) * 0.5;
        }
        Arrays.sort(values);
        return values[values.length / 2];
    }

    private static boolean matches(int color, MaskKind kind) {
        return kind == MaskKind.GAUGE ? isGaugeColor(color) : isNeutralColor(color);
    }

    private static boolean isNeutralColor(int color) {
        int r = red(color);
        int g = green(color);
        int b = blue(color);
        int maximum = Math.max(r, Math.max(g, b));
        int minimum = Math.min(r, Math.min(g, b));
        double light = (r + g + b) / 3.0;
        return light >= 55 && light <= 125 && maximum - minimum <= 20;
    }

    private static int median(int[] values) {
        int[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static double medianAbsoluteDeviation(int[] values, int center) {
        int[] deviations = new int[values.length];
        for (int index = 0; index < values.length; index++) {
            deviations[index] = Math.abs(values[index] - center);
        }
        Arrays.sort(deviations);
        return deviations[deviations.length / 2];
    }

    private static int[] toArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int index = 0; index < values.size(); index++) result[index] = values.get(index);
        return result;
    }

    private static Result analyzeProfile(PixelSource source, int screenWidth, int left, int centerY,
                                         int trackWidth, int trackHeight) {
        int innerRadius = Math.max(1, (int) Math.round(trackHeight * 0.24));
        int rowCount = innerRadius * 2 + 1;
        int[] reds = new int[rowCount];
        int[] greens = new int[rowCount];
        int[] blues = new int[rowCount];
        double[] rawR = new double[trackWidth];
        double[] rawG = new double[trackWidth];
        double[] rawB = new double[trackWidth];

        for (int offset = 0; offset < trackWidth; offset++) {
            int count = 0;
            for (int y = centerY - innerRadius; y <= centerY + innerRadius; y++) {
                int color = source.get(left + offset, y);
                reds[count] = red(color);
                greens[count] = green(color);
                blues[count] = blue(color);
                count++;
            }
            Arrays.sort(reds, 0, count);
            Arrays.sort(greens, 0, count);
            Arrays.sort(blues, 0, count);
            rawR[offset] = reds[count / 2];
            rawG[offset] = greens[count / 2];
            rawB[offset] = blues[count / 2];
        }

        int smoothRadius = Math.max(1, (int) Math.round(trackWidth * 0.008));
        double[] r = smooth(rawR, smoothRadius);
        double[] g = smooth(rawG, smoothRadius);
        double[] b = smooth(rawB, smoothRadius);
        double[] luminance = new double[trackWidth];
        double[] saturation = new double[trackWidth];
        for (int index = 0; index < trackWidth; index++) {
            luminance[index] = (r[index] + g[index] + b[index]) / 3.0;
            saturation[index] = Math.max(r[index], Math.max(g[index], b[index]))
                    - Math.min(r[index], Math.min(g[index], b[index]));
        }

        int initial = Math.max(4, (int) Math.round(trackWidth * 0.08));
        double initialSaturation = mean(saturation, 0, initial);
        double initialGreen = meanDifference(g, b, 0, initial);
        if (initialSaturation < 14 && initialGreen < 7) {
            return new Result(0, 0, Direction.NONE,
                    new Anchor(left / (float) screenWidth, centerY / (float) screenWidth), 0.68f);
        }

        int window = Math.max(3, (int) Math.round(trackWidth * 0.025));
        int gap = Math.max(1, (int) Math.round(trackWidth * 0.006));
        int minBoundary = Math.max(window + gap + 1, (int) Math.round(trackWidth * 0.03));
        int maxBoundary = (int) Math.round(trackWidth * 0.94);
        Edge gain = new Edge(-1e9, -1);
        Edge loss = new Edge(-1e9, -1);
        for (int boundary = minBoundary; boundary <= maxBoundary; boundary++) {
            int leftStart = boundary - gap - window;
            int leftEnd = boundary - gap;
            int rightStart = boundary + gap;
            int rightEnd = boundary + gap + window;
            if (leftStart < 0 || rightEnd > trackWidth) continue;
            double leftLight = mean(luminance, leftStart, leftEnd);
            double rightLight = mean(luminance, rightStart, rightEnd);
            double leftRed = mean(r, leftStart, leftEnd);
            double rightRed = mean(r, rightStart, rightEnd);
            double leftSat = mean(saturation, leftStart, leftEnd);
            double rightSat = mean(saturation, rightStart, rightEnd);
            double gainScore = rightLight - leftLight + 0.35 * (rightRed - leftRed);
            double lossScore = leftLight - rightLight + 0.30 * (leftSat - rightSat);
            if (gainScore > gain.score) gain = new Edge(gainScore, boundary);
            if (lossScore > loss.score) loss = new Edge(lossScore, boundary);
        }

        // A recovery preview can be only about three stamina points wide.  The
        // old 5.5% minimum skipped that real edge and latched onto the shaded
        // tail of the track instead.  Keep this just above the smoothing
        // footprint so it still works on narrow foldable captures.
        int gainPreviewMin = Math.max(4, (int) Math.round(trackWidth * 0.012));
        int lossPreviewMin = Math.max(10, (int) Math.round(trackWidth * 0.055));
        Direction direction = Direction.NONE;
        int internal = -1;
        int finalBoundary = trackWidth;

        // A +3 preview can be only four or five source pixels wide. Sampling
        // after the classifier gap skipped the entire bright segment on low
        // resolution captures, so inspect immediately to the right of the edge.
        int gainZoneStart = Math.min(trackWidth, gain.position);
        int gainZoneEnd = Math.min(trackWidth,
                gainZoneStart + Math.max(gainPreviewMin, window));
        double gainZoneLight = mean(luminance, gainZoneStart, gainZoneEnd);
        double gainZoneSaturation = mean(saturation, gainZoneStart, gainZoneEnd);
        if (gain.score >= 24 && gainZoneLight >= 115 && gainZoneSaturation >= 35
                && trackWidth - gain.position >= gainPreviewMin) {
            direction = Direction.GAIN;
            internal = gain.position;
            Edge tail = new Edge(-1e9, -1);
            for (int boundary = internal + gainPreviewMin;
                 boundary <= trackWidth - window - gap; boundary++) {
                int leftStart = boundary - gap - window;
                int leftEnd = boundary - gap;
                int rightStart = boundary + gap;
                int rightEnd = boundary + gap + window;
                double deltaLight = mean(luminance, rightStart, rightEnd)
                        - mean(luminance, leftStart, leftEnd);
                double deltaSat = mean(saturation, leftStart, leftEnd)
                        - mean(saturation, rightStart, rightEnd);
                double deltaRed = mean(r, leftStart, leftEnd) - mean(r, rightStart, rightEnd);
                double score = -deltaLight + 0.25 * deltaSat + 0.15 * deltaRed;
                if (score > tail.score) tail = new Edge(score, boundary);
            }
            if (tail.score >= 24) finalBoundary = tail.position;
        }

        if (direction == Direction.NONE && loss.score >= 24) {
            int boundary = loss.position;
            int zoneStart = Math.min(trackWidth, boundary + gap + 2);
            int zoneEnd = Math.min(trackWidth,
                    zoneStart + Math.max(lossPreviewMin, window * 2));
            double rightSat = mean(saturation, zoneStart, zoneEnd);
            double rightGreen = meanDifference(g, b, zoneStart, zoneEnd);
            Edge neutral = new Edge(-1e9, -1);
            if (trackWidth - boundary >= lossPreviewMin) {
                for (int point = boundary + lossPreviewMin;
                     point <= trackWidth - window - gap; point++) {
                    int leftStart = point - gap - window;
                    int leftEnd = point - gap;
                    int rightStart = point + gap;
                    int rightEnd = point + gap + window;
                    double deltaSat = mean(saturation, leftStart, leftEnd)
                            - mean(saturation, rightStart, rightEnd);
                    double distance = vectorDistance(
                            mean(r, leftStart, leftEnd), mean(g, leftStart, leftEnd), mean(b, leftStart, leftEnd),
                            mean(r, rightStart, rightEnd), mean(g, rightStart, rightEnd), mean(b, rightStart, rightEnd));
                    double score = deltaSat + 0.20 * distance;
                    if (score > neutral.score) neutral = new Edge(score, point);
                }
            }
            if (rightSat >= 17 && rightGreen >= 8) {
                direction = Direction.LOSS;
                internal = boundary;
                finalBoundary = neutral.score >= 8 ? neutral.position : trackWidth;
            } else {
                finalBoundary = boundary;
            }
        }

        // The wide windows above are deliberately robust enough to classify a
        // preview in gradients and bloom.  They also move a short edge several
        // pixels outwards.  Once its kind is known, relocate each edge with a
        // small local comparison; this preserves the robust classification but
        // measures the actual filled length.
        if (direction == Direction.GAIN) {
            internal = refineBoundary(r, g, b, luminance, saturation, internal,
                    trackWidth, BoundaryKind.GAIN_START);
            if (finalBoundary < trackWidth) {
                finalBoundary = refineBoundary(r, g, b, luminance, saturation, finalBoundary,
                        trackWidth, BoundaryKind.GAIN_END);
            }
        } else if (direction == Direction.LOSS) {
            // The wider classifier is more stable for the dim, translucent
            // loss preview.  Only sharpen its neutral tail (the current value);
            // sharpening the first edge overreacts to the in-game gradient.
            if (finalBoundary < trackWidth) {
                finalBoundary = refineBoundary(r, g, b, luminance, saturation, finalBoundary,
                        trackWidth, BoundaryKind.LOSS_END);
            }
        } else if (finalBoundary < trackWidth) {
            finalBoundary = refineBoundary(r, g, b, luminance, saturation, finalBoundary,
                    trackWidth, BoundaryKind.NORMAL_END);
        }

        int currentBoundary;
        int afterBoundary;
        if (direction == Direction.GAIN) {
            currentBoundary = internal;
            afterBoundary = finalBoundary;
        } else if (direction == Direction.LOSS) {
            currentBoundary = finalBoundary;
            afterBoundary = internal;
        } else {
            currentBoundary = finalBoundary;
            afterBoundary = finalBoundary;
        }
        int current = valueForBoundary(currentBoundary, trackWidth);
        int after = valueForBoundary(afterBoundary, trackWidth);
        float confidence = (float) Math.min(0.99, 0.72 + Math.max(gain.score, loss.score) / 500.0);
        return new Result(current, after, direction,
                new Anchor(left / (float) screenWidth, centerY / (float) screenWidth), confidence);
    }

    private static int refineBoundary(double[] r, double[] g, double[] b,
                                      double[] luminance, double[] saturation,
                                      int approximate, int trackWidth, BoundaryKind kind) {
        int microWindow = Math.max(2, (int) Math.round(trackWidth * 0.006));
        int searchRadius = Math.max(6, (int) Math.round(trackWidth * 0.030));
        int first = Math.max(microWindow, approximate - searchRadius);
        int last = Math.min(trackWidth - microWindow, approximate + searchRadius);
        if (first > last) return approximate;

        Edge best = new Edge(-1e9, approximate);
        for (int boundary = first; boundary <= last; boundary++) {
            int leftStart = boundary - microWindow;
            int rightEnd = boundary + microWindow;
            double leftLight = mean(luminance, leftStart, boundary);
            double rightLight = mean(luminance, boundary, rightEnd);
            double leftSat = mean(saturation, leftStart, boundary);
            double rightSat = mean(saturation, boundary, rightEnd);
            double distance = vectorDistance(
                    mean(r, leftStart, boundary), mean(g, leftStart, boundary),
                    mean(b, leftStart, boundary), mean(r, boundary, rightEnd),
                    mean(g, boundary, rightEnd), mean(b, boundary, rightEnd));
            double deltaLight = rightLight - leftLight;
            double deltaSat = rightSat - leftSat;
            double score;
            switch (kind) {
                case GAIN_START:
                    score = deltaLight - 0.25 * deltaSat + 0.10 * distance;
                    break;
                case GAIN_END:
                    score = -deltaLight + 0.15 * deltaSat + 0.10 * distance;
                    break;
                case LOSS_END:
                    score = deltaLight - deltaSat + 0.10 * distance;
                    break;
                case NORMAL_END:
                default:
                    score = -deltaLight - 0.25 * deltaSat + 0.10 * distance;
                    break;
            }
            if (score > best.score) best = new Edge(score, boundary);
        }
        return best.position;
    }

    private static int valueForBoundary(int boundary, int trackWidth) {
        int value = (int) Math.round(clamp(boundary, 0, trackWidth) * 100.0 / trackWidth);
        // Rounded end caps and resampling can consume one or two visual pixels.
        // Snap only the extreme two percent so a visibly full/empty gauge is not
        // reported as 99/1 while ordinary values remain unaltered.
        if (value >= 98) return 100;
        if (value <= 2) return 0;
        return value;
    }

    private static double[] smooth(double[] values, int radius) {
        double[] prefix = new double[values.length + 1];
        for (int index = 0; index < values.length; index++) prefix[index + 1] = prefix[index] + values[index];
        double[] result = new double[values.length];
        for (int index = 0; index < values.length; index++) {
            int start = Math.max(0, index - radius);
            int end = Math.min(values.length, index + radius + 1);
            result[index] = (prefix[end] - prefix[start]) / (end - start);
        }
        return result;
    }

    private static double mean(double[] values, int start, int end) {
        start = clamp(start, 0, values.length);
        end = clamp(end, start, values.length);
        if (end <= start) return 0.0;
        double sum = 0.0;
        for (int index = start; index < end; index++) sum += values[index];
        return sum / (end - start);
    }

    private static double meanDifference(double[] left, double[] right, int start, int end) {
        return mean(left, start, end) - mean(right, start, end);
    }

    private static boolean isGaugeColor(int color) {
        int r = red(color);
        int g = green(color);
        int b = blue(color);
        int maximum = Math.max(r, Math.max(g, b));
        int minimum = Math.min(r, Math.min(g, b));
        return g >= 65 && g - r >= 7 && g - b >= -22 && maximum - minimum >= 13;
    }

    private static int red(int color) { return (color >>> 16) & 0xff; }
    private static int green(int color) { return (color >>> 8) & 0xff; }
    private static int blue(int color) { return color & 0xff; }
    private static double brightness(int color) { return (red(color) + green(color) + blue(color)) / 3.0; }
    private static double colorDistance(int first, int second) {
        return (Math.abs(red(first) - red(second))
                + Math.abs(green(first) - green(second))
                + Math.abs(blue(first) - blue(second))) / 3.0;
    }

    private static double vectorDistance(double r1, double g1, double b1,
                                         double r2, double g2, double b2) {
        double dr = r1 - r2;
        double dg = g1 - g2;
        double db = b1 - b2;
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class PixelSource {
        final Region region;
        final int[] pixels;
        PixelSource(Region region, int[] pixels) { this.region = region; this.pixels = pixels; }
        int get(int x, int y) {
            if (!contains(x, y)) return 0xff000000;
            return pixels[(y - region.top) * region.width + x - region.left];
        }
        boolean contains(int x, int y) {
            return x >= region.left && x < region.left + region.width
                    && y >= region.top && y < region.top + region.height;
        }
    }

    private static final class Candidate {
        final double score;
        final int left;
        final int colorEnd;
        final int right;
        final int top;
        final int bottom;
        final boolean empty;

        Candidate(double score, int left, int colorEnd, int right,
                  int top, int bottom, boolean empty) {
            this.score = score;
            this.left = left;
            this.colorEnd = colorEnd;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
            this.empty = empty;
        }
    }

    private static final class Run {
        final int start;
        final int end;
        Run(int start, int end) { this.start = start; this.end = end; }
    }

    private static final class StableBand {
        final int top;
        final int bottom;
        final int[] starts;
        final int[] ends;

        StableBand(int top, int bottom, int[] starts, int[] ends) {
            this.top = top;
            this.bottom = bottom;
            this.starts = starts;
            this.ends = ends;
        }
    }

    private static final class Tail {
        final int start;
        final int end;
        Tail(int start, int end) { this.start = start; this.end = end; }
    }

    private static final class Edge {
        final double score;
        final int position;
        Edge(double score, int position) { this.score = score; this.position = position; }
    }

    private enum MaskKind { GAUGE, NEUTRAL }
    private enum BoundaryKind { GAIN_START, GAIN_END, LOSS_END, NORMAL_END }
}
