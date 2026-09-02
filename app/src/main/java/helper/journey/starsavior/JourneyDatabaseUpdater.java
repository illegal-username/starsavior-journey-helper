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
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 25_000;
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
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

            progress(listener, "선택지 DB를 받고 있습니다…");
            ensureNotInterrupted();
            String downloaded = download();
            JourneyModels.Data candidate = JourneyRepository.parse(downloaded);
            validateRemoteDatabase(current, candidate);
            if (sameDatabase(current, candidate)) return UpdateResult.current(current);

            progress(listener, "검증된 DB를 적용하고 있습니다…");
            ensureNotInterrupted();
            JourneyModels.Data installed = JourneyRepository.installUpdated(
                    context.getApplicationContext(), downloaded);
            return UpdateResult.installed(installed);
        } finally {
            UPDATING.set(false);
        }
    }

    static boolean sameDatabase(JourneyModels.Data current, JourneyModels.Data candidate) {
        return !current.upstreamRevision.isEmpty()
                && current.upstreamRevision.equals(candidate.upstreamRevision)
                && current.source.equals(candidate.source);
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
        int minimumRecords = Math.max(20, current.recordCount / 2);
        int minimumChoices = Math.max(40, current.choiceCount / 2);
        if (candidate.recordCount < minimumRecords || candidate.choiceCount < minimumChoices) {
            throw new JSONException("새 DB의 데이터가 비정상적으로 적어 적용하지 않았습니다.");
        }
    }

    private static String download() throws IOException {
        HttpURLConnection connection = open();
        try {
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("선택지 DB 다운로드 실패 (HTTP " + status + ")");
            }
            int declared = connection.getContentLength();
            if (declared > MAX_FILE_BYTES) throw new IOException("선택지 DB 응답이 너무 큽니다.");
            String contentType = connection.getContentType();
            if (contentType == null
                    || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                throw new IOException("선택지 DB가 JSON으로 응답하지 않았습니다.");
            }

            byte[] bytes;
            try (InputStream input = connection.getInputStream()) {
                bytes = readLimited(input);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(DATABASE_URL).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "StarSaviorJourneyHelper/"
                + BuildConfig.VERSION_NAME + " (Android; manual database update)");
        return connection;
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int fileBytes = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            ensureNotInterrupted();
            fileBytes += count;
            if (fileBytes > MAX_FILE_BYTES) {
                throw new IOException("선택지 DB 응답 크기가 안전 제한을 넘었습니다.");
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
