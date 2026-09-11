package helper.journey.starsavior;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

final class JourneyDatabaseManifest {
    static final int MANIFEST_SCHEMA = 2;
    private static final int DATABASE_SCHEMA = 5;
    private static final int MAX_DATABASE_BYTES = 8 * 1024 * 1024;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    final String language;
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
        this(manifestSchema, databaseSchema, contentSha256, contentLength, upstreamRevision,
                generatedAt, recordCount, choiceCount, minimumAppVersionCode, "ko-KR");
    }

    JourneyDatabaseManifest(int manifestSchema, int databaseSchema, String contentSha256,
            int contentLength, String upstreamRevision, String generatedAt, int recordCount,
            int choiceCount, int minimumAppVersionCode, String language) {
        this.language = language;
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
        if (root.optInt("manifestSchema") == 1 && root.has("language")
                && !"ko-KR".equals(root.optString("language"))) {
            throw new JSONException("Legacy manifest cannot declare another language.");
        }
        JourneyDatabaseManifest manifest = new JourneyDatabaseManifest(
                checkedInt(root, "manifestSchema"),
                checkedInt(root, "databaseSchema"),
                root.getString("contentSha256"),
                checkedInt(root, "contentLength"),
                root.getString("upstreamRevision"),
                root.getString("generatedAt"),
                checkedInt(root, "recordCount"),
                checkedInt(root, "choiceCount"),
                checkedInt(root, "minimumAppVersionCode"),
                root.optInt("manifestSchema") == 1 ? "ko-KR" : root.getString("language"));
        manifest.validate();
        return manifest;
    }

    void validate() throws JSONException {
        if (manifestSchema != MANIFEST_SCHEMA && manifestSchema != 1) {
            throw new JSONException("Unsupported database manifest schema.");
        }
        try { GameLanguage.require(language); }
        catch (IllegalArgumentException error) { throw new JSONException(error.getMessage()); }
        if (manifestSchema == 1 && !"ko-KR".equals(language)) throw new JSONException("Legacy manifest must be Korean.");
        if (databaseSchema < 1) throw new JSONException("Invalid database schema number.");
        if (!SHA256.matcher(contentSha256).matches()) {
            throw new JSONException("Invalid database release hash.");
        }
        if (contentLength < 1 || contentLength > MAX_DATABASE_BYTES) {
            throw new JSONException("Database size exceeds the permitted range.");
        }
        if (upstreamRevision.isEmpty()) throw new JSONException("Empty database source revision.");
        if (generatedAt.isEmpty()) throw new JSONException("Empty database generation time.");
        try {
            Instant.parse(generatedAt);
        } catch (RuntimeException error) {
            throw new JSONException("Invalid database generation time.");
        }
        if (recordCount < 1 || choiceCount < 2) {
            throw new JSONException("Invalid database release counts.");
        }
        if (minimumAppVersionCode < 1) {
            throw new JSONException("Invalid minimum app version.");
        }
    }

    boolean isCompatible(int appVersionCode) {
        return ((manifestSchema == MANIFEST_SCHEMA && databaseSchema == DATABASE_SCHEMA)
                || (manifestSchema == 1 && databaseSchema == 4))
                && minimumAppVersionCode <= appVersionCode;
    }

    boolean matches(JourneyModels.Data data) {
        if (data == null || !language.equals(data.language)) return false;
        if (!data.contentSha256.isEmpty()) return contentSha256.equals(data.contentSha256);
        return upstreamRevision.equals(data.upstreamRevision);
    }

    void verifyCandidate(JourneyModels.Data data) throws JSONException {
        if (!contentSha256.equals(data.contentSha256)) {
            throw new JSONException("Downloaded database SHA-256 differs from the manifest.");
        }
        if (contentLength != data.contentLength) {
            throw new JSONException("Downloaded database size differs from the manifest.");
        }
        if (!language.equals(data.language) || databaseSchema != data.schema
                || !upstreamRevision.equals(data.upstreamRevision)
                || !generatedAt.equals(data.generatedAt)
                || recordCount != data.recordCount
                || choiceCount != data.choiceCount) {
            throw new JSONException("Downloaded database metadata differs from the manifest.");
        }
    }

    String toJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("manifestSchema", manifestSchema);
        root.put("databaseSchema", databaseSchema);
        if (manifestSchema >= 2) root.put("language", language);
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
            throw new JSONException(name + " is out of range.");
        }
        return (int) value;
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }
}
