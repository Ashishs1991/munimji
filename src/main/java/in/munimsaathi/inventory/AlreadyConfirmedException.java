package in.munimsaathi.inventory;

public class AlreadyConfirmedException extends RuntimeException {

    public AlreadyConfirmedException() {
        super("This count is already confirmed. A recount needs a new count session.");
    }
}
