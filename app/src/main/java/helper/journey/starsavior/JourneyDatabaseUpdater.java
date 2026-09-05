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
        final String message;

        private UpdateResult(
                boolean changed,
                boolean busy,
                boolean incompatible,
                JourneyModels.Data data,
                String message) {
            this.changed = changed;
            this.busy = busy;
            this.incompatible = incompatible;
            this.data = data;
            this.message = message;
        }

        static UpdateResult busy() {
            return new UpdateResult(false, true, false, null, "이미 DB를 업데이트하고 있습니다.");
        }

        static UpdateResult current(JourneyModels.Data data) {
            return new UpdateResult(false, false, false, data,
                    String.format(Locale.KOREA, "이미 최신 DB입니다. (레코드 %,d개 · 선택지 %,d개)",
                            data.recordCount, data.choiceCount));
        }

        static UpdateResult incompatible(JourneyModels.Data data, JourneyDatabaseManifest manifest) {
            return new UpdateResult(false, false, true, data,
                    String.format(Locale.KOREA,
                            "새 DB는 더 최신 앱이 필요합니다. 현재 앱을 업데이트한 뒤 다시 확인해 주세요. "
                                    + "(필요 versionCode %d 이상)",
                            manifest.minimumAppVersionCode));
        }

        static UpdateResult installed(JourneyModels.Data data) {
            return new UpdateResult(true, false, false, data,
                    String.format(Locale.KOREA, "최신 DB를 적용했습니다. (레코드 %,d개 · 선택지 %,d개)",
                            data.recordCount, data.choiceCount));
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
        Context application = context.getApplicationContext();
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
        Context application = context.getApplicationContext();
        try {
            JourneyModels.Data current = JourneyRepository.load(application);
            if (BuildConfig.BUNDLED_TEST_DATABASE) {
                return new UpdateResult(false, false, false, current, "테스트 APK는 내장 DB를 사용합니다.");
            }
            progress(listener, "최신 버전을 확인하고 있습니다…");
            CheckResult check = checkLocked(application, current, true);
            if (check.incompatible && check.manifest != null) {
                return UpdateResult.incompatible(current, check.manifest);
            }
            if (!check.available) return UpdateResult.current(current);

            progress(listener, "선택지 DB를 받고 있습니다…");
            ensureNotInterrupted();
            String downloaded = check.downloadedDatabase;
            if (downloaded == null) {
                downloaded = requireDatabaseResponse(
                        request(DATABASE_URL, MAX_FILE_BYTES, "", "manual database update"));
            }
            JourneyModels.Data candidate = JourneyRepository.parse(downloaded);
            validateRemoteDatabase(current, candidate);
            if (check.manifest != null) check.manifest.verifyCandidate(candidate);
            if (sameDatabase(current, candidate)) return UpdateResult.current(current);

            progress(listener, "검증된 DB를 적용하고 있습니다…");
            ensureNotInterrupted();
            JourneyModels.Data installed = JourneyRepository.installUpdated(application, downloaded);
            return UpdateResult.installed(installed);
        } finally {
            UPDATING.set(false);
        }
    }

    static boolean sameDatabase(JourneyModels.Data current, JourneyModels.Data candidate) {
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
        if (!DATABASE_URL.equals(candidate.source)) {
            throw new JSONException("DB 출처 주소가 일치하지 않습니다.");
        }
        if (candidate.upstreamRevision.trim().isEmpty()) {
            throw new JSONException("DB 원본 버전이 비어 있습니다.");
        }
        validateCounts(current, candidate.recordCount, candidate.choiceCount);
    }

    static void validateManifestAgainstCurrent(
            JourneyModels.Data current, JourneyDatabaseManifest manifest) throws JSONException {
        manifest.validate();
        validateCounts(current, manifest.recordCount, manifest.choiceCount);
    }

    private static CheckResult checkLocked(
            Context application, JourneyModels.Data current, boolean force) throws Exception {
        JourneyUpdateStateStore.State state = JourneyUpdateStateStore.load(application);
        JourneyDatabaseManifest cachedManifest = state.manifest();
        HttpResponse response = request(
                MANIFEST_URL,
                MAX_MANIFEST_BYTES,
                state.manifestEtag,
                force ? "manual database update check" : "automatic database update check");

        if (response.status == HttpURLConnection.HTTP_NOT_MODIFIED) {
            JourneyDatabaseManifest cached = cachedManifest;
            if (cached == null) {
                response = request(
                        MANIFEST_URL,
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
                    DATABASE_URL,
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

        String manifestJson = requireJsonResponse(response, "DB 릴리스 정보 확인");
        JourneyDatabaseManifest manifest = JourneyDatabaseManifest.parse(manifestJson);
        validateManifestAgainstCurrent(current, manifest);
        JourneyUpdateStateStore.recordManifestSuccess(
                application, manifestJson, response.etag);
        return CheckResult.classify(current, manifest, true, null);
    }

    private static JourneyDatabaseManifest manifestFrom(JourneyModels.Data data) throws JSONException {
        JourneyDatabaseManifest manifest = new JourneyDatabaseManifest(
                JourneyDatabaseManifest.MANIFEST_SCHEMA,
                data.schema,
                data.contentSha256,
                data.contentLength,
                data.upstreamRevision,
                data.generatedAt,
                data.recordCount,
                data.choiceCount,
                1);
        manifest.validate();
        return manifest;
    }

    private static void validateCounts(
            JourneyModels.Data current, int candidateRecords, int candidateChoices) throws JSONException {
        int minimumRecords = Math.max(20, current.recordCount / 2);
        int minimumChoices = Math.max(40, current.choiceCount / 2);
        if (candidateRecords < minimumRecords || candidateChoices < minimumChoices) {
            throw new JSONException("새 DB의 데이터가 비정상적으로 적어 적용하지 않았습니다.");
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
                throw new IOException("데이터 서버 요청 실패 (HTTP " + status + ")");
            }
            long declared = connection.getContentLengthLong();
            if (declared > maximumBytes) throw new IOException("데이터 서버 응답이 너무 큽니다.");
            String contentType = connection.getContentType();
            if (contentType == null
                    || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                throw new IOException("데이터 서버가 JSON으로 응답하지 않았습니다.");
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
        return requireJsonResponse(response, "선택지 DB 다운로드");
    }

    private static String requireJsonResponse(HttpResponse response, String operation) throws IOException {
        if (response.status < 200 || response.status >= 300) {
            throw new IOException(operation + " 실패 (HTTP " + response.status + ")");
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
                throw new IOException("데이터 서버 응답 크기가 안전 제한을 넘었습니다.");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void ensureNotInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("DB 업데이트가 취소되었습니다.");
    }

    private static void progress(ProgressListener listener, String message) {
        if (listener == null) return;
        try {
            listener.onProgress(message);
        } catch (RuntimeException ignored) {}
    }
}
