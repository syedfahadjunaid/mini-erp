package com.minierp.partners;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the partners API. */
public final class PartnerDtos {

    private PartnerDtos() {}

    private static final String CODE = "[A-Z0-9][A-Z0-9-]{0,19}";

    public record CreateSupplierRequest(
            @NotBlank @Pattern(regexp = CODE) String code,
            @NotBlank @Size(max = 200) String name,
            @Email @Size(max = 254) String email,
            @NotBlank String currency,
            @Min(0) @Max(365) int paymentTermsDays) {}

    public record SupplierResponse(long id, String code, String name, String email, String currency,
            int paymentTermsDays, boolean active) {}

    public record CreateCustomerRequest(
            @NotBlank @Pattern(regexp = CODE) String code,
            @NotBlank @Size(max = 200) String name,
            @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 200) String addressLine1,
            @Size(max = 200) String addressLine2,
            @NotBlank @Size(max = 20) String postalCode,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Pattern(regexp = "[A-Z]{2}") String countryCode,
            @NotBlank String currency,
            @Min(0) @Max(365) int paymentTermsDays) {}

    public record CustomerResponse(long id, String code, String name, String email, Customer.Address address,
            String currency, int paymentTermsDays, boolean active) {}
}
