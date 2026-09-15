package helper.journey.starsavior;

import java.util.List;

/** Raid tiers and journey difficulties belong to a separate recognition domain. */
final class RaidModels {
    private RaidModels() {}

    static final class Data {
        static final Data EMPTY = new Data("", "", List.of(), List.of());
        final String rankLabel;
        final String coinLabel;
        final List<String> tierLabels;
        final List<Event> events;

        Data(String rankLabel, String coinLabel, List<String> tierLabels, List<Event> events) {
            this.rankLabel = rankLabel;
            this.coinLabel = coinLabel;
            this.tierLabels = List.copyOf(tierLabels);
            this.events = List.copyOf(events);
        }
    }

    static final class Event {
        final String name;
        final String journeyDifficulty;
        final List<Option> options;

        Event(String name, String journeyDifficulty, List<Option> options) {
            this.name = name;
            this.journeyDifficulty = journeyDifficulty;
            this.options = List.copyOf(options);
        }

        boolean isSingleBattle() { return options.size() == 1 && options.get(0).tier == 0; }
    }

    static final class Option {
        final int tier;
        final String title;
        final int recommendedRank;
        final int victoryCoin;
        final int missionBonusCoin;
        final int missionCount;
        final String success;
        final String failure;

        Option(int tier, String title, int recommendedRank, int victoryCoin,
               int missionBonusCoin, int missionCount, String success, String failure) {
            this.tier = tier;
            this.title = title;
            this.recommendedRank = recommendedRank;
            this.victoryCoin = victoryCoin;
            this.missionBonusCoin = missionBonusCoin;
            this.missionCount = missionCount;
            this.success = success;
            this.failure = failure;
        }

        boolean hasSameRewards(Option other) {
            return victoryCoin == other.victoryCoin && missionBonusCoin == other.missionBonusCoin
                    && missionCount == other.missionCount && success.equals(other.success)
                    && failure.equals(other.failure);
        }
    }

    static final class Line {
        final String text;
        final double left, top, right, bottom;

        Line(String text, double left, double top, double right, double bottom) {
            this.text = text;
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }

        double height() { return Math.max(1, bottom - top); }
        boolean overlapsX(Line other) { return Math.min(right, other.right) > Math.max(left, other.left); }
    }

    static final class Match {
        final boolean raidScreen;
        final List<Event> events;
        final int selectedTier;
        final int recommendedRank;
        final boolean difficultyResolved;
        final boolean rankMismatch;

        Match(boolean raidScreen, List<Event> events, int selectedTier,
              int recommendedRank, boolean difficultyResolved, boolean rankMismatch) {
            this.raidScreen = raidScreen;
            this.events = List.copyOf(events);
            this.selectedTier = selectedTier;
            this.recommendedRank = recommendedRank;
            this.difficultyResolved = difficultyResolved;
            this.rankMismatch = rankMismatch;
        }
    }
}
