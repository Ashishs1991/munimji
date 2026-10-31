package in.munimsaathi.eway;

public record EwayDetails(
        String supplyType,
        String subSupplyType,
        EwayParty from,
        EwayParty to,
        Integer transactionType,
        String distance,
        String transMode,
        String vehicleNo,
        String transporterId,
        Long hsnCode,
        String qtyUnit,
        EwayTax tax
) {
}
