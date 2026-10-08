package helper.journey.starsavior;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Reads the current frame's exposed gauge edges and material transitions.
 * Search windows and candidate priors never supply a missing endpoint length.
 */
final class StaminaGaugeDetector {
    private static final double SEARCH_LEFT = .14;
    private static final double SEARCH_RIGHT = .62;
    private static final double SEARCH_BOTTOM = .064;

    enum Direction { NONE, GAIN, LOSS }

    static final class Region {
        final int left, top, width, height;
        Region(int left, int top, int width, int height) {
            this.left = left; this.top = top; this.width = width; this.height = height;
        }
    }

    static final class Anchor {
        final float leftRatio, centerYRatio;
        Anchor(float leftRatio, float centerYRatio) {
            this.leftRatio = leftRatio; this.centerYRatio = centerYRatio;
        }
    }

    static final class Result {
        final int current, after;
        final Direction direction;
        final Anchor anchor;
        final float confidence;
        Result(int current, int after, Direction direction, Anchor anchor, float confidence) {
            this.current = clampValue(current); this.after = clampValue(after);
            this.direction = direction; this.anchor = anchor; this.confidence = confidence;
        }
        boolean hasPreview() { return direction != Direction.NONE && current != after; }
        Result stabilize(Result previous) {
            if (previous == null || previous.direction != direction) return this;
            if (Math.abs(previous.current - current) > 2) return this;
            if (previous.after - previous.current != after - current) return this;
            if (previous.current == current) return this;
            // Move the observed pair together so stabilization cannot change an
            // action's delta. Exact endpoints belong to the current observation;
            // retaining an old 0/100 or hiding a new one would be endpoint snapping.
            if (current == 0 || current == 100 || after == 0 || after == 100
                    || previous.current == 0 || previous.current == 100
                    || previous.after == 0 || previous.after == 100) return this;
            return new Result(previous.current, previous.after, direction, anchor, confidence);
        }
        private static int clampValue(int value) { return Math.max(0, Math.min(100, value)); }
    }

    private StaminaGaugeDetector() {}

    static Region scanRegion(int width, int height) {
        int left = (int) (width * SEARCH_LEFT), right = (int) Math.ceil(width * SEARCH_RIGHT);
        return new Region(left, 0, Math.max(0, right - left),
                Math.max(0, Math.min(height, Math.max(64, (int) Math.ceil(width * SEARCH_BOTTOM)))));
    }

    static Result detect(int screenWidth, int screenHeight, Region region, int[] pixels,
                         Anchor previousAnchor) {
        if (screenWidth <= 0 || screenHeight <= 0 || region == null || pixels == null
                || region.width <= 0 || region.height <= 0 || region.left < 0 || region.top != 0
                || region.width * (long) region.height > pixels.length
                || region.left + (long) region.width > screenWidth || region.height > screenHeight) return null;
        Crop crop = new Crop(screenWidth, region, pixels);
        Geometry best = null;
        double bestPriority = Double.NEGATIVE_INFINITY;
        List<Proposal> measuredBands = new ArrayList<>();
        for (Proposal proposal : proposals(crop)) {
            // Proposals are ordered by paired-outline evidence. Keep nearby
            // alternatives until a band is successfully measured, then avoid
            // replacing its outer borders with a stronger-colored inner stripe.
            boolean alreadyMeasured = false;
            for (Proposal measured : measuredBands) {
                double tolerance = Math.max(1, .18 * Math.min(
                        measured.bottom - measured.top + 1, proposal.bottom - proposal.top + 1));
                if (Math.abs(proposal.top - measured.top) <= tolerance
                        && Math.abs(proposal.bottom - measured.bottom) <= tolerance) {
                    alreadyMeasured = true;
                    break;
                }
            }
            if (alreadyMeasured) continue;
            Geometry geometry = measure(crop, proposal);
            if (geometry == null) continue;
            measuredBands.add(proposal);
            double priority = candidatePriority(geometry, screenWidth, previousAnchor);
            if (best == null || priority > bestPriority) {
                best = geometry;
                bestPriority = priority;
            }
        }
        // Do not turn an ambiguous HUD into a number by choosing a weaker candidate.
        if (best == null || hasAmbiguousEndpoint(crop, best)) return null;
        return interpret(best, screenWidth);
    }

    private static double candidatePriority(Geometry geometry, int width, Anchor previous) {
        double center = (geometry.top + geometry.bottom) * .5 / width;
        // The proposal stage already favors the top HUD. Preserve that broad
        // positional evidence after endpoint measurement, so a saturated lower
        // decoration cannot win solely because an empty HUD has no green fill.
        double height = (geometry.bottom - geometry.top + 1.0) / width;
        double outside = Math.max(0, Math.max(.022 - center, center - .050));
        double priority = geometry.score / (1 + Math.pow(outside / (height * .18), 2));
        if (previous != null) {
            double dx = (geometry.left + geometry.x0) / width - previous.leftRatio;
            double dy = center - previous.centerYRatio;
            // History is a bounded tie preference between already measured
            // candidates. It supplies neither endpoints nor a stamina value.
            priority *= 1 + .05 / (1 + (dx * dx + dy * dy) / (height * height));
        }
        return priority;
    }

