package helper.journey.starsavior;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class JourneyDifficultyFilterTest {
    private static JourneyModels.Outcome outcome(String difficulty, String success) {
        return new JourneyModels.Outcome("", difficulty, "", success, "");
    }

    @Test
    public void detectedDifficultyKeepsOnlyMatchingOutcome() {
        JourneyModels.Outcome easy = outcome("이지", "힘 +5");
        JourneyModels.Outcome normal = outcome("노말", "힘 +10");
        JourneyModels.Outcome hard = outcome("하드", "힘 +15");
        JourneyModels.Choice choice = new JourneyModels.Choice(
                "슬라임을 몰아내자", List.of(easy, normal, hard));

        List<JourneyModels.Outcome> visible = choice.outcomesForDifficulty("하드");

        assertEquals(1, visible.size());
        assertSame(hard, visible.get(0));
    }

    @Test
    public void combinedDifficultyTagMatchesEitherDifficulty() {
        JourneyModels.Outcome shared = outcome("이지/노말", "체력 +5");
        JourneyModels.Outcome hard = outcome("하드", "체력 +10");
        JourneyModels.Choice choice = new JourneyModels.Choice("대피를 돕자", List.of(shared, hard));

        assertSame(shared, choice.outcomesForDifficulty("이지").get(0));
        assertSame(shared, choice.outcomesForDifficulty("노말").get(0));
        assertSame(hard, choice.outcomesForDifficulty("하드").get(0));
    }

    @Test
    public void genericOutcomeRemainsAvailableForEventsWithoutDifficultyVariants() {
        JourneyModels.Outcome generic = outcome("", "스태미나 +10");
        JourneyModels.Choice choice = new JourneyModels.Choice("쉬어 간다", List.of(generic));

        assertSame(generic, choice.outcomesForDifficulty("하드").get(0));
    }

    @Test
    public void missingDifficultyDoesNotFilterOutcomes() {
        JourneyModels.Outcome easy = outcome("이지", "힘 +5");
        JourneyModels.Outcome hard = outcome("하드", "힘 +15");
        JourneyModels.Choice choice = new JourneyModels.Choice("계속한다", List.of(easy, hard));

        assertEquals(2, choice.outcomesForDifficulty("").size());
        assertTrue(choice.outcomesForDifficulty(null).containsAll(List.of(easy, hard)));
    }
}
