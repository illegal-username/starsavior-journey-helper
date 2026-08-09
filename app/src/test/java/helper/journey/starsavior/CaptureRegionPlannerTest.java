package helper.journey.starsavior;

import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class CaptureRegionPlannerTest {
    @Test
    public void widePhoneKeepsEstablishedChoiceAndEventAreas() {
        CaptureRegionPlanner.Region choice = CaptureRegionPlanner.choice(3120, 1440);
        CaptureRegionPlanner.Region event = CaptureRegionPlanner.event(3120, 1440);
        assertTrue(choice.contains(2500, 700));
        assertTrue(event.contains(700, 300));
    }

    @Test
    public void foldableUsesExpandedTallLayoutAreas() {
        CaptureRegionPlanner.Region choice = CaptureRegionPlanner.choice(1280, 1153);
        CaptureRegionPlanner.Region event = CaptureRegionPlanner.event(1280, 1153);
        assertTrue(choice.contains(1050, 850));
        assertTrue(event.contains(260, 180));
    }
}
