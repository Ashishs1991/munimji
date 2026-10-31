package in.munimsaathi.inventory;

public class StaleCountException extends RuntimeException {

    public StaleCountException() {
        super("Stock changed after this count was proposed. Count it again before confirming.");
    }
}
