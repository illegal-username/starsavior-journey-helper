package helper.journey.starsavior;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Exercises the real updater, parser, cache policy and file store without a network or Android runtime. */
public class JourneyDatabaseUpdateFlowTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    @After public void clearInterruption() { Thread.interrupted(); assertFalse(JourneyDatabaseUpdater.isUpdating()); }

    static class LocalOperations extends JourneyDatabaseFileStore.FileOperations {
        @Override void syncDirectory(File directory) throws IOException { /* Android adapter has separate platform behavior. */ }
    }

    final class Backend implements JourneyDatabaseUpdater.Backend {
        final File files = temporary.newFolder();
        final Map<GameLanguage, JourneyUpdateStateStore.State> states = new EnumMap<>(GameLanguage.class);
        final List<GameLanguage> installedLanguages = new ArrayList<>();
        String failure = "";
        boolean interruptAfterCommit;
        JourneyDatabaseFileStore.FileOperations operations = new LocalOperations();
        Backend() throws IOException {}
        void failAt(String stage) throws IOException {
            if (stage.equals(failure)) throw new IOException("Injected backend " + stage);
        }
        public JourneyModels.Data load(GameLanguage language) throws Exception {
            failAt("load");
            JourneyModels.Data downloaded = JourneyRepository.loadDownloaded(files, language);
            if (downloaded != null) return downloaded;
            String example = new JSONObject(DatabaseTestData.json(language, "example", 3))
                    .put("source", "public-example").toString();
            return JourneyRepository.parseValidated(example, language);
        }
        public JourneyModels.Data install(GameLanguage language, String json) throws Exception {
            failAt("install");
            JourneyDatabaseFileStore.install(JourneyDatabaseFileStore.directory(files, language), json, language, operations);
            installedLanguages.add(language);
            JourneyModels.Data installed = JourneyRepository.loadDownloaded(files, language);
            if (interruptAfterCommit) Thread.currentThread().interrupt();
            return installed;
        }
        public JourneyUpdateStateStore.State state(GameLanguage language) throws Exception {
            failAt("state");
            return states.getOrDefault(language, new JourneyUpdateStateStore.State("", ""));
        }
        public void cache(GameLanguage language, String json, String etag) throws Exception {
            failAt("cache");
            states.put(language, JourneyUpdateStateStore.State.verified(json, etag, language));
        }
        void seed(GameLanguage language, String json) throws Exception {
            File directory = JourneyDatabaseFileStore.directory(files, language);
            assertTrue(directory.isDirectory() || directory.mkdirs());
            Files.write(JourneyDatabaseFileStore.updated(directory).toPath(), json.getBytes(StandardCharsets.UTF_8));
        }
    }

    interface RequestHook { void run(int request) throws IOException; }
    static final class Http implements JourneyDatabaseUpdater.HttpTransport {
        final Deque<Object> responses = new ArrayDeque<>();
        final List<String> urls = new ArrayList<>();
        final List<String> etags = new ArrayList<>();
        RequestHook hook = count -> {};
        Http(Object... values) { responses.addAll(Arrays.asList(values)); }
        public JourneyDatabaseUpdater.HttpResponse request(String url, int limit, String etag, String purpose) throws IOException {
            urls.add(url); etags.add(etag); hook.run(urls.size());
            assertFalse("Unexpected HTTP request: " + url, responses.isEmpty());
            Object response = responses.remove();
            if (response instanceof IOException) throw (IOException) response;
            return (JourneyDatabaseUpdater.HttpResponse) response;
        }
    }

    final class Fixture {
        final Backend backend = new Backend();
        final GameLanguage language;
        final GameLanguage other;
        final JourneyDatabaseUpdater.Session session;
        final String old;
        final String next;
        final String meta;
        final String oldHash;
        final String otherHash;
        Fixture(GameLanguage language, boolean example) throws Exception {
            this.language = language;
            other = language == GameLanguage.ENGLISH ? GameLanguage.JAPANESE : GameLanguage.ENGLISH;
            old = DatabaseTestData.json(language, "old", 20);
            next = DatabaseTestData.json(language, "new", 20);
            meta = DatabaseTestData.manifest(next, 51);
            if (!example) backend.seed(language, old);
            backend.seed(other, DatabaseTestData.json(other, "other", 20));
            oldHash = backend.load(language).contentSha256;
            otherHash = backend.load(other).contentSha256;
            session = new JourneyDatabaseUpdater.Session(language, backend, false);
        }
        Fixture() throws Exception { this(GameLanguage.KOREAN, false); }
        Http success() { return new Http(ok(meta, "new-meta"), ok(next, "body")); }
        void unchanged() throws Exception {
            assertEquals(oldHash, backend.load(language).contentSha256);
            assertEquals(otherHash, backend.load(other).contentSha256);
            assertTrue(backend.installedLanguages.isEmpty());
            assertFalse(JourneyDatabaseUpdater.isUpdating());
        }
        void retry() throws Exception {
            assertTrue(JourneyDatabaseUpdater.update(session, success(), null).changed);
            assertEquals(JourneyRepository.parse(next).contentSha256, backend.load(language).contentSha256);
            assertEquals(otherHash, backend.load(other).contentSha256);
            assertFalse(JourneyDatabaseUpdater.isUpdating());
        }
    }

    @Test public void normalAndFirstRealDatabaseUpdatesWorkInEveryLanguage() throws Exception {
        for (GameLanguage language : GameLanguage.values()) {
            for (boolean example : new boolean[] {false, true}) {
                Fixture fixture = new Fixture(language, example);
                assertEquals(example, JourneyRepository.isExampleDatabase(fixture.backend.load(language)));
                Http http = fixture.success();
                JourneyDatabaseUpdater.UpdateResult result = JourneyDatabaseUpdater.update(fixture.session, http, null);
                assertTrue(result.changed);
                assertEquals(Arrays.asList(language.manifestUrl(), language.databaseUrl()), http.urls);
                assertEquals(Arrays.asList("", ""), http.etags);
                assertEquals(JourneyRepository.parse(fixture.next).contentSha256, result.data.contentSha256);
                assertNotNull(fixture.backend.state(language).manifest(language));
                assertEquals(fixture.otherHash, fixture.backend.load(fixture.other).contentSha256);
                assertFalse(JourneyDatabaseUpdater.isUpdating());
            }
        }
    }

    @Test public void multibyteJsonIsInstalledWithItsExactManifestHashAndByteLength() throws Exception {
        Fixture fixture = new Fixture();
        JSONObject database = new JSONObject(fixture.next);
        database.getJSONArray("records").getJSONObject(0).getJSONArray("choices").getJSONObject(0)
                .put("text", "합성 선택 café 中文");
        String json = database.toString() + "\n";
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length > json.length());
        String meta = DatabaseTestData.manifest(json, 51);
        JourneyDatabaseUpdater.UpdateResult result = JourneyDatabaseUpdater.update(fixture.session,
                new Http(ok(meta, "unicode"), ok(json, "")), null);
        assertTrue(result.changed);
        assertEquals(json.getBytes(StandardCharsets.UTF_8).length, result.data.contentLength);
        assertEquals(JourneyRepository.parse(json).contentSha256, result.data.contentSha256);
    }

    @Test public void checkOnlyCachesMetadataAndDoesNotInstall() throws Exception {
        Fixture fixture = new Fixture();
        Http http = new Http(ok(fixture.meta, "checked"));
        JourneyDatabaseUpdater.CheckResult check = JourneyDatabaseUpdater.checkForUpdate(fixture.session, false, http);
        assertTrue(check.available); assertTrue(check.networkChecked);
        assertEquals(1, http.urls.size());
        fixture.unchanged();
        assertEquals("checked", fixture.backend.state(fixture.language).manifestEtag);
    }

    @Test public void conditional304UsesOnlyValidatedCacheAndPreservesOrRefreshesEtag() throws Exception {
        for (boolean alreadyCurrent : new boolean[] {false, true}) {
            Fixture fixture = new Fixture();
            String meta = alreadyCurrent ? DatabaseTestData.manifest(fixture.old, 51) : fixture.meta;
            fixture.backend.cache(fixture.language, meta, "old-etag");
            Http http = alreadyCurrent ? new Http(response(304, "", ""))
                    : new Http(response(304, "", "new-etag"), ok(fixture.next, ""));
            JourneyDatabaseUpdater.UpdateResult result = JourneyDatabaseUpdater.update(fixture.session, http, null);
            assertEquals(!alreadyCurrent, result.changed);
            assertEquals("old-etag", http.etags.get(0));
            assertEquals(alreadyCurrent ? "old-etag" : "new-etag", fixture.backend.state(fixture.language).manifestEtag);
            assertEquals(alreadyCurrent ? 1 : 2, http.urls.size());
        }
    }

    @Test public void missingDamagedWrongLanguageAndSyntheticCachesRequireFreshMetadata() throws Exception {
        Fixture template = new Fixture();
        List<JourneyUpdateStateStore.State> unusable = Arrays.asList(
                new JourneyUpdateStateStore.State("", "orphan-etag"),
                new JourneyUpdateStateStore.State("{broken", "bad", 1, template.language.manifestUrl()),
                JourneyUpdateStateStore.State.verified(DatabaseTestData.manifest(
                        DatabaseTestData.json(GameLanguage.ENGLISH, "other", 20), 51), "wrong-language", GameLanguage.ENGLISH),
                new JourneyUpdateStateStore.State(DatabaseTestData.manifest(template.next, 1), "old-synthetic"),
                new JourneyUpdateStateStore.State(template.meta, "unknown-version", 999, template.language.manifestUrl()),
                new JourneyUpdateStateStore.State(template.meta, "wrong-url", 1, JourneyDatabaseUpdater.MANIFEST_URL));
        for (JourneyUpdateStateStore.State state : unusable) {
            Fixture fixture = new Fixture();
            fixture.backend.states.put(fixture.language, state);
            Http http = new Http(response(304, "", ""), ok(fixture.meta, "fresh"), ok(fixture.next, ""));
            assertTrue(JourneyDatabaseUpdater.update(fixture.session, http, null).changed);
            assertEquals(Arrays.asList("", "", ""), http.etags);
            assertEquals(3, http.urls.size());
        }
    }

    @Test public void repeated304WithoutCacheFailsAndReleasesLock() throws Exception {
        Fixture fixture = new Fixture();
        Http http = new Http(response(304, "", ""), response(304, "", ""));
        assertThrows(IOException.class, () -> JourneyDatabaseUpdater.update(fixture.session, http, null));
        fixture.unchanged(); assertEquals(2, http.urls.size()); fixture.retry();
    }

    @Test public void metadata404NeverDownloadsBodyEvenWithAValidCacheOrExample() throws Exception {
        for (boolean example : new boolean[] {false, true}) {
            for (boolean cached : new boolean[] {false, true}) {
                Fixture fixture = new Fixture(GameLanguage.KOREAN, example);
                if (cached) fixture.backend.cache(fixture.language, fixture.meta, "existing");
                Http http = new Http(response(404, "", "bad-etag"), ok(fixture.next, ""));
                Exception error = assertThrows(JourneyDatabaseUpdater.MetadataUnavailableException.class,
                        () -> JourneyDatabaseUpdater.update(fixture.session, http, null));
                assertEquals(R.string.update_metadata_unavailable, JourneyDatabaseUpdater.errorMessageId(error));
                assertEquals(1, http.urls.size()); assertEquals(1, http.responses.size());
                fixture.unchanged(); fixture.retry();
            }
        }
    }

    @Test public void metadataFailuresDoNotReplaceValidatedCacheOrDownloadedDatabase() throws Exception {
        Fixture template = new Fixture();
        List<Object> responses = Arrays.asList(response(500, "", "bad"), new SocketTimeoutException("connect timeout"),
                new SocketTimeoutException("read timeout"), new IOException("Server did not return JSON"),
                ok("{truncated", "bad"), ok(change(template.meta, "manifestSchema", 3), "bad"),
                ok(change(template.meta, "language", "en-US"), "bad"),
                ok(change(template.meta, "databaseSchema", 4), "bad"),
                ok(change(template.meta, "contentSha256", "not-a-hash"), "bad"),
                ok(change(template.meta, "generatedAt", "invalid-time"), "bad"),
                ok(change(template.meta, "recordCount", 1), "bad"),
                ok(" ".repeat(16 * 1024 + 1), "bad"));
        for (Object response : responses) {
            Fixture fixture = new Fixture();
            fixture.backend.cache(fixture.language, fixture.meta, "preserved");
            Http http = new Http(response);
            assertThrows(Exception.class, () -> JourneyDatabaseUpdater.update(fixture.session, http, null));
            assertEquals("preserved", fixture.backend.state(fixture.language).manifestEtag);
            fixture.unchanged(); fixture.retry();
        }
    }

    @Test public void databaseTransportAndIntegrityFailuresPreserveCurrentAndAllowRetry() throws Exception {
        Fixture template = new Fixture();
        List<Object[]> cases = Arrays.asList(
                new Object[] {template.meta, response(500, "", "")},
                new Object[] {template.meta, new SocketTimeoutException("body timeout")},
                new Object[] {template.meta, ok("{truncated", "")},
                new Object[] {template.meta, ok(DatabaseTestData.json(GameLanguage.ENGLISH, "new", 20), "")},
                new Object[] {change(template.meta, "contentSha256", "0".repeat(64)), ok(template.next, "")},
                new Object[] {change(template.meta, "contentLength", template.next.getBytes(StandardCharsets.UTF_8).length + 1), ok(template.next, "")},
                new Object[] {change(template.meta, "recordCount", 21), ok(template.next, "")},
                new Object[] {change(template.meta, "choiceCount", 41), ok(template.next, "")},
                new Object[] {change(template.meta, "upstreamRevision", "different"), ok(template.next, "")},
                new Object[] {change(template.meta, "generatedAt", "2026-09-13T00:00:00Z"), ok(template.next, "")},
                new Object[] {template.meta, ok(change(template.next, "source", "https://example.invalid/db.json"), "")});
        for (Object[] test : cases) {
            Fixture fixture = new Fixture();
            Http http = new Http(ok((String) test[0], "valid-meta"), test[1]);
            assertThrows(Exception.class, () -> JourneyDatabaseUpdater.update(fixture.session, http, null));
            fixture.unchanged(); assertEquals(2, http.urls.size()); fixture.retry();
        }
    }

    @Test public void validMetadataCanBeReusedAfterFailedBodyDownload() throws Exception {
        Fixture fixture = new Fixture();
        assertThrows(IOException.class, () -> JourneyDatabaseUpdater.update(fixture.session,
                new Http(ok(fixture.meta, "retry-etag"), new IOException("body interrupted")), null));
        fixture.unchanged();
        Http retry = new Http(response(304, "", ""), ok(fixture.next, ""));
        assertTrue(JourneyDatabaseUpdater.update(fixture.session, retry, null).changed);
        assertEquals("retry-etag", retry.etags.get(0));
    }

    @Test public void futureSchemaAndMinimumVersionBlockDownloadEvenForMatchingHashOr304() throws Exception {
        for (boolean cached : new boolean[] {false, true}) {
            for (boolean sameHash : new boolean[] {false, true}) {
                for (boolean futureSchema : new boolean[] {false, true}) {
                    Fixture fixture = new Fixture();
                    String meta = DatabaseTestData.manifest(sameHash ? fixture.old : fixture.next,
                            futureSchema ? 51 : BuildConfig.VERSION_CODE + 1);
                    if (futureSchema) meta = change(meta, "databaseSchema", 6);
                    if (cached) fixture.backend.cache(fixture.language, meta, "future");
                    Http http = new Http(cached ? response(304, "", "") : ok(meta, "future"));
                    JourneyDatabaseUpdater.UpdateResult result = JourneyDatabaseUpdater.update(fixture.session, http, null);
                    assertTrue(result.incompatible); assertFalse(result.changed);
                    fixture.unchanged(); assertEquals(1, http.urls.size());
                }
            }
        }
    }

    @Test public void validServerMetadataMayDeclareMinimumOneButOldCacheDoesNotAuthorizeIt() throws Exception {
        Fixture fixture = new Fixture();
        String meta = DatabaseTestData.manifest(fixture.next, 1);
        fixture.backend.states.put(fixture.language, new JourneyUpdateStateStore.State(meta, "untrusted"));
        Http http = new Http(ok(meta, "server"), ok(fixture.next, ""));
        assertTrue(JourneyDatabaseUpdater.update(fixture.session, http, null).changed);
        assertEquals("", http.etags.get(0));
    }

    @Test public void loadStateCacheAndInstallExceptionsReleaseTheSameGlobalLock() throws Exception {
        for (String failure : Arrays.asList("load", "state", "cache", "install")) {
            Fixture fixture = new Fixture();
            fixture.backend.failure = failure;
            assertThrows(IOException.class, () -> JourneyDatabaseUpdater.update(fixture.session, fixture.success(), null));
            fixture.backend.failure = "";
            fixture.unchanged(); fixture.retry();
        }
        for (String failure : Arrays.asList("load", "state", "cache")) {
            Fixture fixture = new Fixture();
            fixture.backend.failure = failure;
            assertThrows(IOException.class, () -> JourneyDatabaseUpdater.checkForUpdate(fixture.session, false, fixture.success()));
            fixture.backend.failure = "";
            fixture.unchanged(); fixture.retry();
        }
    }

    @Test public void actualFileReplacementFailurePreservesDownloadedDatabaseAndReleasesLock() throws Exception {
        Fixture fixture = new Fixture();
        fixture.backend.operations = new LocalOperations() {
            @Override boolean rename(File from, File to) throws IOException {
                return !from.getName().equals(JourneyDatabaseFileStore.TEMP_NAME) && super.rename(from, to);
            }
        };
        assertThrows(IOException.class, () -> JourneyDatabaseUpdater.update(fixture.session, fixture.success(), null));
        fixture.unchanged(); fixture.backend.operations = new LocalOperations(); fixture.retry();
    }

    @Test public void cancellationBeforeRequestAfterResponsesAndBeforeInstallPreservesCurrent() throws Exception {
        for (int boundary = 0; boundary < 4; boundary++) {
            Fixture fixture = new Fixture();
            final int selectedBoundary = boundary;
            Http http = fixture.success();
            http.hook = count -> { if (selectedBoundary == count) Thread.currentThread().interrupt(); };
            if (boundary == 0) Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> JourneyDatabaseUpdater.update(fixture.session, http,
                        id -> { if (selectedBoundary == 3 && id == R.string.applying_database) Thread.currentThread().interrupt(); }));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            fixture.unchanged(); fixture.retry();
        }
    }

    @Test public void lateCancellationDoesNotRelabelACommittedDatabaseAsUninstalled() throws Exception {
        Fixture fixture = new Fixture();
        fixture.backend.interruptAfterCommit = true;
        try {
            JourneyDatabaseUpdater.UpdateResult result = JourneyDatabaseUpdater.update(fixture.session, fixture.success(), null);
            assertTrue(result.changed); assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(JourneyRepository.parse(fixture.next).contentSha256, fixture.backend.load(fixture.language).contentSha256);
        } finally { Thread.interrupted(); }
    }

    @Test public void changingLanguageDuringUpdateCannotRedirectCacheOrInstallation() throws Exception {
        Fixture fixture = new Fixture();
        GameLanguage[] selected = {fixture.language};
        Http http = fixture.success();
        http.hook = count -> selected[0] = fixture.other;
        assertTrue(JourneyDatabaseUpdater.update(fixture.session, http, null).changed);
        assertEquals(fixture.other, selected[0]);
        assertEquals(Arrays.asList(fixture.language), fixture.backend.installedLanguages);
        assertEquals(Arrays.asList(fixture.language.manifestUrl(), fixture.language.databaseUrl()), http.urls);
        assertFalse(fixture.backend.states.containsKey(fixture.other));
        assertEquals(fixture.otherHash, fixture.backend.load(fixture.other).contentSha256);
    }

    @Test public void concurrentCheckAndUpdateReturnBusyUntilOwnerReleasesLock() throws Exception {
        Fixture fixture = new Fixture();
        Http http = fixture.success();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        http.hook = count -> {
            if (count != 1) return;
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test barrier timed out"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
        };
        FutureTask<JourneyDatabaseUpdater.UpdateResult> task = new FutureTask<>(
                () -> JourneyDatabaseUpdater.update(fixture.session, http, null));
        Thread worker = new Thread(task, "db-update-test");
        worker.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(JourneyDatabaseUpdater.isUpdating());
            assertTrue(JourneyDatabaseUpdater.update(fixture.session, new Http(), null).busy);
            assertTrue(JourneyDatabaseUpdater.checkForUpdate(fixture.session, false, new Http()).busy);
            release.countDown(); assertTrue(task.get(5, TimeUnit.SECONDS).changed);
        } finally { release.countDown(); worker.join(5_000); }
        assertFalse(worker.isAlive()); assertFalse(JourneyDatabaseUpdater.isUpdating());
    }

    @Test public void internalBundledDatabaseSkipsAllRemoteRequests() throws Exception {
        Fixture fixture = new Fixture();
        JourneyDatabaseUpdater.Session bundled = new JourneyDatabaseUpdater.Session(fixture.language, fixture.backend, true);
        assertFalse(JourneyDatabaseUpdater.update(bundled, new Http(), null).changed);
        assertFalse(JourneyDatabaseUpdater.checkForUpdate(bundled, true, new Http()).networkChecked);
        fixture.unchanged();
    }

    @Test public void responseBytesRejectMalformedUtf8OversizeAndInterruptedReads() throws Exception {
        String text = "한글 漢字 café\n";
        assertEquals(text, JourneyDatabaseUpdater.decodeUtf8(text.getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> JourneyDatabaseUpdater.decodeUtf8(new byte[] {(byte) 0xc3, 0x28}));
        assertThrows(IOException.class, () -> JourneyDatabaseUpdater.readLimited(new ByteArrayInputStream(new byte[9]), 8));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> JourneyDatabaseUpdater.readLimited(new ByteArrayInputStream(new byte[8]), 8));
        } finally { Thread.interrupted(); }
        assertFalse(JourneyDatabaseUpdater.isUpdating());
    }

    static JourneyDatabaseUpdater.HttpResponse ok(String body, String etag) { return response(200, body, etag); }
    static JourneyDatabaseUpdater.HttpResponse response(int status, String body, String etag) {
        return new JourneyDatabaseUpdater.HttpResponse(status, body, etag);
    }
    static String change(String json, String field, Object value) throws Exception {
        return new JSONObject(json).put(field, value).toString();
    }
}
