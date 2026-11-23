package com.minierp.purchasing;

import static com.minierp.purchasing.PurchaseOrderAction.APPROVE;
import static com.minierp.purchasing.PurchaseOrderAction.CANCEL;
import static com.minierp.purchasing.PurchaseOrderAction.CLOSE;
import static com.minierp.purchasing.PurchaseOrderAction.REJECT;
import static com.minierp.purchasing.PurchaseOrderAction.SUBMIT;
import static com.minierp.purchasing.PurchaseOrderStatus.APPROVED;
import static com.minierp.purchasing.PurchaseOrderStatus.CANCELLED;
import static com.minierp.purchasing.PurchaseOrderStatus.CLOSED;
import static com.minierp.purchasing.PurchaseOrderStatus.DRAFT;
import static com.minierp.purchasing.PurchaseOrderStatus.PARTIALLY_RECEIVED;
import static com.minierp.purchasing.PurchaseOrderStatus.RECEIVED;
import static com.minierp.purchasing.PurchaseOrderStatus.SUBMITTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minierp.shared.Errors;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The whole transition table, written out: every (status, action) pair is either listed or refused. */
class PurchaseOrderStatusTests {

    private static final Map<PurchaseOrderStatus, Map<PurchaseOrderAction, PurchaseOrderStatus>> ALLOWED =
            new EnumMap<>(PurchaseOrderStatus.class);

    static {
        ALLOWED.put(DRAFT, Map.of(SUBMIT, SUBMITTED, CANCEL, CANCELLED));
        ALLOWED.put(SUBMITTED, Map.of(APPROVE, APPROVED, REJECT, DRAFT, CANCEL, CANCELLED));
        ALLOWED.put(APPROVED, Map.of(CANCEL, CANCELLED));
        ALLOWED.put(PARTIALLY_RECEIVED, Map.of(CLOSE, CLOSED));
        ALLOWED.put(RECEIVED, Map.of());
        ALLOWED.put(CLOSED, Map.of());
        ALLOWED.put(CANCELLED, Map.of());
    }

    @Test
    void everyTransitionIsExactlyAsDesigned() {
        for (PurchaseOrderStatus status : PurchaseOrderStatus.values()) {
            for (PurchaseOrderAction action : PurchaseOrderAction.values()) {
                PurchaseOrderStatus expected = ALLOWED.get(status).get(action);
                if (expected != null) {
                    assertThat(status.after(action)).as("%s + %s", status, action).isEqualTo(expected);
                    assertThat(status.allows(action)).isTrue();
                } else {
                    assertThat(status.allows(action)).as("%s + %s", status, action).isFalse();
                    assertThatThrownBy(() -> status.after(action)).isInstanceOf(Errors.InvalidTransitionException.class);
                }
            }
        }
    }

    @Test
    void receiptsOnlyAgainstApprovedOrders() {
        assertThat(APPROVED.afterReceipt(false)).isEqualTo(PARTIALLY_RECEIVED);
        assertThat(APPROVED.afterReceipt(true)).isEqualTo(RECEIVED);
        assertThat(PARTIALLY_RECEIVED.afterReceipt(true)).isEqualTo(RECEIVED);
        for (PurchaseOrderStatus status : new PurchaseOrderStatus[] {DRAFT, SUBMITTED, RECEIVED, CLOSED, CANCELLED}) {
            assertThat(status.acceptsReceipts()).isFalse();
            assertThatThrownBy(() -> status.afterReceipt(true)).isInstanceOf(Errors.InvalidTransitionException.class);
        }
    }
}
