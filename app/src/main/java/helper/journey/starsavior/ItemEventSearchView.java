package helper.journey.starsavior;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/** One level of event browsing; item details deliberately receive no search callback. */
final class ItemEventSearchView {
    private ItemEventSearchView() {}

    static View render(Context context, ItemDetails.Item item, ItemEventSearch.Results results,
                       RaidModels.Data raidData, Runnable close) {
        LinearLayout panel = OverlayResultView.panel(context);
        OverlayResultView.addHeader(context, panel, context.getString(R.string.item_search_title, item.name), close);
        panel.addView(Ui.text(context, context.getString(R.string.item_search_description), 12, Ui.MUTED),
                OverlayResultView.margins(context, -1, -2, 0, 4, 0, 8));
        panel.addView(Ui.text(context, context.getString(results.size() == 0
                        ? R.string.event_search_empty : R.string.event_search_count, results.size()), 12, Ui.MUTED),
                OverlayResultView.margins(context, -1, -2, 0, 0, 0, 8));
        for (JourneyModels.Event event : results.events) {
            addEvent(context, panel, event.name,
                    event.sameProgress ? context.getString(R.string.same_progress_title) : event.context, body -> {
                if (event.sameProgress) {
                    body.addView(Ui.text(context, context.getString(R.string.same_progress_message), 12, Ui.GREEN));
                }
                for (int i = 0; i < event.choices.size(); i++) {
                    JourneyModels.Choice choice = event.choices.get(i);
                    heading(context, body, (i + 1) + ". " + choice.text, Ui.TEXT);
                    for (JourneyModels.Outcome outcome : choice.outcomes) {
                        String label = outcome.label;
                        if (!outcome.difficulty.isEmpty()) {
                            label += (label.isEmpty() ? "" : " · ") + outcome.difficulty;
                        }
                        if (!label.isEmpty()) heading(context, body, label, Ui.ORANGE);
                        effect(context, body, R.string.condition_label, outcome.condition, Ui.ORANGE, outcome.items.condition);
                        effect(context, body, outcome.failure.isEmpty() ? R.string.effect_label : R.string.success_label,
                                outcome.success, Ui.GREEN, outcome.items.success);
                        effect(context, body, R.string.failure_label, outcome.failure, Ui.RED, outcome.items.failure);
                    }
                }
            });
        }
        for (RaidModels.Event event : results.raids) {
            addEvent(context, panel, event.name, context.getString(R.string.raid_journey, event.journeyDifficulty), body -> {
                for (RaidModels.Option option : event.options) {
                    if (!event.isSingleBattle()) heading(context, body, option.title, Ui.TEXT);
                    String prefix = raidData.coinLabel + " +" + option.victoryCoin + " · ";
                    OverlayResultView.addEffect(context, body, context.getString(R.string.success_label),
                            prefix + option.success, Ui.GREEN, option.items.success, prefix.length());
                    effect(context, body, R.string.failure_label, option.failure, Ui.RED, option.items.failure);
                    if (option.missionBonusCoin > 0 && option.missionCount > 0) {
                        body.addView(Ui.text(context, context.getString(R.string.raid_mission_bonus,
                                raidData.coinLabel, option.missionBonusCoin, option.missionCount), 11, Ui.MUTED),
                                OverlayResultView.margins(context, -1, -2, 0, 7, 0, 0));
                    }
                }
            });
        }
        return OverlayResultView.wrap(context, panel);
    }

    private static void addEvent(Context context, LinearLayout panel, String name, String detail,
                                 java.util.function.Consumer<LinearLayout> populate) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 12), Ui.dp(context, 8));
        card.setBackground(Ui.rounded(context, Ui.CARD_ALT, 12));
        TextView toggle = Ui.text(context, "▾ " + name, 14, Ui.BLUE);
        toggle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        toggle.setMinHeight(Ui.dp(context, 48));
        toggle.setGravity(android.view.Gravity.CENTER_VERTICAL);
        toggle.setFocusable(true);
        card.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        if (!detail.isEmpty()) card.addView(Ui.text(context, detail, 11, Ui.MUTED));
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        populate.accept(body);
        card.addView(body, new LinearLayout.LayoutParams(-1, -2));
        toggle.setOnClickListener(v -> {
            boolean expand = body.getVisibility() != View.VISIBLE;
            body.setVisibility(expand ? View.VISIBLE : View.GONE);
            toggle.setText((expand ? "▾ " : "▸ ") + name);
            toggle.setContentDescription(context.getString(expand
                    ? R.string.event_search_collapse : R.string.event_search_expand, name));
        });
        toggle.setContentDescription(context.getString(R.string.event_search_collapse, name));
        panel.addView(card, OverlayResultView.margins(context, -1, -2, 0, 0, 0, 8));
    }

    private static void heading(Context context, LinearLayout parent, String value, int color) {
        TextView heading = Ui.text(context, value, 12, color);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        parent.addView(heading, OverlayResultView.margins(context, -1, -2, 0, 10, 0, 2));
    }

    private static void effect(Context context, LinearLayout parent, int label, String value, int color,
                               List<ItemDetails.Link> links) {
        if (!value.isEmpty()) OverlayResultView.addEffect(context, parent, context.getString(label), value, color, links, 0);
    }
}
