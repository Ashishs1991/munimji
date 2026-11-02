package in.munimsaathi.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "products")
public class Product {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private String name;

    private String brand;

    private String variant;

    @Column(name = "base_unit", nullable = false)
    private String baseUnit;

    @Column(name = "base_unit_divisible", nullable = false)
    private boolean baseUnitDivisible;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "tax_configuration_status", nullable = false)
    private String taxConfigurationStatus;

    protected Product() {
    }

    public Product(
            UUID id,
            UUID businessId,
            String sku,
            String name,
            String brand,
            String variant,
            String baseUnit,
            boolean baseUnitDivisible
    ) {
        this.id = id;
        this.businessId = businessId;
        this.sku = sku;
        this.name = name;
        this.brand = brand;
        this.variant = variant;
        this.baseUnit = baseUnit;
        this.baseUnitDivisible = baseUnitDivisible;
        this.active = true;
        this.taxConfigurationStatus = "UNCONFIGURED";
    }

    public UUID getId() {
        return id;
    }

    public UUID getBusinessId() {
        return businessId;
    }

    public boolean isActive() { return active; }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getBrand() {
        return brand;
    }

    public String getVariant() {
        return variant;
    }

    public String getBaseUnit() {
        return baseUnit;
    }

    public boolean isBaseUnitDivisible() {
        return baseUnitDivisible;
    }

    public String getTaxConfigurationStatus() {
        return taxConfigurationStatus;
    }
}
