package helper.journey.starsavior;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JourneyMatcherTest {
    @Test
    public void oneSharedActionDoesNotIdentifyAnUnregisteredChoiceScreen() {
        JourneyModels.Event wrong = new JourneyModels.Event("탐사 중단", "", List.of(
                new JourneyModels.Choice("집으로 돌아가자", List.of()),
                new JourneyModels.Choice("회복 물약을 전달한다", List.of())));
        JourneyModels.Event correct = new JourneyModels.Event("연습 중단", "", List.of(
                new JourneyModels.Choice("오늘은 그만하자", List.of()),
                new JourneyModels.Choice("차근차근 연습하자", List.of()),
                new JourneyModels.Choice("회복 물약을 전달한다", List.of())));
        List<String> lines = List.of("이제 잠깐 휴식이 필요해", "다시 한번 도전해보자", "회복 물약을 전달한다");
        assertFalse(new JourneyMatcher(List.of(wrong, correct))
                .match(List.of("연습 중단"), lines).isConfident());
        JourneyModels.Event withAliases = new JourneyModels.Event("연습 중단", "", List.of(
                new JourneyModels.Choice("오늘은 그만하자", List.of(lines.get(0)), List.of()),
                new JourneyModels.Choice("차근차근 연습하자", List.of(lines.get(1)), List.of()),
                correct.choices.get(2)));
        JourneyModels.Match match = new JourneyMatcher(List.of(wrong, withAliases))
                .match(List.of("연습 중단"), lines);
        assertTrue(match.isConfident());
        assertEquals("연습 중단", match.event.name);
        assertEquals(lines.get(0), JourneyMatcher.displayChoiceText(withAliases.choices.get(0), lines));
    }

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

    @Test
    public void shortenedChoiceAliasesMatchTheSameEventOutcomes() {
        JourneyModels.Event weather = new JourneyModels.Event("오늘의 날씨 - 낙뢰", "", List.of(
                new JourneyModels.Choice(
                        "고작 천둥번개를 겁낼 수는 없지!",
                        List.of("훈련을 이어나간다."),
                        List.of()),
                new JourneyModels.Choice(
                        "무리하지 말고 대피하자.",
                        List.of("숙소로 돌아간다."),
                        List.of())
        ));
        JourneyMatcher matcher = new JourneyMatcher(List.of(weather));

        JourneyModels.Match match = matcher.match(
                List.of("여정 이벤트", "오늘의 날씨 - 낙뢰"),
                List.of("훈련을 이어나간다.", "숙소로 돌아간다."));

        assertTrue(match.isConfident());
        assertEquals("오늘의 날씨 - 낙뢰", match.event.name);
        assertEquals(1.0, match.choiceConfidence, 0.0001);
    }
}
