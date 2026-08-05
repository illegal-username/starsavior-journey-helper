package helper.journey.starsavior;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JourneyMatcherTest {
    private JourneyModels.Event sampleEvent() {
        return new JourneyModels.Event("공개 예제 이벤트", "", List.of(
                new JourneyModels.Choice("반짝이는 첫 번째 선택", List.of()),
                new JourneyModels.Choice("차분한 두 번째 선택", List.of()),
                new JourneyModels.Choice("따뜻한 세 번째 선택", List.of()),
                new JourneyModels.Choice("평범한 네 번째 선택", List.of())
        ));
    }

    @Test
    public void exactChoiceGroupMatches() {
        JourneyMatcher matcher = new JourneyMatcher(List.of(sampleEvent()));
        JourneyModels.Match match = matcher.match(List.of(
                "반짝이는 첫 번째 선택 20",
                "차분한 두 번째 선택 20",
                "따뜻한 세 번째 선택 20",
                "평범한 네 번째 선택"
        ));

        assertTrue(match.isConfident());
        assertEquals("공개 예제 이벤트", match.event.name);
    }

    @Test
    public void toleratesOcrErrorsAndSplitLine() {
        JourneyMatcher matcher = new JourneyMatcher(List.of(
                sampleEvent(),
                new JourneyModels.Event("다른 이벤트", "", List.of(
                        new JourneyModels.Choice("문을 연다", List.of()),
                        new JourneyModels.Choice("돌아간다", List.of())
                ))
        ));
        JourneyModels.Match match = matcher.match(List.of(
                "반짝이는 첫 번째 선댁",
                "차분한 두 번째 선택",
                "따뜻한 세 번째",
                "선택",
                "평범한 네 번째 선택"
        ));

        assertTrue(match.confidence >= 0.70);
        assertEquals("공개 예제 이벤트", match.event.name);
    }

    @Test
    public void eventTitleSeparatesIdenticalChoices() {
        JourneyModels.Event morning = new JourneyModels.Event("공개 예제 - 아침", "", List.of(
                new JourneyModels.Choice("첫 번째 예제 선택", List.of()),
                new JourneyModels.Choice("두 번째 예제 선택", List.of())
        ));
        JourneyModels.Event evening = new JourneyModels.Event("공개 예제 - 저녁", "", List.of(
                new JourneyModels.Choice("첫 번째 예제 선택", List.of()),
                new JourneyModels.Choice("두 번째 예제 선택", List.of())
        ));
        JourneyMatcher matcher = new JourneyMatcher(List.of(evening, morning));

        JourneyModels.Match match = matcher.match(
                List.of("여정 이벤트", "공개 예제 - 아침"),
                List.of("첫 번째 예제 선택", "두 번째 예제 선택"));

        assertTrue(match.isConfident());
        assertTrue(match.eventNameUsed);
        assertEquals("공개 예제 - 아침", match.event.name);
    }

    @Test
    public void identicalChoicesWithoutFullEventTitleAreAmbiguous() {
        JourneyModels.Event morning = new JourneyModels.Event("공개 예제 - 아침", "", List.of(
                new JourneyModels.Choice("첫 번째 예제 선택", List.of()),
                new JourneyModels.Choice("두 번째 예제 선택", List.of())
        ));
        JourneyModels.Event evening = new JourneyModels.Event("공개 예제 - 저녁", "", List.of(
                new JourneyModels.Choice("첫 번째 예제 선택", List.of()),
                new JourneyModels.Choice("두 번째 예제 선택", List.of())
        ));
        JourneyMatcher matcher = new JourneyMatcher(List.of(morning, evening));

        JourneyModels.Match match = matcher.match(
                List.of("공개 예제"),
                List.of("첫 번째 예제 선택", "두 번째 예제 선택"));

        assertFalse(match.isConfident());
        assertTrue(match.ambiguous);
    }
}
