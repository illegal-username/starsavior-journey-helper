package helper.journey.starsavior;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Release gate for externally generated DB/metadata pairs (never committed).
 *
 * <p>Runs only when {@code -PstarJourney.candidateRoot=<dir>} is supplied. Each
 * {@code <dir>/<language>/[publish/]journey_choices{,.meta}.json} pair goes through
 * the real updater: metadata check, download, validation, file installation and
 * reload, for a fresh install and (with {@code starJourney.currentRoot}) over the
 * current production DB, followed by a cached 304 recheck.
 */
public class CandidateDatabaseGateTest {
    static final String CANDIDATE_ROOT = "starJourney.candidateRoot";
    static final String CURRENT_ROOT = "starJourney.currentRoot";
    static final String REPORT_PATH = "starJourney.reportPath";
    private static final String DB = "journey_choices.json";
    private static final String META = "journey_choices.meta.json";

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void candidatePairsInstallThroughTheRealUpdater() throws Exception {
        String candidateRoot = System.getProperty(CANDIDATE_ROOT, "");
        Assume.assumeFalse("No candidate supplied", candidateRoot.isEmpty());
        String currentRoot = System.getProperty(CURRENT_ROOT, "");
        JSONArray report = new JSONArray();
        List<String> failures = verifyAll(Path.of(candidateRoot),
                currentRoot.isEmpty() ? null : Path.of(currentRoot), report);
        writeReport(report, failures.isEmpty());
        if (!failures.isEmpty()) fail(String.join("\n", failures));
    }

    /** CI coverage of the gate itself, with synthetic pairs only. */
    @Test public void gateAcceptsValidPairsAndRejectsTamperedOrMissingOnes() throws Exception {
        Path candidate = temporary.newFolder("candidate").toPath();
        Path current = temporary.newFolder("current").toPath();
        for (GameLanguage language : GameLanguage.values()) {
            String next = DatabaseTestData.json(language, "next", 24);
            write(candidate.resolve(language.tag).resolve("publish"), next, DatabaseTestData.manifest(next, 51));
            String old = DatabaseTestData.json(language, "old", 22);
            write(current.resolve(language.tag), old, DatabaseTestData.manifest(old, 51));
        }
        assertEquals(List.of(), verifyAll(candidate, current, new JSONArray()));

        // A DB edited after its metadata was generated must be refused.
        Path korean = candidate.resolve("ko-KR").resolve("publish").resolve(DB);
        Files.write(korean, new String(Files.readAllBytes(korean), StandardCharsets.UTF_8)
                .replace("Synthetic reward next", "Synthetic reward edit").getBytes(StandardCharsets.UTF_8));
        Files.delete(candidate.resolve("vi-VN").resolve("publish").resolve(META));
        List<String> failures = verifyAll(candidate, current, new JSONArray());
        assertEquals(2, failures.size());
        assertTrue(failures.get(0), failures.get(0).startsWith("ko-KR: "));
        assertTrue(failures.get(1), failures.get(1).startsWith("vi-VN: candidate pair is missing"));
    }

    private List<String> verifyAll(Path candidateRoot, Path currentRoot, JSONArray report) throws Exception {
        List<String> failures = new ArrayList<>();
        for (GameLanguage language : GameLanguage.values()) {
            Pair candidate = Pair.find(candidateRoot, language);
            JSONObject row = new JSONObject().put("language", language.tag);
            report.put(row);
            if (candidate == null) {
                row.put("status", "failed").put("error", "candidate pair is missing");
                failures.add(language.tag + ": candidate pair is missing");
                continue;
            }
            Pair current = currentRoot == null ? null : Pair.find(currentRoot, language);
            try {
                row.put("freshInstall", verify(language, candidate, null));
                if (current != null) row.put("overCurrent", verify(language, candidate, current));
                row.put("status", "passed");
            } catch (Exception | AssertionError error) {
                row.put("status", "failed").put("error", error.toString());
                failures.add(language.tag + ": " + error);
            }
        }
        return failures;
    }

    private static void write(Path folder, String database, String metadata) throws IOException {
        Files.createDirectories(folder);
        Files.write(folder.resolve(DB), database.getBytes(StandardCharsets.UTF_8));
        Files.write(folder.resolve(META), metadata.getBytes(StandardCharsets.UTF_8));
    }

