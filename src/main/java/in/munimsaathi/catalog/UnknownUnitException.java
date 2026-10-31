package in.munimsaathi.catalog;

public class UnknownUnitException extends RuntimeException {

    public UnknownUnitException(String unitLabel) {
        super("Unit " + unitLabel + " has not been approved for this product.");
    }
}
