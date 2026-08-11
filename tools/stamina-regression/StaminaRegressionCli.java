package helper.journey.starsavior;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs the production stamina detector over a private corpus of still images and videos.
 *
 * <p>The media stays outside the public repository. This small runner is intentionally
 * dependency-free so every supplied sample can be replayed with the same detector code
 * that ships in the app.</p>
 */
public final class StaminaRegressionCli {
    private static final Set<String> STILL_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".webp");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            ".mp4", ".mov", ".mkv", ".webm");

    private StaminaRegressionCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "usage: <ffmpeg> <ffprobe> <corpus-root> <report.tsv> <expectations.tsv>");
        }
        Path ffmpeg = Path.of(args[0]).toAbsolutePath().normalize();
        Path ffprobe = Path.of(args[1]).toAbsolutePath().normalize();
        Path corpus = Path.of(args[2]).toAbsolutePath().normalize();
        Path report = Path.of(args[3]).toAbsolutePath().normalize();
        Path expectationFile = Path.of(args[4]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(ffmpeg) || !Files.isRegularFile(ffprobe)) {
            throw new IllegalArgumentException("ffmpeg or ffprobe is missing");
        }
        if (!Files.isDirectory(corpus)) {
            throw new IllegalArgumentException("corpus root is missing: " + corpus);
        }
        Map<String, Map<Integer, Expectation>> expectations = readExpectations(expectationFile);

        List<Path> media = new ArrayList<>();
        try (var paths = Files.walk(corpus)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> isCorpusSource(corpus, path))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(media::add);
        }
        if (media.isEmpty()) throw new IllegalArgumentException("corpus contains no media");
        if (report.getParent() != null) Files.createDirectories(report.getParent());
        List<String> failures = new ArrayList<>();
        Set<String> replayedFiles = new HashSet<>();

        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(
                report, StandardCharsets.UTF_8))) {
            writer.println("record\tfile\tframe\tseconds\traw\tstable\tcount\twidth\theight\tsha256");
            for (Path file : media) {
                String relative = relative(corpus, file);
                replayedFiles.add(relative);
                Map<Integer, Expectation> fileExpectations = expectations.get(relative);
                if (fileExpectations == null || fileExpectations.isEmpty()) {
                    failures.add(relative + ": no expectation rows");
                    fileExpectations = Map.of();
                }
                replay(ffmpeg, ffprobe, corpus, file, writer, fileExpectations, failures);
            }
        }
        for (String expectedFile : expectations.keySet()) {
            if (!replayedFiles.contains(expectedFile)) {
                failures.add(expectedFile + ": expectation refers to missing canonical media");
            }
        }
        int expectationCount = expectations.values().stream().mapToInt(Map::size).sum();
        System.out.println("corpus=" + corpus);
        System.out.println("media=" + media.size());
        System.out.println("expectations=" + expectationCount);
        System.out.println("report=" + report);
        if (!failures.isEmpty()) {
            System.err.println("Regression expectation failures:");
            for (String failure : failures) System.err.println("  " + failure);
            throw new AssertionError(failures.size() + " regression expectation(s) failed");
        }
    }

    private static void replay(Path ffmpeg, Path ffprobe, Path corpus, Path file,
                               PrintWriter writer, Map<Integer, Expectation> expectations,
                               List<String> failures) throws Exception {
        Metadata metadata = probe(ffprobe, file);
        StaminaGaugeDetector.Region region = StaminaGaugeDetector.scanRegion(
                metadata.width, metadata.height);
        List<String> command = new ArrayList<>(List.of(
                ffmpeg.toString(), "-v", "fatal", "-i", file.toString(),
                "-an", "-sn", "-dn", "-vf",
                "format=bgra,crop=" + region.width + ":" + region.height + ":"
                        + region.left + ":" + region.top,
                "-fps_mode", "passthrough", "-pix_fmt", "bgra",
                "-f", "rawvideo"));
        if (isStill(file)) command.addAll(List.of("-frames:v", "1"));
        command.add("pipe:1");
        Process process = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();

        int frameBytes = Math.multiplyExact(Math.multiplyExact(region.width, region.height), 4);
        byte[] bytes = new byte[frameBytes];
        int[] pixels = new int[region.width * region.height];
        Map<String, Integer> rawCounts = new LinkedHashMap<>();
        Map<String, Integer> stableCounts = new LinkedHashMap<>();
        StaminaGaugeDetector.Anchor anchor = null;
        StaminaGaugeDetector.Result previousStable = null;
        String previousRaw = null;
        String previousStableKey = null;
        String relative = relative(corpus, file);
        String sha256 = sha256(file);
        int frame = 0;
        Set<Integer> checkedExpectations = new HashSet<>();

        try (InputStream input = process.getInputStream()) {
            while (readFrame(input, bytes)) {
                for (int index = 0, offset = 0; index < pixels.length; index++, offset += 4) {
                    int blue = bytes[offset] & 0xff;
                    int green = bytes[offset + 1] & 0xff;
                    int red = bytes[offset + 2] & 0xff;
                    pixels[index] = 0xff000000 | (red << 16) | (green << 8) | blue;
                }
                StaminaGaugeDetector.Result raw = StaminaGaugeDetector.detect(
                        metadata.width, metadata.height, region, pixels, anchor);
                StaminaGaugeDetector.Result stable = raw == null
                        ? null : raw.stabilize(previousStable);
                if (raw != null) anchor = raw.anchor;
                if (stable != null) previousStable = stable;
                String rawKey = key(raw);
                String stableKey = key(stable);
                rawCounts.merge(rawKey, 1, Integer::sum);
                stableCounts.merge(stableKey, 1, Integer::sum);
                if (!rawKey.equals(previousRaw) || !stableKey.equals(previousStableKey)) {
                    writer.printf(Locale.ROOT,
                            "transition\t%s\t%d\t%.4f\t%s\t%s\t\t%d\t%d\t%s%n",
                            tsv(relative), frame, frame / metadata.framesPerSecond,
                            rawKey, stableKey, metadata.width, metadata.height, sha256);
                    previousRaw = rawKey;
                    previousStableKey = stableKey;
                }
                Expectation expectation = expectations.get(frame);
                if (expectation != null) {
                    checkedExpectations.add(frame);
                    String failure = expectation.failure(raw);
                    String expectedKey = expectation.key() + " +/-" + expectation.tolerance;
                    writer.printf(Locale.ROOT,
                            "%s\t%s\t%d\t%.4f\t%s\t%s\t\t%d\t%d\t%s%n",
                            failure == null ? "expectation-pass" : "expectation-fail",
                            tsv(relative), frame, frame / metadata.framesPerSecond,
                            rawKey, expectedKey, metadata.width, metadata.height, sha256);
                    if (failure != null) failures.add(relative + " frame " + frame + ": " + failure);
                }
                frame++;
            }
        }
        int exit = process.waitFor();
        if (exit != 0) throw new IOException("ffmpeg failed for " + file + ": " + exit);
        writer.printf(Locale.ROOT,
                "file\t%s\t\t\t\t\t%d\t%d\t%d\t%s%n",
                tsv(relative), frame, metadata.width, metadata.height, sha256);
        writeCounts(writer, relative, "raw-count", rawCounts, metadata, sha256);
        writeCounts(writer, relative, "stable-count", stableCounts, metadata, sha256);
        for (int expectedFrame : expectations.keySet()) {
            if (!checkedExpectations.contains(expectedFrame)) {
                failures.add(relative + " frame " + expectedFrame
                        + ": frame was not present (decoded " + frame + " frames)");
            }
        }
        writer.flush();
        System.out.println(relative + " frames=" + frame + " raw=" + rawCounts);
    }

    private static Map<String, Map<Integer, Expectation>> readExpectations(Path file)
            throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("expectation file is missing: " + file);
        }
        Map<String, Map<Integer, Expectation>> result = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank() || line.startsWith("#") || line.startsWith("file\t")) continue;
            String[] columns = line.split("\t", -1);
            if (columns.length < 6) {
                throw new IllegalArgumentException("invalid expectation row " + (index + 1));
            }
            String relative = columns[0].replace('\\', '/');
            int frame = Integer.parseInt(columns[1]);
            int current = Integer.parseInt(columns[2]);
            int after = Integer.parseInt(columns[3]);
            StaminaGaugeDetector.Direction direction =
                    StaminaGaugeDetector.Direction.valueOf(columns[4]);
            int tolerance = Integer.parseInt(columns[5]);
            if (frame < 0 || current < 0 || current > 100 || after < 0 || after > 100
                    || tolerance < 0 || tolerance > 10) {
                throw new IllegalArgumentException("out-of-range expectation row " + (index + 1));
            }
            Map<Integer, Expectation> byFrame = result.computeIfAbsent(
                    relative, ignored -> new LinkedHashMap<>());
            if (byFrame.put(frame,
                    new Expectation(current, after, direction, tolerance)) != null) {
                throw new IllegalArgumentException("duplicate expectation for "
                        + relative + " frame " + frame);
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("expectation file is empty");
        return result;
    }

    private static void writeCounts(PrintWriter writer, String relative, String record,
                                    Map<String, Integer> counts, Metadata metadata,
                                    String sha256) {
        counts.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .forEach(entry -> writer.printf(Locale.ROOT,
                        "%s\t%s\t\t\t%s\t\t%d\t%d\t%d\t%s%n",
                        record, tsv(relative), entry.getKey(), entry.getValue(),
                        metadata.width, metadata.height, sha256));
    }

    private static Metadata probe(Path ffprobe, Path file) throws Exception {
        Process process = new ProcessBuilder(
                ffprobe.toString(), "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=width,height,avg_frame_rate",
                "-of", "csv=p=0:s=,", file.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        String line;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            line = reader.readLine();
        }
        int exit = process.waitFor();
        if (exit != 0 || line == null) throw new IOException("ffprobe failed for " + file);
        String[] parts = line.trim().split(",");
        if (parts.length < 3) throw new IOException("unexpected ffprobe output: " + line);
        return new Metadata(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                parseRate(parts[2]));
    }

    private static double parseRate(String value) {
        String[] parts = value.split("/");
        double rate = parts.length == 2
                ? Double.parseDouble(parts[0]) / Double.parseDouble(parts[1])
                : Double.parseDouble(value);
        return Double.isFinite(rate) && rate > 0 ? rate : 1.0;
    }

    private static boolean readFrame(InputStream input, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int count = input.read(target, offset, target.length - offset);
            if (count < 0) {
                if (offset == 0) return false;
                throw new EOFException("partial raw video frame");
            }
            offset += count;
        }
        return true;
    }

    private static String key(StaminaGaugeDetector.Result result) {
        return result == null ? "null"
                : result.current + ">" + result.after + "/" + result.direction;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) >= 0;) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static boolean isCorpusSource(Path corpus, Path file) {
        Path relative = corpus.relativize(file);
        if (relative.getNameCount() < 2) return false;
        String first = relative.getName(0).toString();
        boolean sourceDirectory = first.equals("new")
                || (first.equals("prior") && relative.getNameCount() >= 3
                && relative.getName(1).toString().equals("extracted"));
        if (!sourceDirectory) return false;
        String extension = extension(file);
        return STILL_EXTENSIONS.contains(extension) || VIDEO_EXTENSIONS.contains(extension);
    }

    private static boolean isStill(Path file) {
        return STILL_EXTENSIONS.contains(extension(file));
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String tsv(String value) {
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static String relative(Path corpus, Path file) {
        return corpus.relativize(file).toString().replace('\\', '/');
    }

    private static final class Expectation {
        final int current;
        final int after;
        final StaminaGaugeDetector.Direction direction;
        final int tolerance;

        Expectation(int current, int after, StaminaGaugeDetector.Direction direction,
                    int tolerance) {
            this.current = current;
            this.after = after;
            this.direction = direction;
            this.tolerance = tolerance;
        }

        String key() {
            return current + ">" + after + "/" + direction;
        }

        String failure(StaminaGaugeDetector.Result actual) {
            if (actual == null) return "expected " + key() + " but was null";
            if (actual.direction != direction) {
                return "expected " + key() + " but was " + StaminaRegressionCli.key(actual);
            }
            if (Math.abs(actual.current - current) > tolerance
                    || Math.abs(actual.after - after) > tolerance) {
                return "expected " + key() + " +/-" + tolerance
                        + " but was " + StaminaRegressionCli.key(actual);
            }
            return null;
        }
    }

    private static final class Metadata {
        final int width;
        final int height;
        final double framesPerSecond;

        Metadata(int width, int height, double framesPerSecond) {
            this.width = width;
            this.height = height;
            this.framesPerSecond = framesPerSecond;
        }
    }
}
