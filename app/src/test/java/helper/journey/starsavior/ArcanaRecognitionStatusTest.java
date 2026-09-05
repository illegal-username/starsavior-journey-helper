package helper.journey.starsavior;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.Test;

public class ArcanaRecognitionStatusTest {
    @Test
    public void reportsOnlyTheRecognizedArcanaSource() {
        JourneyModels.Event event = eventWithSources("7101601", "7101602", "7101603");

        ArcanaRecognitionStatus status = ArcanaRecognitionStatus.from(
                event, "", Set.of("7101602"));

        assertTrue(status.applicable);
        assertTrue(status.recognized);
        assertEquals("린 · 하얀 달의 온기는 햇빛처럼", status.detectedSource);
        assertEquals(
                "감지된 아르카나: 린 · 하얀 달의 온기는 햇빛처럼",
                status.message());
    }

    @Test
    public void explainsFallbackWhenImageWasNotResolved() {
        ArcanaRecognitionStatus status = ArcanaRecognitionStatus.from(
                eventWithSources("one", "two", "three"), "", Set.of());

        assertTrue(status.applicable);
        assertFalse(status.recognized);
        assertEquals("아르카나 이미지 판별 실패 · 전체 결과 표시", status.message());
    }

    @Test
    public void omitsStatusWhenVisualFilteringCannotChangeTheResults() {
        JourneyModels.Outcome outcome = new JourneyModels.Outcome(
                "단일 이벤트 · 린 · 하나뿐인 아르카나", "", "", "효과", "",
                List.of("only"));
        JourneyModels.Event event = new JourneyModels.Event(
                "단일 이벤트", "린 · 하나뿐인 아르카나",
                List.of(new JourneyModels.Choice("선택", List.of(outcome))));

        ArcanaRecognitionStatus status = ArcanaRecognitionStatus.from(
                event, "", Set.of("only"));

        assertFalse(status.applicable);
        assertEquals("", status.message());
    }

    private static JourneyModels.Event eventWithSources(String... ids) {
        String[] sources = {
                "린 · 누각 위, 유리달 맞이",
                "린 · 하얀 달의 온기는 햇빛처럼",
                "린 · 미래의 세 번째 아르카나"
        };
        java.util.ArrayList<JourneyModels.Outcome> outcomes = new java.util.ArrayList<>();
        for (int index = 0; index < ids.length; index++) {
            outcomes.add(new JourneyModels.Outcome(
                    "만물이 지닌 가치 · " + sources[index], "", "", "효과", "",
                    List.of(ids[index])));
        }
        return new JourneyModels.Event(
                "만물이 지닌 가치",
                String.join(" / ", sources),
                List.of(new JourneyModels.Choice("선택", outcomes)));
    }
}
