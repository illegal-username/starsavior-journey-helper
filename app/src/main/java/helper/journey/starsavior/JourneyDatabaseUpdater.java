package helper.journey.starsavior;

import android.content.Context;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

final class JourneyDatabaseUpdater {
    private static final String BASE_URL = "https://star-savior-arcana-db.pages.dev/data/";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 25_000;
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 24 * 1024 * 1024;
    private static final AtomicBoolean UPDATING = new AtomicBoolean(false);

    interface ProgressListener {
        void onProgress(String message);
    }

    static final class UpdateResult {
        final boolean changed;
        final boolean busy;
        final JourneyModels.Data data;
        final String message;

        private UpdateResult(boolean changed, boolean busy, JourneyModels.Data data, String message) {
            this.changed = changed;
            this.busy = busy;
            this.data = data;
            this.message = message;
        }

        static UpdateResult busy() {
            return new UpdateResult(false, true, null, "이미 DB를 업데이트하고 있습니다.");
        }

        static UpdateResult current(JourneyModels.Data data) {
            return new UpdateResult(false, false, data,
                    String.format(Locale.KOREA, "이미 최신 DB입니다. (레코드 %,d개 · 선택지 %,d개)",
                            data.recordCount, data.choiceCount));
        }

        static UpdateResult installed(JourneyModels.Data data) {
            return new UpdateResult(true, false, data,
                    String.format(Locale.KOREA, "최신 DB를 적용했습니다. (레코드 %,d개 · 선택지 %,d개)",
                            data.recordCount, data.choiceCount));
        }
    }

    private JourneyDatabaseUpdater() {}

    static boolean isUpdating() {
        return UPDATING.get();
    }

    static UpdateResult update(Context context, ProgressListener listener) throws Exception {
        if (!UPDATING.compareAndSet(false, true)) return UpdateResult.busy();
        try {
            JourneyModels.Data current = JourneyRepository.load(context.getApplicationContext());
            progress(listener, "최신 버전을 확인하고 있습니다…");

            Map<String, String> headEtags = fetchHeadEtags();
            if (headEtags != null) {
                String headRevision = revisionFromValues("etag", headEtags);
                if (!current.upstreamRevision.isEmpty() && headRevision.equals(current.upstreamRevision)) {
                    return UpdateResult.current(current);
                }
            }

            Map<String, String> documents = new LinkedHashMap<>();
            Map<String, String> getEtags = new LinkedHashMap<>();
            int[] totalBytes = {0};
            for (int index = 0; index < JourneyDataTransformer.FILES.size(); index++) {
                ensureNotInterrupted();
                String file = JourneyDataTransformer.FILES.get(index);
                progress(listener, String.format(Locale.KOREA, "원본 데이터 받는 중… (%d/%d)",
                        index + 1, JourneyDataTransformer.FILES.size()));
                Download download = download(file, totalBytes);
                documents.put(file, download.body);
                if (!download.etag.isEmpty()) getEtags.put(file, download.etag);
            }

            String revision = getEtags.size() == JourneyDataTransformer.FILES.size()
                    ? revisionFromValues("etag", getEtags)
                    : revisionFromValues("content", documents);
            if (!current.upstreamRevision.isEmpty() && revision.equals(current.upstreamRevision)) {
                return UpdateResult.current(current);
            }

            progress(listener, "선택지와 보상을 정리하고 있습니다…");
            ensureNotInterrupted();
            String normalized = JourneyDataTransformer.transform(documents, revision, Instant.now().toString());
            JourneyModels.Data candidate = JourneyRepository.parse(normalized);
            JourneyRepository.validate(candidate);
            validateSize(current, candidate);

            progress(listener, "검증된 DB를 적용하고 있습니다…");
            ensureNotInterrupted();
            JourneyModels.Data installed = JourneyRepository.installUpdated(context.getApplicationContext(), normalized);
            return UpdateResult.installed(installed);
        } finally {
            UPDATING.set(false);
        }
    }

    private static void validateSize(JourneyModels.Data current, JourneyModels.Data candidate) throws JSONException {
        int minimumRecords = Math.max(20, current.recordCount / 2);
        int minimumChoices = Math.max(40, current.choiceCount / 2);
        if (candidate.recordCount < minimumRecords || candidate.choiceCount < minimumChoices) {
            throw new JSONException("새 DB의 데이터가 비정상적으로 적어 적용하지 않았습니다.");
        }
    }

    private static Map<String, String> fetchHeadEtags() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String file : JourneyDataTransformer.FILES) {
            HttpURLConnection connection = null;
            try {
                connection = open(file);
                connection.setRequestMethod("HEAD");
                int status = connection.getResponseCode();
                String etag = cleanEtag(connection.getHeaderField("ETag"));
                if (status < 200 || status >= 300 || etag.isEmpty()) return null;
                result.put(file, etag);
            } catch (IOException ignored) {
                return null;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return result;
    }

    private static Download download(String file, int[] totalBytes) throws IOException {
        HttpURLConnection connection = open(file);
        try {
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException(file + " 다운로드 실패 (HTTP " + status + ")");
            }
            int declared = connection.getContentLength();
            if (declared > MAX_FILE_BYTES) throw new IOException(file + " 응답이 너무 큽니다.");

            byte[] bytes;
            try (InputStream input = connection.getInputStream()) {
                bytes = readLimited(input, file, totalBytes);
            }
            return new Download(new String(bytes, StandardCharsets.UTF_8),
                    cleanEtag(connection.getHeaderField("ETag")));
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String file) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE_URL + file).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "StarSaviorJourneyHelper/2.1.0 (Android; manual database update)");
        return connection;
    }

    private static byte[] readLimited(InputStream input, String file, int[] totalBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int fileBytes = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            ensureNotInterrupted();
            fileBytes += count;
            totalBytes[0] += count;
            if (fileBytes > MAX_FILE_BYTES || totalBytes[0] > MAX_TOTAL_BYTES) {
                throw new IOException(file + " 응답 크기가 안전 제한을 넘었습니다.");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void ensureNotInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("DB 업데이트가 취소되었습니다.");
    }

    private static String revisionFromValues(String kind, Map<String, String> values) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(kind.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        for (String file : JourneyDataTransformer.FILES) {
            digest.update(file.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(values.get(file).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return kind + "-sha256:" + hex;
    }

    private static String cleanEtag(String value) {
        return value == null ? "" : value.trim();
    }

    private static void progress(ProgressListener listener, String message) {
        if (listener == null) return;
        try {
            listener.onProgress(message);
        } catch (RuntimeException ignored) {}
    }

    private static final class Download {
        final String body;
        final String etag;

        Download(String body, String etag) {
            this.body = body;
            this.etag = etag;
        }
    }
}
