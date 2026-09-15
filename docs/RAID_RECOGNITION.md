# Journey raids and emergency requests

Raid recognition is a separate domain from dialogue and reward choices.
`RaidModels`, `RaidRepository`, `RaidMatcher`, and `RaidResultView` own its data,
validation, decisions, and presentation. Raid records never enter `JourneyMatcher`,
`JourneyRecognitionCoordinator`, or the choice `DifficultyResolver`.

## Screen evidence

The regional OCR pass looks for raid names and request labels before its
stamina-only shortcut. A possible raid uses full-screen OCR with text bounding
boxes. The detail-card title above the recommended-rank label identifies the
selected Roman tier. Only numbers next to that label are rank evidence; the
player's current rank, reward coins, and relative battle-difficulty badges are
not journey-difficulty evidence.

The matcher compares the event name, selected raid tier, and recommended rank
against the supplied records. It has no event names, rank lookup constants, or
resolution-specific offsets. Missing tiers, unreadable ranks, equal candidates,
and unregistered ranks retain the relevant journey candidates. A matching rank
is required even when the current data contains only one journey for a name.
Recommended ranks are internal evidence only and never appear in the result UI.
Selectable raids list all three tiers with their victory/defeat rewards. Single-battle emergency requests have no Roman tier. Adjacent title lines
can be joined when their size and column agree. When journey
difficulty is unknown and every candidate has identical rewards, the result
shows the shared rewards once and retains each journey candidate.

## Data contract

Database schema 6 adds a required `raids` block to the existing language-specific
download envelope. The block has schema 1, localized rank/tier/coin labels, and
records containing `name`, `journeyDifficulty`, and `options`: either three
ordered tiers 1/2/3 or one unnumbered battle with tier 0.
Each option carries `tier`, `title`, `recommendedRank`, `victoryCoin`,
`missionBonusCoin`, `missionCount`, `success`, and `failure`.

The dialogue `records`, `recordCount`, and `choiceCount` keep their existing
meaning. The download and metadata pair still install atomically and share the
exact-byte hash; no second updater can leave mixed revisions behind. The existing
language endpoint can carry schema 5 or 6. Old clients treat schema 6 as an app
update requirement. A raid publication requires app versionCode 57 or later.
The metadata cache contract is incremented so old ETags cannot suppress its
first validation. Valid schema 4/5 local data remains usable for choices.

The private generator opts in with `--include-raids` / `-IncludeRaids` and reads
scenario, turn, request-group, and journey-battle tables. It follows explicit
references, includes single-battle emergency requests, excludes evaluation groups,
then follows separate victory/defeat event IDs and their reward groups.
The defeat event's own `SuccessReward` field is its executed reward anchor;
it must not be confused with failure of a dialogue choice. Alternative rewards
within one group remain alternatives. Missing references, divergent character
variants, conditional anchors, and unsupported new rewards fail generation.

Candidate snapshots and real inputs stay outside this repository. Internal
builds accept schema 5/6 external databases. Production assets contain synthetic
examples only. Publish a compatible app before publishing a new DB/metadata pair.

## Verification

`RaidRecognitionTest` covers rank/tier collisions, player-rank distractors,
Roman OCR variants, moved/scaled bounding boxes, missing evidence, old databases,
single-battle recognition without menu tiers, and schema validation.
`RaidResultViewTest` also checks shared and distinct emergency rewards and
ensures rank labels and values stay hidden in resolved and unresolved results. It verifies long rewards at narrow width
and large fonts on API 28/35. Run the normal Android scope from `CONTRIBUTING.md`.
Private screenshot OCR checks are separate from device ML Kit verification.
