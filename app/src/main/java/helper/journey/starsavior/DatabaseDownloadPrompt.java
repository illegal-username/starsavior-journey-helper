package helper.journey.starsavior;

import android.content.Context;
import android.content.SharedPreferences;

/** Once per language until real data is installed; survives activity recreation. */
final class DatabaseDownloadPrompt {
    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("database_download_prompt", Context.MODE_PRIVATE);
    }

    static boolean shouldShow(Context context, JourneyModels.Data data) {
        return JourneyRepository.isExampleDatabase(data)
                && !preferences(context).getBoolean(data.language, false);
    }

    static void shown(Context context, JourneyModels.Data data) {
        preferences(context).edit().putBoolean(data.language, true).apply();
    }

    static void installed(Context context, JourneyModels.Data data) {
        if (data != null && data.origin != JourneyModels.DatabaseOrigin.UNLOADED
                && !JourneyRepository.isExampleDatabase(data)) {
            preferences(context).edit().remove(data.language).apply();
        }
    }

    private DatabaseDownloadPrompt() {}
}
