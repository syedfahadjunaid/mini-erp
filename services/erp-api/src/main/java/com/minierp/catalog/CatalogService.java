package com.minierp.catalog;

import com.minierp.shared.BusinessCalendar;
import com.minierp.shared.Errors;
import com.minierp.shared.Quantities;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Products and their units of measure. Also the catalog module's public API for the others:
 * {@link #product}, {@link #products} and {@link #convert} (typed quantity to base units).
 *
 * <p>Products are JPA entities; the small {@code product_units} table is read and written with
 * plain SQL through {@link JdbcClient}, which is simpler than mapping its composite key.
 */
@Service
@Transactional(readOnly = true)
public class CatalogService {

    /** What other modules need to know about a product. */
    public record ProductInfo(long id, String sku, String name, String baseUom, int taxRateBp, boolean active) {}

    /**
     * A typed quantity and the same amount in base units.
     *
     * @param baseUnitsPerUom how many base units one {@code uom} holds for this product
     */
    public record UnitConversion(long productId, String uom, BigDecimal quantity, long baseUnitsPerUom, long baseQuantity) {}

    private final ProductRepository products;
    private final JdbcClient jdbc;
    private final BusinessCalendar calendar;

    CatalogService(ProductRepository products, JdbcClient jdbc, BusinessCalendar calendar) {
        this.products = products;
        this.jdbc = jdbc;
        this.calendar = calendar;
    }

    @Transactional
    public CatalogDtos.ProductResponse create(CatalogDtos.CreateProductRequest request) {
        requireUnitExists(request.baseUom(), "baseUom");
        if (products.existsBySku(request.sku())) {
            throw Errors.BusinessRuleException.forField("duplicate_sku", "sku", "SKU " + request.sku() + " already exists.");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < request.units().size(); i++) {
            String uom = request.units().get(i).uom();
            String field = "units[" + i + "].uom";
            requireUnitExists(uom, field);
            if (uom.equals(request.baseUom())) {
                throw Errors.BusinessRuleException.forField("base_unit_listed", field,
                        "The base unit is always available with factor 1; don't list it.");
            }
            if (!seen.add(uom)) {
                throw Errors.BusinessRuleException.forField("duplicate_unit", field, uom + " is listed twice.");
            }
        }

        // IDENTITY ids: Hibernate inserts right away, and the database trigger adds the
        // base-unit row (factor 1) to product_units in the same statement.
        Product product = products.save(new Product(request.sku(), request.name(), request.baseUom(),
                request.taxRateBp(), calendar.now()));
        for (CatalogDtos.UnitRequest unit : request.units()) {
            jdbc.sql("INSERT INTO product_units (product_id, uom_code, base_units_per_uom) VALUES (:product, :uom, :factor)")
                    .param("product", product.getId())
                    .param("uom", unit.uom())
                    .param("factor", unit.baseUnitsPerUom())
                    .update();
        }
        return toResponse(product);
    }

    public List<CatalogDtos.ProductResponse> list() {
        return products.findAllByOrderBySkuAsc().stream().map(this::toResponse).toList();
    }

    public CatalogDtos.ProductResponse get(long id) {
        return toResponse(products.findById(id).orElseThrow(() -> new Errors.NotFoundException("Product", id)));
    }

    public List<CatalogDtos.UnitOfMeasureResponse> unitsOfMeasure() {
        return jdbc.sql("SELECT code, name FROM units_of_measure ORDER BY code")
                .query(CatalogDtos.UnitOfMeasureResponse.class)
                .list();
    }

    // ---- public API for other modules ---------------------------------------------------------

    /** The product, or a 422 naming the request field if it doesn't exist or is inactive. */
    public ProductInfo requireActiveProduct(long productId, String field) {
        ProductInfo product = products.findById(productId).map(CatalogService::info)
                .orElseThrow(() -> Errors.BusinessRuleException.forField("unknown_product", field,
                        "Product " + productId + " does not exist."));
        if (!product.active()) {
            throw Errors.BusinessRuleException.forField("inactive_product", field, "Product " + product.sku() + " is inactive.");
        }
        return product;
    }

    public ProductInfo product(long productId) {
        return products.findById(productId).map(CatalogService::info)
                .orElseThrow(() -> new Errors.NotFoundException("Product", productId));
    }

    /** Several products at once (for reports and invoices), keyed by id, in the given order. */
    public Map<Long, ProductInfo> products(Collection<Long> ids) {
        Map<Long, ProductInfo> byId = new LinkedHashMap<>();
        for (Product product : products.findAllById(ids)) {
            byId.put(product.getId(), info(product));
        }
        return byId;
    }

    /**
     * "10 BOX of product 7" in base units, e.g. 120 (EA). A 422 on {@code field} if the product
     * isn't sold in that unit, or the amount isn't a whole number of base units.
     */
    public UnitConversion convert(long productId, String uom, BigDecimal quantity, String field) {
        long factor = baseUnitsPerUom(productId, uom, field);
        return new UnitConversion(productId, uom, quantity, factor, Quantities.toBase(quantity, factor, field));
    }

    public long baseUnitsPerUom(long productId, String uom, String field) {
        return jdbc.sql("SELECT base_units_per_uom FROM product_units WHERE product_id = :product AND uom_code = :uom")
                .param("product", productId)
                .param("uom", uom)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> Errors.BusinessRuleException.forField("unknown_unit", field,
                        "Product " + productId + " has no unit " + uom + "."));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private void requireUnitExists(String uom, String field) {
        boolean exists = jdbc.sql("SELECT count(*) FROM units_of_measure WHERE code = :code")
                .param("code", uom)
                .query(Long.class)
                .single() > 0;
        if (!exists) {
            throw Errors.BusinessRuleException.forField("unknown_unit", field, "Unknown unit of measure: " + uom);
        }
    }

    private CatalogDtos.ProductResponse toResponse(Product product) {
        List<CatalogDtos.UnitResponse> units = jdbc.sql("""
                        SELECT uom_code, base_units_per_uom FROM product_units
                         WHERE product_id = :product ORDER BY base_units_per_uom, uom_code""")
                .param("product", product.getId())
                .query((rs, row) -> new CatalogDtos.UnitResponse(rs.getString("uom_code"), rs.getLong("base_units_per_uom")))
                .list();
        return new CatalogDtos.ProductResponse(product.getId(), product.getSku(), product.getName(), product.getBaseUom(),
                product.getTaxRateBp(), product.isActive(), units);
    }

    private static ProductInfo info(Product product) {
        return new ProductInfo(product.getId(), product.getSku(), product.getName(), product.getBaseUom(),
                product.getTaxRateBp(), product.isActive());
    }
}
