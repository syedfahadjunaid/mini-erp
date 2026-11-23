package com.minierp.inventory;

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

/** Warehouses and their bins. Everyone signed in can read; only ADMIN creates. */
@RestController
@RequestMapping("/api/warehouses")
class WarehouseController {

    private final WarehouseService warehouses;

    WarehouseController(WarehouseService warehouses) {
        this.warehouses = warehouses;
    }

    @GetMapping
    List<WarehouseDtos.WarehouseResponse> list() {
        return warehouses.list();
    }

    @GetMapping("/{id}")
    WarehouseDtos.WarehouseResponse get(@PathVariable long id) {
        return warehouses.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    WarehouseDtos.WarehouseResponse create(@Valid @RequestBody WarehouseDtos.CreateWarehouseRequest request) {
        return warehouses.create(request);
    }

    @PostMapping("/{id}/bins")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    WarehouseDtos.BinResponse addBin(@PathVariable long id, @Valid @RequestBody WarehouseDtos.CreateBinRequest request) {
        return warehouses.addBin(id, request);
    }
}
