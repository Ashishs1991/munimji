package in.munimsaathi.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    Optional<Product> findByBusinessIdAndSku(UUID businessId, String sku);

    boolean existsByBusinessIdAndSku(UUID businessId, String sku);

    @Query("""
            select p from Product p
            where p.businessId = :businessId
              and p.active = true
              and (
                lower(p.name) like lower(concat('%', :term, '%'))
                or lower(coalesce(p.brand, '')) like lower(concat('%', :term, '%'))
                or lower(coalesce(p.variant, '')) like lower(concat('%', :term, '%'))
                or lower(p.sku) like lower(concat('%', :term, '%'))
              )
            order by p.sku
            """)
    List<Product> search(@Param("businessId") UUID businessId, @Param("term") String term);
}
