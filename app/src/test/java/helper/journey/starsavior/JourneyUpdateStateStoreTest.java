package helper.journey.starsavior;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class JourneyUpdateStateStoreTest {
    @Test public void unmarkedOldCacheIsUntrustedRegardlessOfMinimumVersion() throws Exception {
        String json = DatabaseTestData.json(GameLanguage.KOREAN, "new", 20);
        for (int minimum : new int[] {1, 51, 999}) {
            assertNull(new JourneyUpdateStateStore.State(DatabaseTestData.manifest(json, minimum), "old-etag")
                    .manifest(GameLanguage.KOREAN));
        }
    }

    @Test public void verifiedMetadataIsBoundToLanguageAndEndpoint() throws Exception {
        for (GameLanguage language : GameLanguage.values()) {
            String meta = DatabaseTestData.manifest(DatabaseTestData.json(language, "new", 20), 51);
            JourneyUpdateStateStore.State state = JourneyUpdateStateStore.State.verified(meta, "etag", language);
            assertNotNull(state.manifest(language));
            GameLanguage other = language == GameLanguage.ENGLISH ? GameLanguage.JAPANESE : GameLanguage.ENGLISH;
            assertNull(state.manifest(other));
            assertNull(new JourneyUpdateStateStore.State(meta, "etag", state.contractVersion,
                    JourneyDatabaseUpdater.MANIFEST_URL).manifest(language));
        }
    }

    @Test public void corruptedCacheCannotGainTrustFromItsMarker() throws Exception {
        String meta = DatabaseTestData.manifest(DatabaseTestData.json(GameLanguage.KOREAN, "new", 20), 51);
        JourneyUpdateStateStore.State valid = JourneyUpdateStateStore.State.verified(meta, "etag", GameLanguage.KOREAN);
        for (String invalid : new String[] {"{broken", new JSONObject(meta).put("language", "en-US").toString(),
                new JSONObject(meta).put("manifestSchema", 1).put("databaseSchema", 4).toString()}) {
            assertNull(new JourneyUpdateStateStore.State(invalid, "etag", valid.contractVersion, valid.manifestUrl)
                    .manifest(GameLanguage.KOREAN));
        }
    }

    @Test public void futureMetadataIsCacheableButNeverCompatible() throws Exception {
        String meta = DatabaseTestData.manifest(DatabaseTestData.json(GameLanguage.KOREAN, "new", 20), BuildConfig.VERSION_CODE + 1);
        JourneyDatabaseManifest cached = JourneyUpdateStateStore.State.verified(meta, "future", GameLanguage.KOREAN)
                .manifest(GameLanguage.KOREAN);
        assertNotNull(cached); assertFalse(cached.isCompatible(BuildConfig.VERSION_CODE));
        assertThrows(JSONException.class, () -> JourneyUpdateStateStore.State.verified(meta, "etag", GameLanguage.ENGLISH));
    }
}
