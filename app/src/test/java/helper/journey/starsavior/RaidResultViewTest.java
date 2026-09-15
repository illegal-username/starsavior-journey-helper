package helper.journey.starsavior;

import android.content.Context;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RaidResultViewTest {
    @Test public void longRaidRewardsRemainCompleteAtNarrowWidthAndLargeFonts() {
        Configuration config = new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());
        config.fontScale = 2;
        Context context = RuntimeEnvironment.getApplication().createConfigurationContext(config);
        List<RaidModels.Option> options = new ArrayList<>();
        String reward = "A complete reward description with several possible effects. ".repeat(15) + "END";
        for (int i = 1; i <= 3; i++) options.add(new RaidModels.Option(i, "Example raid " + i,
                15700 + i, 13, 5, 3, reward, reward));
        RaidModels.Event event = new RaidModels.Event("Example raid", "Example journey", options);
        RaidModels.Data data = new RaidModels.Data("Recommended rank", "coin", List.of(), List.of(event));
        View view = RaidResultView.render(context, data,
                new RaidModels.Match(true, List.of(event), 2, 15702, true, false), () -> {});
        view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(520, View.MeasureSpec.AT_MOST));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
        List<TextView> texts = new ArrayList<>();
        collect(view, texts);
        int rewards = 0;
        for (TextView text : texts) {
            if (!text.getText().toString().endsWith("END")) continue;
            rewards++;
            assertNotNull(text.getLayout());
            int last = text.getLineCount() - 1;
            assertEquals(text.getText().length(), text.getLayout().getLineEnd(last));
            assertTrue(text.getHeight() >= text.getLayout().getLineBottom(last)
                    + text.getCompoundPaddingTop() + text.getCompoundPaddingBottom());
            View parent = (View) text.getParent();
            assertTrue(text.getBottom() <= parent.getHeight());
        }
        assertEquals(6, rewards);
        assertRanksHidden(texts, 15701, 15702, 15703);
        for (String tier : List.of("I", "II", "III")) {
            assertTrue(texts.stream().anyMatch(t -> t.getText().toString()
                    .startsWith(context.getString(R.string.raid_tier, tier))));
        }
        assertTrue(texts.stream().anyMatch(t -> t.getText().toString().equals(
                context.getString(R.string.raid_tier, "II") + " · " + context.getString(R.string.raid_selected))));
        assertNotNull(view.findViewWithTag("raid_result"));
    }

    @Test public void unresolvedIdenticalEmergencyRewardsAppearOnceWithJourneyCandidatesOnly() {
        Context context = RuntimeEnvironment.getApplication();
        List<RaidModels.Event> events = List.of(RaidRecognitionTest.single("A", 131, "Emergency defeat"),
                RaidRecognitionTest.single("B", 239, "Emergency defeat"),
                RaidRecognitionTest.single("C", 361, "Emergency defeat"));
        RaidModels.Data data = new RaidModels.Data("Recommended rank", "coin", List.of(), events);
        assertTrue(RaidResultView.hasSharedSingleRewards(events));
        View view = RaidResultView.render(context, data,
                new RaidModels.Match(true, events, 0, -1, false, false), () -> {});
        List<TextView> texts = new ArrayList<>();
        collect(view, texts);
        assertEquals(1, texts.stream().filter(t -> t.getText().toString().contains("Emergency victory")).count());
        assertEquals(1, texts.stream().filter(t -> t.getText().toString().equals("Emergency defeat")).count());
        assertTrue(texts.stream().anyMatch(t -> t.getText().toString().equals(context.getString(R.string.raid_common_rewards))));
        assertRanksHidden(texts, 131, 239, 361);
        for (String journey : List.of("A", "B", "C")) {
            assertTrue(texts.stream().anyMatch(t -> t.getText().toString()
                    .contains(context.getString(R.string.raid_journey, journey))));
        }
        assertTrue(texts.stream().anyMatch(t -> t.getText().toString().equals(context.getString(R.string.raid_unknown_journey))));
    }

    @Test public void differentEmergencyRewardsAreKeptSeparateAndResolvedSingleHasNoTierLabel() {
        Context context = RuntimeEnvironment.getApplication();
        List<RaidModels.Event> different = List.of(RaidRecognitionTest.single("A", 131, "defeat A"),
                RaidRecognitionTest.single("B", 239, "defeat B"));
        assertFalse(RaidResultView.hasSharedSingleRewards(different));
        RaidModels.Data data = new RaidModels.Data("Recommended rank", "coin", List.of(), different);
        View view = RaidResultView.render(context, data,
                new RaidModels.Match(true, different, 0, 997, false, true), () -> {});
        List<TextView> texts = new ArrayList<>();
        collect(view,texts);
        assertEquals(2,texts.stream().filter(t -> t.getText().toString().contains("Emergency victory")).count());
        assertRanksHidden(texts, 131, 239, 997);
        assertTrue(texts.stream().anyMatch(t -> t.getText().toString()
                .equals(context.getString(R.string.raid_unknown_journey))));
        View resolved = RaidResultView.render(context,data,
                new RaidModels.Match(true,List.of(different.get(0)),0,131,true,false),() -> {});
        texts.clear(); collect(resolved,texts);
        assertRanksHidden(texts, 131);
        assertEquals(1, texts.stream().filter(t -> t.getText().toString().contains("Emergency victory")).count());
        assertFalse(texts.stream().anyMatch(t -> t.getText().toString().startsWith(context.getString(R.string.raid_tier, "I"))));
        assertFalse(texts.stream().anyMatch(t -> t.getText().toString().contains(context.getString(R.string.raid_selected))));
    }

    private static void assertRanksHidden(List<TextView> texts, int... ranks) {
        for (TextView text : texts) {
            String value = text.getText().toString();
            assertFalse(value, value.contains("Recommended rank"));
            for (int rank : ranks) assertFalse(value, value.contains(Integer.toString(rank)));
        }
    }

    private static void collect(View view, List<TextView> texts) {
        if (view instanceof TextView) texts.add((TextView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), texts);
        }
    }
}
