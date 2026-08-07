#!/usr/bin/env node

import fs from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const KO = "ko-KR";
const BASE_URL = "https://star-savior-arcana-db.pages.dev/data";
const FILES = [
    "journeys.json",
    "journey_items.json",
    "potentials.json",
    "stat_potentials.json",
    "journey_buffs.json",
    "arcanas.json",
];

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const projectDir = path.resolve(scriptDir, "..");
const outputPath = path.join(projectDir, "app/src/main/assets/journey_choices.json");

const inputArgIndex = process.argv.indexOf("--input-dir");
const inputDir = inputArgIndex >= 0 ? path.resolve(process.argv[inputArgIndex + 1]) : null;

async function readJson(name) {
    if (inputDir) {
        return JSON.parse(await fs.readFile(path.join(inputDir, name), "utf8"));
    }

    const response = await fetch(`${BASE_URL}/${name}`);
    if (!response.ok) throw new Error(`${name}: HTTP ${response.status}`);
    return response.json();
}

function local(value) {
    if (typeof value === "string") return value;
    return value?.[KO] ?? "";
}

function clean(value) {
    return String(value ?? "")
        .replace(/<color=[^>]*>/gi, "")
        .replace(/<\/color>/gi, "")
        .replace(/<br\s*\/?>/gi, " ")
        .replace(/<[^>]+>/g, "")
        .replace(/\s+/g, " ")
        .trim();
}

function normalize(value) {
    return clean(value).normalize("NFKC").replace(/[^가-힣A-Za-z0-9]/g, "").toLowerCase();
}

function signed(min, max = min) {
    if (typeof min !== "number") return "";
    const mark = (n) => (n > 0 ? `+${n}` : `${n}`);
    return min === max || typeof max !== "number" ? mark(min) : `${mark(min)}~${mark(max)}`;
}

function conciseDescription(value) {
    const description = clean(value);
    const sentences = description.split(/(?<=\.)\s+/).filter(Boolean);
    if (sentences.length <= 1) return description;
    return sentences.slice(1).join(" ");
}

const statNames = {
    JST_POWER: "힘",
    JST_HEALTH: "체력",
    JST_ENDURANCE: "인내",
    JST_FOCUS: "집중",
    JST_PROTECT: "보호",
};

const itemStatNames = {
    UT_JST_POWER: "힘",
    UT_JST_HEALTH: "체력",
    UT_JST_ENDURANCE: "인내",
    UT_JST_FOCUS: "집중",
    UT_JST_PROTECT: "보호",
    UT_PP: "잠재력 포인트",
    UT_STAMINA: "스태미나",
    UT_CONDITION: "컨디션",
    UT_TRAINING_EXP_POWER: "힘 훈련 경험치",
    UT_TRAINING_EXP_HEALTH: "체력 훈련 경험치",
    UT_TRAINING_EXP_ENDURANCE: "인내 훈련 경험치",
    UT_TRAINING_EXP_FOCUS: "집중 훈련 경험치",
    UT_TRAINING_EXP_PROTECT: "보호 훈련 경험치",
};

const [journeys, journeyItems, potentials, statPotentials, buffs, arcanas] = await Promise.all(FILES.map(readJson));

const itemMap = new Map(journeyItems.map((item) => [Number(item.id), item]));
const potentialMap = new Map(potentials.map((item) => [Number(item.id), item]));
const statPotentialMap = new Map(statPotentials.map((item) => [Number(item.id), item]));
const buffMap = new Map(buffs.map((item) => [Number(item.id), item]));

function formatItemStats(item) {
    if (!Array.isArray(item?.stats) || item.stats.length === 0) return "";
    const parts = item.stats.map((stat) => {
        if (itemStatNames[stat.type]) return `${itemStatNames[stat.type]} ${signed(stat.value)}`;
        if (stat.type === "UT_BUFF_DELETE_NEGATIVE") return "해로운 여정 버프 제거";
        if (stat.type === "UT_BUFF_ADD") {
            const buff = buffMap.get(Number(stat.value));
            return buff ? `버프: ${local(buff.name)}` : "여정 버프 획득";
        }
        return stat.type;
    });
    return parts.join(" · ");
}

