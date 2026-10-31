package in.munimsaathi.catalog;

public class DuplicateSkuException extends RuntimeException {

    public DuplicateSkuException(String sku) {
        super("SKU " + sku + " is already used in this business.");
    }
}
