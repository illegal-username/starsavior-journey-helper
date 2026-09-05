package helper.journey.starsavior;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class JourneyMatcherStoreTest {
    @Test
    public void failedHotReloadKeepsThePreviouslyUsableMatcher() throws Exception {
        JourneyMatcherStore store = new JourneyMatcherStore();
        store.reload(() -> data("기존 이벤트", "기존 선택", "돌아간다"));
        JourneyMatcher previous = store.current();

        assertThrows(IOException.class, () -> store.reload(() -> {
            throw new IOException("replacement failed");
        }));

        assertSame(previous, store.current());
        assertTrue(store.current().match(List.of("기존 선택", "돌아간다")).isConfident());
    }

    @Test
    public void successfulHotReloadPublishesTheNewMatcherAsOneSnapshot() throws Exception {
        JourneyMatcherStore store = new JourneyMatcherStore();
        store.reload(() -> data("기존 이벤트", "기존 선택", "돌아간다"));

        store.reload(() -> data("새 이벤트", "새 선택", "쉬어간다"));

        assertTrue(store.current().match(List.of("새 선택", "쉬어간다")).isConfident());
    }

    private static JourneyModels.Data data(String event, String first, String second) {
        JourneyModels.Outcome outcome = new JourneyModels.Outcome("", "", "", "효과", "");
        JourneyModels.Event value = new JourneyModels.Event(event, "", List.of(
                new JourneyModels.Choice(first, List.of(outcome)),
                new JourneyModels.Choice(second, List.of(outcome))));
        return new JourneyModels.Data(4, "", "test", "revision", 1, 2, List.of(value));
    }
}
