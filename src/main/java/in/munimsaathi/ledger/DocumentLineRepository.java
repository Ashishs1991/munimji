package in.munimsaathi.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface DocumentLineRepository extends JpaRepository<DocumentLine, UUID> {

    @Query("""
            select line.baseQuantity
            from DocumentLine line
            where line.documentId = :documentId and line.productId = :productId
            """)
    List<BigDecimal> baseQuantities(@Param("documentId") UUID documentId, @Param("productId") UUID productId);

    @Query("""
            select line.baseQuantity
            from DocumentLine line, PostedDocument document
            where line.documentId = document.id
              and document.sourceDocumentId = :saleId
              and document.kind = in.munimsaathi.ledger.DocumentKind.CREDIT
              and line.productId = :productId
            """)
    List<BigDecimal> returnedBaseQuantities(@Param("saleId") UUID saleId, @Param("productId") UUID productId);
}
