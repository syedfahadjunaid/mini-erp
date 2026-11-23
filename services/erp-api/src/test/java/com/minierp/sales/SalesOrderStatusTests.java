package com.minierp.sales;

import static com.minierp.sales.SalesOrderAction.CANCEL;
import static com.minierp.sales.SalesOrderAction.CLOSE;
import static com.minierp.sales.SalesOrderAction.CONFIRM;
import static com.minierp.sales.SalesOrderStatus.CANCELLED;
import static com.minierp.sales.SalesOrderStatus.CLOSED;
import static com.minierp.sales.SalesOrderStatus.CONFIRMED;
import static com.minierp.sales.SalesOrderStatus.DRAFT;
import static com.minierp.sales.SalesOrderStatus.PARTIALLY_SHIPPED;
import static com.minierp.sales.SalesOrderStatus.SHIPPED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minierp.shared.Errors;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SalesOrderStatusTests {

    private static final Map<SalesOrderStatus, Map<SalesOrderAction, SalesOrderStatus>> ALLOWED =
            new EnumMap<>(SalesOrderStatus.class);

    static {
        ALLOWED.put(DRAFT, Map.of(CONFIRM, CONFIRMED, CANCEL, CANCELLED));
        ALLOWED.put(CONFIRMED, Map.of(CANCEL, CANCELLED));
        ALLOWED.put(PARTIALLY_SHIPPED, Map.of(CLOSE, CLOSED));
        ALLOWED.put(SHIPPED, Map.of());
        ALLOWED.put(CLOSED, Map.of());
        ALLOWED.put(CANCELLED, Map.of());
    }

    @Test
    void everyTransitionIsExactlyAsDesigned() {
        for (SalesOrderStatus status : SalesOrderStatus.values()) {
            for (SalesOrderAction action : SalesOrderAction.values()) {
                SalesOrderStatus expected = ALLOWED.get(status).get(action);
                if (expected != null) {
                    assertThat(status.after(action)).as("%s + %s", status, action).isEqualTo(expected);
                } else {
                    assertThat(status.allows(action)).as("%s + %s", status, action).isFalse();
                    assertThatThrownBy(() -> status.after(action)).isInstanceOf(Errors.InvalidTransitionException.class);
                }
            }
        }
    }

    @Test
    void onlyConfirmedOrdersHoldReservationsAndShip() {
        assertThat(CONFIRMED.holdsReservations()).isTrue();
        assertThat(PARTIALLY_SHIPPED.holdsReservations()).isTrue();
        for (SalesOrderStatus status : new SalesOrderStatus[] {DRAFT, SHIPPED, CLOSED, CANCELLED}) {
            assertThat(status.holdsReservations()).isFalse();
            assertThatThrownBy(() -> status.afterShipment(true)).isInstanceOf(Errors.InvalidTransitionException.class);
        }
        assertThat(CONFIRMED.afterShipment(false)).isEqualTo(PARTIALLY_SHIPPED);
        assertThat(PARTIALLY_SHIPPED.afterShipment(true)).isEqualTo(SHIPPED);
    }
}
