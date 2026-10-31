package in.munimsaathi.catalog;

import in.munimsaathi.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class CatalogService {

    private static final int CANDIDATE_LIMIT = 20;

    private final TenantContext tenants;
    private final ProductRepository products;
    private final ProductAliasRepository aliases;
    private final ProductUnitRepository units;

    public CatalogService(
            TenantContext tenants,
            ProductRepository products,
            ProductAliasRepository aliases,
            ProductUnitRepository units
    ) {
        this.tenants = tenants;
        this.products = products;
        this.aliases = aliases;
        this.units = units;
    }

    @Transactional
    public Product registerProduct(
            UUID userId,
            String sku,
            String name,
            String brand,
            String variant,
            String baseUnit,
            boolean baseUnitDivisible
    ) {
        UUID businessId = tenants.currentBusinessId(userId);
        String normalizedSku = sku.trim();
        if (products.existsByBusinessIdAndSku(businessId, normalizedSku)) {
            throw new DuplicateSkuException(normalizedSku);
        }
        String unit = Names.normalize(baseUnit);
        Product product = products.save(new Product(
                UUID.randomUUID(),
                businessId,
                normalizedSku,
                name.trim(),
                blankToNull(brand),
                blankToNull(variant),
                unit,
                baseUnitDivisible
        ));
        units.save(new ProductUnit(UUID.randomUUID(), product.getId(), unit, BigDecimal.ONE, userId));
        return product;
    }

    @Transactional
    public void approveAlias(UUID userId, UUID productId, String alias) {
        UUID businessId = tenants.currentBusinessId(userId);
        Product product = requireProduct(businessId, productId);
        aliases.save(new ProductAlias(
                UUID.randomUUID(),
                businessId,
                product.getId(),
                Names.normalize(alias),
                null
        ));
    }

    @Transactional
    public void approveUnit(UUID userId, UUID productId, String unitLabel, BigDecimal factorToBase) {
        if (factorToBase == null || factorToBase.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("A pack conversion must be greater than zero.");
        }
        UUID businessId = tenants.currentBusinessId(userId);
        Product product = requireProduct(businessId, productId);
        String label = Names.normalize(unitLabel);
        if (units.findByProductIdAndUnitLabel(product.getId(), label).isPresent()) {
            throw new IllegalArgumentException("Unit " + label + " is already approved. Add a new unit instead of rewriting it.");
        }
        units.save(new ProductUnit(UUID.randomUUID(), product.getId(), label, factorToBase, userId));
    }

    @Transactional(readOnly = true)
    public BigDecimal toBaseQuantity(UUID userId, UUID productId, BigDecimal displayQuantity, String unitLabel) {
        return convert(userId, productId, displayQuantity, unitLabel).baseQuantity();
    }

    @Transactional(readOnly = true)
    public QuantityConversion convert(UUID userId, UUID productId, BigDecimal displayQuantity, String unitLabel) {
        UUID businessId = tenants.currentBusinessId(userId);
        Product product = requireProduct(businessId, productId);
        if (displayQuantity == null) {
            throw new IllegalArgumentException("Quantity is required.");
        }
        ProductUnit unit = units.findByProductIdAndUnitLabel(product.getId(), Names.normalize(unitLabel))
                .orElseThrow(() -> new UnknownUnitException(unitLabel));
        BigDecimal base = displayQuantity.multiply(unit.getFactorToBase());
        if (!product.isBaseUnitDivisible() && !isWhole(base)) {
            throw new IndivisibleQuantityException();
        }
        return new QuantityConversion(displayQuantity, unit.getUnitLabel(), unit.getFactorToBase(), base);
    }

    @Transactional(readOnly = true)
    public Optional<Product> resolveApproved(UUID userId, String text) {
        UUID businessId = tenants.currentBusinessId(userId);
        String sku = text.trim();
        Optional<Product> bySku = products.findByBusinessIdAndSku(businessId, sku);
        if (bySku.isPresent()) {
            return bySku;
        }
        return aliases.findByBusinessIdAndNormalizedAliasAndSupplierPartyIdIsNull(businessId, Names.normalize(text))
                .flatMap(alias -> products.findById(alias.getProductId()))
                .filter(product -> product.getBusinessId().equals(businessId));
    }

    @Transactional(readOnly = true)
    public List<Product> candidates(UUID userId, String term) {
        if (term == null || term.isBlank()) {
            return List.of();
        }
        UUID businessId = tenants.currentBusinessId(userId);
        return products.search(businessId, term.trim()).stream().limit(CANDIDATE_LIMIT).toList();
    }

    private Product requireProduct(UUID businessId, UUID productId) {
        Product product = products.findById(productId).orElseThrow(() -> new IllegalArgumentException("Unknown product."));
        if (!product.getBusinessId().equals(businessId)) {
            throw new IllegalArgumentException("Unknown product.");
        }
        return product;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static boolean isWhole(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0;
    }
}
