package helper.journey.starsavior;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class ArcanaImageRecognizerTest {
    @Test
    public void recognizesOneSourceFromAnyNumberOfCandidates() {
        int width = 1600;
        int height = 900;
        int[] screen = new int[width * height];
        java.util.Arrays.fill(screen, 0xff444444);
        ArcanaImageRecognizer.Anchor anchor = new ArcanaImageRecognizer.Anchor(220, 170, 20);
        paintRotatedCard(screen, width, height, anchor, 0xff00ff00);

        Map<String, JourneyModels.ArcanaImageFeature> features = new LinkedHashMap<>();
        features.put("red", solidFeature("red", 3));
        features.put("green", solidFeature("green", 19));
        features.put("blue", solidFeature("blue", 35));
        ArcanaImageRecognizer.Region region = ArcanaImageRecognizer.scanRegion(width, height, anchor);
        int[] crop = crop(screen, width, region);

        ArcanaImageRecognizer.Result result = ArcanaImageRecognizer.recognize(
                region, crop, anchor, features,
                Set.of("red", "green", "blue"));

        assertTrue(result.isConfident());
        assertEquals(Set.of("green"), result.recognizedArcanaIds);
        assertTrue(result.bestDistance < 0.01);
        assertTrue(result.margin > 0.9);
    }

    @Test
    public void equalCandidatesFallBackWithoutFiltering() {
        int width = 1600;
        int height = 900;
        int[] screen = new int[width * height];
        java.util.Arrays.fill(screen, 0xff444444);
        ArcanaImageRecognizer.Anchor anchor = new ArcanaImageRecognizer.Anchor(220, 170, 20);
        paintRotatedCard(screen, width, height, anchor, 0xff00ff00);
        JourneyModels.ArcanaImageFeature green = solidFeature("first", 19);
        Map<String, JourneyModels.ArcanaImageFeature> features = Map.of(
                "first", green,
                "second", new JourneyModels.ArcanaImageFeature("second", green.histogram));
        ArcanaImageRecognizer.Region region = ArcanaImageRecognizer.scanRegion(width, height, anchor);

        ArcanaImageRecognizer.Result result = ArcanaImageRecognizer.recognize(
                region, crop(screen, width, region), anchor, features,
                Set.of("first", "second"));

        assertFalse(result.isConfident());
        assertTrue(result.recognizedArcanaIds.isEmpty());
    }

    @Test
    public void candidateIdsCollectEveryArcanaWithoutAssumingPairSize() {
        JourneyModels.Choice choice = new JourneyModels.Choice("choice", List.of(
                outcome("one"), outcome("two"), outcome("three")));
        JourneyModels.Event event = new JourneyModels.Event("event", "", List.of(choice));

        assertEquals(
                Set.of("one", "two", "three"),
                ArcanaImageRecognizer.candidateIds(event, ""));
    }

    private static JourneyModels.Outcome outcome(String arcanaId) {
        return new JourneyModels.Outcome("", "", "", "", "", List.of(arcanaId));
    }

    private static JourneyModels.ArcanaImageFeature solidFeature(String id, int bin) {
        int[] histogram = new int[JourneyModels.ArcanaImageFeature.HISTOGRAM_SIZE];
        histogram[bin] = 65535;
        return new JourneyModels.ArcanaImageFeature(id, histogram);
    }

    private static void paintRotatedCard(
            int[] pixels, int width, int height, ArcanaImageRecognizer.Anchor anchor, int color) {
        double cardHeight = anchor.titleHeight * 7.8;
        double cardWidth = cardHeight * 704.0 / 1314.0;
        double centerX = anchor.titleLeft - anchor.titleHeight * 4.2;
        double centerY = anchor.titleTop + anchor.titleHeight * 1.2;
        double radians = Math.toRadians(-10.0);
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double dx = x - centerX;
                double dy = y - centerY;
                double localX = cosine * dx + sine * dy;
                double localY = -sine * dx + cosine * dy;
                if (Math.abs(localX) <= cardWidth / 2.0
                        && Math.abs(localY) <= cardHeight / 2.0) {
                    pixels[y * width + x] = color;
                }
            }
        }
    }

    private static int[] crop(int[] screen, int screenWidth, ArcanaImageRecognizer.Region region) {
        int[] result = new int[region.width * region.height];
        for (int row = 0; row < region.height; row++) {
            System.arraycopy(
                    screen, (region.top + row) * screenWidth + region.left,
                    result, row * region.width, region.width);
        }
        return result;
    }
}
