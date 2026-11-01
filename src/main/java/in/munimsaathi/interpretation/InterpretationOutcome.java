package in.munimsaathi.interpretation;

import in.munimsaathi.draft.DraftProposal;

import java.util.List;
import java.util.UUID;

public record InterpretationOutcome(DraftProposal draft, List<String> refusedTools, UUID ewayDraftId, String answer) {
}
