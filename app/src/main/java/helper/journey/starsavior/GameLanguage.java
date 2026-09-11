package helper.journey.starsavior;

import java.util.Locale;

/** One selected language owns the UI, recognizer and database. */
enum GameLanguage {
    KOREAN("ko-KR", "여정 이벤트", "아르카나", "이벤트"),
    ENGLISH("en-US", "Journey Event", "Arcana", "Event"),
    JAPANESE("ja-JP", "救援の旅イベント", "アルカナ", "イベント"),
    TRADITIONAL_CHINESE("zh-TW", "旅程事件", "阿爾克那", "事件"),
    SIMPLIFIED_CHINESE("zh-CN", "旅程事件", "阿尔克那", "事件"),
    FRENCH("fr-FR", "Événement de Périple", "Arcana", "Événement"),
    GERMAN("de-DE", "Reise-Event", "Arkana", "Event"),
    SPANISH("es-ES", "Evento de La Travesía", "Arcana", "Evento"),
    PORTUGUESE("pt-BR", "Evento A Jornada", "Arcana", "Evento"),
    INDONESIAN("id-ID", "Event Perjalanan", "Arcana", "Event"),
    VIETNAMESE("vi-VN", "Sự Kiện Hành Trình", "Arcana", "Sự kiện");

    static final String LEGACY_DATABASE_URL = "https://starsavior-journey-data.pages.dev/journey_choices.json";
    final String tag;
    final String eventHeader;
    private final String arcana;
    private final String event;

    GameLanguage(String tag, String eventHeader, String arcana, String event) {
        this.tag = tag;
        this.eventHeader = eventHeader;
        this.arcana = arcana;
        this.event = event;
    }

    static GameLanguage fromSystemLocale(Locale locale) {
        switch (locale.getLanguage()) {
            case "fr": return FRENCH;
            case "de": return GERMAN;
            case "es": return SPANISH;
            case "pt": return PORTUGUESE;
            case "id": return INDONESIAN;
            case "vi": return VIETNAMESE;
            case "in": return INDONESIAN;
            case "ko": return KOREAN;
            case "ja": return JAPANESE;
            case "zh":
                if ("Hant".equals(locale.getScript())) return TRADITIONAL_CHINESE;
                if ("Hans".equals(locale.getScript())) return SIMPLIFIED_CHINESE;
                String country = locale.getCountry();
                return "TW".equals(country) || "HK".equals(country) || "MO".equals(country)
                        ? TRADITIONAL_CHINESE : SIMPLIFIED_CHINESE;
            default: return ENGLISH;
        }
    }

    static GameLanguage forApp(GameLanguage system, String overrideTag) {
        for (GameLanguage language : values()) {
            if (language.tag.equals(overrideTag)) return language;
        }
        return system;
    }

    String nativeName() {
        switch (this) {
            case FRENCH: return "Français";
            case GERMAN: return "Deutsch";
            case SPANISH: return "Español";
            case PORTUGUESE: return "Português";
            case INDONESIAN: return "Bahasa Indonesia";
            case VIETNAMESE: return "Tiếng Việt";
            case KOREAN: return "한국어";
            case JAPANESE: return "日本語";
            case TRADITIONAL_CHINESE: return "繁體中文";
            case SIMPLIFIED_CHINESE: return "简体中文";
            default: return "English";
        }
    }

    static GameLanguage require(String tag) {
        for (GameLanguage language : values()) if (language.tag.equals(tag)) return language;
        throw new IllegalArgumentException("Unsupported database language: " + tag);
    }

    Locale locale() { return Locale.forLanguageTag(tag); }

    String databaseUrl() {
        return "https://starsavior-journey-data.pages.dev/v5/" + tag + "/journey_choices.json";
    }

    String manifestUrl() { return databaseUrl().replace(".json", ".meta.json"); }

    boolean isArcanaHeader(String line) {
        String normalized = JourneyMatcher.normalize(line);
        return normalized.contains(JourneyMatcher.normalize(arcana))
                && normalized.contains(JourneyMatcher.normalize(event));
    }
}
