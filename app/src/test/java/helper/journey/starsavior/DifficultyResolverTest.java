package helper.journey.starsavior;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class DifficultyResolverTest {
    @Test
    public void resolvesBeachQualifierFromThatEventsOwnRequirements() {
        JourneyModels.Event event = beachQualifier();

        assertEquals("이지", DifficultyResolver.fromRecognizedLines(event, List.of(
                "상대팀의 약점을 공략하자", "200",
                "우리 팀의 강점을 활용한다", "100",
                "일단 몸을 풀자", "20")));
        assertEquals("노말", DifficultyResolver.fromRecognizedLines(event, List.of(
                "상대팀의 약점을 공략하자", "300",
                "우리 팀의 강점을 활용한다", "125",
                "일단 몸을 풀자", "20")));
        assertEquals("하드", DifficultyResolver.fromRecognizedLines(event, List.of(
                "상대팀의 약점을 공략하자", "500",
                "우리 팀의 강점을 활용한다", "150",
                "일단 몸을 풀자", "20")));
    }

    @Test
    public void worksWithArbitraryFutureRequirementValues() {
        JourneyModels.Event event = event("새 이벤트", choice("새로운 방법을 시도한다",
                "보호 73 필요", "보호 147 필요", "보호 911 필요"));

        assertEquals("노말", DifficultyResolver.fromRecognizedLines(
                event, List.of("새로운 방법을 시도한다 147")));
        assertEquals("하드", DifficultyResolver.fromRecognizedLines(
                event, List.of("새로운 방법을 시도한다", "911")));
    }

    @Test
    public void associatesSameNumberWithTheCorrectChoice() {
        JourneyModels.Event event = event("교차 조건 이벤트",
                choice("첫 번째 계획", "힘 111 필요", "힘 222 필요", "힘 500 필요"),
                choice("두 번째 계획", "집중 500 필요", "집중 600 필요", "집중 700 필요"));

        assertEquals("이지", DifficultyResolver.fromRecognizedLines(
                event, List.of("두 번째 계획", "500")));
        assertEquals("하드", DifficultyResolver.fromRecognizedLines(
                event, List.of("첫 번째 계획", "500")));
    }

    @Test
    public void joinsAChoiceSplitAcrossOcrLines() {
        JourneyModels.Event event = beachQualifier();

        assertEquals("하드", DifficultyResolver.fromRecognizedLines(event,
                List.of("상대팀의 약점을", "공략하자", "500")));
    }

    @Test
    public void sharedCostsAndUnrelatedScreenNumbersDoNotChooseDifficulty() {
        JourneyModels.Event event = beachQualifier();

        assertEquals("", DifficultyResolver.fromRecognizedLines(event,
                List.of("일단 몸을 풀자", "20", "오래된 동전 30", "Lv. 6")));
    }

    @Test
    public void ambiguousRequirementDoesNotFilterResults() {
        JourneyModels.Event event = event("겹치는 조건 이벤트", choice("계속한다",
                "힘 100 필요", "힘 200 필요", "힘 100 필요"));

        assertEquals("", DifficultyResolver.fromRecognizedLines(
                event, List.of("계속한다", "100")));
        assertEquals("", DifficultyResolver.fromRecognizedLines(
                null, List.of("계속한다", "100")));
    }

    private static JourneyModels.Event beachQualifier() {
        return event("비치발리볼 대회 예선전",
                choice("상대팀의 약점을 공략하자",
                        "힘 200 필요", "힘 300 필요", "힘 500 필요"),
                choice("우리 팀의 강점을 활용한다",
                        "집중 100 필요", "집중 125 필요", "집중 150 필요"),
                choice("일단 몸을 풀자",
                        "스태미나 -20", "스태미나 -20", "스태미나 -20"));
    }

    private static JourneyModels.Event event(String name, JourneyModels.Choice... choices) {
        return new JourneyModels.Event(name, "", List.of(choices));
    }

    private static JourneyModels.Choice choice(
            String text, String easy, String normal, String hard) {
        List<JourneyModels.Outcome> outcomes = new ArrayList<>();
        outcomes.add(outcome("이지", easy));
        outcomes.add(outcome("노말", normal));
        outcomes.add(outcome("하드", hard));
        return new JourneyModels.Choice(text, outcomes);
    }

    private static JourneyModels.Outcome outcome(String difficulty, String condition) {
        return new JourneyModels.Outcome("", difficulty, condition, "효과", "");
    }
}