function formatBuff(rewardId) {
    const buff = buffMap.get(Number(rewardId));
    if (!buff) return "여정 버프 획득";
    const effect = conciseDescription(local(buff.desc));
    const turn = Number(buff.turn) > 0 ? `${buff.turn}턴` : "";
    const detail = [effect, turn].filter(Boolean).join(", ");
    return detail ? `${local(buff.name)} (${detail})` : local(buff.name);
}

function formatPotentialDiscount(reward) {
    if (typeof reward?.min !== "number") return "";
    const percent = (value) => Number((value * 10).toFixed(10)).toString();
    const maximum = typeof reward.max === "number" ? reward.max : reward.min;
    return reward.min === maximum
        ? `${percent(reward.min)}% 할인`
        : `${percent(reward.min)}~${percent(maximum)}% 할인`;
}

function formatPotential(map, rewardId, discount = "") {
    const potential = map.get(Number(rewardId));
    if (!potential) return discount ? `잠재력 ${discount}` : "잠재력 획득";
    const description = clean(local(potential.desc));
    const heading = discount ? `${local(potential.name)} ${discount}` : local(potential.name);
    return description ? `${heading} (${description})` : heading;
}

function formatReward(reward) {
    const value = signed(reward.min, reward.max);
    switch (reward.type) {
        case "RT_STAT":
            return `${statNames[reward.reward_stat] ?? reward.reward_stat} ${value}`;
        case "RT_STAMINA":
            return `스태미나 ${value}`;
        case "RT_CONDITION":
            return `컨디션 ${value}`;
        case "RT_COIN":
            return `오래된 동전 ${value}`;
        case "RT_POTEN_POINT":
            return `잠재력 포인트 ${value}`;
        case "RT_JOURNEY_BUFF":
            return formatBuff(reward.reward_id);
        case "RT_JOURNEY_BUFF_REMOVE_NEG":
            return "해로운 여정 버프 제거";
        case "RT_JOURNEY_BUFF_REMOVE_POS":
            return "이로운 여정 버프 제거";
        case "RT_JOURNEY_ITEM": {
            const item = itemMap.get(Number(reward.reward_id));
            if (!item) return "여정 아이템 획득";
            const stats = formatItemStats(item);
            return stats ? `${local(item.name)} (${stats})` : local(item.name);
        }
        case "RT_SE_POTEN":
            return formatPotential(potentialMap, reward.reward_id, formatPotentialDiscount(reward));
        case "RT_STAT_POTEN":
            return formatPotential(statPotentialMap, reward.reward_id);
        case "SELECTABLE_CHARM":
            return "여정 부적 선택";
        default:
            return reward.type ? `${reward.type}${value ? ` ${value}` : ""}` : "알 수 없는 효과";
    }
}

function formatRewardGroups(groups) {
    if (!Array.isArray(groups) || groups.length === 0) return "효과 없음";
    return groups
        .map((group) => (Array.isArray(group) ? group.map(formatReward).join(" 또는 ") : formatReward(group)))
        .filter(Boolean)
        .join(" · ");
}

function formatCondition(condition) {
    if (!condition) return "";
    switch (condition.type) {
        case "RR_COIN_USE":
            return `오래된 동전 -${condition.value}`;
        case "RR_STAMINA_USE":
            return `스태미나 -${condition.value}`;
        case "RR_PP_USE":
            return `잠재력 포인트 -${condition.value}`;
        case "RR_STAT":
            return `${statNames[condition.target] ?? condition.target} ${condition.value} 필요`;
        case "RR_ITEM_USE": {
            const item = itemMap.get(Number(condition.target));
            return `${item ? local(item.name) : "여정 아이템"} ${condition.value ?? 1}개 소모`;
        }
        default:
            return condition.type;
    }
}

