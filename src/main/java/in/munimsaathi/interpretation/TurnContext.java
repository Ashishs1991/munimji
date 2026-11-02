package in.munimsaathi.interpretation;

final class TurnContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TurnContext() {
    }

    static void set(String value) {
        CURRENT.set(value);
    }

    static String current() {
        return CURRENT.get();
    }

    static void clear() {
        CURRENT.remove();
    }
}
