package helper.journey.starsavior;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.assertEquals;

public class ChoiceDisplayTextTest {
    private final JourneyModels.Choice choice = new JourneyModels.Choice(
            "용기를 내어 먼 숲으로 출발한다.",
            List.of("숲으로 간다.", "탐험을 시작한다.", "길을 나선다."), List.of());

    @Test public void usesNormalWordingForNormalScreen() {
        assertEquals(choice.text, JourneyMatcher.displayChoiceText(choice,
                List.of("용기를 내어 먼 숲으로 출발한다.")));
    }

    @Test public void usesRegisteredAliasForShortenedScreenAndOcrNoise() {
        assertEquals("숲으로 간다.", JourneyMatcher.displayChoiceText(choice,
                List.of("숲으로 간다")));
        assertEquals("탐험을 시작한다.", JourneyMatcher.displayChoiceText(choice,
                List.of("탐험을 시직한다")));
        assertEquals("길을 나선다.", JourneyMatcher.displayChoiceText(choice,
                List.of("길을", "나선다")));
    }

    @Test public void fallsBackForMissingOrTiedEvidence() {
        assertEquals(choice.text, JourneyMatcher.displayChoiceText(choice, List.of()));
        assertEquals(choice.text, JourneyMatcher.displayChoiceText(choice, List.of("스태미나 40")));
        assertEquals(choice.text, JourneyMatcher.displayChoiceText(choice,
                List.of("숲으로 간다.", "탐험을 시작한다.")));
    }

    @Test public void selectsEachChoiceIndependentlyWithoutChangingRewards() {
        JourneyModels.Outcome reward = new JourneyModels.Outcome("", "", "", "효과", "");
        JourneyModels.Choice other = new JourneyModels.Choice("여기서 잠시 쉬어 가기로 한다.",
                List.of("휴식한다."), List.of(reward));
        List<String> lines = List.of("숲으로 간다.", "휴식한다.");
        assertEquals("숲으로 간다.", JourneyMatcher.displayChoiceText(choice, lines));
        assertEquals("휴식한다.", JourneyMatcher.displayChoiceText(other, lines));
        assertEquals(reward, other.outcomes.get(0));
    }
}
