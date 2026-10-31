package in.munimsaathi.eway;

public record EwayReply(
        String status,
        String ewayBillNo,
        String ewayBillDate,
        String validUpto,
        String error,
        boolean billRequestSent
) {
    public static EwayReply unknown(String error) {
        return new EwayReply(null, null, null, null, error, false);
    }

    public static EwayReply unknownSent(String error) {
        return new EwayReply(null, null, null, null, error, true);
    }
}
