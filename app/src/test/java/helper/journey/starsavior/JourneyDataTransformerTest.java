package helper.journey.starsavior;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JourneyDataTransformerTest {
    @Test
    public void transformsLocalizedDifficultyAndRewards() throws Exception {
        Map<String, String> documents = new LinkedHashMap<>();
        documents.put("journeys.json", """
                {"sample":[{
                  "name":{"ko-KR":"샘플 이벤트"},
                  "difficulties":[{"ko-KR":"이지"}],
                  "choices":[
                    {
                      "name":{"ko-KR":"첫 번째 선택"},
                      "condition":{"type":"RR_ITEM_USE","target":10,"value":1},
                      "success_rewards":[
                        [{"type":"RT_STAT","reward_stat":"JST_POWER","min":10,"max":10}],
                        [{"type":"RT_JOURNEY_BUFF","reward_id":20}]
                      ],
                      "failure_rewards":[[{"type":"RT_STAMINA","min":-5,"max":-5}]]
                    },
                    {
                      "name":{"ko-KR":"두 번째 선택"},
                      "condition":null,
                      "success_rewards":[[{"type":"RT_JOURNEY_ITEM","reward_id":10}]],
                      "failure_rewards":null
                    }
                  ]
                },{
                  "name":{"ko-KR":"샘플 이벤트"},
                  "difficulties":[{"ko-KR":"노말"}],
                  "choices":[
                    {
                      "name":{"ko-KR":"첫 번째 선택"},
                      "condition":{"type":"RR_ITEM_USE","target":10,"value":1},
                      "success_rewards":[[{"type":"RT_STAT","reward_stat":"JST_POWER","min":20,"max":20}]],
                      "failure_rewards":null
                    },
                    {
                      "name":{"ko-KR":"두 번째 선택"},
                      "condition":null,
                      "success_rewards":[[{"type":"RT_JOURNEY_ITEM","reward_id":10}]],
                      "failure_rewards":null
                    }
                  ]
                }]}
                """);
        documents.put("journey_items.json", """
                [{"id":10,"name":{"ko-KR":"활력 포션"},
                  "stats":[{"type":"UT_STAMINA","value":5}]}]
                """);
        documents.put("potentials.json", "[]");
        documents.put("stat_potentials.json", "[]");
        documents.put("journey_buffs.json", """
                [{"id":20,"name":{"ko-KR":"용기"},
                  "desc":{"ko-KR":"마음이 든든합니다. 훈련 효과가 증가합니다."},"turn":3}]
                """);
        documents.put("arcanas.json", "[]");

        String json = JourneyDataTransformer.transform(
                documents, "etag-sha256:test", "2026-08-03T00:00:00Z");
        JourneyModels.Data data = JourneyRepository.parse(json);
        JourneyRepository.validate(data);

        assertEquals(1, data.recordCount);
        assertEquals(2, data.choiceCount);
        assertEquals("etag-sha256:test", data.upstreamRevision);
        JourneyModels.Outcome first = data.events.get(0).choices.get(0).outcomes.get(0);
        assertEquals("이지", first.difficulty);
        assertTrue(first.label.contains("이지"));
        assertFalse(first.label.contains("[object Object]"));
        assertEquals("활력 포션 1개 소모", first.condition);
        assertTrue(first.success.contains("힘 +10"));
        assertTrue(first.success.contains("용기 (훈련 효과가 증가합니다., 3턴)"));
        assertEquals("스태미나 -5", first.failure);
        JourneyModels.Choice secondChoice = data.events.get(0).choices.get(1);
        assertEquals(2, secondChoice.outcomes.size());
        assertEquals(1, secondChoice.outcomesForDifficulty("이지").size());
        assertEquals("이지", secondChoice.outcomesForDifficulty("이지").get(0).difficulty);
        assertEquals(1, secondChoice.outcomesForDifficulty("노말").size());
        assertEquals("노말", secondChoice.outcomesForDifficulty("노말").get(0).difficulty);
        assertTrue(secondChoice.outcomesForDifficulty("노말").get(0).success
                .contains("활력 포션 (스태미나 +5)"));

        JourneyModels.Choice firstChoice = data.events.get(0).choices.get(0);
        assertTrue(firstChoice.outcomesForDifficulty("이지").get(0).success.contains("힘 +10"));
        assertTrue(firstChoice.outcomesForDifficulty("노말").get(0).success.contains("힘 +20"));
    }

    @Test
    public void keepsPotentialDiscountAndDescriptionTogether() throws Exception {
        Map<String, String> documents = new LinkedHashMap<>();
        documents.put("journeys.json", """
                {"merchant":[{"name":{"ko-KR":"정체불명의 상인"},"choices":[
                  {"name":{"ko-KR":"공격 비술서"},"condition":null,
                   "success_rewards":[[
                     {"type":"RT_SE_POTEN","reward_id":21001,"min":1,"max":1},
                     {"type":"RT_SE_POTEN","reward_id":21001,"min":2,"max":2}
                   ]],"failure_rewards":null},
                  {"name":{"ko-KR":"그냥 받기"},"condition":null,
                   "success_rewards":[[{"type":"RT_SE_POTEN","reward_id":21002}]],
                   "failure_rewards":null}
                ]}]}
                """);
        documents.put("journey_items.json", "[]");
        documents.put("potentials.json", """
                [
                  {"id":21001,"name":{"ko-KR":"공격의 솜씨"},
                   "desc":{"ko-KR":"공격력이 2% 증가합니다."}},
                  {"id":21002,"name":{"ko-KR":"생명의 솜씨"},
                   "desc":{"ko-KR":"최대 생명력이 2% 증가합니다."}}
                ]
                """);
        documents.put("stat_potentials.json", "[]");
        documents.put("journey_buffs.json", "[]");
        documents.put("arcanas.json", "[]");

        JourneyModels.Data data = JourneyRepository.parse(JourneyDataTransformer.transform(
                documents, "test", "2026-08-07T00:00:00Z"));
        JourneyRepository.validate(data);

        JourneyModels.Outcome discounted = data.events.get(0).choices.get(0).outcomes.get(0);
        assertEquals("공격의 솜씨 10% 할인 (공격력이 2% 증가합니다.) 또는 "
                + "공격의 솜씨 20% 할인 (공격력이 2% 증가합니다.)", discounted.success);

        JourneyModels.Outcome granted = data.events.get(0).choices.get(1).outcomes.get(0);
        assertEquals("생명의 솜씨 (최대 생명력이 2% 증가합니다.)", granted.success);
        assertFalse(granted.success.contains("할인"));
    }

    @Test
    public void keepsDifferentEventTitlesWithSameChoicesSeparate() throws Exception {
        Map<String, String> documents = new LinkedHashMap<>();
        documents.put("journeys.json", """
                {
                  "morning":[{"name":{"ko-KR":"공개 예제 - 아침"},"choices":[
                    {"name":{"ko-KR":"첫 번째 예제 선택"},"success_rewards":[[{"type":"RT_STAT","reward_stat":"JST_FOCUS","min":20,"max":20}]]},
                    {"name":{"ko-KR":"두 번째 예제 선택"},"success_rewards":[[{"type":"RT_STAMINA","min":10,"max":10}]]}
                  ]}],
                  "evening":[{"name":{"ko-KR":"공개 예제 - 저녁"},"choices":[
                    {"name":{"ko-KR":"첫 번째 예제 선택"},"success_rewards":[[{"type":"RT_CONDITION","min":1,"max":1}]]},
                    {"name":{"ko-KR":"두 번째 예제 선택"},"success_rewards":[[{"type":"RT_CONDITION","min":-1,"max":-1}]]}
                  ]}]
                }
                """);
        documents.put("journey_items.json", "[]");
        documents.put("potentials.json", "[]");
        documents.put("stat_potentials.json", "[]");
        documents.put("journey_buffs.json", "[]");
        documents.put("arcanas.json", "[]");

        JourneyModels.Data data = JourneyRepository.parse(JourneyDataTransformer.transform(
                documents, "test", "2026-08-03T00:00:00Z"));
        JourneyRepository.validate(data);

        assertEquals(4, data.schema);
        assertEquals(2, data.recordCount);
        assertEquals(4, data.choiceCount);
        assertTrue(data.events.stream().anyMatch(event -> event.name.equals("공개 예제 - 아침")));
        assertTrue(data.events.stream().anyMatch(event -> event.name.equals("공개 예제 - 저녁")));
    }
}
