package helper.journey.starsavior;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/** Raid presentation shares panel geometry, never choice models or difficulty filters. */
final class RaidResultView {
    private RaidResultView() {}

    static View render(Context context, RaidModels.Data data, RaidModels.Match match, Runnable close) {
        LinearLayout panel = OverlayResultView.panel(context);
        panel.setTag("raid_result");
        OverlayResultView.addHeader(context, panel, match.events.get(0).name, close);
        String status = match.difficultyResolved
                ? context.getString(R.string.raid_journey, match.events.get(0).journeyDifficulty)
                : context.getString(R.string.raid_unknown_journey);
        TextView subtitle = Ui.text(context, status, 13, match.difficultyResolved ? Ui.GREEN : Ui.ORANGE);
        panel.addView(subtitle, OverlayResultView.margins(context, -1, -2, 0, 3, 0, 8));
        TextView note = Ui.text(context, context.getString(R.string.raid_rewards_note), 11, Ui.MUTED);
        panel.addView(note, OverlayResultView.margins(context, -1, -2, 0, 0, 0, 10));

        boolean shared = hasSharedSingleRewards(match.events);
        if (shared) {
            StringBuilder journeys = new StringBuilder();
            for (RaidModels.Event event : match.events) {
                if (journeys.length() > 0) journeys.append("\n");
                journeys.append(context.getString(R.string.raid_journey, event.journeyDifficulty));
            }
            panel.addView(Ui.text(context, journeys.toString(), 12, Ui.MUTED),
                    OverlayResultView.margins(context, -1, -2, 0, 0, 0, 10));
        }
        List<RaidModels.Event> displayed = shared ? List.of(match.events.get(0)) : match.events;
        for (RaidModels.Event event : displayed) {
            if (!match.difficultyResolved && !shared) {
                TextView difficulty = Ui.text(context,
                        context.getString(R.string.raid_journey, event.journeyDifficulty), 14, Ui.ORANGE);
                difficulty.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                panel.addView(difficulty, OverlayResultView.margins(context, -1, -2, 0, 7, 0, 6));
            }
            for (RaidModels.Option option : event.options) {
                LinearLayout card = new LinearLayout(context);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(Ui.dp(context, 13), Ui.dp(context, 11), Ui.dp(context, 13), Ui.dp(context, 11));
                card.setBackground(Ui.rounded(context, Ui.CARD_ALT, 14));
                String heading;
                if (shared) heading = context.getString(R.string.raid_common_rewards);
                else if (event.isSingleBattle()) heading = "";
                else {
                    String roman = new String[]{"I", "II", "III"}[option.tier - 1];
                    heading = context.getString(R.string.raid_tier, roman);
                }
                if (option.tier > 0 && option.tier == match.selectedTier && match.difficultyResolved) {
                    heading += " · " + context.getString(R.string.raid_selected);
                }
                if (!heading.isEmpty()) {
                    TextView title = Ui.text(context, heading, 14, Ui.TEXT);
                    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    card.addView(title, new LinearLayout.LayoutParams(-1, -2));
                }
                OverlayResultView.addEffect(context, card, context.getString(R.string.success_label),
                        data.coinLabel + " +" + option.victoryCoin + " · " + option.success, Ui.GREEN);
                OverlayResultView.addEffect(context, card, context.getString(R.string.failure_label),
                        option.failure, Ui.RED);
                if (option.missionBonusCoin > 0 && option.missionCount > 0) {
                    TextView bonus = Ui.text(context, context.getString(R.string.raid_mission_bonus,
                            data.coinLabel, option.missionBonusCoin, option.missionCount), 11, Ui.MUTED);
                    card.addView(bonus, OverlayResultView.margins(context, -1, -2, 0, 7, 0, 0));
                }
                panel.addView(card, OverlayResultView.margins(context, -1, -2, 0, 0, 0, 8));
            }
        }
        return OverlayResultView.wrap(context, panel);
    }

    static boolean hasSharedSingleRewards(List<RaidModels.Event> events) {
        if (events.size() < 2 || !events.get(0).isSingleBattle()) return false;
        RaidModels.Event first = events.get(0);
        for (RaidModels.Event event : events) {
            if (!event.isSingleBattle() || !event.name.equals(first.name)
                    || !first.options.get(0).hasSameRewards(event.options.get(0))) return false;
        }
        return true;
    }
}
