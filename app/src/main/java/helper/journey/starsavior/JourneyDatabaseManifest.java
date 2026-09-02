package helper.journey.starsavior;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

final class JourneyDatabaseManifest {
    static final int MANIFEST_SCHEMA = 1;
    private static final int DATABASE_SCHEMA = 4;
    private static final int MAX_DATABASE_BYTES = 8 * 1024 * 1024;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    final int manifestSchema;
    final int databaseSchema;
    final String contentSha256;
    final int contentLength;
    final String upstreamRevision;
    final String generatedAt;
    final int recordCount;
    final int choiceCount;
    final int minimumAppVersionCode;

    JourneyDatabaseManifest(
            int manifestSchema,
            int databaseSchema,
            String contentSha256,
            int contentLength,
            String upstreamRevision,
            String generatedAt,
            int recordCount,
            int choiceCount,
            int minimumAppVersionCode) {
        this.manifestSchema = manifestSchema;
        this.databaseSchema = databaseSchema;
        this.contentSha256 = value(contentSha256).toLowerCase(Locale.ROOT);
        this.contentLength = contentLength;
        this.upstreamRevision = value(upstreamRevision);
        this.generatedAt = value(generatedAt);
        this.recordCount = recordCount;
        this.choiceCount = choiceCount;
        this.minimumAppVersionCode = minimumAppVersionCode;
    }

    static JourneyDatabaseManifest parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JourneyDatabaseManifest manifest = new JourneyDatabaseManifest(
                checkedInt(root, "manifestSchema"),
                checkedInt(root, "databaseSchema"),
                root.getString("contentSha256"),
                checkedInt(root, "contentLength"),
                root.getString("upstreamRevision"),
                root.getString("generatedAt"),
                checkedInt(root, "recordCount"),
                checkedInt(root, "choiceCount"),
                checkedInt(root, "minimumAppVersionCode"));
        manifest.validate();
        return manifest;
    }

    void validate() throws JSONException {
        if (manifestSchema != MANIFEST_SCHEMA) {
            throw new JSONException("지원하지 않는 DB 릴리스 정보 형식입니다.");
        }
        if (databaseSchema < 1) throw new JSONException("DB 형식 번호가 올바르지 않습니다.");
        if (!SHA256.matcher(contentSha256).matches()) {
            throw new JSONException("DB 릴리스 해시가 올바르지 않습니다.");
        }
        if (contentLength < 1 || contentLength > MAX_DATABASE_BYTES) {
            throw new JSONException("DB 릴리스 크기가 안전 범위를 벗어났습니다.");
        }
        if (upstreamRevision.isEmpty()) throw new JSONException("DB 원본 버전이 비어 있습니다.");
        if (generatedAt.isEmpty()) throw new JSONException("DB 생성 시각이 비어 있습니다.");
        try {
            Instant.parse(generatedAt);
        } catch (RuntimeException error) {
            throw new JSONException("DB 생성 시각이 올바르지 않습니다.");
        }
        if (recordCount < 1 || choiceCount < 2) {
            throw new JSONException("DB 릴리스 개수가 올바르지 않습니다.");
        }
        if (minimumAppVersionCode < 1) {
            throw new JSONException("최소 앱 버전이 올바르지 않습니다.");
        }
    }

    boolean isCompatible(int appVersionCode) {
        return databaseSchema == DATABASE_SCHEMA && minimumAppVersionCode <= appVersionCode;
    }

    boolean matches(JourneyModels.Data data) {
        if (data == null) return false;
        if (!data.contentSha256.isEmpty()) return contentSha256.equals(data.contentSha256);
        return upstreamRevision.equals(data.upstreamRevision);
    }

    void verifyCandidate(JourneyModels.Data data) throws JSONException {
        if (!contentSha256.equals(data.contentSha256)) {
            throw new JSONException("다운로드한 DB의 SHA-256이 릴리스 정보와 일치하지 않습니다.");
        }
        if (contentLength != data.contentLength) {
            throw new JSONException("다운로드한 DB의 크기가 릴리스 정보와 일치하지 않습니다.");
        }
        if (databaseSchema != data.schema
                || !upstreamRevision.equals(data.upstreamRevision)
                || !generatedAt.equals(data.generatedAt)
                || recordCount != data.recordCount
                || choiceCount != data.choiceCount) {
            throw new JSONException("다운로드한 DB의 메타데이터가 릴리스 정보와 일치하지 않습니다.");
        }
    }

    String toJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("manifestSchema", manifestSchema);
        root.put("databaseSchema", databaseSchema);
        root.put("contentSha256", contentSha256);
        root.put("contentLength", contentLength);
        root.put("upstreamRevision", upstreamRevision);
        root.put("generatedAt", generatedAt);
        root.put("recordCount", recordCount);
        root.put("choiceCount", choiceCount);
        root.put("minimumAppVersionCode", minimumAppVersionCode);
        return root.toString();
    }

    private static int checkedInt(JSONObject root, String name) throws JSONException {
        long value = root.getLong(name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new JSONException(name + " 값이 너무 큽니다.");
        }
        return (int) value;
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }
}
