package in.munimsaathi.whatsapp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InboundMessageRepository extends JpaRepository<InboundMessage, UUID> {

    Optional<InboundMessage> findByMessageId(String messageId);

    List<InboundMessage> findByStatusAndDraftIdIsNull(InboundStatus status);

    List<InboundMessage> findByStatus(InboundStatus status);

    long countByMessageId(String messageId);
}
