package helper.journey.starsavior;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DatasetIntegrationTest {
    @Test
    public void publicExampleDatasetIsValidAndContainsNoProductionRecords() throws Exception {
        Path asset = exampleAsset();
        JourneyModels.Data data = JourneyRepository.parse(new String(Files.readAllBytes(asset), StandardCharsets.UTF_8));
        JourneyRepository.validate(data);

        assertEquals("public-example", data.source);
        assertEquals(2, data.recordCount);
        assertEquals(4, data.choiceCount);
    }

    @Test
    public void publicExampleExercisesEventTitleDisambiguation() throws Exception {
        Path asset = exampleAsset();
        JourneyModels.Data data = JourneyRepository.parse(new String(Files.readAllBytes(asset), StandardCharsets.UTF_8));
        JourneyRepository.validate(data);

        JourneyMatcher matcher = new JourneyMatcher(data.events);
        JourneyModels.Match match = matcher.match(
                List.of("여정 이벤트", "공개 예제 - 아침"),
                List.of("첫 번째 예제 선택", "두 번째 예제 선택"));

        assertTrue(match.isConfident());
        assertEquals("공개 예제 - 아침", match.event.name);
        assertEquals(1, match.event.choices.get(0).outcomes.size());
        assertEquals("예제 효과 A", match.event.choices.get(0).outcomes.get(0).success);
    }

    private static Path exampleAsset() {
        Path asset = Path.of("app/src/main/assets/journey_choices.example.json");
        if (!Files.exists(asset)) asset = Path.of("src/main/assets/journey_choices.example.json");
        return asset;
    }
}
