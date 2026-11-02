package in.munimsaathi.interpretation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConversationTurnRepository extends JpaRepository<ConversationTurn, UUID> {

    List<ConversationTurn> findTop12ByBusinessIdAndActorIdOrderByCreatedAtDesc(UUID businessId, UUID actorId);
}
