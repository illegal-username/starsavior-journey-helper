package helper.journey.starsavior;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;

final class JourneyUpdateStateStore {
    private static final String PREFERENCES = "journey_database_update_state";
    private static final String MANIFEST_JSON = "manifest_json";
    private static final String MANIFEST_ETAG = "manifest_etag";

    static final class State {
        final String manifestJson;
        final String manifestEtag;

        State(String manifestJson, String manifestEtag) {
            this.manifestJson = manifestJson;
            this.manifestEtag = manifestEtag;
        }

        JourneyDatabaseManifest manifest() {
            if (manifestJson.isEmpty()) return null;
            try {
                return JourneyDatabaseManifest.parse(manifestJson);
            } catch (JSONException ignored) {
                return null;
            }
        }
    }

    private JourneyUpdateStateStore() {}

    static State load(Context context) {
        SharedPreferences preferences = preferences(context);
        return new State(
                preferences.getString(MANIFEST_JSON, ""),
                preferences.getString(MANIFEST_ETAG, ""));
    }

    static void recordManifestSuccess(
            Context context, String manifestJson, String manifestEtag) {
        preferences(context).edit()
                .putString(MANIFEST_JSON, manifestJson)
                .putString(MANIFEST_ETAG, manifestEtag == null ? "" : manifestEtag)
                .apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
