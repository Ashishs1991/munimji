package in.munimsaathi.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PartyLedgerRepository extends JpaRepository<PartyLedgerEntry, UUID> {

    @Query("""
            select coalesce(sum(entry.signedPaise), 0)
            from PartyLedgerEntry entry
            where entry.businessId = :businessId
              and entry.partyId = :partyId
              and entry.accountSide = in.munimsaathi.ledger.AccountSide.RECEIVABLE
            """)
    long receivablePaise(@Param("businessId") UUID businessId, @Param("partyId") UUID partyId);

    @Query("""
            select coalesce(sum(entry.signedPaise), 0)
            from PartyLedgerEntry entry
            where entry.businessId = :businessId
              and entry.partyId = :partyId
              and entry.accountSide = in.munimsaathi.ledger.AccountSide.PAYABLE
            """)
    long payablePaise(@Param("businessId") UUID businessId, @Param("partyId") UUID partyId);

    long countByPartyId(UUID partyId);

    boolean existsBySourceDocumentIdAndAccountSideAndReason(
            UUID sourceDocumentId,
            AccountSide accountSide,
            LedgerReason reason
    );

    List<PartyLedgerEntry> findByBusinessIdAndPartyIdAndAccountSideAndReason(
            UUID businessId,
            UUID partyId,
            AccountSide accountSide,
            LedgerReason reason
    );
}
