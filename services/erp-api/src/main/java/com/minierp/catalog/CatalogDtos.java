package com.minierp.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request and response bodies of the catalog API (records: immutable, JSON-friendly). */
public final class CatalogDtos {

    private CatalogDtos() {}

    /**
     * @param units extra units the product is bought or sold in, e.g. {"uom": "BOX", "baseUnitsPerUom": 12}.
     *              The base unit is always available (factor 1) and must not be listed.
     */
    public record CreateProductRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9._-]{1,39}", message = "upper-case letters, digits, . _ -")
            String sku,
            @NotBlank @Size(max = 200) String name,
            @NotBlank String baseUom,
            @Min(0) @Max(10_000) int taxRateBp,
            @Valid List<UnitRequest> units) {

        public CreateProductRequest {
            units = units == null ? List.of() : List.copyOf(units);
        }
    }

    public record UnitRequest(@NotBlank String uom, @Positive long baseUnitsPerUom) {}

    public record UnitResponse(String uom, long baseUnitsPerUom) {}

    public record ProductResponse(long id, String sku, String name, String baseUom, int taxRateBp, boolean active,
            List<UnitResponse> units) {}

    public record UnitOfMeasureResponse(String code, String name) {}
}
