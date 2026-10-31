package in.munimsaathi.inventory;

import java.math.BigDecimal;
import java.util.UUID;

public interface StockSlice {

    UUID getLocationId();

    UUID getProductId();

    BigDecimal getQuantity();
}
