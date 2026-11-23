package com.minierp.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Request and response bodies for inter-warehouse transfers. */
public final class TransferDtos {

    private TransferDtos() {}

    public record CreateTransferRequest(
            @NotNull Long fromWarehouseId,
            @NotNull Long toWarehouseId,
            @NotEmpty @Size(max = 200) List<@Valid LineRequest> lines) {}

    /** @param uom the unit of {@code quantity}; the product's base unit when omitted */
    public record LineRequest(@NotNull Long productId, @NotNull Long fromBinId, @NotNull @Positive BigDecimal quantity,
            String uom) {}

    /** @param toBinId where the goods go; the destination's receiving bin when omitted */
    public record ReceiveTransferRequest(Long toBinId) {}

    public record LineResponse(long id, int lineNo, long productId, long quantity, long fromBinId, Long toBinId) {}

    public record TransferResponse(long id, String transferNumber, long fromWarehouseId, long toWarehouseId,
            TransferStatus status, String createdBy, Instant createdAt, String receivedBy, Instant receivedAt,
            List<LineResponse> lines) {}
}
