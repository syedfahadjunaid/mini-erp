package com.minierp.replenishment;

import com.minierp.shared.Actor;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reorder points (MANAGER sets them) and low-stock alerts (everyone sees them). */
@RestController
@RequestMapping("/api")
class ReplenishmentController {

    private final ReplenishmentService replenishment;

    ReplenishmentController(ReplenishmentService replenishment) {
        this.replenishment = replenishment;
    }

    @GetMapping("/reorder-rules")
    List<ReplenishmentDtos.ReorderRuleResponse> rules(@RequestParam(required = false) Long warehouseId) {
        return replenishment.rules(warehouseId);
    }

    @PutMapping("/reorder-rules/{warehouseId}/{productId}")
    @PreAuthorize("hasRole('MANAGER')")
    ReplenishmentDtos.ReorderRuleResponse setRule(@PathVariable long warehouseId, @PathVariable long productId,
            @Valid @RequestBody ReplenishmentDtos.ReorderRuleRequest request, Actor actor) {
        return replenishment.setRule(warehouseId, productId, request, actor);
    }

    @GetMapping("/alerts")
    List<ReplenishmentDtos.AlertResponse> alerts(@RequestParam(required = false) AlertStatus status,
            @RequestParam(required = false) Long warehouseId) {
        return replenishment.alerts(status, warehouseId);
    }

    @PostMapping("/alerts/{id}/acknowledge")
    @PreAuthorize("hasAnyRole('MANAGER', 'PURCHASING')")
    ReplenishmentDtos.AlertResponse acknowledge(@PathVariable long id, Actor actor) {
        return replenishment.acknowledge(id, actor);
    }
}