function outcomeFromChoice(choice, label) {
    return {
        label,
        condition: formatCondition(choice.condition),
        success: formatRewardGroups(choice.success_rewards),
        failure:
            Array.isArray(choice.failure_rewards) && choice.failure_rewards.length > 0
                ? formatRewardGroups(choice.failure_rewards)
                : "",
    };
}

const grouped = new Map();

function addRecord({ event, context = "", choiceTexts, outcomes }) {
    if (choiceTexts.length < 2 || choiceTexts.some((text) => !normalize(text))) return;
    // The event title is part of the lookup key. Different events can reuse the
    // exact same choices while granting different rewards (for example fog and
    // lightning weather events), so grouping by choices alone loses information.
    const signature = `${normalize(event)}|${choiceTexts.map(normalize).join("|")}`;
    if (!grouped.has(signature)) grouped.set(signature, []);
    grouped.get(signature).push({ event, context, choiceTexts, outcomes });
}

for (const [eventKey, variants] of Object.entries(journeys)) {
    variants.forEach((variant, variantIndex) => {
        const choiceTexts = (variant.choices ?? []).map((choice) => local(choice.name));
        const difficulty = Array.isArray(variant.difficulties)
            ? variant.difficulties.map(local).filter(Boolean).join("/")
            : "";
        const variantHint = difficulty || (variants.length > 1 ? `경우 ${variantIndex + 1}` : "");
        const event = local(variant.name) || eventKey;
        const label = variantHint ? `${event} · ${variantHint}` : event;
        addRecord({
            event,
            choiceTexts,
            outcomes: (variant.choices ?? []).map((choice) => outcomeFromChoice(choice, label)),
        });
    });
}

for (const arcana of arcanas) {
    for (const eventData of arcana.events ?? []) {
        const choiceTexts = (eventData.choices ?? []).map((choice) => local(choice.name));
        const event = local(eventData.name);
        const context = `${local(arcana.char_name)} · ${local(arcana.name)}`;
        const label = `${event} · ${context}`;
        addRecord({
            event,
            context,
            choiceTexts,
            outcomes: (eventData.choices ?? []).map((choice) => outcomeFromChoice(choice, label)),
        });
    }
}

const records = [];
for (const sources of grouped.values()) {
    const contexts = [...new Set(sources.map((source) => source.context).filter(Boolean))];
    const choices = sources[0].choiceTexts.map((text, choiceIndex) => {
        const unique = new Map();
        for (const source of sources) {
            const outcome = source.outcomes[choiceIndex];
            const key = `${outcome.condition}|${outcome.success}|${outcome.failure}`;
            if (!unique.has(key)) unique.set(key, outcome);
        }
        const outcomes = [...unique.values()];
        if (outcomes.length === 1) outcomes[0].label = "";
        return { text, outcomes };
    });

    records.push({
        event: sources[0].event,
        context: contexts.length <= 2 ? contexts.join(" / ") : `${contexts[0]} 외 ${contexts.length - 1}개`,
        choices,
    });
}

records.sort((a, b) => a.event.localeCompare(b.event, "ko"));

const result = {
    schema: 3,
    generatedAt: new Date().toISOString(),
    source: "https://star-savior-arcana-db.pages.dev/journey",
    notice: "비영리 팬 데이터베이스의 선택지/보상 정보를 가공했습니다. 게임 및 원자료의 권리는 각 권리자에게 있습니다.",
    recordCount: records.length,
    choiceCount: records.reduce((sum, record) => sum + record.choices.length, 0),
    records,
};

await fs.mkdir(path.dirname(outputPath), { recursive: true });
await fs.writeFile(outputPath, `${JSON.stringify(result)}\n`, "utf8");
console.log(`Generated ${result.recordCount} records / ${result.choiceCount} choices -> ${outputPath}`);
console.log("The generated production database is ignored by Git. Do not commit it to the public repository.");
