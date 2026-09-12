package helper.journey.starsavior;

import android.content.Context;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

final class JourneyDatabaseUpdater {
    static final String DATABASE_URL =
            "https://starsavior-journey-data.pages.dev/journey_choices.json";
    static final String MANIFEST_URL =
            "https://starsavior-journey-data.pages.dev/journey_choices.meta.json";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 25_000;
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 16 * 1024;
    private static final AtomicBoolean UPDATING = new AtomicBoolean(false);

    interface ProgressListener {
        void onProgress(String message);
    }

    interface HttpTransport {
        HttpResponse request(String url, int maximumBytes, String etag, String purpose) throws IOException;
    }

    interface Backend {
        JourneyModels.Data load(GameLanguage language) throws Exception;
        JourneyModels.Data install(GameLanguage language, String json) throws Exception;
        JourneyUpdateStateStore.State state(GameLanguage language) throws Exception;
        void cache(GameLanguage language, String json, String etag) throws Exception;
    }

    interface StageListener { void onProgress(int messageId); }

    static final class Session {
        final GameLanguage language;
        final Backend backend;
        final boolean bundled;
        Session(GameLanguage language, Backend backend, boolean bundled) {
            this.language = language; this.backend = backend; this.bundled = bundled;
        }
    }

    static final class MetadataUnavailableException extends IOException {
        MetadataUnavailableException() { super("The required v5 database metadata is unavailable (HTTP 404)."); }
    }

    static int errorMessageId(Exception error) {
        if (error instanceof MetadataUnavailableException) return R.string.update_metadata_unavailable;
        if (error instanceof JSONException) return R.string.update_validation_failed;
        return 0;
    }

    static final class CheckResult {
        final boolean available;
        final boolean incompatible;
        final boolean busy;
        final boolean networkChecked;
        final JourneyModels.Data current;
        final JourneyDatabaseManifest manifest;

        private CheckResult(
                boolean available,
                boolean incompatible,
                boolean busy,
                boolean networkChecked,
                JourneyModels.Data current,
                JourneyDatabaseManifest manifest) {
            this.available = available;
            this.incompatible = incompatible;
            this.busy = busy;
            this.networkChecked = networkChecked;
            this.current = current;
            this.manifest = manifest;
        }

        static CheckResult busy(JourneyModels.Data current) {
            return new CheckResult(false, false, true, false, current, null);
        }

        static CheckResult classify(
                JourneyModels.Data current,
                JourneyDatabaseManifest manifest,
                boolean networkChecked) {
            boolean compatible = manifest.isCompatible(BuildConfig.VERSION_CODE);
            boolean available = !manifest.matches(current);
            return new CheckResult(
                    available && compatible,
                    !compatible,
                    false,
                    networkChecked,
                    current,
                    manifest);
        }

        static CheckResult current(JourneyModels.Data current, boolean networkChecked) {
            return new CheckResult(false, false, false, networkChecked, current, null);
        }
    }

    static final class UpdateResult {
        final boolean changed;
        final boolean busy;
        final boolean incompatible;
        final JourneyModels.Data data;
        private final int messageId;
        private final Object[] arguments;

        private UpdateResult(boolean changed, boolean busy, boolean incompatible,
                JourneyModels.Data data, int messageId, Object... arguments) {
            this.changed = changed;
            this.busy = busy;
            this.incompatible = incompatible;
            this.data = data;
            this.messageId = messageId;
            this.arguments = arguments;
        }

        String message(Context context) { return context.getString(messageId, arguments); }

        static UpdateResult busy() {
            return new UpdateResult(false, true, false, null, R.string.update_already_busy);
        }
        static UpdateResult current(JourneyModels.Data data) {
            return new UpdateResult(false, false, false, data, R.string.update_current,
                    data.recordCount, data.choiceCount);
        }
        static UpdateResult incompatible(JourneyModels.Data data, JourneyDatabaseManifest manifest) {
            return manifest.minimumAppVersionCode > BuildConfig.VERSION_CODE
                    ? new UpdateResult(false, false, true, data, R.string.update_requires_version, manifest.minimumAppVersionCode)
                    : new UpdateResult(false, false, true, data, R.string.update_schema_unsupported);
        }
        static UpdateResult installed(JourneyModels.Data data) {
            return new UpdateResult(true, false, false, data, R.string.update_installed,
                    data.recordCount, data.choiceCount);
        }
    }

    static final class HttpResponse {
        final int status;
        final String body;
        final String etag;

        HttpResponse(int status, String body, String etag) {
            this.status = status;
            this.body = body;
            this.etag = etag == null ? "" : etag;
        }
    }

    private JourneyDatabaseUpdater() {}

    static boolean isUpdating() {
        return UPDATING.get();
    }

