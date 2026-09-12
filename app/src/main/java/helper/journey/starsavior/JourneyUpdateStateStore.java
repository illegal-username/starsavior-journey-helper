package helper.journey.starsavior;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONException;

final class JourneyUpdateStateStore {
    private static final String PREFERENCES = "journey_database_update_state";
    private static final String MANIFEST_JSON = "manifest_json";
    private static final String MANIFEST_ETAG = "manifest_etag";
    private static final String CONTRACT_VERSION = "metadata_contract_version";
    private static final String MANIFEST_URL = "manifest_url";
    private static final int VERIFIED_METADATA_CONTRACT = 1;

    static final class State {
        final String manifestJson;
        final String manifestEtag;
        final int contractVersion;
        final String manifestUrl;

        // Old cache entries can contain synthetic metadata: no inferred trust from minimum version.
        State(String json, String etag) { this(json, etag, 0, ""); }
        State(String json, String etag, int version, String url) {
            manifestJson = json == null ? "" : json;
            manifestEtag = etag == null ? "" : etag;
            contractVersion = version;
            manifestUrl = url == null ? "" : url;
        }

        static State verified(String json, String etag, GameLanguage language) throws JSONException {
            JourneyDatabaseManifest manifest = JourneyDatabaseManifest.parse(json);
            manifest.validateV5Contract(language);
            return new State(json, etag, VERIFIED_METADATA_CONTRACT, language.manifestUrl());
        }

        JourneyDatabaseManifest manifest(GameLanguage language) {
            if (contractVersion != VERIFIED_METADATA_CONTRACT || !language.manifestUrl().equals(manifestUrl)
                    || manifestJson.isEmpty()) return null;
            try {
                JourneyDatabaseManifest manifest = JourneyDatabaseManifest.parse(manifestJson);
                manifest.validateV5Contract(language);
                return manifest;
            } catch (JSONException invalid) {
                return null;
            }
        }
    }

    private JourneyUpdateStateStore() {}

    static State load(Context context, GameLanguage language) {
        SharedPreferences preferences = preferences(context, language);
        return new State(preferences.getString(MANIFEST_JSON, ""), preferences.getString(MANIFEST_ETAG, ""),
                preferences.getInt(CONTRACT_VERSION, 0), preferences.getString(MANIFEST_URL, ""));
    }

    static void recordManifestSuccess(Context context, GameLanguage language, String json, String etag)
            throws JSONException {
        State state = State.verified(json, etag, language);
        preferences(context, language).edit()
                .putString(MANIFEST_JSON, state.manifestJson)
                .putString(MANIFEST_ETAG, state.manifestEtag)
                .putInt(CONTRACT_VERSION, state.contractVersion)
                .putString(MANIFEST_URL, state.manifestUrl)
                .apply();
    }

    private static SharedPreferences preferences(Context context, GameLanguage language) {
        return context.getApplicationContext().getSharedPreferences(
                PREFERENCES + "_v5_" + language.tag, Context.MODE_PRIVATE);
    }
}
