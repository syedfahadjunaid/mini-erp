package com.minierp.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Converting between the unit people type (10 BOX, 1.5 KG) and the product's base unit, in
 * which all stock is counted (120 EA, 1500 G).
 *
 * <p>Base quantities are whole numbers ({@code long}), so stock arithmetic is exact. The
 * conversion refuses anything that isn't a whole number of base units: for a product counted
 * in EA, "0.5 EA" or "1.25 BOX of 6" is rejected rather than rounded, because rounding stock
 * silently loses or invents goods.
 */
public final class Quantities {

    /** Typed quantities have at most 3 decimals (NUMERIC(18,3) in the database). */
    public static final int MAX_SCALE = 3;

    private Quantities() {}

    /**
     * @param quantity        what was typed, in some unit of the product
     * @param baseUnitsPerUom how many base units one of that unit holds (1 for the base unit itself)
     * @param field           the request field, for the error message
     * @return the same amount in base units
     */
    public static long toBase(BigDecimal quantity, long baseUnitsPerUom, String field) {
        if (quantity.signum() <= 0) {
            throw Errors.BusinessRuleException.forField("invalid_quantity", field, "Quantity must be greater than zero.");
        }
        if (quantity.stripTrailingZeros().scale() > MAX_SCALE) {
            throw Errors.BusinessRuleException.forField("invalid_quantity", field,
                    "Quantity may have at most " + MAX_SCALE + " decimals.");
        }
        BigDecimal base = quantity.multiply(BigDecimal.valueOf(baseUnitsPerUom));
        try {
            return base.stripTrailingZeros().longValueExact();
        } catch (ArithmeticException notWhole) {
            throw Errors.BusinessRuleException.forField("fractional_base_quantity", field,
                    quantity.stripTrailingZeros().toPlainString() + " is " + base.stripTrailingZeros().toPlainString()
                            + " base units; stock is counted in whole base units.");
        }
    }

    /**
     * Base units back into a unit, for display (e.g. "remaining: 2.5 BOX"). Rounds half up to 3
     * decimals, so only use the result for showing, never for stock arithmetic.
     */
    public static BigDecimal fromBase(long baseQuantity, long baseUnitsPerUom) {
        return BigDecimal.valueOf(baseQuantity).divide(BigDecimal.valueOf(baseUnitsPerUom), MAX_SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }
}
