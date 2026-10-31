package in.munimsaathi.interpretation;

import in.munimsaathi.draft.DraftProposal;

import java.util.List;

public record InterpretationOutcome(DraftProposal draft, List<String> refusedTools) {
}
