package in.munimsaathi.ledger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaiseTest {

    @Test
    void convertsWholeRupeesWithoutFractions() {
        assertThat(Paise.fromRupees(1000)).isEqualTo(100_000L);
        assertThat(Paise.fromRupees(400)).isEqualTo(40_000L);
        assertThat(Paise.subtract(Paise.fromRupees(1000), Paise.fromRupees(400))).isEqualTo(60_000L);
    }

    @Test
    void rejectsOverflow() {
        assertThatThrownBy(() -> Paise.add(Long.MAX_VALUE, 1)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Paise.fromRupees(Long.MAX_VALUE)).isInstanceOf(ArithmeticException.class);
    }
}
