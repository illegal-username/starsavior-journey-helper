package helper.journey.starsavior;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;

import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

final class AppLanguage {
    private AppLanguage() {}

    static GameLanguage system() {
        return GameLanguage.fromSystemLocale(Resources.getSystem().getConfiguration().getLocales().get(0));
    }

    static GameLanguage of(Context context) {
        return GameLanguage.fromSystemLocale(context.getResources().getConfiguration().getLocales().get(0));
    }

    static GameLanguage selected(Context context) {
        String override = preference(context);
        return GameLanguage.forApp(system(), override);
    }

    static String preference(Context context) {
        android.content.SharedPreferences settings = context.getSharedPreferences("app_language", Context.MODE_PRIVATE);
        if (settings.contains("language")) return settings.getString("language", "");
        // Preserve a choice made in the previously delivered language test APK.
        return context.getSharedPreferences("test_language", Context.MODE_PRIVATE).getString("language", "");
    }

    static void setLanguage(Context context, String tag) {
        if (!tag.isEmpty()) GameLanguage.require(tag);
        context.getSharedPreferences("app_language", Context.MODE_PRIVATE)
                .edit().putString("language", tag).apply();
    }

    static Context wrap(Context context) {
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        GameLanguage language = selected(context);
        configuration.setLocales(new LocaleList(language.locale()));
        configuration.setLayoutDirection(language.locale());
        return context.createConfigurationContext(configuration);
    }

    static TextRecognizer recognizer(GameLanguage language) {
        switch (language) {
            case KOREAN: return TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
            case JAPANESE: return TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
            case TRADITIONAL_CHINESE:
            case SIMPLIFIED_CHINESE:
                return TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            default: return TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        }
    }
}
