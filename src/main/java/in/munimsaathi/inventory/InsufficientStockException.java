package in.munimsaathi.inventory;

public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException() {
        super("There is not enough stock at that godown.");
    }
}