    private static Session session(Context context) {
        GameLanguage language = AppLanguage.of(context);
        Backend backend = new Backend() {
            public JourneyModels.Data load(GameLanguage selected) throws Exception {
                return JourneyRepository.load(context, selected);
            }
            public JourneyModels.Data install(GameLanguage selected, String json) throws Exception {
                return JourneyRepository.installUpdated(context, json, selected);
            }
            public JourneyUpdateStateStore.State state(GameLanguage selected) {
                return JourneyUpdateStateStore.load(context, selected);
            }
            public void cache(GameLanguage selected, String json, String etag) throws Exception {
                JourneyUpdateStateStore.recordManifestSuccess(context, selected, json, etag);
            }
        };
        return new Session(language, backend, BuildConfig.BUNDLED_TEST_DATABASE);
    }

    static CheckResult checkForUpdate(Context context, boolean force) throws Exception {
        return checkForUpdate(session(context), force, JourneyDatabaseUpdater::request);
    }

    static CheckResult checkForUpdate(Session session, boolean force, HttpTransport transport) throws Exception {
        if (!UPDATING.compareAndSet(false, true)) return CheckResult.busy(null);
        try {
            ensureNotInterrupted();
            JourneyModels.Data current = session.backend.load(session.language);
            JourneyRepository.requireLanguage(current, session.language);
            if (session.bundled) return CheckResult.current(current, false);
            return checkLocked(session, current, force, transport);
        } finally {
            UPDATING.set(false);
        }
    }

    static UpdateResult update(Context context, ProgressListener listener) throws Exception {
        return update(session(context), JourneyDatabaseUpdater::request,
                id -> progress(listener, context.getString(id)));
    }

    static UpdateResult update(Session session, HttpTransport transport, StageListener listener) throws Exception {
        if (!UPDATING.compareAndSet(false, true)) return UpdateResult.busy();
        try {
            ensureNotInterrupted();
            JourneyModels.Data current = session.backend.load(session.language);
            JourneyRepository.requireLanguage(current, session.language);
            if (session.bundled) return new UpdateResult(false, false, false, current, R.string.test_uses_bundled);
            stage(listener, R.string.checking_latest);
            CheckResult check = checkLocked(session, current, true, transport);
            if (check.incompatible) return UpdateResult.incompatible(current, check.manifest);
            if (!check.available) return UpdateResult.current(current);

            stage(listener, R.string.downloading_database);
            ensureNotInterrupted();
            String downloaded = requireDatabaseResponse(transport.request(
                    session.language.databaseUrl(), MAX_FILE_BYTES, "", "manual database update"));
            ensureNotInterrupted();
            JourneyModels.Data candidate = JourneyRepository.parse(downloaded);
            validateRemoteDatabase(current, candidate);
            if (candidate.schema != 5) throw new JSONException("A v5 update requires a schema 5 database.");
            check.manifest.verifyCandidate(candidate);
            if (sameDatabase(current, candidate)) return UpdateResult.current(current);

            stage(listener, R.string.applying_database);
            ensureNotInterrupted();
            JourneyModels.Data installed = session.backend.install(session.language, downloaded);
            // File installation has committed; a late interrupt must not relabel it as uninstalled.
            return UpdateResult.installed(installed);
        } finally {
            UPDATING.set(false);
        }
    }

    private static void stage(StageListener listener, int messageId) {
        if (listener == null) return;
        try { listener.onProgress(messageId); } catch (RuntimeException ignored) {}
    }

    static boolean sameDatabase(JourneyModels.Data current, JourneyModels.Data candidate) {
        if (!current.language.equals(candidate.language)) return false;
        if (!current.source.equals(candidate.source)) return false;
        if (!current.contentSha256.isEmpty() && !candidate.contentSha256.isEmpty()) {
            return current.contentSha256.equals(candidate.contentSha256);
        }
        return !current.upstreamRevision.isEmpty()
                && current.upstreamRevision.equals(candidate.upstreamRevision);
    }

    static void validateRemoteDatabase(JourneyModels.Data current, JourneyModels.Data candidate)
            throws JSONException {
        JourneyRepository.validate(candidate);
        JourneyRepository.requireLanguage(candidate, GameLanguage.require(current.language));
        String expectedSource = candidate.schema == 4 ? DATABASE_URL
                : GameLanguage.require(current.language).databaseUrl();
        if (!expectedSource.equals(candidate.source)) {
            throw new JSONException("Database source URL mismatch.");
        }
        if (candidate.upstreamRevision.trim().isEmpty()) {
            throw new JSONException("Empty database source revision.");
        }
        validateCounts(current, candidate.recordCount, candidate.choiceCount);
    }

    static void validateManifestAgainstCurrent(
            JourneyModels.Data current, JourneyDatabaseManifest manifest) throws JSONException {
        manifest.validate();
        if (!current.language.equals(manifest.language)) throw new JSONException("Manifest language mismatch.");
        validateCounts(current, manifest.recordCount, manifest.choiceCount);
    }

