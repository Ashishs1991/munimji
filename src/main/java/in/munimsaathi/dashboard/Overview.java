package in.munimsaathi.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record Overview(
        boolean selectionRequired,
        String businessName,
        LocalDate date,
        List<BusinessChoice> businesses,
        List<StockRow> stock,
        List<String> unknownExpiry,
        long receivablePaise,
        long payablePaise,
        long salesPaise,
        long returnPaise,
        long receiptPaise,
        List<UUID> saleDocumentIds,
        List<UUID> returnDocumentIds,
        List<UUID> receiptDocumentIds,
        int clarificationDrafts,
        int awaitingApproval,
        int financePending,
        List<AlertRow> lowStock,
        boolean forecastAvailable
) {
    public String receivableRupees() {
        return rupees(receivablePaise);
    }

    public String payableRupees() {
        return rupees(payablePaise);
    }

    public String salesRupees() {
        return rupees(salesPaise);
    }

    public String returnRupees() {
        return rupees(returnPaise);
    }

    public String receiptRupees() {
        return rupees(receiptPaise);
    }

    public static String rupees(long paise) {
        boolean negative = paise < 0;
        long absolute = negative ? Math.negateExact(paise) : paise;
        long rupees = absolute / 100;
        long fraction = absolute % 100;
        return (negative ? "-" : "") + rupees + "." + (fraction < 10 ? "0" : "") + fraction;
    }

    public record BusinessChoice(UUID id, String name, boolean selected) {
    }

    public record StockRow(UUID productId, String productName, String godownName, BigDecimal quantity, String quantityText) {
    }

    public record AlertRow(String productName, String observedText, String thresholdText) {
    }
}
