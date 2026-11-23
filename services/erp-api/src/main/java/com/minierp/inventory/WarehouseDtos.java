package com.minierp.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request and response bodies for warehouses and bins. */
public final class WarehouseDtos {

    private WarehouseDtos() {}

    public record CreateWarehouseRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9-]{0,19}") String code,
            @NotBlank @Size(max = 200) String name) {}

    public record CreateBinRequest(@NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9-]{0,29}") String code) {}

    public record BinResponse(long id, long warehouseId, String code, boolean receiving, boolean active) {}

    public record WarehouseResponse(long id, String code, String name, boolean active, List<BinResponse> bins) {}
}
