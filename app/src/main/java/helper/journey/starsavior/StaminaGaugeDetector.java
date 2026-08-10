package helper.journey.starsavior;

import java.util.Arrays;

/**
 * Finds the journey stamina gauge without fixed device-pixel coordinates.
 *
 * <p>The game scales this HUD from the screen width, including on tall foldable
 * screens. Callers copy only {@link #scanRegion(int, int)} into an int array;
 * every subsequent lookup is an ordinary array access rather than a costly
 * Bitmap.getPixel call.</p>
 */
final class StaminaGaugeDetector {
    private static final double REFERENCE_WIDTH = 3120.0;
    private static final double TRACK_WIDTH_AT_REFERENCE = 428.0;
    private static final double TRACK_HEIGHT_AT_REFERENCE = 33.0;
    private static final double TRACK_CENTER_Y_AT_REFERENCE = 100.0;

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
        int left = clamp((int) Math.floor(screenWidth * 0.16), 0, screenWidth - 1);
        int right = clamp((int) Math.ceil(screenWidth * 0.62), left + 1, screenWidth);
        int top = clamp((int) Math.floor(screenWidth * 0.010), 0, screenHeight - 1);
        int bottom = clamp((int) Math.ceil(screenWidth * 0.056), top + 1, screenHeight);
        return new Region(left, top, right - left, bottom - top);
    }

    static Result detect(int screenWidth, int screenHeight, Region region, int[] pixels,
                         Anchor previousAnchor) {
        if (screenWidth < 320 || screenHeight < 200 || region == null || pixels == null
                || region.width <= 0 || region.height <= 0
                || pixels.length < region.width * region.height) return null;

        PixelSource source = new PixelSource(region, pixels);
        double scale = screenWidth / REFERENCE_WIDTH;
        int trackWidth = Math.max(72, (int) Math.round(TRACK_WIDTH_AT_REFERENCE * scale));
        int trackHeight = Math.max(8, (int) Math.round(TRACK_HEIGHT_AT_REFERENCE * scale));
        int expectedY = (int) Math.round(TRACK_CENTER_Y_AT_REFERENCE * scale);
        int searchY0 = Math.max(region.top + trackHeight / 2 + 1, expectedY - trackHeight);
        int searchY1 = Math.min(region.top + region.height - trackHeight / 2 - 2,
                expectedY + trackHeight);
        int searchX0 = Math.max(region.left, (int) Math.round(screenWidth * 0.18));
        int searchX1 = Math.min(region.left + region.width - 1,
                (int) Math.round(screenWidth * 0.56));
        int xCenter = previousAnchor == null
                ? (int) Math.round(screenWidth * 0.375)
                : (int) Math.round(previousAnchor.leftRatio * screenWidth);

        Candidate best = findColoredCandidate(source, screenWidth, scale, trackWidth,
                trackHeight, expectedY, searchX0, searchX1, searchY0, searchY1, xCenter);
        boolean usedColoredCandidate = best != null;
        if (best == null) {
            best = findStructuralCandidate(source, screenWidth, scale, trackWidth, trackHeight,
                    expectedY, xCenter, searchX0, searchX1);
        }
        if (best == null) return null;

        Result primary = analyzeCandidate(source, screenWidth, best, scale, trackWidth,
                trackHeight, searchX0, searchX1, searchY0, searchY1);
        if (primary == null) return null;

        // When an ordinary partially-filled gauge is resampled by the display
        // compositor, the sandwich icon or another green HUD detail can beat
        // the real bar by a small amount.  That false profile has no convincing
        // fill boundary and therefore looks like a low-confidence 0/100 value.
        // Re-check such an extreme with the track's dark border structure.
        if ((primary.current == 0 || primary.current == 100)
                && primary.direction == Direction.NONE && primary.confidence < 0.80f
                && usedColoredCandidate
                && best.runEnd - best.runStart < Math.round(trackWidth * 0.80f)) {
            Candidate structural = findStructuralCandidate(source, screenWidth, scale,
                    trackWidth, trackHeight, expectedY, xCenter, searchX0, searchX1);
            if (structural != null) {
                Result alternative = analyzeCandidate(source, screenWidth, structural, scale,
                        trackWidth, trackHeight, searchX0, searchX1, searchY0, searchY1);
                if (alternative != null && alternative.confidence > primary.confidence + 0.02f) {
                    return alternative;
                }
            }
        }
        return primary;
    }

    private static Result analyzeCandidate(PixelSource source, int screenWidth, Candidate best,
                                           double scale, int trackWidth, int trackHeight,
                                           int searchX0, int searchX1, int searchY0, int searchY1) {

        // The captured app can be letterboxed or shifted by a display cutout.
        // The search already found the vertical center of the colored/structural
        // track, so forcing it back to the reference row reads background pixels
        // on those devices and turns a partial gauge into 0 or 100.
        int centerY = clamp(best.centerY, searchY0, searchY1);
        int left = refineTrackLeft(source, best.runStart, centerY, scale, trackHeight,
                searchX0, searchX1);
        if (!source.contains(left, centerY) || !source.contains(left + trackWidth - 1, centerY)) {
            return null;
        }
        return analyzeProfile(source, screenWidth, left, centerY, trackWidth, trackHeight);
    }

    private static Candidate findColoredCandidate(PixelSource source, int screenWidth, double scale,
                                                     int trackWidth, int trackHeight, int expectedY,
                                                     int searchX0, int searchX1, int searchY0,
                                                     int searchY1, int expectedX) {
        Candidate best = null;
        int yStep = Math.max(1, (int) Math.round(2 * scale));
        int rowOffset = Math.max(1, (int) Math.round(5 * scale));
        int maxGap = Math.max(1, (int) Math.round(3 * scale));
        int minRun = Math.max(5, (int) Math.round(screenWidth * 0.008));

        for (int y = searchY0; y <= searchY1; y += yStep) {
            int runStart = -1;
            int lastColored = -1;
            for (int x = searchX0; x <= searchX1; x++) {
                int votes = 0;
                if (isGaugeColor(source.get(x, y - rowOffset))) votes++;
                if (isGaugeColor(source.get(x, y))) votes++;
                if (isGaugeColor(source.get(x, y + rowOffset))) votes++;
                boolean colored = votes >= 2;
                if (colored) {
                    if (runStart < 0) runStart = x;
                    lastColored = x;
                }
                boolean close = runStart >= 0 && (!colored && x - lastColored > maxGap);
                if (close || (x == searchX1 && runStart >= 0)) {
                    int end = lastColored + 1;
                    int length = end - runStart;
                    if (length >= minRun && length <= Math.round(trackWidth * 1.15)) {
                        int middle = (runStart + end) / 2;
                        VerticalRun vertical = verticalRun(source, middle, y, searchY0, searchY1,
                                Math.max(1, (int) Math.round(scale)));
                        double structure = structuralScore(source, runStart, vertical.center,
                                trackWidth, trackHeight);
                        double score = Math.min(length, trackWidth)
                                // Green scenery and character effects can form a longer run than
                                // the actual fill.  The stamina track itself has stable dark top
                                // and bottom borders across HUD layouts, so structural evidence
                                // must outweigh raw run length.
                                + structure * 2.0
                                // A remembered position is only a tie-breaker.
                                // HUD placement changes between devices and
                                // captured-content layouts, while the run length
                                // and height are direct evidence from this frame.
                                - Math.min(Math.abs(runStart - expectedX) * 4.0 / trackWidth, 4.0)
                                - Math.min(Math.abs(vertical.center - expectedY) * 4.0
                                        / trackHeight, 4.0)
                                - Math.abs(vertical.length - trackHeight) * 0.70;
                        if (best == null || score > best.score) {
                            best = new Candidate(score, runStart, end, vertical.center);
                        }
                    }
                    runStart = -1;
                    lastColored = -1;
                }
            }
        }
        return best;
    }

    private static VerticalRun verticalRun(PixelSource source, int x, int targetY,
                                           int top, int bottom, int maxGap) {
        int bestTop = targetY;
        int bestBottom = targetY + 1;
        int runTop = -1;
        int lastColored = -1;
        for (int y = top; y <= bottom; y++) {
            boolean colored = isGaugeColor(source.get(x, y));
            if (colored) {
                if (runTop < 0) runTop = y;
                lastColored = y;
            }
            boolean close = runTop >= 0 && (!colored && y - lastColored > maxGap);
            if (close || (y == bottom && runTop >= 0)) {
                int runBottom = lastColored + 1;
                if (runTop <= targetY && targetY < runBottom) {
                    bestTop = runTop;
                    bestBottom = runBottom;
                    break;
                }
                runTop = -1;
                lastColored = -1;
            }
        }
        return new VerticalRun((bestTop + bestBottom - 1) / 2, bestBottom - bestTop);
    }

    private static Candidate findStructuralCandidate(PixelSource source, int screenWidth,
                                                       double scale, int trackWidth, int trackHeight,
                                                       int expectedY, int expectedX,
                                                       int searchX0, int searchX1) {
        Candidate best = null;
        int step = Math.max(1, (int) Math.round(2 * scale));
        int yRange = Math.max(trackHeight, (int) Math.round(screenWidth * 0.008));
        int xMin = Math.max(searchX0, (int) Math.round(screenWidth * 0.30));
        int xMax = Math.min(searchX1 - trackWidth, (int) Math.round(screenWidth * 0.44));
        for (int y = expectedY - yRange; y <= expectedY + yRange; y += step) {
            for (int x = xMin; x <= xMax; x += step) {
                double score = structuralScore(source, x, y, trackWidth, trackHeight)
                        - Math.min(Math.abs(x - expectedX) * 6.0 / trackWidth, 6.0)
                        - Math.min(Math.abs(y - expectedY) * 6.0 / trackHeight, 6.0);
                if (best == null || score > best.score) {
                    best = new Candidate(score, x, x, y);
                }
            }
        }
        return best != null && best.score >= 24.0 ? best : null;
    }

    private static double structuralScore(PixelSource source, int left, int centerY,
                                          int trackWidth, int trackHeight) {
        int half = trackHeight / 2;
        int innerTop = centerY - half + 2;
        int innerBottom = centerY + half - 2;
        int outerTop = centerY - half - 3;
        int outerBottom = centerY + half + 3;
        if (!source.contains(left, outerTop)
                || !source.contains(left + trackWidth - 1, outerBottom)) return -1e9;
        double edge = 0.0;
        double rough = 0.0;
        double light = 0.0;
        int samples = 12;
        for (int index = 0; index < samples; index++) {
            int x = left + (int) Math.round((index + 0.5) * trackWidth / samples);
            int center = source.get(x, centerY);
            int top = source.get(x, innerTop);
            int bottom = source.get(x, innerBottom);
            edge += colorDistance(top, source.get(x, outerTop));
            edge += colorDistance(bottom, source.get(x, outerBottom));
            rough += colorDistance(top, center) + colorDistance(bottom, center);
            light += brightness(center);
        }
        return edge / samples - rough / samples * 0.45 + light / samples * 0.10;
    }

    private static int refineTrackLeft(PixelSource source, int runStart, int centerY,
                                       double scale, int trackHeight, int searchX0, int searchX1) {
        int verticalHalf = Math.max(3, (int) Math.round(trackHeight * 0.45));
        int required = Math.max(3, (int) Math.round(8 * scale));
        int start = Math.max(searchX0, runStart - (int) Math.round(10 * scale));
        int end = Math.min(searchX1 - required, runStart + Math.max(
                (int) Math.round(30 * scale), (int) Math.round(trackHeight * 2.4)));
        int rows = verticalHalf * 2 + 1;
        for (int candidate = start; candidate <= end; candidate++) {
            boolean continuous = true;
            for (int x = candidate; x < candidate + required; x++) {
                int colored = 0;
                for (int y = centerY - verticalHalf; y <= centerY + verticalHalf; y++) {
                    if (isGaugeColor(source.get(x, y))) colored++;
                }
                if (colored < Math.ceil(rows * 0.78)) {
                    continuous = false;
                    break;
                }
            }
            if (continuous) return candidate;
        }
        return runStart;
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

        int gainZoneStart = Math.min(trackWidth, gain.position + gap + 2);
        int gainZoneEnd = Math.min(trackWidth,
                gainZoneStart + Math.max(gainPreviewMin, window * 2));
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
        final int runStart;
        final int runEnd;
        final int centerY;
        Candidate(double score, int runStart, int runEnd, int centerY) {
            this.score = score; this.runStart = runStart; this.runEnd = runEnd; this.centerY = centerY;
        }
    }

    private static final class VerticalRun {
        final int center;
        final int length;
        VerticalRun(int center, int length) { this.center = center; this.length = length; }
    }

    private static final class Edge {
        final double score;
        final int position;
        Edge(double score, int position) { this.score = score; this.position = position; }
    }

    private enum BoundaryKind { GAIN_START, GAIN_END, LOSS_END, NORMAL_END }
}
