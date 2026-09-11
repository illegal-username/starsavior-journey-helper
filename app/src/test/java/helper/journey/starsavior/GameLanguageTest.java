package helper.journey.starsavior;

import org.junit.Test;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class GameLanguageTest {
    @Test public void resolvesSystemLanguageAndChineseScriptBeforeRegion() {
        assertEquals(GameLanguage.KOREAN, language("ko-US"));
        assertEquals(GameLanguage.ENGLISH, language("en-GB"));
        assertEquals(GameLanguage.FRENCH, language("fr-CA"));
        assertEquals(GameLanguage.GERMAN, language("de-AT"));
        assertEquals(GameLanguage.SPANISH, language("es-MX"));
        assertEquals(GameLanguage.PORTUGUESE, language("pt-PT"));
        assertEquals(GameLanguage.INDONESIAN, language("id-ID"));
        assertEquals(GameLanguage.INDONESIAN, language("in-ID"));
        assertEquals(GameLanguage.VIETNAMESE, language("vi-VN"));
        assertEquals(GameLanguage.ENGLISH, language("th-TH"));
        assertEquals(GameLanguage.ENGLISH, language("hi-IN"));
        assertEquals(GameLanguage.ENGLISH, language("it-IT"));
        assertEquals(GameLanguage.JAPANESE, language("ja"));
        assertEquals(GameLanguage.TRADITIONAL_CHINESE, language("zh-HK"));
        assertEquals(GameLanguage.TRADITIONAL_CHINESE, language("zh-Hant-CN"));
        assertEquals(GameLanguage.SIMPLIFIED_CHINESE, language("zh-Hans-TW"));
        assertEquals(GameLanguage.SIMPLIFIED_CHINESE, language("zh-SG"));
        assertThrows(IllegalArgumentException.class, () -> GameLanguage.require("../../ko-KR"));
    }

    @Test public void preservesMixedLatinFullWidthAndEastAsianWords() {
        assertEquals("go出発１２３".replace("１２３", "123"), JourneyMatcher.normalize("ＧＯ！ 出発 １２３"));
        assertEquals("go出發123", JourneyMatcher.normalize("GO! 出發 123"));
        assertEquals("go출발123", JourneyMatcher.normalize("GO! 출발 123"));
        assertNotEquals(JourneyMatcher.normalize("去森林"), JourneyMatcher.normalize("去海邊"));
        assertTrue(GameLanguage.JAPANESE.isArcanaHeader("アルカナ イベント"));
        assertTrue(GameLanguage.TRADITIONAL_CHINESE.isArcanaHeader("阿爾克那事件"));
        assertFalse(GameLanguage.KOREAN.isArcanaHeader("Arcana Event"));
    }

    @Test public void matchesEveryLanguageExampleAndRejectsWrongLanguageDatabase() throws Exception {
        for (GameLanguage language : GameLanguage.values()) {
            JourneyModels.Data data = example(language);
            JourneyRepository.validate(data);
            assertEquals(language.tag, data.language);
            assertEquals(5, data.schema);
            JourneyModels.Event event = data.events.get(0);
            JourneyMatcher matcher = new JourneyMatcher(data.events, language);
            JourneyModels.Match match = matcher.match(List.of(language.eventHeader, event.name),
                    List.of(event.choices.get(0).text, event.choices.get(1).text));
            assertTrue(language.tag, match.isConfident());
            assertSame(event, match.event);
            GameLanguage other = language == GameLanguage.KOREAN ? GameLanguage.ENGLISH : GameLanguage.KOREAN;
            assertThrows(JSONException.class, () -> JourneyRepository.requireLanguage(data, other));
            JSONObject json = new JSONObject(new String(Files.readAllBytes(asset(language)), java.nio.charset.StandardCharsets.UTF_8));
            JSONObject legacy = new JSONObject(json.toString()).put("schema", 4).put("language", "en-US");
            assertThrows(JSONException.class, () -> JourneyRepository.parse(legacy.toString()));
            json.remove("language");
            assertThrows(JSONException.class, () -> JourneyRepository.parse(json.toString()));
        }
    }

    @Test public void handlesFourLineEnglishChoiceWithoutAcceptingOneSharedChoice() {
        JourneyModels.Choice first = new JourneyModels.Choice("Follow the winding path across the silent valley and enter the distant forest", List.of());
        JourneyModels.Choice second = new JourneyModels.Choice("Wait safely near the village", List.of());
        JourneyModels.Event event = new JourneyModels.Event("A quiet morning", "", List.of(first, second));
        JourneyMatcher matcher = new JourneyMatcher(List.of(event), GameLanguage.ENGLISH);
        List<String> lines = List.of("Follow the winding path", "across the silent valley", "and enter the", "distant forest", second.text);
        assertTrue(matcher.match(List.of(event.name), lines).isConfident());
        assertFalse(matcher.match(List.of(event.name), List.of(second.text)).isConfident());
    }

    @Test public void languageIdentityIsCheckedEvenWithTheSameHashAndRevision() throws Exception {
        JourneyModels.Data data = example(GameLanguage.JAPANESE);
        JourneyDatabaseManifest manifest = new JourneyDatabaseManifest(2, 5, data.contentSha256,
                data.contentLength, data.upstreamRevision, "2026-09-11T00:00:00Z", data.recordCount,
                data.choiceCount, 49, "en-US");
        assertFalse(manifest.matches(data));
        assertThrows(JSONException.class, () -> manifest.verifyCandidate(data));
        assertThrows(JSONException.class, () -> JourneyDatabaseUpdater.validateManifestAgainstCurrent(data, manifest));
        assertFalse(manifest.isCompatible(48));
        assertTrue(manifest.isCompatible(49));
        File files = new File("synthetic-files");
        assertNotEquals(JourneyDatabaseFileStore.directory(files, GameLanguage.JAPANESE),
                JourneyDatabaseFileStore.directory(files, GameLanguage.KOREAN));
    }

    @Test public void slowOldLanguageLoadCannotReplaceTheNewLanguage() throws Exception {
        JourneyMatcherStore store = new JourneyMatcherStore();
        store.selectLanguage(GameLanguage.KOREAN);
        JourneyModels.Data korean = example(GameLanguage.KOREAN);
        JourneyModels.Data english = example(GameLanguage.ENGLISH);
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread old = new Thread(() -> {
            try { store.reload(() -> { loading.countDown(); finish.await(); return korean; }); }
            catch (Throwable error) { failure.set(error); }
        });
        old.start();
        assertTrue(loading.await(5, java.util.concurrent.TimeUnit.SECONDS));
        store.selectLanguage(GameLanguage.ENGLISH);
        assertNull(store.current());
        store.reload(() -> english);
        finish.countDown();
        old.join(5000);
        assertFalse(old.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertSame(english, store.currentData());
    }

    @Test
    public void savedSelectionOverridesEverySystemLanguage() {
        for (GameLanguage system : GameLanguage.values()) {
            for (GameLanguage selected : GameLanguage.values()) {
                assertSame(selected, GameLanguage.forApp(system, selected.tag));
            }
        }
    }

    @Test
    public void systemOptionAndInvalidPreferencesUseSystemLanguage() {
        for (String saved : new String[] {"", "invalid", "zh", null}) {
            assertSame(GameLanguage.JAPANESE,
                    GameLanguage.forApp(GameLanguage.JAPANESE, saved));
        }
    }

    @Test public void preservesAccentsWithoutMergingDifferentWords() {
        String value = "Été, Straße, ação, Đường １２３";
        assertEquals("étéstraßeaçãođường123", JourneyMatcher.normalize(value));
        assertEquals(JourneyMatcher.normalize(value), JourneyMatcher.normalize(
                java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)));
        assertNotEquals(JourneyMatcher.normalize("ma"), JourneyMatcher.normalize("má"));
        assertNotEquals(JourneyMatcher.normalize("Đá"), JourneyMatcher.normalize("á"));
        assertTrue(GameLanguage.FRENCH.isArcanaHeader("Événement Arcana"));
        assertTrue(GameLanguage.VIETNAMESE.isArcanaHeader("Sự kiện Arcana"));
    }

    @Test public void matchesWrappedAccentedChoicesAcrossLatinLanguages() {
        for (GameLanguage language : new GameLanguage[] {GameLanguage.FRENCH, GameLanguage.GERMAN,
                GameLanguage.SPANISH, GameLanguage.PORTUGUESE, GameLanguage.INDONESIAN, GameLanguage.VIETNAMESE}) {
            JourneyModels.Choice first = new JourneyModels.Choice("Été Straße ação Đường exemple", List.of());
            JourneyModels.Choice second = new JourneyModels.Choice("Attendre près du café", List.of());
            JourneyModels.Event event = new JourneyModels.Event("Scène synthétique", "", List.of(first, second));
            JourneyMatcher matcher = new JourneyMatcher(List.of(event), language);
            assertTrue(language.tag, matcher.match(List.of(event.name),
                    List.of("Été Straße", "ação Đường", "exemple", second.text)).isConfident());
            assertFalse(matcher.match(List.of(event.name), List.of(second.text)).isConfident());
        }
    }

    private static GameLanguage language(String tag) { return GameLanguage.fromSystemLocale(Locale.forLanguageTag(tag)); }
    private static Path asset(GameLanguage language) {
        Path root = Path.of("src/main/assets");
        if (!Files.exists(root)) root = Path.of("app/src/main/assets");
        return root.resolve(language.tag).resolve("journey_choices.example.json");
    }
    private static JourneyModels.Data example(GameLanguage language) throws Exception {
        return JourneyRepository.parse(new String(Files.readAllBytes(asset(language)), java.nio.charset.StandardCharsets.UTF_8));
    }
}