    private static CheckResult checkLocked(Session session, JourneyModels.Data current,
            boolean force, HttpTransport transport) throws Exception {
        JourneyUpdateStateStore.State state = session.backend.state(session.language);
        JourneyDatabaseManifest cached = state.manifest(session.language);
        if (cached != null) {
            try { validateManifestAgainstCurrent(current, cached); }
            catch (JSONException invalidForCurrent) { cached = null; }
        }
        String purpose = force ? "manual database update check" : "automatic database update check";
        ensureNotInterrupted();
        HttpResponse response = transport.request(session.language.manifestUrl(), MAX_MANIFEST_BYTES,
                cached == null ? "" : state.manifestEtag, purpose);
        ensureNotInterrupted();
        if (response.status == HttpURLConnection.HTTP_NOT_MODIFIED) {
            if (cached != null) {
                session.backend.cache(session.language, state.manifestJson,
                        response.etag.isEmpty() ? state.manifestEtag : response.etag);
                return CheckResult.classify(current, cached, true);
            }
            // Never authorize an update from an absent, untrusted or wrong-language 304 cache.
            response = transport.request(session.language.manifestUrl(), MAX_MANIFEST_BYTES, "", purpose);
            ensureNotInterrupted();
        }
        if (response.status == HttpURLConnection.HTTP_NOT_FOUND) throw new MetadataUnavailableException();
        String json = requireJsonResponse(response, "Database manifest request", MAX_MANIFEST_BYTES);
        JourneyDatabaseManifest manifest = JourneyDatabaseManifest.parse(json);
        manifest.validateV5Contract(session.language);
        validateManifestAgainstCurrent(current, manifest);
        ensureNotInterrupted();
        session.backend.cache(session.language, json, response.etag);
        return CheckResult.classify(current, manifest, true);
    }

    private static void validateCounts(
            JourneyModels.Data current, int candidateRecords, int candidateChoices) throws JSONException {
        int minimumRecords = Math.max(20, current.recordCount / 2);
        int minimumChoices = Math.max(40, current.choiceCount / 2);
        if (candidateRecords < minimumRecords || candidateChoices < minimumChoices) {
            throw new JSONException("The new database contains too few records; installation refused.");
        }
    }

    static HttpResponse request(
            String url, int maximumBytes, String etag, String purpose) throws IOException {
        ensureNotInterrupted();
        HttpURLConnection connection = open(url, etag, purpose);
        try {
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            ensureNotInterrupted();
            String responseEtag = connection.getHeaderField("ETag");
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED
                    || status == HttpURLConnection.HTTP_NOT_FOUND) {
                return new HttpResponse(status, "", responseEtag);
            }
            if (status < 200 || status >= 300) {
                throw new IOException("Database request failed (HTTP " + status + ")");
            }
            long declared = connection.getContentLengthLong();
            if (declared > maximumBytes) throw new IOException("Database response is too large.");
            String contentType = connection.getContentType();
            if (contentType == null
                    || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                throw new IOException("The database server did not return JSON.");
            }

            byte[] bytes;
            try (InputStream input = connection.getInputStream()) {
                bytes = readLimited(input, maximumBytes);
            }
            if (declared >= 0 && declared != bytes.length) throw new IOException("Incomplete database response.");
            ensureNotInterrupted();
            return new HttpResponse(status, decodeUtf8(bytes), responseEtag);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String etag, String purpose) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Cache-Control", "no-cache");
        if (etag != null && !etag.isEmpty()) connection.setRequestProperty("If-None-Match", etag);
        connection.setRequestProperty("User-Agent", "StarSaviorJourneyHelper/"
                + BuildConfig.VERSION_NAME + " (Android; " + purpose + ")");
        return connection;
    }

    private static String requireDatabaseResponse(HttpResponse response) throws IOException {
        return requireJsonResponse(response, "Choice database download", MAX_FILE_BYTES);
    }

    private static String requireJsonResponse(HttpResponse response, String operation, int maximumBytes) throws IOException {
        if (response.status < 200 || response.status >= 300) {
            throw new IOException(operation + " failed (HTTP " + response.status + ")");
        }
        if (response.body.getBytes(StandardCharsets.UTF_8).length > maximumBytes) throw new IOException("Database response is too large.");
        return response.body;
    }

    static String decodeUtf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    static byte[] readLimited(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int fileBytes = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            ensureNotInterrupted();
            fileBytes += count;
            if (fileBytes > maximumBytes) {
                throw new IOException("Database response exceeds the size limit.");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void ensureNotInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Database update cancelled.");
    }

    private static void progress(ProgressListener listener, String message) {
        if (listener == null) return;
        try {
            listener.onProgress(message);
        } catch (RuntimeException ignored) {}
    }
}
