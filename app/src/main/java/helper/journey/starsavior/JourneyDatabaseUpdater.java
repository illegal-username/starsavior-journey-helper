package helper.journey.starsavior;

import android.content.Context;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

    static final class CheckResult {
        final boolean available;
        final boolean incompatible;
        final boolean busy;
        final boolean networkChecked;
        final JourneyModels.Data current;
        final JourneyDatabaseManifest manifest;
        final String downloadedDatabase;

        private CheckResult(
                boolean available,
                boolean incompatible,
                boolean busy,
                boolean networkChecked,
                JourneyModels.Data current,
                JourneyDatabaseManifest manifest,
                String downloadedDatabase) {
            this.available = available;
            this.incompatible = incompatible;
            this.busy = busy;
            this.networkChecked = networkChecked;
            this.current = current;
            this.manifest = manifest;
            this.downloadedDatabase = downloadedDatabase;
        }

        static CheckResult busy(JourneyModels.Data current) {
            return new CheckResult(false, false, true, false, current, null, null);
        }

        static CheckResult classify(
                JourneyModels.Data current,
                JourneyDatabaseManifest manifest,
                boolean networkChecked,
                String downloadedDatabase) {
            boolean compatible = manifest.isCompatible(BuildConfig.VERSION_CODE);
            boolean available = !manifest.matches(current);
            return new CheckResult(
                    available && compatible,
                    available && !compatible,
                    false,
                    networkChecked,
                    current,
                    manifest,
                    downloadedDatabase);
        }

        static CheckResult current(JourneyModels.Data current, boolean networkChecked) {
            return new CheckResult(false, false, false, networkChecked, current, null, null);
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
            return new UpdateResult(false, false, true, data, R.string.update_requires_version,
                    manifest.minimumAppVersionCode);
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

    static CheckResult checkForUpdate(Context context, boolean force) throws Exception {
        Context application = context;
        JourneyModels.Data current = JourneyRepository.load(application);
        if (BuildConfig.BUNDLED_TEST_DATABASE) return CheckResult.current(current, false);
        if (!UPDATING.compareAndSet(false, true)) return CheckResult.busy(current);
        try {
            return checkLocked(application, current, force);
        } finally {
            UPDATING.set(false);
        }
    }

    static UpdateResult update(Context context, ProgressListener listener) throws Exception {
        if (!UPDATING.compareAndSet(false, true)) return UpdateResult.busy();
        Context application = context;
        try {
            JourneyModels.Data current = JourneyRepository.load(application);
            if (BuildConfig.BUNDLED_TEST_DATABASE) {
                return new UpdateResult(false, false, false, current, R.string.test_uses_bundled);
            }
            progress(listener, application.getString(R.string.checking_latest));
            CheckResult check = checkLocked(application, current, true);
            if (check.incompatible && check.manifest != null) {
                return UpdateResult.incompatible(current, check.manifest);
            }
            if (!check.available) return UpdateResult.current(current);

            progress(listener, application.getString(R.string.downloading_database));
            ensureNotInterrupted();
            String downloaded = check.downloadedDatabase;
            if (downloaded == null) {
                downloaded = requireDatabaseResponse(
                        request(GameLanguage.require(current.language).databaseUrl(), MAX_FILE_BYTES, "", "manual database update"));
            }
            JourneyModels.Data candidate = JourneyRepository.parse(downloaded);
            validateRemoteDatabase(current, candidate);
            if (check.manifest != null) check.manifest.verifyCandidate(candidate);
            if (sameDatabase(current, candidate)) return UpdateResult.current(current);

            progress(listener, application.getString(R.string.applying_database));
            ensureNotInterrupted();
            JourneyModels.Data installed = JourneyRepository.installUpdated(application, downloaded);
            return UpdateResult.installed(installed);
        } finally {
            UPDATING.set(false);
        }
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

    private static CheckResult checkLocked(
            Context application, JourneyModels.Data current, boolean force) throws Exception {
        JourneyUpdateStateStore.State state = JourneyUpdateStateStore.load(application);
        JourneyDatabaseManifest cachedManifest = state.manifest();
        HttpResponse response = request(
                GameLanguage.require(current.language).manifestUrl(),
                MAX_MANIFEST_BYTES,
                state.manifestEtag,
                force ? "manual database update check" : "automatic database update check");

        if (response.status == HttpURLConnection.HTTP_NOT_MODIFIED) {
            JourneyDatabaseManifest cached = cachedManifest;
            if (cached == null) {
                response = request(
                        GameLanguage.require(current.language).manifestUrl(),
                        MAX_MANIFEST_BYTES,
                        "",
                        force ? "manual database update check" : "automatic database update check");
            } else {
                validateManifestAgainstCurrent(current, cached);
                JourneyUpdateStateStore.recordManifestSuccess(
                        application,
                        state.manifestJson,
                        response.etag.isEmpty() ? state.manifestEtag : response.etag);
                return CheckResult.classify(current, cached, true, null);
            }
        }

        if (response.status == HttpURLConnection.HTTP_NOT_FOUND) {
            String downloaded = requireDatabaseResponse(request(
                    GameLanguage.require(current.language).databaseUrl(),
                    MAX_FILE_BYTES,
                    "",
                    force ? "manual database update fallback" : "automatic database update fallback"));
            JourneyModels.Data candidate = JourneyRepository.parse(downloaded);
            validateRemoteDatabase(current, candidate);
            JourneyDatabaseManifest synthetic = manifestFrom(candidate);
            JourneyUpdateStateStore.recordManifestSuccess(
                    application, synthetic.toJson(), "");
            return CheckResult.classify(current, synthetic, true, downloaded);
        }

        String manifestJson = requireJsonResponse(response, "Database manifest request");
        JourneyDatabaseManifest manifest = JourneyDatabaseManifest.parse(manifestJson);
        validateManifestAgainstCurrent(current, manifest);
        JourneyUpdateStateStore.recordManifestSuccess(
                application, manifestJson, response.etag);
        return CheckResult.classify(current, manifest, true, null);
    }

    private static JourneyDatabaseManifest manifestFrom(JourneyModels.Data data) throws JSONException {
        JourneyDatabaseManifest manifest = new JourneyDatabaseManifest(
                data.schema == 4 ? 1 : JourneyDatabaseManifest.MANIFEST_SCHEMA,
                data.schema,
                data.contentSha256,
                data.contentLength,
                data.upstreamRevision,
                data.generatedAt,
                data.recordCount,
                data.choiceCount,
                1, data.language);
        manifest.validate();
        return manifest;
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
        HttpURLConnection connection = open(url, etag, purpose);
        try {
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
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
            return new HttpResponse(
                    status, new String(bytes, StandardCharsets.UTF_8), responseEtag);
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
        return requireJsonResponse(response, "Choice database download");
    }

    private static String requireJsonResponse(HttpResponse response, String operation) throws IOException {
        if (response.status < 200 || response.status >= 300) {
            throw new IOException(operation + " failed (HTTP " + response.status + ")");
        }
        return response.body;
    }

    private static byte[] readLimited(InputStream input, int maximumBytes) throws IOException {
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
        if (Thread.currentThread().isInterrupted()) throw new IOException("Database update cancelled.");
    }

    private static void progress(ProgressListener listener, String message) {
        if (listener == null) return;
        try {
            listener.onProgress(message);
        } catch (RuntimeException ignored) {}
    }
}
