package in.munimsaathi.tenant;

public class BusinessAccessDeniedException extends RuntimeException {

    public BusinessAccessDeniedException() {
        super("Select a business you belong to.");
    }
}
