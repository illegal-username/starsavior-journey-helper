package helper.journey.starsavior;

import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ItemEventSearchTest {
    static final ItemDetails.Item ITEM = new ItemDetails.Item("token", "Sample token", "A keepsake for a later event.", "Trade for a reward.");
    static final ItemDetails.Item OTHER = new ItemDetails.Item("other", ITEM.name, "A different item with the same name.", "");

    static ItemDetails.References refs(ItemDetails.Item item, int field) {
        List<ItemDetails.Link> links = List.of(new ItemDetails.Link(item, 0, item.name.length()));
        return new ItemDetails.References(field == 0 ? links : List.of(), field == 1 ? links : List.of(), field == 2 ? links : List.of());
    }

    static JourneyModels.Outcome outcome(String label, String difficulty, ItemDetails.Item item, int field, String... arcana) {
        return new JourneyModels.Outcome(label, difficulty, field == 0 ? item.name : "",
                field == 1 ? item.name : "Reward +7", field == 2 ? item.name : "", List.of(arcana), refs(item, field));
    }

    static JourneyModels.Event event(String name, JourneyModels.Outcome... outcomes) {
        return new JourneyModels.Event(name, "Synthetic event", List.of(new JourneyModels.Choice("Choose", List.of(outcomes))));
    }

    static JourneyModels.Data data() {
        JourneyModels.Event first = new JourneyModels.Event("First event", "Arcana A / Arcana B", List.of(
                new JourneyModels.Choice("Take the keepsake", List.of(outcome("First event · Arcana A", "", ITEM, 1, "a"),
                        outcome("First event · Arcana B", "", OTHER, 1, "b"))),
                new JourneyModels.Choice("Leave it", List.of(new JourneyModels.Outcome("First event · Arcana A", "", "", "Nothing happens", "", List.of("a"))))));
        JourneyModels.Event later = event("Later event", outcome("Later event · Arcana A", "Hard", ITEM, 0, "a", "shared"),
                outcome("Later event · Arcana A", "Easy", ITEM, 2, "a"));
        JourneyModels.Event unrelated = event("Other item event", outcome("Other", "", OTHER, 1, "b"));
        RaidModels.Option raid = new RaidModels.Option(0, "Raid", 0, 5, 2, 3, "Reward +3", ITEM.name, refs(ITEM, 2));
        RaidModels.Data raids = new RaidModels.Data("Rank", "Coin", List.of("I", "II", "III"),
                List.of(new RaidModels.Event("Raid event", "Hard", List.of(raid))));
        return new JourneyModels.Data(7, "", "synthetic", "", 3, 4, "", -1,
                List.of(first, later, unrelated), Map.of(), "en-US", raids);
    }

    static JourneyModels.Match match(JourneyModels.Data data) {
        return new JourneyModels.Match(data.events.get(0), 1, 1, 1, true, false, List.of(), List.of(), List.of());
    }

    @Test public void itemSearchFindsConditionsSuccessFailureAndRaidsWithoutMatchingNames() {
        JourneyModels.Data data = data();
        ItemEventSearch.Results results = ItemEventSearch.forItem(data, ITEM.id);
        assertEquals(List.of(data.events.get(0), data.events.get(1)), results.events);
        assertEquals(data.raids.events, results.raids);
        assertEquals(3, results.size());
        assertEquals(2, results.events.get(0).choices.size());
        assertEquals(0, ItemEventSearch.forItem(data, "absent").size());
        assertEquals(0, ItemEventSearch.forItem(data, ITEM.name).size());
    }

    @Test public void oldDatabaseWithoutItemLinksHasNoItemMatches() {
        JourneyModels.Event old = event("Legacy", new JourneyModels.Outcome("Legacy A", "", "", "Reward", "", List.of("a")));
        JourneyModels.Data data = new JourneyModels.Data(5, "", "synthetic", "", 1, 1, List.of(old));
        assertEquals(0, ItemEventSearch.forItem(data, ITEM.id).size());
    }
}
