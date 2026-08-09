package helper.journey.starsavior;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DifficultyResolver {
    private static final Pattern NUMBER = Pattern.compile("(?<!\\d)(60|80|100)(?!\\d)");

    private DifficultyResolver() {}

    static String fromRecognizedLines(List<String> lines) {
        int maximum = 0;
        if (lines != null) {
            for (String line : lines) {
                if (line == null) continue;
                Matcher matcher = NUMBER.matcher(line);
                while (matcher.find()) maximum = Math.max(maximum, Integer.parseInt(matcher.group(1)));
            }
        }
        if (maximum == 100) return "하드";
        if (maximum == 80) return "노말";
        if (maximum == 60) return "이지";
        return "";
    }
}
