package in.munimsaathi.whatsapp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InboundMessageRepository extends JpaRepository<InboundMessage, UUID> {

    @Modifying
    @Query(value = """
            insert into inbound_messages
                (id, business_id, user_id, message_id, phone, body, status, media_id, media_type, approval_bound)
            values
                (:id, :businessId, :userId, :messageId, :phone, :body, :status, :mediaId, :mediaType, false)
            on conflict (message_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("businessId") UUID businessId,
            @Param("userId") UUID userId,
            @Param("messageId") String messageId,
            @Param("phone") String phone,
            @Param("body") String body,
            @Param("status") String status,
            @Param("mediaId") String mediaId,
            @Param("mediaType") String mediaType
    );

    Optional<InboundMessage> findByMessageId(String messageId);

    List<InboundMessage> findByStatusAndDraftIdIsNull(InboundStatus status);

    List<InboundMessage> findByStatus(InboundStatus status);

    @Query(value = """
            select * from inbound_messages
            where business_id = :businessId and user_id = :userId and phone = :phone
              and proposal_shown_at is not null and status = 'REPLIED' and approval_bound = false
            order by proposal_shown_at desc limit 1
            """, nativeQuery = true)
    Optional<InboundMessage> latestShownProposal(@Param("businessId") UUID businessId,
                                                @Param("userId") UUID userId,
                                                @Param("phone") String phone);

    @Query(value = """
            select * from inbound_messages
            where business_id = :businessId and user_id = :userId and phone = :phone
              and status = 'REPLIED' and reply_sent_at is not null
            order by reply_sent_at desc limit 1
            """, nativeQuery = true)
    Optional<InboundMessage> latestReply(@Param("businessId") UUID businessId,
                                       @Param("userId") UUID userId, @Param("phone") String phone);

    long countByMessageId(String messageId);
}
