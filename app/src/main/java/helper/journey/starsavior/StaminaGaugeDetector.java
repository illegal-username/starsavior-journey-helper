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
    // Scale-independent rendered shape used only to rank gauge-shaped components.
    // Endpoint coordinates always come from visible boundaries in the current image.
    private static final double TRACK_ASPECT = 12.8;
    private static final double PREFERRED_CENTER_MIN = 0.022;
    private static final double PREFERRED_CENTER_MAX = 0.050;

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
        Candidate gauge = findBestCandidate(source, screenWidth, previousAnchor, MaskKind.GAUGE);
        Candidate best = gauge;
        if (gauge == null || !isInPreferredHudBand(gauge, screenWidth)
                || hudCenterDistance(gauge, screenWidth) > 0.010) {
            Candidate loss = findBestCandidate(source, screenWidth, previousAnchor,
                    MaskKind.LOSS_PREVIEW);
            if (loss != null) {
                loss = attachObservedGaugeHead(source, loss);
            }
            Candidate neutral = findBestCandidate(source, screenWidth, previousAnchor,
                    MaskKind.NEUTRAL);
            Candidate alternative = null;
            if (loss != null && isInPreferredHudBand(loss, screenWidth)) {
                alternative = loss;
            }
            if (neutral != null && isInPreferredHudBand(neutral, screenWidth)
                    && (alternative == null || hudCenterDistance(neutral, screenWidth) + 0.004
                    < hudCenterDistance(alternative, screenWidth))) {
                alternative = neutral;
            }
            if (alternative != null && (gauge == null
                    || !isInPreferredHudBand(gauge, screenWidth)
                    || hudCenterDistance(alternative, screenWidth) + 0.008
                    < hudCenterDistance(gauge, screenWidth))) {
                best = alternative.kind == MaskKind.NEUTRAL
                        ? attachObservedGaugeHead(source, alternative) : alternative;
            } else if (best == null) {
                best = loss != null ? loss : neutral;
                if (best != null && best.kind == MaskKind.NEUTRAL) {
                    best = attachObservedGaugeHead(source, best);
                }
            }
        }
        if (best == null) return null;

        int trackHeight = best.bottom - best.top;
        // The value ruler is the observed track itself. The left endpoint comes
        // from the outermost corroborated row of the same 2-D gauge component and
        // the right endpoint comes from its observed colored/neutral end. No cap
        // length, expected value, device resolution, or previous frame is added.
        int left = findObservedTrackLeft(source, screenWidth, best);
        int right = best.right;
        int centerY = (best.top + best.bottom - 1) / 2;
        left = Math.max(left, region.left);
        right = Math.min(right, region.left + region.width - 1);
        int trackWidth = right - left;
        if (trackWidth < trackHeight * 6
                || !source.contains(left, centerY)
                || !source.contains(right - 1, centerY)) return null;
        int reliableLeft = best.kind == MaskKind.GAUGE
                ? findReliableGaugeColumn(source, best, centerY, trackHeight)
                : best.kind == MaskKind.LOSS_PREVIEW ? Math.max(left, best.left) : left;
        return analyzeProfile(source, screenWidth, left, centerY, trackWidth, trackHeight,
                reliableLeft, best.colorEnd - left,
                best.kind == MaskKind.LOSS_PREVIEW ? 0 : best.lossStart < 0
                        ? -1 : best.lossStart - left, best.hasNeutralTail,
                best.kind);
    }

    /**
     * Locates the visible zero endpoint from the complete two-dimensional track.
     *
     * <p>The sandwich icon can cover the middle rows at the track head, while the
     * upper and lower rows still expose the same continuous fill/preview/neutral
     * component.  Starting at the already-qualified candidate, this method follows
     * that union across nearby rows and uses a supported lower edge of the observed
     * starts.  It does not extend an endpoint by a cap size or by an expected value.</p>
     */
    private static int findObservedTrackLeft(PixelSource source, int screenWidth,
                                             Candidate candidate) {
        int height = Math.max(1, candidate.bottom - candidate.top);
        int centerY = (candidate.top + candidate.bottom - 1) / 2;
        int maximumGap = Math.max(
                Math.max(1, (int) Math.round(height * 0.12)),
                (int) Math.round(screenWidth * 0.0012));
        Run reference = findTrackRun(source, centerY, candidate.left,
                candidate.colorEnd, maximumGap);
        if (reference == null) return candidate.observedLeft;

        int referenceWidth = reference.end - reference.start;
        int coreWidth = Math.max(1, candidate.colorEnd - candidate.left);
        int minimumCoreOverlap = Math.max(2, (int) Math.round(coreWidth * 0.35));
        int rightTolerance = Math.max(2, (int) Math.round(height * 0.80));
        int minimumWidth = Math.max(coreWidth,
                (int) Math.round(referenceWidth * 0.70));
        int maximumWidth = Math.max(minimumWidth + 1,
                (int) Math.round(referenceWidth * 1.30));
        int firstY = Math.max(source.region.top, candidate.top - height);
        int lastY = Math.min(source.region.top + source.region.height,
                candidate.bottom + height);
        List<Integer> starts = new ArrayList<>();
        for (int y = firstY; y < lastY; y++) {
            Run run = findTrackRun(source, y, candidate.left,
                    candidate.colorEnd, maximumGap);
            if (run == null) continue;
            int width = run.end - run.start;
            if (overlap(run.start, run.end, candidate.left, candidate.colorEnd)
                    < minimumCoreOverlap
                    || Math.abs(run.end - reference.end) > rightTolerance
                    || width < minimumWidth || width > maximumWidth) {
                continue;
            }
            starts.add(run.start);
        }
        int minimumRows = Math.max(3, (int) Math.ceil(height * 0.25));
        if (starts.size() < minimumRows) return candidate.observedLeft;
        return percentile(toArray(starts), 0.20);
    }

    /** Finds the track-colored run with the greatest overlap with a known core. */
    private static Run findTrackRun(PixelSource source, int y, int coreLeft,
                                    int coreRight, int maximumGap) {
        if (y < source.region.top
                || y >= source.region.top + source.region.height) return null;
        Run best = null;
        int bestOverlap = 0;
        int start = -1;
        int previous = -1;
        int regionRight = source.region.left + source.region.width;
        for (int x = source.region.left; x <= regionRight + maximumGap; x++) {
            boolean matches = x < regionRight && isTrackInteriorColor(source.get(x, y));
            if (matches) {
                if (start < 0) start = x;
                previous = x;
            }
            if (start >= 0 && (!matches && x - previous > maximumGap)) {
                Run run = new Run(start, previous + 1);
                int overlap = overlap(run.start, run.end, coreLeft, coreRight);
                if (overlap > bestOverlap
                        || (overlap == bestOverlap && best != null
                        && run.end - run.start > best.end - best.start)) {
                    best = run;
                    bestOverlap = overlap;
                }
                start = -1;
                previous = -1;
            }
        }
        return bestOverlap > 0 ? best : null;
    }

    private static boolean isTrackInteriorColor(int color) {
        return isGaugeColor(color) || isLossPreviewColor(color)
                || isDimLossPreviewColor(color) || isNeutralColor(color);
    }

    private static int overlap(int firstStart, int firstEnd,
                               int secondStart, int secondEnd) {
        return Math.max(0, Math.min(firstEnd, secondEnd)
                - Math.max(firstStart, secondStart));
    }

    /** Finds a visibly gauge-colored profile column rather than shifting by a cap estimate. */
    private static int findReliableGaugeColumn(PixelSource source, Candidate candidate,
                                               int centerY, int trackHeight) {
        int radius = Math.max(1, (int) Math.round(trackHeight * 0.24));
        int firstY = Math.max(source.region.top, centerY - radius);
        int lastY = Math.min(source.region.top + source.region.height, centerY + radius + 1);
        int[] reds = new int[Math.max(1, lastY - firstY)];
        int[] greens = new int[reds.length];
        int[] blues = new int[reds.length];
        int previous = -2;
        for (int x = candidate.left; x < candidate.colorEnd; x++) {
            int color = medianColor(source, x, firstY, lastY, reds, greens, blues);
            if (isGaugeColor(color)) {
                if (x == previous + 1) return previous;
                previous = x;
            } else {
                previous = -2;
            }
        }
        return Math.min(candidate.colorEnd - 1, candidate.left);
    }

    private static boolean isInPreferredHudBand(Candidate candidate, int screenWidth) {
        double center = (candidate.top + candidate.bottom) * 0.5 / screenWidth;
        return center >= PREFERRED_CENTER_MIN && center <= PREFERRED_CENTER_MAX;
    }

    private static double hudCenterDistance(Candidate candidate, int screenWidth) {
        return Math.abs((candidate.top + candidate.bottom) * 0.5 / screenWidth - 0.032);
    }

    /**
     * Joins a very short colored head to the adjacent loss or neutral body using
     * only pixels that are actually present in the image.
     *
     * <p>At low stamina the sandwich sprite covers the middle rows of the head,
     * but the upper and lower gauge rows still expose the full short segment. The
     * previous implementation sampled only the middle rows and then subtracted an
     * estimated cap length. Here every row is inspected independently, and the
     * left endpoint is the corroborated minimum of the observed runs that touch
     * the neutral body. If no such run exists, the gauge remains genuinely empty.</p>
     */
    private static Candidate attachObservedGaugeHead(PixelSource source, Candidate body) {
        int height = body.bottom - body.top;
        int allowedGap = Math.max(1, (int) Math.round(height * 0.08));
        int minimumX = Math.max(source.region.left,
                body.left - (int) Math.ceil(height * 2.0));
        List<Integer> starts = new ArrayList<>();
        for (int y = body.top; y < body.bottom; y++) {
            int firstMatch = -1;
            int lastMatch = -1;
            int matchCount = 0;
            int gap = 0;
            for (int x = body.left - 1; x >= minimumX; x--) {
                if (isGaugeColor(source.get(x, y))) {
                    if (lastMatch < 0) lastMatch = x;
                    firstMatch = x;
                    matchCount++;
                    gap = 0;
                } else {
                    gap++;
                    if (gap > allowedGap) break;
                }
            }
            if (firstMatch >= 0 && body.left - lastMatch - 1 <= allowedGap
                    && matchCount >= 2) {
                starts.add(firstMatch);
            }
        }
        // A real head is a vertically supported piece of the same rectangular
        // track. Two or three green antialiasing pixels from the sandwich icon
        // must not turn a loss-to-zero gauge into a non-zero remainder.
        int minimumSupportedRows = Math.max(2, (int) Math.ceil(height * 0.40));
        if (starts.size() < minimumSupportedRows) {
            return body;
        }
        int[] observedStarts = toArray(starts);
        int profileLeft = percentile(observedStarts, 0.75);
        int observedLeft = supportedMinimum(observedStarts);
        int colorEnd = body.kind == MaskKind.NEUTRAL ? body.left : body.colorEnd;
        boolean hasNeutralTail = body.kind == MaskKind.NEUTRAL || body.hasNeutralTail;
        int lossStart = body.kind == MaskKind.LOSS_PREVIEW ? body.left : body.lossStart;
        return new Candidate(body.score, profileLeft, colorEnd, body.right,
                body.top, body.bottom, false, hasNeutralTail, observedLeft, MaskKind.GAUGE,
                lossStart);
    }

    private static Candidate findBestCandidate(PixelSource source, int screenWidth,
                                               Anchor previousAnchor, MaskKind kind) {
        int maxGap = Math.max(1, (int) Math.round(screenWidth * 0.0012));
        int minRun = Math.max(6, (int) Math.round(screenWidth * 0.004));
        // The physical gauge interior stays near one percent of the capture width
        // across the supplied phone, foldable and gallery-scaled captures. Thin
        // strokes from the GOOD/NORMAL status text can have a gauge-like aspect
        // ratio on the same row, but are less than half the track's height.
        int minHeight = Math.max(4, (int) Math.round(screenWidth * 0.0055));
        int maxHeight = Math.max(minHeight + 1, (int) Math.round(screenWidth * 0.020));
        int yStep = Math.max(1, (int) Math.round(screenWidth / 1800.0));

        @SuppressWarnings("unchecked")
        List<Run>[] rows = new List[source.region.height];
        for (int localY = 0; localY < source.region.height; localY++) {
            rows[localY] = findRuns(source, source.region.top + localY,
                    source.region.left, source.region.left + source.region.width,
                    maxGap, minRun, kind);
        }

        Candidate best = null;
        Candidate bestInHudBand = null;
        Set<Long> seen = new HashSet<>();
        for (int localY = 0; localY < rows.length; localY += yStep) {
            for (Run seed : rows[localY]) {
                StableBand band = stableBand(rows, localY, seed);
                int height = band.bottom - band.top;
                int observedHeight = band.observedBottom - band.observedTop;
                int supportedHeight = Math.max(height, observedHeight);
                if (supportedHeight < minHeight || supportedHeight > maxHeight
                        || band.starts.length < supportedHeight * 0.55) continue;
                int left = median(band.starts);
                int observedLeft = supportedMinimum(band.starts);
                int colorEnd = median(band.ends);
                int measuredEnd = colorEnd;
                int colorWidth = colorEnd - left;
                // Recovery/loss overlays may have different horizontal endpoints
                // in their upper and lower rows. That can make the rectangular
                // stable core thinner than the rendered track. Measure vertical
                // mask runs well inside the same colored segment and combine that
                // observation with the multi-row band. Both are local geometry;
                // capture resolution never selects a separate correction path.
                double geometryHeight = (height + observedHeight) * 0.5;
                int tailTop = band.observedTop;
                int tailBottom = band.observedBottom;
                if (kind != MaskKind.NEUTRAL) {
                    int[] interiorBand = interiorBandBounds(source, left, colorEnd,
                            band.top, band.bottom, kind);
                    double interiorHeight = interiorBand == null ? 0.0 : interiorBand[2];
                    if (interiorBand != null) {
                        // Use the complete vertical run that is actually visible at
                        // interior gauge columns. The stable core can be shorter when
                        // preview colors taper near their horizontal boundary.
                        // The per-column color run can be shortened by a
                        // translucent gradient. It may expand the observed band,
                        // but must not shrink the multi-row component and truncate
                        // the still-visible right end of the track search.
                        geometryHeight = Math.max(geometryHeight, interiorHeight);
                        tailTop = Math.min(tailTop, interiorBand[0]);
                        tailBottom = Math.max(tailBottom, interiorBand[1]);
                    }
                }
                double colorAspect = colorWidth / (double) geometryHeight;
                // A real track has a visible left cap inside the deliberately broad scan
                // region.  Runs touching that region's artificial boundary are clipped UI
                // decorations or scenery, so their apparent width/profile is unknowable.
                if (left - source.region.left < height) continue;
                if (colorAspect < 0.9 || colorAspect > 17.0) continue;
                long key = (((long) (left / 2)) << 42)
                        ^ (((long) (colorEnd / 2)) << 20)
                        ^ ((long) band.top << 10) ^ band.bottom;
                if (!seen.add(key)) continue;

                double density = maskDensity(source, left, colorEnd, band.top, band.bottom, kind);
                double minimumDensity = kind == MaskKind.GAUGE ? 0.48
                        : kind == MaskKind.LOSS_PREVIEW ? 0.62 : 0.72;
                if (density < minimumDensity) continue;

                Tail tail = kind != MaskKind.NEUTRAL
                        ? findTrackTail(source, left, colorEnd, tailTop, tailBottom,
                        geometryHeight)
                        : null;
                if (tail != null && tail.hasNeutral && tail.start > colorEnd
                        && tail.end - tail.start < geometryHeight * 1.5
                        && (tailTop != band.top || tailBottom != band.bottom)) {
                    // A translucent preview can make the broad observed band the
                    // best way to follow a long empty track.  For a very short
                    // continuation, however, the broad band can also include the
                    // adjacent condition panel.  Confirm such a continuation in
                    // the stable colored core.  This compares two directly
                    // observed vertical cross-sections; it does not infer a track
                    // endpoint from its expected width or stamina value.
                    Tail coreTail = findTrackTail(source, left, colorEnd,
                            band.top, band.bottom, geometryHeight);
                    if (coreTail == null || !coreTail.hasNeutral) tail = coreTail;
                }
                int right;
                int tailStart;
                double aspect;
                double tailBonus;
                boolean empty = kind == MaskKind.NEUTRAL;
                if (tail != null) {
                    right = tail.end;
                    tailStart = tail.start;
                    aspect = (right - left) / (double) geometryHeight;
                    tailBonus = 36.0;
                } else if (kind != MaskKind.NEUTRAL
                        && colorAspect >= (kind == MaskKind.LOSS_PREVIEW ? 10.0 : 8.0)) {
                    right = colorEnd;
                    tailStart = -1;
                    aspect = colorAspect;
                    // A completely filled normal/recovery track has no gray tail.
                    // Give its full-width rectangular core enough margin for small
                    // JPEG-decoder color differences at the rounded end cap.
                    tailBonus = kind == MaskKind.LOSS_PREVIEW ? 24.0 : 16.0;
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
                int profileLeft = percentile(band.starts, 0.75);
                int lossStart = kind == MaskKind.LOSS_PREVIEW ? left : -1;
                int observedColorEnd = colorEnd;
                if (tail != null && tail.start > colorEnd
                        && (kind == MaskKind.GAUGE || kind == MaskKind.LOSS_PREVIEW)) {
                    if (kind == MaskKind.GAUGE) lossStart = colorEnd;
                    observedColorEnd = tail.start;
                }
                int endpointLeft = kind == MaskKind.LOSS_PREVIEW ? profileLeft : observedLeft;
                Candidate candidate = new Candidate(score, profileLeft, observedColorEnd, right,
                        band.top + source.region.top, band.bottom + source.region.top, empty,
                        tail != null && tail.hasNeutral,
                        endpointLeft, kind, lossStart);
                if (best == null || candidate.score > best.score) best = candidate;
                if (location >= PREFERRED_CENTER_MIN && location <= PREFERRED_CENTER_MAX
                        && (bestInHudBand == null || candidate.score > bestInHudBand.score)) {
                    bestInHudBand = candidate;
                }
            }
        }
        double minimumScore = kind == MaskKind.GAUGE ? 92.0
                : kind == MaskKind.LOSS_PREVIEW ? 98.0 : 105.0;
        // Date banners and goal badges can contain long saturated strips above the HUD.
        // Prefer a qualified candidate in the broad gauge band, but retain the full
        // scan as a fallback for letterboxed or vertically translated layouts.
        if (bestInHudBand != null && bestInHudBand.score >= minimumScore) {
            return bestInHudBand;
        }
        if (best == null) return null;
        return best.score >= minimumScore ? best : null;
    }

    /** Measures the visible vertical mask band inside a candidate. */
    private static int[] interiorBandBounds(PixelSource source, int left, int colorEnd,
                                            int localTop, int localBottom, MaskKind kind) {
        int colorWidth = colorEnd - left;
        if (colorWidth <= 0 || localBottom <= localTop) return null;
        int centerY = source.region.top + (localTop + localBottom - 1) / 2;
        int[] tops = new int[3];
        int[] bottoms = new int[3];
        int[] heights = new int[3];
        int count = 0;
        double[] fractions = {0.25, 0.50, 0.75};
        for (double fraction : fractions) {
            int x = clamp(left + (int) Math.round(colorWidth * fraction),
                    left, colorEnd - 1);
            if (!source.contains(x, centerY) || !matches(source.get(x, centerY), kind)) {
                continue;
            }
            int top = centerY;
            while (top > source.region.top && matches(source.get(x, top - 1), kind)) top--;
            int bottom = centerY + 1;
            int regionBottom = source.region.top + source.region.height;
            while (bottom < regionBottom && matches(source.get(x, bottom), kind)) bottom++;
            tops[count] = top - source.region.top;
            bottoms[count] = bottom - source.region.top;
            heights[count++] = bottom - top;
        }
        if (count == 0) return null;
        return new int[] {
                median(Arrays.copyOf(tops, count)),
                median(Arrays.copyOf(bottoms, count)),
                median(Arrays.copyOf(heights, count))
        };
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
            return new StableBand(coreTop, coreBottom, toArray(coreStarts), toArray(coreEnds),
                    top, bottom);
        }
        return new StableBand(top, bottom, toArray(starts), toArray(ends), top, bottom);
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

    /**
     * Finds the visible right edge of the track from its two-dimensional border.
     *
     * <p>The condition panel can touch the gauge and can even have the same gray
     * center-line color as an empty part of the track.  Color connectivity alone
     * therefore joins two different HUD components.  The actual track is still
     * visible as a horizontal band: pixels immediately above and below it differ
     * from its interior until the physical right edge.  This method follows that
     * observed band and stops at its first vertical boundary.  It never predicts
     * an endpoint from height, a previous frame, or an expected stamina value.</p>
     */
    private static Tail findTrackTail(PixelSource source, int left, int colorEnd,
                                      int localTop, int localBottom, double geometryHeight) {
        int top = localTop + source.region.top;
        int bottom = localBottom + source.region.top;
        int height = bottom - top;
        int maximum = Math.min(source.region.left + source.region.width,
                left + (int) Math.round(geometryHeight * 17.0));
        int minimum = Math.max(left, colorEnd - Math.max(1,
                (int) Math.round(height * 0.12)));
        if (maximum - colorEnd < 3) return null;

        int inset = Math.max(1, (int) Math.round(height * 0.22));
        int firstY = Math.min(bottom - 1, top + inset);
        int lastY = Math.max(firstY + 1, bottom - inset);
        // The color mask can begin a few rows inside a translucent track. Sample
        // a scale-relative band back through each edge so the median lands on the
        // actually drawn dark outline, rather than on similar scenery beyond it.
        int borderRows = Math.max(2, (int) Math.round(height * 0.30));
        int aboveLast = Math.max(source.region.top + 1, top);
        int aboveFirst = Math.max(source.region.top, aboveLast - borderRows);
        int belowFirst = Math.min(source.region.top + source.region.height - 1, bottom);
        int belowLast = Math.min(source.region.top + source.region.height,
                belowFirst + borderRows);
        if (aboveLast <= aboveFirst || belowLast <= belowFirst) return null;

        int profileLength = maximum - minimum;
        double[] support = new double[profileLength];
        double[] preciseSupport = new double[profileLength];
        boolean[] neutral = new boolean[profileLength];
        boolean[] loss = new boolean[profileLength];
        int[] colors = new int[profileLength];
        int[] reds = new int[Math.max(lastY - firstY,
                Math.max(aboveLast - aboveFirst, belowLast - belowFirst))];
        int[] greens = new int[reds.length];
        int[] blues = new int[reds.length];
        int preciseRows = Math.max(1, (int) Math.round(height * 0.10));
        int preciseAboveFirst = Math.max(source.region.top, top - preciseRows);
        int preciseAboveLast = Math.max(preciseAboveFirst + 1, top);
        int preciseBelowFirst = Math.min(source.region.top + source.region.height - 1,
                bottom);
        int preciseBelowLast = Math.min(source.region.top + source.region.height,
                preciseBelowFirst + preciseRows);
        for (int offset = 0; offset < profileLength; offset++) {
            int x = minimum + offset;
            int inside = medianColor(source, x, firstY, lastY, reds, greens, blues);
            int above = medianColor(source, x, aboveFirst, aboveLast, reds, greens, blues);
            int below = medianColor(source, x, belowFirst, belowLast, reds, greens, blues);
            colors[offset] = inside;
            support[offset] = Math.min(colorVectorDistance(inside, above),
                    colorVectorDistance(inside, below));
            int preciseAbove = medianColor(source, x, preciseAboveFirst,
                    preciseAboveLast, reds, greens, blues);
            int preciseBelow = medianColor(source, x, preciseBelowFirst,
                    preciseBelowLast, reds, greens, blues);
            preciseSupport[offset] = Math.min(colorVectorDistance(inside, preciseAbove),
                    colorVectorDistance(inside, preciseBelow));
            neutral[offset] = isNeutralColor(inside);
            // Bright olive previews and very dark translucent previews occupy
            // different parts of the same rendered family. Following their
            // union prevents a hue crossing inside the preview from masquerading
            // as the physical end of the track.
            loss[offset] = isLossPreviewColor(inside)
                    || isDimLossPreviewColor(inside);
        }

        int firstTail = clamp(colorEnd - minimum, 0, profileLength);
        int transition = Math.max(2, (int) Math.round(height * 0.15));
        int probeWidth = Math.max(3, (int) Math.round(height * 0.20));
        int probeStart = clamp(firstTail + transition, 0, profileLength);
        int probeEnd = clamp(probeStart + probeWidth, probeStart, profileLength);
        int neutralProbe = countTrue(neutral, probeStart, probeEnd);
        boolean startsWithNeutralTail = probeEnd - probeStart >= 2
                && neutralProbe * 2 >= probeEnd - probeStart;
        int lossProbe = countTrue(loss, probeStart, probeEnd);
        boolean startsWithLossTail = probeEnd - probeStart >= 2
                && lossProbe * 2 >= probeEnd - probeStart;
        // Loss previews can be both olive and nearly gray in the same rendered
        // segment. When both masks agree at the first probe, follow LOSS first;
        // otherwise the overlapping gray portion is mistaken for a very short
        // empty tail and truncates the physical track.
        if (startsWithLossTail) {
            int lossEnd = findObservedLossEnd(colors, loss, firstTail, height);
            boolean distinctLoss = lossEnd >= 0 && (!startsWithNeutralTail
                    || hasDistinctLossEvidence(loss, neutral, firstTail, lossEnd, height));
            if (distinctLoss) {
                int neutralStart = clamp(lossEnd + transition, 0, profileLength);
                int neutralEnd = clamp(neutralStart + probeWidth,
                        neutralStart, profileLength);
                boolean continuesAsNeutralTrack = neutralEnd - neutralStart >= 2
                        && countTrue(neutral, neutralStart, neutralEnd) * 2
                        >= neutralEnd - neutralStart;
                if (continuesAsNeutralTrack) {
                    Tail observedNeutralTail = findObservedNeutralTail(colors, neutral,
                            lossEnd, height, minimum, colorEnd);
                    if (observedNeutralTail != null && hasTrackBorderSupport(support,
                            preciseSupport, neutralStart, neutralEnd,
                            observedNeutralTail.end - observedNeutralTail.start, height)) {
                        int observedEnd = refineTrackEndByBorder(support, preciseSupport,
                                lossEnd, observedNeutralTail.end - minimum, height);
                        return new Tail(minimum + lossEnd, minimum + observedEnd, true);
                    }
                }
                int observedLossEnd = minimum + lossEnd;
                return new Tail(observedLossEnd, observedLossEnd, false);
            }
        }

        if (startsWithNeutralTail) {
            Tail observedNeutralTail = findObservedNeutralTail(colors, neutral,
                    firstTail, height, minimum, colorEnd);
            if (observedNeutralTail != null && hasTrackBorderSupport(support,
                    preciseSupport, probeStart, probeEnd,
                    observedNeutralTail.end - observedNeutralTail.start, height)) {
                int observedEnd = refineTrackEndByBorder(support, preciseSupport,
                        firstTail, observedNeutralTail.end - minimum, height);
                return new Tail(observedNeutralTail.start, minimum + observedEnd, true);
            }
        }

        // If the pixels after colorEnd are neither a bordered neutral track nor a
        // sustained loss segment, colorEnd itself is the last observed gauge edge.
        return null;
    }

    private static boolean hasDistinctLossEvidence(boolean[] loss, boolean[] neutral,
                                                   int start, int end, int height) {
        int minimum = Math.max(2, (int) Math.round(height * 0.25));
        int run = 0;
        for (int index = clamp(start, 0, loss.length);
             index < clamp(end, 0, loss.length); index++) {
            if (loss[index] && !neutral[index]) {
                if (++run >= minimum) return true;
            } else {
                run = 0;
            }
        }
        return false;
    }

    /** Refines a gray color run to the first directly observed end of its outline. */
    private static int refineTrackEndByBorder(double[] broad, double[] precise,
                                              int start, int end, int height) {
        start = clamp(start, 0, Math.min(broad.length, precise.length));
        end = clamp(end, start, Math.min(broad.length, precise.length));
        int window = Math.max(2, (int) Math.round(height * 0.15));
        // The color-defined end is already a visible observation. The outline may
        // only refine its local neighborhood; a distant contrast loss can be a
        // character or scenery crossing the translucent bar, not the track end.
        int first = Math.max(Math.max(window, start + window),
                end - Math.max(window, (int) Math.round(height * 0.75)));
        int last = Math.min(Math.min(broad.length, precise.length) - window,
                end + window);
        if (first > last) return end;

        double[] outline = new double[Math.min(broad.length, precise.length)];
        for (int index = 0; index < outline.length; index++) {
            outline[index] = Math.max(broad[index], precise[index]);
        }
        Edge best = new Edge(-1e9, end);
        int bestDistance = Integer.MAX_VALUE;
        for (int boundary = first; boundary <= last; boundary++) {
            double before = medianValue(outline, boundary - window, boundary);
            double after = medianValue(outline, boundary, boundary + window);
            double drop = before - after;
            if (before >= 12.0 && after <= Math.max(6.0, before * 0.45)
                    && drop >= 8.0 && (Math.abs(boundary - end) < bestDistance
                    || (Math.abs(boundary - end) == bestDistance && drop > best.score))) {
                best = new Edge(drop, boundary);
                bestDistance = Math.abs(boundary - end);
            }
        }
        return best.position;
    }

    private static boolean hasTrackBorderSupport(double[] broad, double[] precise,
                                                 int start, int end,
                                                 int observedTailLength, int height) {
        boolean preciseOutline = medianValue(precise, start, end) >= 15.0;
        boolean broadOutline = medianValue(broad, start, end) >= 15.0;
        // A long neutral body supplies repeated two-dimensional evidence even if
        // transparency over a nearly uniform background hides both outline rows.
        // A short patch next to the gauge must preserve the outline exactly,
        // otherwise it is the adjacent condition panel. All lengths are compared
        // with the observed component height, never with device pixels.
        boolean longObservedBody = observedTailLength >= height * 4.0;
        return preciseOutline || longObservedBody
                || (broadOutline && observedTailLength >= height * 1.5);
    }

    /** Finds the first directly visible transition out of a sustained loss segment. */
    private static int findObservedLossEnd(int[] colors, boolean[] loss,
                                           int firstTail, int height) {
        int voteWindow = Math.max(3, (int) Math.round(height * 0.22));
        int first = clamp(firstTail + voteWindow, voteWindow,
                loss.length - voteWindow);
        int approximate = -1;
        for (int boundary = first; boundary <= loss.length - voteWindow; boundary++) {
            int leftLoss = countTrue(loss, boundary - voteWindow, boundary);
            int rightLoss = countTrue(loss, boundary, boundary + voteWindow);
            if (leftLoss * 3 >= voteWindow * 2 && rightLoss * 3 <= voteWindow) {
                approximate = boundary;
                break;
            }
        }
        if (approximate < 0) return -1;

        int microWindow = Math.max(1, (int) Math.round(height * 0.06));
        int searchRadius = Math.max(microWindow, voteWindow);
        int searchStart = Math.max(microWindow, approximate - searchRadius);
        int searchEnd = Math.min(colors.length - microWindow,
                approximate + searchRadius);
        Edge best = new Edge(-1e9, approximate);
        for (int boundary = searchStart; boundary <= searchEnd; boundary++) {
            double distance = vectorDistance(
                    meanChannel(colors, boundary - microWindow, boundary, 16),
                    meanChannel(colors, boundary - microWindow, boundary, 8),
                    meanChannel(colors, boundary - microWindow, boundary, 0),
                    meanChannel(colors, boundary, boundary + microWindow, 16),
                    meanChannel(colors, boundary, boundary + microWindow, 8),
                    meanChannel(colors, boundary, boundary + microWindow, 0));
            if (distance > best.score) best = new Edge(distance, boundary);
        }
        return best.position;
    }

    private static Tail findObservedNeutralTail(int[] colors, boolean[] neutral,
                                                int firstTail, int height,
                                                int minimum, int colorEnd) {
        int maxGap = Math.max(1, (int) Math.round(height * 0.14));
        int minimumRun = Math.max(3, (int) Math.round(height * 0.45));
        int runStart = -1;
        int previous = -1;
        for (int index = firstTail; index <= neutral.length + maxGap; index++) {
            boolean matches = index < neutral.length && neutral[index];
            if (matches) {
                if (runStart < 0) runStart = index;
                previous = index;
            }
            if (runStart >= 0 && (!matches && index - previous > maxGap)) {
                int end = previous + 1;
                if (end - runStart >= minimumRun
                        && runStart - firstTail <= maxGap) {
                    end = refineNeutralEnd(colors, runStart, end,
                            minimumRun, height);
                    return new Tail(colorEnd, minimum + end, true);
                }
                runStart = -1;
                previous = -1;
            }
        }
        return null;
    }

    private static int countTrue(boolean[] values, int start, int end) {
        start = clamp(start, 0, values.length);
        end = clamp(end, start, values.length);
        int count = 0;
        for (int index = start; index < end; index++) if (values[index]) count++;
        return count;
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
        for (int index = start; index < end; index++) {
            sum += (colors[index] >>> shift) & 0xff;
        }
        return sum / Math.max(1, end - start);
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
        switch (kind) {
            case GAUGE:
                return isGaugeColor(color);
            case LOSS_PREVIEW:
                return isDimLossPreviewColor(color);
            case NEUTRAL:
            default:
                return isNeutralColor(color);
        }
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

    private static boolean isLossPreviewColor(int color) {
        return isLossPreviewColor(red(color), green(color), blue(color));
    }

    private static boolean isLossPreviewColor(double r, double g, double b) {
        double maximum = Math.max(r, Math.max(g, b));
        double minimum = Math.min(r, Math.min(g, b));
        double light = (r + g + b) / 3.0;
        // A loss preview is an olive, low-luminance segment. When current stamina
        // is 100 it reaches the physical right cap, so there is no neutral tail
        // after it. Treating only gray as track made the bright pre-loss segment
        // look like an independently full bar and erased the preview entirely.
        return light >= 48 && light <= 125
                && g >= 58 && g - b >= 8 && Math.abs(g - r) <= 32
                && maximum - minimum >= 17 && maximum - minimum <= 55;
    }

    private static boolean isDimLossPreviewColor(double r, double g, double b) {
        double maximum = Math.max(r, Math.max(g, b));
        double minimum = Math.min(r, Math.min(g, b));
        double light = (r + g + b) / 3.0;
        // On dark training backgrounds the translucent loss preview can be much
        // dimmer and less olive than it is over a bright scene. This broader mask
        // is only used to seed a loss candidate and classify the track head; it is
        // deliberately not allowed to extend the physical right edge of a track.
        boolean darkBlueShift = light <= 85 && g - b >= -10;
        return light >= 38 && light <= 125
                && g >= 45 && g - r >= 3 && (g - b >= 0 || darkBlueShift)
                && Math.abs(g - r) <= 40
                && maximum - minimum >= 10 && maximum - minimum <= 55;
    }

    private static boolean isDimLossPreviewColor(int color) {
        return isDimLossPreviewColor(red(color), green(color), blue(color));
    }

    private static int median(int[] values) {
        int[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static int percentile(int[] values, double fraction) {
        int[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        int index = clamp((int) Math.round((copy.length - 1) * fraction), 0, copy.length - 1);
        return copy[index];
    }

    /** Returns the outer observed edge after discarding at most one isolated row. */
    private static int supportedMinimum(int[] values) {
        int[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        return copy[Math.min(1, copy.length - 1)];
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
                                         int trackWidth, int trackHeight, int reliableLeft,
                                         int knownColorEnd, int knownLossStart,
                                         boolean hasNeutralTail,
                                         MaskKind candidateKind) {
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
                int color = source.get(Math.max(left + offset, reliableLeft), y);
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

        int headWindow = Math.max(4, (int) Math.round(trackWidth * 0.025));
        double headR = mean(r, 0, headWindow);
        double headG = mean(g, 0, headWindow);
        double headB = mean(b, 0, headWindow);
        double headLight = (headR + headG + headB) / 3.0;
        double headSaturation = Math.max(headR, Math.max(headG, headB))
                - Math.min(headR, Math.min(headG, headB));
        boolean startsWithGainPreview = headLight >= 125 && headR >= 90
                && headG - headB >= 20 && headSaturation >= 35;
        boolean startsWithLossPreview = isDimLossPreviewColor(headR, headG, headB);
        if (candidateKind == MaskKind.LOSS_PREVIEW && !startsWithLossPreview
                && knownLossStart < 0) return null;

        int initial = Math.max(4, (int) Math.round(trackWidth * 0.08));
        double initialSaturation = mean(saturation, 0, initial);
        double initialGreen = meanDifference(g, b, 0, initial);
        if (knownLossStart < 0 && initialSaturation < 14 && initialGreen < 7) {
            return new Result(0, 0, Direction.NONE,
                    new Anchor(left / (float) screenWidth, centerY / (float) screenWidth), 0.68f);
        }

        int window = Math.max(3, (int) Math.round(trackWidth * 0.025));
        int gap = Math.max(1, (int) Math.round(trackWidth * 0.006));
        int observedProfileStart = clamp(reliableLeft - left, 0, trackWidth);
        int minBoundary = Math.max(
                Math.max(window + gap + 1, (int) Math.round(trackWidth * 0.03)),
                observedProfileStart + window + gap);
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
        int finalBoundary = hasNeutralTail
                ? clamp(knownColorEnd, 0, trackWidth) : trackWidth;

        if (knownLossStart >= 0 && knownColorEnd > knownLossStart) {
            direction = Direction.LOSS;
            internal = clamp(knownLossStart, 0, trackWidth);
            finalBoundary = clamp(knownColorEnd, internal, trackWidth);
            // The loss mask proves that this track contains a preview, but a
            // translucent gradient may make only the darker latter part satisfy
            // that absolute mask. Prefer the stronger observed bright-to-dim
            // discontinuity when it occurs earlier in the same colored span.
            if (internal > 0 && loss.score >= 24 && loss.position > 0
                    && loss.position < finalBoundary) {
                internal = loss.position;
            }
        }

        // At either endpoint a preview can be the first and only colored segment:
        // recovery from zero has no normal fill before it, and consumption to zero
        // has no normal fill after it. Those states have no internal normal/preview
        // edge, so classify the short head of the measured track by its absolute
        // preview color and use the preview-to-neutral edge as the other boundary.
        if (direction == Direction.NONE && (startsWithGainPreview || startsWithLossPreview)) {
            direction = startsWithGainPreview ? Direction.GAIN : Direction.LOSS;
            internal = 0;
            int minimumPreview = startsWithGainPreview ? gainPreviewMin : lossPreviewMin;
            Edge neutral = hasNeutralTail
                    ? findNeutralBoundary(r, g, b, luminance, saturation,
                    minimumPreview, trackWidth, window, gap)
                    : new Edge(-1e9, -1);
            finalBoundary = neutral.score >= 8 ? neutral.position : trackWidth;
        }

        // A +3 preview can be only four or five source pixels wide. Sampling
        // after the classifier gap skipped the entire bright segment on low
        // resolution captures, so inspect immediately to the right of the edge.
        int gainZoneStart = Math.min(trackWidth, gain.position);
        int gainZoneEnd = Math.min(trackWidth,
                gainZoneStart + Math.max(gainPreviewMin, window));
        double gainZoneLight = mean(luminance, gainZoneStart, gainZoneEnd);
        double gainZoneSaturation = mean(saturation, gainZoneStart, gainZoneEnd);
        if (direction == Direction.NONE && gain.score >= 24
                && gainZoneLight >= 115 && gainZoneSaturation >= 35
                && trackWidth - gain.position >= gainPreviewMin) {
            direction = Direction.GAIN;
            internal = gain.position;
            Edge tail = new Edge(-1e9, -1);
            if (hasNeutralTail) {
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
            double rightR = mean(r, zoneStart, zoneEnd);
            double rightG = mean(g, zoneStart, zoneEnd);
            double rightB = mean(b, zoneStart, zoneEnd);
            Edge neutral = hasNeutralTail
                    ? findNeutralBoundary(r, g, b, luminance, saturation,
                    boundary + lossPreviewMin, trackWidth, window, gap)
                    : new Edge(-1e9, -1);
            if (rightSat >= 17 && rightGreen >= 8
                    && isDimLossPreviewColor(rightR, rightG, rightB)) {
                direction = Direction.LOSS;
                internal = boundary;
                finalBoundary = neutral.score >= 8 ? neutral.position : trackWidth;
            } else {
                finalBoundary = boundary;
            }
        }

        int classifiedInternal = internal;
        int classifiedFinal = finalBoundary;

        // The wide windows above are deliberately robust enough to classify a
        // preview in gradients and bloom.  They also move a short edge several
        // pixels outwards.  Once its kind is known, relocate each edge with a
        // small local comparison; this preserves the robust classification but
        // measures the actual filled length.
        if (direction == Direction.GAIN) {
            if (internal > 0) {
                internal = refineBoundary(r, g, b, luminance, saturation, internal,
                        trackWidth, BoundaryKind.GAIN_START);
            }
            if (finalBoundary < trackWidth) {
                finalBoundary = refineBoundary(r, g, b, luminance, saturation, finalBoundary,
                        trackWidth, BoundaryKind.GAIN_END);
            }
        } else if (direction == Direction.LOSS) {
            // The wide windows above classify a dim translucent preview reliably,
            // but place its first edge several pixels inside the normal fill. Once
            // LOSS is established, sharpen that edge with a local discontinuity;
            // restricting the search around the classified edge keeps the normal
            // in-game gradient from becoming a second candidate.
            if (internal > 0) {
                internal = refineBoundary(r, g, b, luminance, saturation, internal,
                        trackWidth, BoundaryKind.LOSS_START);
            }
            if (finalBoundary < trackWidth) {
                finalBoundary = refineBoundary(r, g, b, luminance, saturation, finalBoundary,
                        trackWidth, BoundaryKind.LOSS_END);
            }
        } else if (finalBoundary < trackWidth) {
            finalBoundary = refineBoundary(r, g, b, luminance, saturation, finalBoundary,
                    trackWidth, BoundaryKind.NORMAL_END);
        }

        // Refinement is allowed to sharpen an observed edge, but never to invert
        // the two already classified physical boundaries. During a one-frame
        // animation blend the two local extrema can cross; retain the ordered
        // coarse observations for that frame instead of reporting an impossible
        // loss that increases stamina (or a gain that decreases it).
        if ((direction == Direction.LOSS && internal > finalBoundary)
                || (direction == Direction.GAIN && internal > finalBoundary)) {
            internal = classifiedInternal;
            finalBoundary = classifiedFinal;
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

    private static Edge findNeutralBoundary(double[] r, double[] g, double[] b,
                                            double[] luminance, double[] saturation,
                                            int firstPoint, int trackWidth,
                                            int window, int gap) {
        Edge neutral = new Edge(-1e9, -1);
        int first = Math.max(firstPoint, window + gap);
        for (int point = first; point <= trackWidth - window - gap; point++) {
            int leftStart = point - gap - window;
            int leftEnd = point - gap;
            int rightStart = point + gap;
            int rightEnd = point + gap + window;
            double rightLight = mean(luminance, rightStart, rightEnd);
            double rightSat = mean(saturation, rightStart, rightEnd);
            // Pixels beyond the observed track can belong to dark scenery. Such
            // an edge is not a preview-to-neutral boundary inside the track.
            if (rightLight < 58 || rightLight > 135 || rightSat > 25
                    || minimum(luminance, rightStart, rightEnd) < 50) {
                continue;
            }
            double deltaSat = mean(saturation, leftStart, leftEnd) - rightSat;
            double distance = vectorDistance(
                    mean(r, leftStart, leftEnd), mean(g, leftStart, leftEnd),
                    mean(b, leftStart, leftEnd), mean(r, rightStart, rightEnd),
                    mean(g, rightStart, rightEnd), mean(b, rightStart, rightEnd));
            double score = deltaSat + 0.20 * distance;
            if (score > neutral.score) neutral = new Edge(score, point);
        }
        return neutral;
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
                case LOSS_START:
                    score = -deltaLight - 0.25 * deltaSat + 0.10 * distance;
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
        return (int) Math.round(clamp(boundary, 0, trackWidth) * 100.0 / trackWidth);
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

    private static double medianValue(double[] values, int start, int end) {
        start = clamp(start, 0, values.length);
        end = clamp(end, start, values.length);
        if (end <= start) return 0.0;
        double[] copy = Arrays.copyOfRange(values, start, end);
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static double minimum(double[] values, int start, int end) {
        start = clamp(start, 0, values.length);
        end = clamp(end, start, values.length);
        if (end <= start) return 0.0;
        double result = Double.POSITIVE_INFINITY;
        for (int index = start; index < end; index++) result = Math.min(result, values[index]);
        return result;
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
        // The translucent HUD inherits a slight green cast from bright scenery.
        // Real fill colors remain strongly chromatic even in compressed captures.
        boolean normalFill = g >= 65 && g - r >= 7 && g - b >= -22
                && maximum - minimum >= 35;
        // A recovery preview is lime at its first edge but fades through yellow
        // as it approaches the full cap. Requiring green to stay above red cut
        // that pale-yellow tail off and made the shortened run look like 100%,
        // inflating the measured current value. Keep the yellow family narrow:
        // green may only trail red slightly and must remain well above blue.
        boolean gainPreview = r >= 90 && g >= 145 && g - r >= -8
                && g - b >= 35 && maximum - minimum >= 35;
        return normalFill || gainPreview;
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
    private static double colorVectorDistance(int first, int second) {
        return vectorDistance(red(first), green(first), blue(first),
                red(second), green(second), blue(second));
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
        final boolean hasNeutralTail;
        final int observedLeft;
        final MaskKind kind;
        final int lossStart;

        Candidate(double score, int left, int colorEnd, int right,
                  int top, int bottom, boolean empty, boolean hasNeutralTail,
                  int observedLeft, MaskKind kind, int lossStart) {
            this.score = score;
            this.left = left;
            this.colorEnd = colorEnd;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
            this.empty = empty;
            this.hasNeutralTail = hasNeutralTail;
            this.observedLeft = observedLeft;
            this.kind = kind;
            this.lossStart = lossStart;
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
        final int observedTop;
        final int observedBottom;

        StableBand(int top, int bottom, int[] starts, int[] ends,
                   int observedTop, int observedBottom) {
            this.top = top;
            this.bottom = bottom;
            this.starts = starts;
            this.ends = ends;
            this.observedTop = observedTop;
            this.observedBottom = observedBottom;
        }
    }

    private static final class Tail {
        final int start;
        final int end;
        final boolean hasNeutral;
        Tail(int start, int end, boolean hasNeutral) {
            this.start = start;
            this.end = end;
            this.hasNeutral = hasNeutral;
        }
    }

    private static final class Edge {
        final double score;
        final int position;
        Edge(double score, int position) { this.score = score; this.position = position; }
    }

    private enum MaskKind { GAUGE, LOSS_PREVIEW, NEUTRAL }
    private enum BoundaryKind { GAIN_START, GAIN_END, LOSS_START, LOSS_END, NORMAL_END }
}
