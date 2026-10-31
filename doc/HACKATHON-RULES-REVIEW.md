# Hackathon participant-guide review

Date: 31 October 2026 · Applies to PRD/RFC v1.2

Source: user-supplied participant-guide text from [Cerebral Valley details](https://cerebralvalley.ai/e/meta-ai-developer-hackathon/details). The live page required login; this review is of the pasted guide, not a claim of authenticated access. The broader [official terms](https://cerebralvalley.ai/e/meta-ai-developer-hackathon/waiver) remain relevant.

## Decision

Proceed with a business operations assistant for Agra MSMEs under **Build for Your World**, with the plastic-bag wholesaler as the primary demonstration. This is a theme-fit assessment, not organizer certification or a prediction of selection. The guide does not list inventory/accounting assistants as prohibited categories. Platform compliance, data rights and original-work requirements still apply.

The main competitive risk is presenting generic chat or image extraction. Demonstrate state-changing work: understand a multi-item Hindi receipt, resolve its product/unit ambiguity, save stock and location, process a sale, reconcile a correction, and execute an approved scheduled reminder. A named agent count is not a judging criterion.

## Confirmed requirements and consequences

| Guide requirement | Project response |
|---|---|
| Incorporate Meta models; first-stage check includes Meta Model API/open-weight use | Use Meta inference in the central multimodal and tool workflow; record actual calls/model IDs |
| Build toward one problem statement | Select Build for Your World; connect the story to the real owner |
| Public repository | Verify logged-out access; publish code/config examples/redacted fixtures, never credentials/private records |
| Three-minute demo | Prepare the video to that duration, not just a suggested rehearsal length |
| Video, code and live product inform judging | Provide usable demo access/instructions with isolated data |
| New work and clearly identified contributions | Keep actual dated commits and a concise contribution inventory; show event-built functionality |
| Existing-codebase allowance explicitly mentions startups | Indie reuse of the old Tally connector remains ambiguous; obtain clarification or leave it out |
| Maximum four participants; solo allowed | List everyone who worked on the submission; one representative and one submission per team |
| No late submissions | Submit before Nov 4, 9 PM Eastern / Nov 5, 7:30 AM IST; internal target Nov 4 evening IST |
| Legal/ethical/platform compliance and rights to code/data/assets | Obtain permission and redact real business examples; verify messaging consent/templates |

The guide lists Oct 27–Nov 4 as the building period and Oct 26 kickoff. New implementation beginning Oct 31 is inside the listed period. Clarify only if claiming Oct 26 work or earlier code.

## Judging

1. Pass/fail viability, theme fit and Meta model use.
2. Score 0–10 in impact (30%), Meta tooling (25%), technical depth/execution (20%), demo (25%).
3. Top five per track advance; judges watch video demos live and rank using the same rubric.

The participant guide warns against generic examples including basic RAG and simple image analyzers, as well as medical-advice and political/war-related projects. Do not infer that every project involving an image or retrieval is prohibited: the application must go beyond those basic patterns. Conversely, adding a database does not guarantee it clears the generic-project concern.

The medical-image competitor described earlier cannot be treated as an obviously stronger compliant entry; medical advice/diagnostic claims would require careful consideration under the guide. We do not know that team's final implementation or organizer determination.

## Confirmed bonus categories

| Category | Amount | Relevance |
|---|---:|---|
| Best Agentic Use-Case | $5,000 | Directly relevant: meaningful tasks, decisions, multi-step coordination; judged on autonomy, effectiveness and usefulness |
| Best Build on Meta Open Weight Models | $10,000 | Conditional: Muse Glimmer or Muse Spark Open on own/partner infrastructure; verification required |
| Most Viral Project | $20,000 | Optional post-event promotion; X demo post link submitted on platform; engagement through Nov 8 EOD (timezone not specified in pasted bonus text) |
| Community Vote | $10,000 | Public gallery, one vote per verified account; winner Nov 16 |
| Best Creative Build | $5,000 | Muse Image or Muse Wonder creative project; not our primary target |

Target the main Indie rubric and agentic bonus. Do not add an open-weight deployment or image generation solely for a badge if it weakens execution. No social posting or organizer message has been performed by this review.

## Resources and dates

- Meta credits: $150 per accepted participant, individually claimed. User reports receipt. Guide requires a payment method for API use and describes a temporary preauthorization; verify in the account, do not perform financial setup automatically.
- A “Spend Limit” notification is described; do not assume it stops charges. Implement actual usage controls and inspect available account settings.
- Partner credits are conditional, not guaranteed. Baseten's guide-specific model ID is `meta-models/muse-spark-open-1026` if that route is chosen.
- Nov 3 is the last full day identified for submission questions/troubleshooting.
- Weekday office hours: 10–11 AM ET; on Nov 2–4 this corresponds to 8:30–9:30 PM IST.
- Closing/live final judging: Nov 9 at noon ET = 10:30 PM IST. Guide says the top ten (five per track) are announced then.

## Unsettled points: do not invent rules

1. Can an Indie participant reuse a disclosed pre-event connector while showing only newly built work?
2. Does changing the application idea/domain require notification? The supplied guide does not say.
3. Is a non-Meta auxiliary transcription provider permitted alongside central Meta inference? Not expressly answered; avoid needing it if Meta performs adequately.
4. Exact submission form fields, acceptable video hosting, judging login format and any later announcements require portal confirmation.

Recommended organizer query, if the old connector becomes necessary: ask whether a disclosed pre-event Tally integration is allowed for Indie entries and how to mark it. This is not a blocker to building the new standalone assistant, since Tally is optional.

## Immediate build implications

- Start the new standalone core with real Meta calls and dated source history.
- Prioritize a verified receive → locate → sell → correct → remind journey. Forecasting remains in the agreed MVP; include it only with truthful data coverage and evaluation.
- Keep Tally/e-way optional and do not demo earlier connector work as newly created.
- Keep the full PRD as product scope; a three-minute presentation must emphasize one coherent journey rather than enumerate every screen.
- Before submission, verify public repository/video, demo access, contributor list, source/data rights, and the event-built contribution record.

No runtime implementation, credentials, deployed app or live integrations were tested as part of this review.