    private static List<Proposal> proposals(Crop a) {
        int width = a.width, height = a.height;
        List<Proposal> candidates = new ArrayList<>();
        if (height < 2 || width < 2) return candidates;
        float[][] dy = new float[height - 1][width];
        float[] pos = new float[height - 1], neg = new float[height - 1];
        for (int y = 0; y < height - 1; y++) {
            for (int x = 0; x < width; x++) {
                float delta = luma(a.color(y + 1, x)) - luma(a.color(y, x));
                dy[y][x] = delta;
                pos[y] += Math.min(Math.max(delta, 0), 45);
                neg[y] += Math.min(Math.max(-delta, 0), 45);
            }
            pos[y] /= width; neg[y] /= width;
        }
        int[] tops = peaks(pos, 16, 2), bottoms = peaks(neg, 16, 2);
        float[][] sum = new float[height + 1][width * 3];
        float[][] square = new float[height + 1][width * 3];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) for (int c = 0; c < 3; c++) {
                int i = x * 3 + c;
                float v = a.get(y, x, c);
                sum[y + 1][i] = sum[y][i] + v;
                square[y + 1][i] = square[y][i] + v * v;
            }
        }
        for (int top : tops) for (int bot : bottoms) {
            int t = top + 1, b = bot, h = b - t + 1;
            if (h < 4 || h > a.screenWidth * .026) continue;
            float[] contrast = new float[width], valid = new float[width];
            for (int x = 0; x < width; x++) {
                float variance = 0, topDistance = 0, bottomDistance = 0;
                for (int c = 0; c < 3; c++) {
                    int i = x * 3 + c;
                    float mean = (sum[b + 1][i] - sum[t][i]) / h;
                    float var = (square[b + 1][i] - square[t][i]) / h - mean * mean;
                    variance += Math.max(0, var);
                    float td = mean - a.get(top, x, c), bd = mean - a.get(bot + 1, x, c);
                    topDistance += td * td; bottomDistance += bd * bd;
                }
                contrast[x] = (float) Math.min(Math.sqrt(topDistance), Math.sqrt(bottomDistance));
                double flat = Math.sqrt(variance / 3f);
                valid[x] = dy[top][x] > 3 && dy[bot][x] < -3
                        && flat < Math.max(9, contrast[x] * .3f) ? 1 : 0;
            }
            float[] smooth = blur(valid, Math.max(1, (int) (h * .3)));
            boolean[] support = new boolean[width];
            for (int x = 0; x < width; x++) support[x] = smooth[x] > .55;
            for (int[] run : runs(support)) {
                int l = run[0], r = run[1];
                if (r - l < 3 * h || r - l > 19 * h) continue;
                float total = 0;
                for (int x = l; x < r; x++) total += Math.min(contrast[x], 50);
                double value = (r - l) * (double) (total / (r - l))
                        * (1 - Math.min(Math.abs((t + b) * .5 / a.screenWidth - .034), .04) * 5);
                candidates.add(new Proposal(value, t, b, l, r));
            }
        }
        candidates.sort((a1, b1) -> {
            int cmp = Double.compare(b1.score, a1.score);
            if (cmp == 0) cmp = Integer.compare(b1.top, a1.top);
            if (cmp == 0) cmp = Integer.compare(b1.bottom, a1.bottom);
            if (cmp == 0) cmp = Integer.compare(b1.left, a1.left);
            if (cmp == 0) cmp = Integer.compare(b1.right, a1.right);
            return cmp;
        });
        List<Proposal> selected = new ArrayList<>();
        for (Proposal candidate : candidates) {
            boolean duplicate = false;
            for (Proposal prior : selected) {
                if (candidate.top == prior.top && candidate.bottom == prior.bottom) { duplicate = true; break; }
            }
            if (!duplicate) selected.add(candidate);
            if (selected.size() >= 24) break;
        }
        return selected;
    }

    private static List<Double> steps(float[][] profile, double height, double threshold) {
        int k = Math.max(1, (int) Math.rint(height * .12)), length = profile.length;
        List<Double> boundaries = new ArrayList<>();
        if (length <= 2 * k) return boundaries;
        float[][] sums = new float[length + 1][3];
        for (int i = 0; i < length; i++) for (int c = 0; c < 3; c++) sums[i + 1][c] = sums[i][c] + profile[i][c];
        int count = length - 2 * k;
        float[][] left = new float[count][3], delta = new float[count][3];
        float[] sizes = new float[count];
        for (int i = 0; i < count; i++) {
            int x = k + i;
            float norm = 0;
            for (int c = 0; c < 3; c++) {
                left[i][c] = (sums[x][c] - sums[x - k][c]) / k;
                float right = (sums[x + k][c] - sums[x][c]) / k;
                delta[i][c] = right - left[i][c];
                norm += delta[i][c] * delta[i][c];
            }
            sizes[i] = (float) Math.sqrt(norm);
        }
        int[] order = descending(sizes);
        List<Integer> accepted = new ArrayList<>();
        for (int i : order) {
            int x = i + k;
            if (sizes[i] < threshold) break;
            boolean nearby = false;
            for (int previous : accepted) if (Math.abs(x - previous) < k * 2) { nearby = true; break; }
            if (nearby) continue;
            float norm = dot(delta[i], delta[i]);
            float[] values = new float[k * 2];
            for (int j = 0; j < values.length; j++) values[j] = projection(profile[x - k + j], left[i], delta[i], norm);
            double best = x - .5, distance = Double.POSITIVE_INFINITY;
            for (int j = 0; j < values.length - 1; j++) {
                if (values[j] <= .5 && .5 <= values[j + 1] && values[j] != values[j + 1]) {
                    double crossing = x - k + j + (.5 - values[j]) / (values[j + 1] - values[j]);
                    double d = Math.abs(crossing - (x - .5));
                    if (d < distance) { best = crossing; distance = d; }
                }
            }
            accepted.add(x); boundaries.add(best);
        }
        boundaries.sort(Comparator.naturalOrder());
        return boundaries;
    }

    private static Geometry measure(Crop a, Proposal p) {
        int t = p.top, b = p.bottom, h = b - t + 1, width = a.width;
        int q = Math.max(1, (int) Math.rint(h * .16)), outer = Math.max(1, (int) Math.rint(h * .12));
        if (t < 2 * outer || b + 2 * outer >= a.height) return null;
        int middle = (t + b) / 2 + 1;
        if (middle - (t + q) < 2) return null;
        float[][] profile = medianRows(a, t + q, middle);
        float[][] bgTop = medianRows(a, t - 2 * outer, t - outer + 1);
        float[][] bgBottom = medianRows(a, b + outer, b + 2 * outer + 1);
        float[][] nearTopInner = medianRows(a, t + q, t + 2 * q + 1);
        float[][] nearTopOuter = medianRows(a, t - outer, t);
        float[][] nearBottomInner = medianRows(a, b - 2 * q, b - q + 1);
        float[][] nearBottomOuter = medianRows(a, b + 1, b + outer + 1);
        float[][] fullProfile = medianRows(a, t + q, b - q + 1);
        float[] eTop = new float[width], edge = new float[width], flat = new float[width];
        float[] flatVote = new float[width], fullFlat = new float[width], fullVote = new float[width];
        float[] deviations = new float[h];
        for (int x = 0; x < width; x++) {
            eTop[x] = distance(profile[x], bgTop[x]);
            edge[x] = Math.min(distance(nearTopInner[x], nearTopOuter[x]),
                    distance(nearBottomInner[x], nearBottomOuter[x]));
            for (int y = t + q; y < middle; y++) deviations[y - t - q] = distance(a.color(y, x), profile[x]);
            flat[x] = median(deviations, middle - t - q);
            int agrees = 0;
            for (int y = t + q; y < middle - 1; y++) if (maxDifference(a.color(y + 1, x), a.color(y, x)) <= 8) agrees++;
            flatVote[x] = agrees / (float) (middle - t - q - 1);
            for (int y = t + q; y < b - q + 1; y++) deviations[y - t - q] = distance(a.color(y, x), fullProfile[x]);
            fullFlat[x] = median(deviations, b - 2 * q - t + 1);
            agrees = 0;
            for (int y = t + q; y < b - q; y++) if (maxDifference(a.color(y + 1, x), a.color(y, x)) <= 8) agrees++;
            fullVote[x] = agrees / (float) (b - 2 * q - t);
        }
        List<Double> cuts = new ArrayList<>(); cuts.add(-.5); cuts.addAll(steps(profile, h, 16)); cuts.add(width - .5);
        List<Segment> segments = new ArrayList<>();
        boolean[] planar = new boolean[cuts.size() - 1];
        for (int i = 0; i + 1 < cuts.size(); i++) {
            double l = cuts.get(i), r = cuts.get(i + 1);
            int lo = Math.max(0, (int) Math.ceil(l)) + q / 2;
            int hi = Math.min(width, (int) Math.ceil(r) - q / 2);
            if (hi <= lo) { lo = Math.max(0, (int) Math.ceil(l)); hi = Math.min(width, Math.max(lo + 1, (int) Math.ceil(r))); }
            if (lo >= hi) return null;
            float[] c = medianColumns(profile, lo, hi);
            float e = medianRange(edge, lo, hi), f = medianRange(flat, lo, hi);
            int pairs = 0;
            for (int x = lo; x < hi; x++) if (edge[x] > 8) pairs++;
            char kind = classify(c);
            boolean topFill = kind == 'A' && medianRange(eTop, lo, hi) >= 20;
            boolean valid = medianRange(flatVote, lo, hi) >= .85 && f <= 10
                    && (pairs / (double) (hi - lo) >= .5 || topFill)
                    && (kind != 'N' || e >= 15 || r - l >= h * 1.5);
            if (kind != 'A') valid = valid && medianRange(fullFlat, lo, hi) <= 10 && medianRange(fullVote, lo, hi) >= .8;
            segments.add(new Segment(l, r, c, kind, e, valid));
            planar[i] = kind == 'D' && !valid && f <= 10 && medianRange(flatVote, lo, hi) >= .85
                    && medianRange(fullFlat, lo, hi) <= 10 && medianRange(fullVote, lo, hi) >= .8;
        }
        for (int i = 1; i + 1 < segments.size(); i++) {
            Segment before = segments.get(i - 1), segment = segments.get(i), after = segments.get(i + 1);
            // A loss plateau can match the background just outside the outline.
            // Its measured RGB boundaries and flat rows still connect an exposed
            // fill to an exposed neutral tail; it cannot establish an endpoint.
            if (!segment.valid && planar[i] && segment.kind == 'D'
                    && before.valid && before.kind == 'A' && after.valid && after.kind == 'N') {
                segments.set(i, new Segment(segment.left, segment.right, segment.color,
                        segment.kind, segment.edge, true));
            }
        }
        int seed = -1;
        double seedScore = -Double.MAX_VALUE;
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            if (!s.valid || s.right - s.left < h * .8 || s.right <= p.left || s.left >= p.right) continue;
            double score = (s.right - s.left) * Math.min(s.edge, 60);
            if (score > seedScore) { seed = i; seedScore = score; }
        }
        if (seed < 0) return null;
        int first = seed, last = seed;
        while (last + 1 < segments.size()) {
            Segment s = segments.get(last), n = segments.get(last + 1);
            if (n.valid && allowed(s.kind, n.kind)) { last++; continue; }
            if (last + 2 < segments.size() && n.right - n.left < h && mean(n.color) > 180
                    && segments.get(last + 2).valid && s.kind == segments.get(last + 2).kind && s.kind == 'A') { last += 2; continue; }
            break;
        }
        while (first > 0) {
            Segment s = segments.get(first), n = segments.get(first - 1);
            boolean headOrder = !(n.kind == 'N' && s.kind == 'D' && n.right - n.left < h * 1.5);
            if (n.valid && allowed(n.kind, s.kind) && headOrder) { first--; continue; }
            if (first > 1 && n.right - n.left < h && mean(n.color) > 180
                    && segments.get(first - 2).valid && s.kind == segments.get(first - 2).kind && s.kind == 'A') { first -= 2; continue; }
            break;
        }
        double xs = segments.get(first).left, right = segments.get(last).right;
        // A visible outside color sample establishes the end. Reserving a whole
        // gauge height after it would discard measurable large HUDs near the ROI.
        if (right - xs < 7 * h || xs < h || right > width - Math.max(2, outer)) return null;
        List<Double> upperEndEdges = steps(nearTopInner, h, 10);
        List<Double> lowerEndEdges = steps(nearBottomInner, h, 10);
        List<Double> endEdges = new ArrayList<>(upperEndEdges);
        endEdges.addAll(lowerEndEdges);
        int ew = Math.max(2, (int) Math.rint(h * .22));
        double endScore = -Double.MAX_VALUE, refinedRight = right;
        for (double x : endEdges) {
            if (!(x > Math.max(xs + h, right - h * 1.5) && x < right + h * .5)) continue;
            // Extending the observed chain needs the same crossing in both
            // exposed row groups. A scene edge behind a translucent HUD can
            // outlast the track in one group without extending the track itself.
            if (x > right) {
                double tolerance = Math.max(.8, h * .1);
                boolean upperMatch = false, lowerMatch = false;
                for (double edgeX : upperEndEdges) if (Math.abs(edgeX - x) < tolerance) upperMatch = true;
                for (double edgeX : lowerEndEdges) if (Math.abs(edgeX - x) < tolerance) lowerMatch = true;
                if (!upperMatch || !lowerMatch) continue;
            }
            int xi = (int) Math.rint(x);
            float el = medianRange(edge, xi - ew, xi), er = medianRange(edge, xi, xi + ew);
            boolean neutralEnd = false;
            for (int i = first; i <= last; i++) {
                Segment segment = segments.get(i);
                if (segment.kind == 'N' && segment.valid
                        && segment.left <= x - ew && x - ew < segment.right) neutralEnd = true;
            }
            // Separate row medians can localize an antialiased crossing a little
            // differently. This is an agreement tolerance, never an added length.
            boolean pairedCrossing = hasNearby(upperEndEdges, x, Math.max(.8, h * .18))
                    && hasNearby(lowerEndEdges, x, Math.max(.8, h * .18));
            // A textured scene may retain weak vertical contrast beyond the
            // neutral tail. Its absolute contrast need not be almost zero, but
            // both exposed row groups must end together and the outline must
            // fall by more than half. Do not apply this to a fill/loss transition.
            if (el >= 10 && (er < 8 || neutralEnd && pairedCrossing) && er < el * .5
                    && (el - er > endScore || el - er == endScore && x > refinedRight)) {
                endScore = el - er; refinedRight = x;
            }
        }
        // Equal interior colors can conceal the boundary to an adjacent panel.
        // A terminating outline may shorten that chain only when no combined
        // RGB/outline endpoint exists. Outward ends need exposed-row evidence.
        if (endScore == -Double.MAX_VALUE) {
            float[][] outline = new float[width][3];
            for (int x = 0; x < width; x++) Arrays.fill(outline[x], edge[x]);
            for (double x : steps(outline, h, 10)) {
                if (!(x > Math.max(xs + h, right - h * 1.5) && x < right - h * .1)) continue;
                int xi = (int) Math.rint(x);
                float el = medianRange(edge, xi - ew, xi), er = medianRange(edge, xi, xi + ew);
                if (el >= 10 && er < 8 && er < el * .5 && (el - er > endScore || el - er == endScore && x > refinedRight)) {
                    endScore = el - er; refinedRight = x;
                }
            }
        }
        right = refinedRight;
        List<Start> starts = new ArrayList<>(), coloredStarts = new ArrayList<>();
        int xsi = (int) Math.ceil(xs) + 1;
        for (int y = (t + b) / 2; y <= b; y++) {
            int lo = Math.max(0, xsi - h);
            float[] ref = medianRow(a, y, xsi, Math.min(width, xsi + Math.max(2, h / 4)));
            boolean[] mask = new boolean[xsi + 1 - lo];
            for (int x = lo; x <= xsi; x++) {
                float[] c = a.color(y, x);
                mask[x - lo] = c[1] - Math.min(c[0], c[2]) > 55 && c[1] > c[0] + 25 && c[1] >= c[2] - 6;
            }
            int darkEnd = xsi;
            boolean colored = false;
            int[] head = null;
            for (int[] run : runs(mask)) if (run[1] - run[0] >= h * .18 && run[1] + lo >= xsi - h * .25) head = run;
            if (head != null) {
                ref = medianRow(a, y, lo + head[0], lo + head[1]);
                darkEnd = Math.max(lo + 1, head[0] + lo); colored = true;
            }
            if (darkEnd <= lo || xsi >= width) continue;
            int darkIndex = lo;
            for (int x = lo + 1; x < darkEnd; x++) if (luma(a.color(y, x)) < luma(a.color(y, darkIndex))) darkIndex = x;
            float[] dark = a.color(y, darkIndex), axis = subtract(ref, dark);
            float norm = dot(axis, axis);
            if (norm < 400) continue;
            double crossing = Double.NaN;
            float previous = projection(a.color(y, darkIndex), dark, axis, norm);
            for (int x = darkIndex + 1; x <= xsi; x++) {
                float next = projection(a.color(y, x), dark, axis, norm);
                if (previous <= .5 && .5 < next) crossing = x - 1 + (.5 - previous) / (next - previous);
                previous = next;
            }
            if (!Double.isNaN(crossing)) {
                Start start = new Start(y, crossing); starts.add(start);
                if (colored) coloredStarts.add(start);
            }
        }
        if (hasSupported(coloredStarts, h)) starts = coloredStarts;
        Start endpoint = null;
        for (Start start : starts) if (supported(start, starts, h) && (endpoint == null || start.x < endpoint.x)) endpoint = start;
        if (endpoint == null) return null;
        double left = endpoint.x;
        List<Segment> chain = new ArrayList<>();
        for (int i = first; i <= last; i++) if (segments.get(i).left < right) chain.add(segments.get(i).copy());
        if (chain.isEmpty()) return null;
        chain.get(chain.size() - 1).right = right;
        double originalRight = right;
        TailExtension extension = exposedTail(a, t, b, right);
        if (extension != null) {
            right = extension.right;
            double split = Math.max(left, Math.min(originalRight, right) - h * .5);
            List<Segment> prefix = new ArrayList<>();
            for (Segment segment : chain) if (segment.left < split) {
                Segment copy = segment.copy(); copy.right = Math.min(copy.right, split); prefix.add(copy);
            }
            prefix.addAll(profileSections(extension.profile, split, right, h, chain.get(chain.size() - 1).edge));
            chain = prefix;
            for (int x = Math.max(0, (int) Math.floor(split)); x < width; x++) profile[x] = extension.profile[x].clone();
        }
        MaterialProjection observedMaterial = exposedTailMaterial(a, t, b, left, right, chain, profile);
        if (observedMaterial != null) {
            chain = observedMaterial.chain; profile = observedMaterial.profile;
        }
        boolean empty = false;
        for (Segment segment : chain) {
            if (segment.kind == 'N' && segment.valid) empty = true;
            if (empty && segment.kind == 'D') segment.kind = 'N';
        }
        List<Integer> exposedRows = new ArrayList<>();
        for (Start start : starts) if (supported(start, starts, h) && Math.abs(start.x - left) < h * .13) exposedRows.add(start.y);
        float[][] headProfile = medianSelectedRows(a, exposedRows);
        if (xs - left > h * .15) {
            List<Segment> observedHead = profileSections(headProfile, left, xs, h, chain.get(0).edge);
            char hk = observedHead.isEmpty() ? '?' : observedHead.get(0).kind, firstKind = chain.get(0).kind;
            if (hk == 'A' && (firstKind == 'D' || firstKind == 'N') || hk == 'D' && firstKind == 'N') {
                observedHead.addAll(chain); chain = observedHead;
                for (int x = 0; x < Math.min(width, (int) Math.ceil(xs) + q); x++) profile[x] = headProfile[x].clone();
            }
        }
        Segment firstActive = null;
        for (Segment segment : chain) if (segment.kind == 'A') { firstActive = segment; break; }
        float[] headColor;
        if (firstActive != null) {
            int start = Math.max(0, (int) Math.ceil(Math.max(left, firstActive.left)));
            int stop = Math.min(width, Math.max(start + 1, (int) Math.ceil(firstActive.right)));
            int probeStart = start + Math.min(q, Math.max(0, (stop - start - 1) / 3));
            headColor = medianColumns(profile, probeStart, Math.min(stop, probeStart + 2 * q));
        } else {
            headColor = medianColumns(profile, (int) Math.ceil(xs) + q, Math.min(width, (int) Math.ceil(xs) + 3 * q));
        }
        double aspect = (right - left) / h, evidence = 0;
        for (Segment segment : chain) evidence += (segment.right - segment.left) * Math.min(50, segment.edge) * (segment.kind == 'A' ? 2 : 1);
        double rank = (evidence / h) / (1 + Math.pow((aspect - 12.8) / 4, 2));
        return new Geometry(rank, t, b, left, right, a.x0, chain, headColor, profile);
    }

    private static Result interpret(Geometry geometry, int width) {
        int height = geometry.bottom - geometry.top + 1;
        int q = Math.max(1, (int) Math.rint(height * .12));
        float[][] profile = geometry.profile;
        List<Segment> chain = new ArrayList<>();
        for (Segment segment : geometry.chain) chain.add(segment.copy());
        if (chain.size() > 1 && chain.get(chain.size() - 2).kind == 'A'
                && chain.get(chain.size() - 1).kind == 'D') {
            Segment tail = chain.get(chain.size() - 1), before = chain.get(chain.size() - 2);
            if (tail.right - tail.left < height * .4) {
                int boundary = (int) Math.ceil(tail.left), end = Math.min(profile.length, (int) Math.ceil(tail.right));
                List<float[]> neutral = new ArrayList<>();
                for (int x = boundary; x < end; x++) if (classify(profile[x]) == 'N') neutral.add(profile[x]);
                if (!neutral.isEmpty()) {
                    float[][] neutralPixels = neutral.toArray(new float[neutral.size()][]);
                    float[] n = medianColumns(neutralPixels, 0, neutralPixels.length);
                    float[] active = medianColumns(profile, Math.max((int) Math.ceil(before.left), boundary - 2 * q), Math.max(1, boundary - q));
                    float[] axis = subtract(active, n);
                    float norm = Math.max(1, dot(axis, axis));
                    boolean mixtures = true;
                    for (int x = boundary; x < end; x++) {
                        float weight = projection(profile[x], n, axis, norm);
                        if (!(weight >= -.1 && weight <= 1.1)
                                || !(distance(profile[x], blend(n, axis, weight)) < 10)) { mixtures = false; break; }
                    }
                    if (mixtures) tail.kind = 'N';
                }
            }
        }
        if (chain.size() > 1 && (chain.get(0).kind == 'D' || chain.get(0).kind == 'A') && chain.get(1).kind == 'A'
                && chain.get(0).right - chain.get(0).left < height * .2) {
            int edge = (int) Math.ceil(geometry.left);
            float[] background = medianColumns(profile, Math.max(0, edge - q), edge);
            float[] color = chain.get(1).color, c = chain.get(0).color, axis = subtract(color, background);
            float mixture = dot(subtract(c, background), axis) / Math.max(1, dot(axis, axis));
            if (mixture >= 0 && mixture <= 1 && distance(c, blend(background, axis, mixture)) < 10) {
                chain.get(0).kind = 'A'; chain.get(0).edgeBlend = true;
            }
        }
        for (int i = 1; i < chain.size() - 1; i++) {
            Segment before = chain.get(i - 1), segment = chain.get(i), after = chain.get(i + 1);
            if (before.kind == 'A' && segment.kind == 'D' && after.kind == 'N'
                    && segment.right - segment.left < height * .4) {
                int boundary = (int) Math.ceil(segment.left);
                int start = Math.max((int) Math.ceil(before.left), boundary - 2 * q);
                int stop = Math.max(start + 1, boundary - q);
                float[] n = after.color, active = medianColumns(profile, start, stop), c = segment.color;
                float[] axis = subtract(active, n);
                float ratio = dot(subtract(c, n), axis) / Math.max(1, dot(axis, axis));
                if (ratio >= 0 && ratio <= 1 && distance(c, blend(n, axis, ratio)) < 10) segment.kind = 'N';
            }
        }
        classifyRelativeLoss(chain, profile, height);
        List<Segment> merged = new ArrayList<>();
        for (Segment segment : chain) {
            if (!segment.valid && !merged.isEmpty()) continue;
            Segment previous = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            int boundary = (int) Math.ceil(segment.left);
            float localJump = luma(subtract(medianColumns(profile, boundary, boundary + q),
                    medianColumns(profile, Math.max(0, boundary - q), boundary)));
            boolean edgeBlend = previous != null && segment.kind == previous.kind && previous.edgeBlend;
            if (previous != null && segment.kind == previous.kind) previous.edgeBlend = false;
            if (previous != null && segment.kind == previous.kind
                    && (edgeBlend || !(segment.kind == 'A' && localJump > 20))) previous.right = segment.right;
            else merged.add(segment.copy());
        }
        List<Segment> active = new ArrayList<>(), dim = new ArrayList<>();
        for (Segment segment : merged) {
            if (segment.kind == 'A') active.add(segment);
            if (segment.kind == 'D') dim.add(segment);
        }
        double current, after;
        Direction direction;
        if (active.size() > 1) {
            current = value(active.get(0).right, geometry);
            after = value(active.get(active.size() - 1).right, geometry); direction = Direction.GAIN;
        } else if (!active.isEmpty() && !dim.isEmpty()) {
            current = value(dim.get(dim.size() - 1).right, geometry);
            after = value(active.get(0).right, geometry); direction = Direction.LOSS;
        } else if (!active.isEmpty()) {
            float[] c = geometry.headColor;
            after = value(active.get(0).right, geometry);
            if (isRecoveryMaterial(c)) { current = 0; direction = Direction.GAIN; }
            else { current = after; direction = Direction.NONE; }
        } else if (!dim.isEmpty()) {
            current = value(dim.get(dim.size() - 1).right, geometry); after = 0; direction = Direction.LOSS;
        } else { current = after = 0; direction = Direction.NONE; }
        return new Result((int) Math.floor(current + .5), (int) Math.floor(after + .5), direction,
                new Anchor((float) ((geometry.left + geometry.x0) / width),
                        (float) ((geometry.top + geometry.bottom) * .5 / width)),
                (float) Math.max(0, Math.min(.99, geometry.score / 1000)));
    }

    private static MaterialProjection exposedTailMaterial(Crop a, int top, int bottom,
                                                          double left, double right,
                                                          List<Segment> chain, float[][] profile) {
        int height = bottom - top + 1, q = Math.max(1, (int) Math.rint(height * .15));
        float[][] upper = medianRows(a, top + 1, top + q + 1);
        float[][] lower = medianRows(a, bottom - q, bottom);
        float[][] observed = profile.clone();
        boolean changed = false;
        for (Segment segment : chain) {
            if (segment.left < right - height * 1.6 || segment.kind != 'N') continue;
            int lo = Math.max(0, (int) Math.ceil(segment.left)) + q / 2;
            int hi = Math.min(profile.length, (int) Math.floor(segment.right) - q / 2);
            if (hi <= lo) continue;
            float[] ca = medianColumns(upper, lo, hi), cb = medianColumns(lower, lo, hi);
            if (classify(ca) != 'A' || classify(cb) != 'A' || distance(ca, cb) > 18) continue;
            if (luma(segment.color) >= .6 * Math.min(luma(ca), luma(cb))) continue;
            int agrees = 0;
            for (int x = lo; x < hi; x++) if (distance(upper[x], lower[x]) < 18) agrees++;
            if (agrees / (double) (hi - lo) < .85) continue;
            // These RGB observations come from both exposed border rows. The
            // measured endpoints remain unchanged when the interior is covered.
            int first = Math.max(0, (int) Math.ceil(segment.left));
            int end = Math.min(profile.length, (int) Math.ceil(segment.right));
            for (int x = first; x < end; x++) {
                observed[x] = new float[3];
                for (int c = 0; c < 3; c++) observed[x][c] = (upper[x][c] + lower[x][c]) * .5f;
            }
            changed = true;
        }
        return changed ? new MaterialProjection(profileSections(observed, left, right, height,
                chain.get(chain.size() - 1).edge), observed) : null;
    }

    private static void classifyRelativeLoss(List<Segment> chain, float[][] profile, int height) {
        if (chain.size() < 2) return;
        Segment tail = chain.get(chain.size() - 1);
        if (tail.kind != 'N' || !tail.valid) return;
        int inset = Math.max(1, (int) Math.rint(height * .12));
        double[] neutral = relativeMaterialEvidence(tail, profile, inset);
        if (neutral == null) return;
        for (int i = 0; i < chain.size() - 1; i++) {
            Segment segment = chain.get(i);
            if (segment.kind != 'N' || !segment.valid) continue;
            boolean hasNeutralContinuation = true;
            for (int j = i + 1; j < chain.size(); j++) if (chain.get(j).kind != 'N') {
                hasNeutralContinuation = false; break;
            }
            if (!hasNeutralContinuation) continue;
            double[] material = relativeMaterialEvidence(segment, profile, inset);
            if (material == null) continue;
            // Distinct plateaus must differ by more than their measured color
            // variation. The small floors are RGB noise tolerances, not lengths.
            if (material[0] - neutral[0] > Math.max(2, 3 * Math.hypot(material[2], neutral[2]))
                    && neutral[1] - material[1] > Math.max(8, 3 * Math.hypot(material[3], neutral[3]))) {
                segment.kind = 'D';
            }
        }
    }

    private static double[] relativeMaterialEvidence(Segment segment, float[][] profile, int inset) {
        int start = Math.max(0, (int) Math.ceil(segment.left));
        int end = Math.min(profile.length, (int) Math.ceil(segment.right));
        int margin = Math.min(inset, Math.max(0, (end - start - 2) / 2));
        start += margin; end -= margin;
        if (end - start < 2) return null;
        float[] opponent = new float[end - start], light = new float[end - start];
        for (int i = start; i < end; i++) {
            float[] color = profile[i];
            opponent[i - start] = color[1] - (color[0] + color[2]) * .5f;
            light[i - start] = luma(color);
        }
        float greenExcess = median(opponent, opponent.length), luminance = median(light, light.length);
        for (int i = 0; i < opponent.length; i++) {
            opponent[i] = Math.abs(opponent[i] - greenExcess);
            light[i] = Math.abs(light[i] - luminance);
        }
        return new double[] {greenExcess, luminance,
                median(opponent, opponent.length) * 1.4826,
                median(light, light.length) * 1.4826};
    }

    private static boolean hasAmbiguousEndpoint(Crop crop, Geometry geometry) {
        return hasTallUniformSurface(crop, geometry, geometry.left, true)
                || hasTallUniformSurface(crop, geometry, geometry.right, false);
    }

    private static boolean hasTallUniformSurface(Crop crop, Geometry geometry,
                                                double endpoint, boolean leftEnd) {
        int h = geometry.bottom - geometry.top + 1;
        int x = (int) Math.rint(endpoint);
        int radius = Math.max(1, (int) Math.rint(h * .2));
        int upperFirst = Math.max(0, (int) (geometry.top - h * .65));
        int upperEnd = Math.max(0, (int) (geometry.top - h * .15));
        int lowerFirst = Math.min(crop.height, (int) (geometry.bottom + h * .15));
        int lowerEnd = Math.min(crop.height, (int) (geometry.bottom + h * .65));
        int upperCount = upperEnd - upperFirst;
        int lowerCount = lowerEnd - lowerFirst;
        if (upperCount < 2 || lowerCount < 2 || x - radius * 3 < 0
                || x + radius * 3 >= crop.width) return false;

        int count = upperCount + lowerCount;
        int[] rows = new int[count + h];
        for (int i = 0; i < upperCount; i++) rows[i] = upperFirst + i;
        for (int i = 0; i < lowerCount; i++) rows[upperCount + i] = lowerFirst + i;
        for (int i = 0; i < h; i++) rows[count + i] = geometry.top + i;
        float[] rowNoise = new float[count];
        int upperStrong = 0, lowerStrong = 0;
        float[] differences = new float[radius * 6 - 1];
        for (int i = 0; i < count; i++) {
            int y = rows[i];
            float[] before = medianRow(crop, y, x - radius, x);
            float[] after = medianRow(crop, y, x + 1, x + radius + 1);
            for (int j = 0; j < differences.length; j++) {
                int column = x - radius * 3 + j;
                differences[j] = distance(crop.color(y, column + 1), crop.color(y, column));
            }
            rowNoise[i] = median(differences, differences.length);
            if (distance(after, before) > Math.max(5, rowNoise[i] * 4)) {
                if (i < upperCount) upperStrong++;
                else lowerStrong++;
            }
        }
        if (upperStrong / (double) upperCount < .75
                || lowerStrong / (double) lowerCount < .75) return false;

        int outsideColumn = leftEnd ? x - radius : x + radius;
        float[] center = new float[3], samples = new float[rows.length];
        for (int channel = 0; channel < 3; channel++) {
            for (int i = 0; i < rows.length; i++) samples[i] = crop.get(rows[i], outsideColumn, channel);
            center[channel] = median(samples, samples.length);
        }
        float[] deviations = new float[rows.length];
        for (int i = 0; i < rows.length; i++) {
            deviations[i] = distance(crop.color(rows[i], outsideColumn), center);
        }
        Arrays.sort(deviations);
        // Require a uniform foreground surface across most observed rows.
        double position = (deviations.length - 1) * .8;
        int low = (int) Math.floor(position), high = (int) Math.ceil(position);
        double dispersion = deviations[low]
                + (deviations[high] - deviations[low]) * (position - low);
        return dispersion < Math.max(5, median(rowNoise, rowNoise.length) * 4);
    }

    private static TailExtension exposedTail(Crop a, int top, int bottom, double right) {
        int height = bottom - top + 1, q = Math.max(1, (int) Math.rint(height * .15)), width = a.width;
        float[][] upper = medianRows(a, top + 1, top + q + 1), lower = medianRows(a, bottom - q, bottom);
        float[][] above = medianRows(a, Math.max(0, top - q), top), below = medianRows(a, bottom + 1, bottom + q + 1);
        List<Double> upperEdges = steps(upper, height * .7, 14);
        List<Double> lowerEdges = steps(lower, height * .7, 14);
        boolean observedEnd = hasNearby(upperEdges, right, Math.max(.8, height * .1))
                && hasNearby(lowerEdges, right, Math.max(.8, height * .1));
        TailExtension best = null;
        for (double x : upperEdges) {
            // An overlapping middle panel can hide an earlier true endpoint,
            // just as a foreground icon can hide later exposed track material.
            // Search both ways, but measure only an agreeing visible crossing.
            if (!(right - height * 1.6 < x && x < Math.min(width - q - 1, right + height * 1.6))
                    || Math.abs(x - right) <= height * .15) continue;
            // Exposed rows can recover an end hidden in the middle. Once those
            // same rows already show the measured crossing, a later scene edge
            // is not additional track length.
            if (x > right && observedEnd) continue;
            double mate = Double.NaN, nearest = Double.POSITIVE_INFINITY;
            for (double z : lowerEdges) if (Math.abs(z - x) < Math.max(.8, height * .1) && Math.abs(z - x) < nearest) {
                mate = z; nearest = Math.abs(z - x);
            }
            if (Double.isNaN(mate)) continue;
            int xi = (int) Math.ceil(x), continuationStart = Math.max(0, (int) Math.ceil(right) + q / 2);
            int continuationEnd = Math.max(0, xi - q / 2), supporting = 0;
            for (int i = continuationStart; i < continuationEnd; i++) {
                if (Math.min(distance(upper[i], above[i]), distance(lower[i], below[i])) > 12) supporting++;
            }
            if (continuationEnd > continuationStart && supporting / (double) (continuationEnd - continuationStart) < .8) continue;
            int inside = Math.max(0, xi - q), outside = Math.min(width, xi + q);
            float[] ci = medianColumns(upper, inside, xi), cb = medianColumns(lower, inside, xi);
            float[] co = medianColumns(upper, xi, outside), bo = medianColumns(lower, xi, outside);
            float[][] backgrounds = new float[(outside - xi) * 2][3];
            for (int i = xi; i < outside; i++) {
                backgrounds[i - xi] = above[i]; backgrounds[i - xi + outside - xi] = below[i];
            }
            float[] background = medianColumns(backgrounds, 0, backgrounds.length);
            float[] delta = new float[3];
            for (int c = 0; c < 3; c++) delta[c] = (ci[c] + cb[c] - co[c] - bo[c]) * .5f;
            if (distance(ci, cb) > 18 || luma(delta) < 12) continue;
            if (Math.max(distance(co, background), distance(bo, background)) > 16) continue;
            if (Math.min(distance(ci, medianColumns(above, inside, xi)), distance(cb, medianColumns(below, inside, xi))) < 15) continue;
            double measuredRight = (x + mate) * .5;
            if (best == null || measuredRight > best.right) {
                float[][] profile = new float[width][3];
                for (int i = 0; i < width; i++) for (int c = 0; c < 3; c++) profile[i][c] = (upper[i][c] + lower[i][c]) * .5f;
                best = new TailExtension(measuredRight, profile);
            }
        }
        return best;
    }
    private static List<Segment> profileSections(float[][] profile, double left, double right, int height, float evidence) {
        List<Double> boundaries = new ArrayList<>(); boundaries.add(left);
        for (double x : steps(profile, height * .7, 12)) if (left < x && x < right) boundaries.add(x);
        boundaries.add(right);
        List<Segment> result = new ArrayList<>();
        for (int i = 0; i < boundaries.size() - 1; i++) {
            double l = boundaries.get(i), r = boundaries.get(i + 1);
            int first = Math.max(0, (int) Math.ceil(l)), end = Math.min(profile.length, (int) Math.ceil(r));
            if (end <= first) continue;
            float[] color = medianColumns(profile, first, end);
            result.add(new Segment(l, r, color, classify(color), evidence, true));
        }
        return result;
    }
    private static float[][] medianSelectedRows(Crop a, List<Integer> rows) {
        float[][] result = new float[a.width][3];
        float[] samples = new float[rows.size()];
        for (int x = 0; x < a.width; x++) for (int c = 0; c < 3; c++) {
            for (int i = 0; i < rows.size(); i++) samples[i] = a.get(rows.get(i), x, c);
            result[x][c] = median(samples, samples.length);
        }
        return result;
    }
    private static float[] blend(float[] background, float[] axis, float fraction) {
        return new float[] {background[0] + axis[0] * fraction, background[1] + axis[1] * fraction, background[2] + axis[2] * fraction};
    }
    private static double value(double edge, Geometry g) { return Math.max(0, Math.min(100, 100 * (edge - g.left) / (g.right - g.left))); }
    private static boolean allowed(char a, char b) { return a == 'A' || b == 'D' || b == 'N'; }
    private static char classify(float[] c) {
        float r = c[0], g = c[1], b = c[2];
        if (g - Math.min(r, b) > 55 && g > b - 6 && g > r - 18) return 'A';
        // A warm neutral surface can have little blue without being green.
        // Require either red/green separation or clear excess over both other
        // channels; the latter also retains yellow-green loss material.
        if (g - Math.min(r, b) > 9 && g > Math.max(r, b) - 3
                && (g - r > 3 || g - (r + b) * .5f > 9)) return 'D';
        return 'N';
    }
    private static boolean isRecoveryMaterial(float[] color) {
        // Recovery adds a bright neutral component to the green fill. Compare
        // both other channels with green instead of requiring a particular red
        // value or green-blue hue that changes with video color conversion.
        return color[1] >= 200 && Math.min(color[0], color[2]) >= color[1] * .35f;
    }
    private static boolean hasSupported(List<Start> starts, int height) {
        for (Start start : starts) if (supported(start, starts, height)) return true;
        return false;
    }
    private static boolean hasNearby(List<Double> edges, double x, double tolerance) {
        for (double edge : edges) if (Math.abs(edge - x) < tolerance) return true;
        return false;
    }
    private static boolean supported(Start start, List<Start> starts, int height) {
        for (Start other : starts) if (Math.abs(start.y - other.y) == 1 && Math.abs(start.x - other.x) < height * .13) return true;
        return false;
    }
    private static float[][] medianRows(Crop a, int first, int end) {
        first = Math.max(0, first); end = Math.min(a.height, end);
        float[][] result = new float[a.width][3];
        if (end <= first) return result;
        float[] samples = new float[end - first];
        for (int x = 0; x < a.width; x++) for (int c = 0; c < 3; c++) {
            for (int y = first; y < end; y++) samples[y - first] = a.get(y, x, c);
            result[x][c] = median(samples, samples.length);
        }
        return result;
    }
    private static float[][] meanRows(Crop a, int first, int end) {
        first = Math.max(0, first); end = Math.min(a.height, end);
        float[][] result = new float[a.width][3];
        for (int x = 0; x < a.width; x++) for (int c = 0; c < 3; c++) {
            for (int y = first; y < end; y++) result[x][c] += a.get(y, x, c);
            result[x][c] /= Math.max(1, end - first);
        }
        return result;
    }
    private static float[] medianRow(Crop a, int y, int first, int end) {
        float[] result = new float[3], samples = new float[Math.max(1, end - first)];
        for (int c = 0; c < 3; c++) {
            for (int x = first; x < end; x++) samples[x - first] = a.get(y, x, c);
            result[c] = median(samples, end - first);
        }
        return result;
    }
    private static float[] medianColumns(float[][] values, int first, int end) {
        first = Math.max(0, first); end = Math.min(values.length, end);
        float[] result = new float[3], samples = new float[Math.max(1, end - first)];
        for (int c = 0; c < 3; c++) {
            for (int i = first; i < end; i++) samples[i - first] = values[i][c];
            result[c] = median(samples, end - first);
        }
        return result;
    }
    private static float medianRange(float[] values, int first, int end) {
        first = Math.max(0, first); end = Math.min(values.length, end);
        if (end <= first) return Float.NaN;
        float[] copy = Arrays.copyOfRange(values, first, end);
        return median(copy, copy.length);
    }
    private static float median(float[] values, int count) {
        if (count <= 0) return Float.NaN;
        Arrays.sort(values, 0, count);
        return count % 2 == 1 ? values[count / 2] : (values[count / 2 - 1] + values[count / 2]) * .5f;
    }
    private static float mean(float[] c) { return (c[0] + c[1] + c[2]) / 3f; }
    private static float luma(float[] c) { return c[0] * .299f + c[1] * .587f + c[2] * .114f; }
    private static float[] subtract(float[] a, float[] b) { return new float[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    private static float dot(float[] a, float[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static float projection(float[] color, float[] origin, float[] axis, float norm) {
        return ((color[0] - origin[0]) * axis[0] + (color[1] - origin[1]) * axis[1]
                + (color[2] - origin[2]) * axis[2]) / norm;
    }
    private static float distance(float[] a, float[] b) {
        float d0 = a[0] - b[0], d1 = a[1] - b[1], d2 = a[2] - b[2];
        return (float) Math.sqrt(d0 * d0 + d1 * d1 + d2 * d2);
    }
    private static float maxDifference(float[] a, float[] b) {
        return Math.max(Math.abs(a[0] - b[0]), Math.max(Math.abs(a[1] - b[1]), Math.abs(a[2] - b[2])));
    }
    private static List<int[]> runs(boolean[] mask) {
        List<int[]> result = new ArrayList<>();
        int first = -1;
        for (int i = 0; i <= mask.length; i++) {
            boolean value = i < mask.length && mask[i];
            if (value && first < 0) first = i;
            if (!value && first >= 0) { result.add(new int[] {first, i}); first = -1; }
        }
        return result;
    }
    private static int[] peaks(float[] values, int count, int separation) {
        int[] order = descending(values), chosen = new int[Math.min(count, values.length)];
        int size = 0;
        for (int value : order) {
            boolean nearby = false;
            for (int i = 0; i < size; i++) if (Math.abs(chosen[i] - value) <= separation) { nearby = true; break; }
            if (!nearby) chosen[size++] = value;
            if (size >= chosen.length) break;
        }
        return Arrays.copyOf(chosen, size);
    }
    private static int[] descending(float[] values) {
        Integer[] order = new Integer[values.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
            int result = Float.compare(values[b], values[a]);
            return result != 0 ? result : Integer.compare(b, a);
        });
        int[] result = new int[order.length];
        for (int i = 0; i < order.length; i++) result[i] = order[i];
        return result;
    }
    private static float[] blur(float[] values, int size) {
        float[] result = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            float sum = 0;
            for (int j = 0; j < size; j++) {
                int x = i + j - size / 2;
                while (x < 0 || x >= values.length) {
                    if (x < 0) x = -x;
                    else x = values.length * 2 - x - 2;
                }
                sum += values[x];
            }
            result[i] = sum / size;
        }
        return result;
    }
    private static final class Crop {
        final int screenWidth, x0, width, height;
        final float[][][] data;
        Crop(int screenWidth, Region region, int[] pixels) {
            this.screenWidth = screenWidth;
            x0 = Math.max(region.left, (int) (screenWidth * SEARCH_LEFT));
            width = Math.max(0, Math.min(region.left + region.width, (int) Math.ceil(screenWidth * SEARCH_RIGHT)) - x0);
            height = Math.min(region.height, Math.max(64, (int) Math.ceil(screenWidth * SEARCH_BOTTOM)));
            data = new float[height][width][3];
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int color = pixels[y * region.width + x + x0 - region.left];
                data[y][x][0] = (color >>> 16) & 255;
                data[y][x][1] = (color >>> 8) & 255;
                data[y][x][2] = color & 255;
            }
        }
        float get(int y, int x, int channel) { return data[y][x][channel]; }
        float[] color(int y, int x) { return data[y][x]; }
    }
    private static final class Proposal {
        final double score;
        final int top, bottom, left, right;
        Proposal(double score, int top, int bottom, int left, int right) {
            this.score = score; this.top = top; this.bottom = bottom; this.left = left; this.right = right;
        }
    }
    private static final class Segment {
        double left, right;
        final float[] color;
        char kind;
        boolean edgeBlend;
        final float edge, luminance;
        final boolean valid;
        Segment(double left, double right, float[] color, char kind, float edge, boolean valid) {
            this.left = left; this.right = right; this.color = color; this.kind = kind;
            this.edge = edge; this.valid = valid; this.luminance = luma(color);
        }
        Segment copy() { Segment copy = new Segment(left, right, color, kind, edge, valid); copy.edgeBlend = edgeBlend; return copy; }
    }
    private static final class Start {
        final int y; final double x;
        Start(int y, double x) { this.y = y; this.x = x; }
    }
    private static final class Geometry {
        final double score, left, right;
        final int top, bottom, x0;
        final List<Segment> chain;
        final float[] headColor;
        final float[][] profile;
        Geometry(double score, int top, int bottom, double left, double right,
                 int x0, List<Segment> chain, float[] headColor, float[][] profile) {
            this.score = score; this.top = top; this.bottom = bottom; this.left = left;
            this.right = right; this.x0 = x0; this.chain = chain; this.headColor = headColor; this.profile = profile;
        }
    }
    private static final class TailExtension {
        final double right; final float[][] profile;
        TailExtension(double right, float[][] profile) { this.right = right; this.profile = profile; }
    }
    private static final class MaterialProjection {
        final List<Segment> chain; final float[][] profile;
        MaterialProjection(List<Segment> chain, float[][] profile) { this.chain = chain; this.profile = profile; }
    }
}
