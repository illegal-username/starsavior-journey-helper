package helper.journey.starsavior;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class BubbleAppearanceTest {
    @Test
    public void circleProgressKeepsConfiguredEndpoints() {
        assertEquals(30, BubbleAppearance.diameterForProgress(30, 58, 0));
        assertEquals(44, BubbleAppearance.diameterForProgress(30, 58, 50));
        assertEquals(58, BubbleAppearance.diameterForProgress(30, 58, 100));
    }

    @Test
    public void circleProgressIsClamped() {
        assertEquals(30, BubbleAppearance.diameterForProgress(30, 58, -20));
        assertEquals(58, BubbleAppearance.diameterForProgress(30, 58, 140));
    }
}
