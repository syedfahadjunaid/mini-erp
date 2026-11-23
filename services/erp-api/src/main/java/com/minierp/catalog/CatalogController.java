package com.minierp.catalog;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Products and units of measure. Everyone signed in can read; only ADMIN creates. */
@RestController
@RequestMapping("/api")
class CatalogController {

    private final CatalogService catalog;

    CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/units-of-measure")
    List<CatalogDtos.UnitOfMeasureResponse> unitsOfMeasure() {
        return catalog.unitsOfMeasure();
    }

    @GetMapping("/products")
    List<CatalogDtos.ProductResponse> list() {
        return catalog.list();
    }

    @GetMapping("/products/{id}")
    CatalogDtos.ProductResponse get(@PathVariable long id) {
        return catalog.get(id);
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    CatalogDtos.ProductResponse create(@Valid @RequestBody CatalogDtos.CreateProductRequest request) {
        return catalog.create(request);
    }
}
