package com.minierp.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * An amount as the API shows it: integer minor units, the currency, and a display string.
 *
 * <p>Why minor units in a {@code long}? Binary floating point can't represent 0.10 exactly
 * (0.1 + 0.2 != 0.3), and rounding errors in money are bugs. Cents are whole numbers, so
 * integer arithmetic is exact, and {@link Math#addExact} turns an overflow into an exception
 * instead of a silently wrong total.
 *
 * <p>Rounding happens in exactly two places, both {@link RoundingMode#HALF_UP} to the minor
 * unit, both once per line: {@link #lineAmount} (quantity x unit price) and {@link #taxOn}
 * (net x rate). Document totals are sums of already-rounded lines, so the lines on an invoice
 * always add up to its total.
 *
 * @param amountMinor e.g. 1250 for 12.50 EUR (or 1250 JPY, which has no minor unit)
 * @param formatted   e.g. "12.50"; for display only, never parse it back
 */
public record Money(long amountMinor, String currency, String formatted) {

    private static final BigDecimal BASIS_POINTS = BigDecimal.valueOf(10_000);

    public static Money of(long amountMinor, String currency) {
        return new Money(amountMinor, currency, format(amountMinor, currency));
    }

    /** "12.50" for 1250 EUR, using the number of decimals ISO 4217 defines for the currency. */
    public static String format(long amountMinor, String currency) {
        int decimals = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        return BigDecimal.valueOf(amountMinor, decimals).toPlainString();
    }

    /** "12.50 EUR", for messages and PDFs. */
    public static String describe(long amountMinor, String currency) {
        return format(amountMinor, currency) + " " + currency;
    }

    /**
     * Quantity times unit price, rounded half up to a whole minor unit:
     * 1.5 KG at 3.99 EUR/KG = 598.5 cents = 5.99 EUR.
     */
    public static long lineAmount(BigDecimal quantity, long unitPriceMinor) {
        return quantity.multiply(BigDecimal.valueOf(unitPriceMinor))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** Tax on a net amount at a rate in basis points (2000 = 20 %), rounded half up. */
    public static long taxOn(long netMinor, int rateBasisPoints) {
        return BigDecimal.valueOf(netMinor)
                .multiply(BigDecimal.valueOf(rateBasisPoints))
                .divide(BASIS_POINTS, 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** Throws a 422 unless {@code code} is an ISO 4217 currency, e.g. "EUR". */
    public static String requireCurrency(String code, String field) {
        try {
            if (code == null || !code.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException();
            }
            return Currency.getInstance(code).getCurrencyCode();
        } catch (IllegalArgumentException e) {
            throw Errors.BusinessRuleException.forField("unknown_currency", field, "Unknown currency: " + code);
        }
    }
}
