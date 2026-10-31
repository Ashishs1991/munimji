package in.munimsaathi.eway;

public record EwayParty(
        String gstin,
        String name,
        String address,
        String place,
        Integer pincode,
        Integer stateCode,
        Integer actualStateCode
) {
}
