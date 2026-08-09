package helper.journey.starsavior;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public class DifficultyResolverTest {
    @Test
    public void resolvesDifficultyFromRecognizedRequirementNumber() {
        assertEquals("이지", DifficultyResolver.fromRecognizedLines(List.of("집중 60 필요")));
        assertEquals("노말", DifficultyResolver.fromRecognizedLines(List.of("보호 80")));
        assertEquals("하드", DifficultyResolver.fromRecognizedLines(List.of("슬라임을 몰아내자", "100")));
    }

    @Test
    public void ignoresUnrelatedNumbers() {
        assertEquals("", DifficultyResolver.fromRecognizedLines(List.of("오래된 동전 30", "Lv. 6")));
    }
}
