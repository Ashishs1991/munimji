package in.munimsaathi.tenant;

public class ActiveBusinessRequiredException extends RuntimeException {

    public ActiveBusinessRequiredException() {
        super("Choose which business is active before continuing.");
    }
}