    /** Returns the updater outcome; throws on any refusal the app would show the user. */
    private String verify(GameLanguage language, Pair candidate, Pair current) throws Exception {
        Backend backend = new Backend(temporary.newFolder());
        if (current != null) backend.seed(language, current.database);
        JourneyDatabaseUpdater.Session session = new JourneyDatabaseUpdater.Session(language, backend, false);
        Transport transport = new Transport(language, candidate);

        JourneyDatabaseUpdater.UpdateResult result = JourneyDatabaseUpdater.update(session, transport, null);
        assertFalse(language.tag + " requires a newer app (minimumAppVersionCode)", result.incompatible);
        JourneyModels.Data candidateData = JourneyRepository.parse(candidate.database);
        JourneyModels.Data installed = JourneyRepository.loadDownloaded(backend.files, language);
        assertNotNull(language.tag + ": nothing readable after update", installed);
        assertEquals(language.tag + ": installed bytes differ from the candidate",
                candidateData.contentSha256, installed.contentSha256);
        if (current == null) assertTrue(language.tag + ": fresh install did not apply", result.changed);
        // The matcher must accept every installed record set.
        new JourneyMatcher(installed.events, language);

        // A later automatic check served from the verified cache must report current.
        transport.notModified = true;
        JourneyDatabaseUpdater.CheckResult check = JourneyDatabaseUpdater.checkForUpdate(session, false, transport);
        assertFalse(language.tag + ": cached recheck offered another update", check.available);
        assertFalse(language.tag + ": cached recheck reported incompatibility", check.incompatible);
        return result.changed ? "installed" : "unchanged";
    }

    private static void writeReport(JSONArray rows, boolean passed) throws Exception {
        String path = System.getProperty(REPORT_PATH, "");
        if (path.isEmpty()) return;
        JSONObject document = new JSONObject().put("passed", passed)
                .put("appVersionCode", BuildConfig.VERSION_CODE).put("languages", rows);
        Path target = Path.of(path);
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.write(target, document.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    static final class Pair {
        final String database, metadata;
        Pair(String database, String metadata) { this.database = database; this.metadata = metadata; }

        static Pair find(Path root, GameLanguage language) throws IOException {
            for (Path folder : new Path[] {root.resolve(language.tag).resolve("publish"), root.resolve(language.tag)}) {
                Path db = folder.resolve(DB), meta = folder.resolve(META);
                if (Files.isRegularFile(db) && Files.isRegularFile(meta)) {
                    return new Pair(JourneyDatabaseUpdater.decodeUtf8(Files.readAllBytes(db)),
                            JourneyDatabaseUpdater.decodeUtf8(Files.readAllBytes(meta)));
                }
            }
            return null;
        }
    }

    /** Serves the candidate exactly as Pages would: metadata, DB and 304 for the cached ETag. */
    static final class Transport implements JourneyDatabaseUpdater.HttpTransport {
        final GameLanguage language;
        final Pair pair;
        boolean notModified;
        Transport(GameLanguage language, Pair pair) { this.language = language; this.pair = pair; }

        public JourneyDatabaseUpdater.HttpResponse request(String url, int limit, String etag, String purpose)
                throws IOException {
            String body;
            if (url.equals(language.manifestUrl())) {
                if (notModified && "\"candidate-meta\"".equals(etag)) {
                    return new JourneyDatabaseUpdater.HttpResponse(304, "", etag);
                }
                body = pair.metadata;
            } else if (url.equals(language.databaseUrl())) {
                body = pair.database;
            } else {
                throw new IOException("Unexpected request: " + url);
            }
            if (body.getBytes(StandardCharsets.UTF_8).length > limit) throw new IOException("Response exceeds app limit");
            return new JourneyDatabaseUpdater.HttpResponse(200, body,
                    url.equals(language.manifestUrl()) ? "\"candidate-meta\"" : "\"candidate-db\"");
        }
    }

    static final class Backend implements JourneyDatabaseUpdater.Backend {
        final File files;
        final Map<GameLanguage, JourneyUpdateStateStore.State> states = new EnumMap<>(GameLanguage.class);
        Backend(File files) { this.files = files; }

        public JourneyModels.Data load(GameLanguage language) throws Exception {
            JourneyModels.Data downloaded = JourneyRepository.loadDownloaded(files, language);
            if (downloaded != null) return downloaded;
            Path example = Path.of("src/main/assets", language.tag, "journey_choices.example.json");
            if (!Files.exists(example)) example = Path.of("app").resolve(example);
            return JourneyRepository.parseValidated(new String(Files.readAllBytes(example), StandardCharsets.UTF_8),
                    language).withOrigin(JourneyModels.DatabaseOrigin.EXAMPLE);
        }
        public JourneyModels.Data install(GameLanguage language, String json) throws Exception {
            JourneyDatabaseFileStore.install(JourneyDatabaseFileStore.directory(files, language), json, language,
                    new JourneyDatabaseFileStore.FileOperations() {
                        @Override void syncDirectory(File directory) { /* Android-only fsync adapter. */ }
                    });
            return JourneyRepository.loadDownloaded(files, language);
        }
        public JourneyUpdateStateStore.State state(GameLanguage language) {
            return states.getOrDefault(language, new JourneyUpdateStateStore.State("", ""));
        }
        public void cache(GameLanguage language, String json, String etag) throws Exception {
            states.put(language, JourneyUpdateStateStore.State.verified(json, etag, language));
        }
        void seed(GameLanguage language, String json) throws IOException {
            File directory = JourneyDatabaseFileStore.directory(files, language);
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot seed " + directory);
            Files.write(JourneyDatabaseFileStore.updated(directory).toPath(), json.getBytes(StandardCharsets.UTF_8));
        }
    }
}
