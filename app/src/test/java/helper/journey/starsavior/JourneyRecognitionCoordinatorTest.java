package helper.journey.starsavior;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class JourneyRecognitionCoordinatorTest {
    @Test
    public void regionalDecisionUsesAliasForBothMatchingAndDifficulty() {
        JourneyMatcher matcher = new JourneyMatcher(List.of(event()));

        JourneyRecognitionCoordinator.Decision decision =
                JourneyRecognitionCoordinator.evaluateRegional(
                        matcher,
                        List.of("날씨 이벤트"),
                        List.of("훈련을 이어간다", "147", "숙소로 간다"));

        assertEquals(JourneyRecognitionCoordinator.Action.SHOW_MATCH, decision.action);
        assertEquals("노말", decision.difficulty);
    }

    @Test
    public void uncertainRegionalOcrRequestsFullScreenFallback() {
        JourneyMatcher matcher = new JourneyMatcher(List.of(event()));

        JourneyRecognitionCoordinator.Decision decision =
                JourneyRecognitionCoordinator.evaluateRegional(
                        matcher, List.of(), List.of("읽지 못한 화면"));

        assertEquals(JourneyRecognitionCoordinator.Action.SCAN_FULL_SCREEN, decision.action);
    }

    @Test
    public void exactChoicesSeparateNearIdenticalEventsDespiteNoisyTitle() {
        for (boolean alias : List.of(false, true)) {
            JourneyModels.Event stone = similarEvent("돌", alias);
            JourneyMatcher matcher = new JourneyMatcher(List.of(similarEvent("책", false), stone));
            List<String> lines = List.of("상자의 돌을 들어 본다.", "347", "잠시 기다린다.");

            JourneyRecognitionCoordinator.Decision regional =
                    JourneyRecognitionCoordinator.evaluateRegional(
                            matcher, List.of("볼의 신비한 힘"), lines);
            assertEquals(JourneyRecognitionCoordinator.Action.SHOW_MATCH, regional.action);
            assertEquals(stone, regional.match.event);
            assertEquals(1.0, regional.match.choiceConfidence, 0.0001);
            assertEquals("노말", regional.difficulty);

            JourneyRecognitionCoordinator.Decision full =
                    JourneyRecognitionCoordinator.evaluateFull(matcher,
                            List.of("볼의 신비한 힘", lines.get(0), "347", lines.get(2)), lines);
            assertEquals(JourneyRecognitionCoordinator.Action.SHOW_MATCH, full.action);
            assertEquals(stone, full.match.event);
            assertEquals("노말", full.difficulty);

            JourneyRecognitionCoordinator.Decision unknownCondition =
                    JourneyRecognitionCoordinator.evaluateRegional(matcher,
                            List.of("볼의 신비한 힘"),
                            List.of(lines.get(0), "619", lines.get(2)));
            assertEquals(JourneyRecognitionCoordinator.Action.SHOW_MATCH, unknownCondition.action);
            assertEquals("", unknownCondition.difficulty);
        }
    }

    @Test
    public void nearIdenticalEventsRemainAmbiguousWhenDistinctiveWordIsUnreadable() {
        JourneyMatcher matcher = new JourneyMatcher(List.of(
                similarEvent("돌", false), similarEvent("책", false)));
        List<String> lines = List.of("상자의 볼을 들어 본다.", "347", "잠시 기다린다.");

        assertEquals(JourneyRecognitionCoordinator.Action.SCAN_FULL_SCREEN,
                JourneyRecognitionCoordinator.evaluateRegional(
                        matcher, List.of("볼의 신비한 힘"), lines).action);
        assertEquals(JourneyRecognitionCoordinator.Action.AMBIGUOUS,
                JourneyRecognitionCoordinator.evaluateFull(matcher,
                        List.of("볼의 신비한 힘", lines.get(0), "347", lines.get(2)), lines).action);
    }

    @Test
    public void anotherExactChoiceGroupStillPreventsConfirmation() {
        JourneyModels.Event stone = similarEvent("돌", false);
        JourneyModels.Event duplicate = new JourneyModels.Event(stone.name, "별도 예제", stone.choices);
        JourneyMatcher matcher = new JourneyMatcher(List.of(similarEvent("책", false), stone, duplicate));

        assertEquals(JourneyRecognitionCoordinator.Action.AMBIGUOUS,
                JourneyRecognitionCoordinator.evaluateFull(matcher,
                        List.of("볼의 신비한 힘", "상자의 돌을 들어 본다.", "잠시 기다린다."),
                        List.of()).action);
    }

    private static JourneyModels.Event similarEvent(String object, boolean alias) {
        String text = "상자의 " + object + "을 들어 본다.";
        return new JourneyModels.Event(object + "의 신비한 힘", "", List.of(
                new JourneyModels.Choice(alias ? "조심스럽게 확인한다." : text,
                        alias ? List.of("내용물을 살펴본다.", text) : List.of(),
                        List.of(outcome("이지", "집중 83 필요"),
                                outcome("노말", "집중 347 필요"),
                                outcome("하드", "집중 1201 필요"))),
                new JourneyModels.Choice("잠시 기다린다.", List.of())));
    }

    private static JourneyModels.Event event() {
        JourneyModels.Choice first = new JourneyModels.Choice(
                "천둥을 무릅쓰고 훈련한다",
                List.of("훈련을 이어간다"),
                List.of(
                        outcome("이지", "집중 73 필요"),
                        outcome("노말", "집중 147 필요"),
                        outcome("하드", "집중 911 필요")));
        JourneyModels.Choice second = new JourneyModels.Choice(
                "무리하지 않고 대피한다",
                List.of("숙소로 간다"),
                List.of(
                        outcome("이지", ""),
                        outcome("노말", ""),
                        outcome("하드", "")));
        return new JourneyModels.Event("날씨 이벤트", "", List.of(first, second));
    }

    private static JourneyModels.Outcome outcome(String difficulty, String condition) {
        return new JourneyModels.Outcome("", difficulty, condition, "효과", "");
    }
}
