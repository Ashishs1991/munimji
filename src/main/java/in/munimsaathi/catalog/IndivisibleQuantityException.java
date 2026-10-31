package in.munimsaathi.catalog;

public class IndivisibleQuantityException extends RuntimeException {

    public IndivisibleQuantityException() {
        super("This product's base unit does not allow a fractional quantity.");
    }
}
