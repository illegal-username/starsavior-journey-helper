package helper.journey.starsavior;

import java.util.ArrayList;
import java.util.List;

/** Item reference lookup over the same database snapshot as the recognition result. */
final class ItemEventSearch {
    private ItemEventSearch() {}

    static final class Results {
        final List<JourneyModels.Event> events;
        final List<RaidModels.Event> raids;

        Results(List<JourneyModels.Event> events, List<RaidModels.Event> raids) {
            this.events = List.copyOf(events);
            this.raids = List.copyOf(raids);
        }

        int size() { return events.size() + raids.size(); }
    }

    static Results forItem(JourneyModels.Data data, String itemId) {
        List<JourneyModels.Event> events = new ArrayList<>();
        List<RaidModels.Event> raids = new ArrayList<>();
        for (JourneyModels.Event event : data.events) {
            boolean found = false;
            for (JourneyModels.Choice choice : event.choices) {
                for (JourneyModels.Outcome outcome : choice.outcomes) {
                    found |= references(outcome.items, itemId);
                }
            }
            // Keep all choices so the reader can compare alternatives in the event.
            if (found) events.add(event);
        }
        for (RaidModels.Event event : data.raids.events) {
            boolean found = false;
            for (RaidModels.Option option : event.options) found |= references(option.items, itemId);
            if (found) raids.add(event);
        }
        return new Results(events, raids);
    }

    private static boolean references(ItemDetails.References refs, String id) {
        return contains(refs.condition, id) || contains(refs.success, id) || contains(refs.failure, id);
    }

    private static boolean contains(List<ItemDetails.Link> links, String id) {
        for (ItemDetails.Link link : links) if (link.item.id.equals(id)) return true;
        return false;
    }
}
