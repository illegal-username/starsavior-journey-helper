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
