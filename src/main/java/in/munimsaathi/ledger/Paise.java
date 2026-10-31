package in.munimsaathi.ledger;

public final class Paise {

    private Paise() {
    }

    public static long fromRupees(long rupees) {
        return Math.multiplyExact(rupees, 100);
    }

    public static long add(long left, long right) {
        return Math.addExact(left, right);
    }

    public static long subtract(long left, long right) {
        return Math.subtractExact(left, right);
    }
}
