package com.minierp.inventory;

import com.minierp.shared.BusinessCalendar;
import com.minierp.shared.Errors;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Warehouses and bins, and the lookups other modules use to validate their input. */
@Service
@Transactional(readOnly = true)
public class WarehouseService {

    /** What other modules need to know about a warehouse. */
    public record WarehouseInfo(long id, String code, String name, boolean active) {}

    /** What other modules need to know about a bin. */
    public record BinInfo(long id, long warehouseId, String code, boolean receiving) {}

    private final WarehouseRepository warehouses;
    private final BinLocationRepository bins;
    private final BusinessCalendar calendar;

    WarehouseService(WarehouseRepository warehouses, BinLocationRepository bins, BusinessCalendar calendar) {
        this.warehouses = warehouses;
        this.bins = bins;
        this.calendar = calendar;
    }

    /** Creates the warehouse together with its RECEIVING bin (where goods land by default). */
    @Transactional
    public WarehouseDtos.WarehouseResponse create(WarehouseDtos.CreateWarehouseRequest request) {
        if (warehouses.existsByCode(request.code())) {
            throw Errors.BusinessRuleException.forField("duplicate_code", "code", "Warehouse " + request.code() + " already exists.");
        }
        Warehouse warehouse = warehouses.save(new Warehouse(request.code(), request.name(), calendar.now()));
        bins.save(new BinLocation(warehouse.getId(), BinLocation.RECEIVING_CODE, true, calendar.now()));
        return toResponse(warehouse);
    }

    @Transactional
    public WarehouseDtos.BinResponse addBin(long warehouseId, WarehouseDtos.CreateBinRequest request) {
        Warehouse warehouse = warehouses.findById(warehouseId).orElseThrow(() -> new Errors.NotFoundException("Warehouse", warehouseId));
        if (bins.existsByWarehouseIdAndCode(warehouse.getId(), request.code())) {
            throw Errors.BusinessRuleException.forField("duplicate_code", "code",
                    "Bin " + request.code() + " already exists in " + warehouse.getCode() + ".");
        }
        return toResponse(bins.save(new BinLocation(warehouse.getId(), request.code(), false, calendar.now())));
    }

    public List<WarehouseDtos.WarehouseResponse> list() {
        return warehouses.findAllByOrderByCodeAsc().stream().map(this::toResponse).toList();
    }

    public WarehouseDtos.WarehouseResponse get(long id) {
        return toResponse(warehouses.findById(id).orElseThrow(() -> new Errors.NotFoundException("Warehouse", id)));
    }

    // ---- public API for other modules ---------------------------------------------------------

    /** The warehouse, or a 422 on {@code field} if it doesn't exist or is inactive. */
    public WarehouseInfo requireActiveWarehouse(long id, String field) {
        Warehouse warehouse = warehouses.findById(id).orElseThrow(() -> Errors.BusinessRuleException.forField(
                "unknown_warehouse", field, "Warehouse " + id + " does not exist."));
        if (!warehouse.isActive()) {
            throw Errors.BusinessRuleException.forField("inactive_warehouse", field, "Warehouse " + warehouse.getCode() + " is inactive.");
        }
        return info(warehouse);
    }

    public WarehouseInfo warehouse(long id) {
        return info(warehouses.findById(id).orElseThrow(() -> new Errors.NotFoundException("Warehouse", id)));
    }

    /**
     * The bin, if it belongs to that warehouse and is active; otherwise a 422 on {@code field}.
     * (The database would also refuse a bin of another warehouse, via composite foreign keys.)
     */
    public BinInfo requireBin(long warehouseId, long binId, String field) {
        BinLocation bin = bins.findById(binId)
                .filter(b -> b.getWarehouseId() == warehouseId)
                .orElseThrow(() -> Errors.BusinessRuleException.forField("unknown_bin", field,
                        "Bin " + binId + " is not a bin of warehouse " + warehouseId + "."));
        if (!bin.isActive()) {
            throw Errors.BusinessRuleException.forField("inactive_bin", field, "Bin " + bin.getCode() + " is inactive.");
        }
        return info(bin);
    }

    /** The bin given, or the warehouse's receiving bin when {@code binId} is null. */
    public BinInfo binOrReceiving(long warehouseId, Long binId, String field) {
        if (binId != null) {
            return requireBin(warehouseId, binId, field);
        }
        return bins.findByWarehouseIdAndReceivingTrue(warehouseId).map(WarehouseService::info)
                .orElseThrow(() -> Errors.BusinessRuleException.forField("no_receiving_bin", field,
                        "Warehouse " + warehouseId + " has no receiving bin; name a bin."));
    }

    // ---- mapping ------------------------------------------------------------------------------

    private WarehouseDtos.WarehouseResponse toResponse(Warehouse warehouse) {
        List<WarehouseDtos.BinResponse> binList = bins.findByWarehouseIdOrderByCodeAsc(warehouse.getId()).stream()
                .map(WarehouseService::toResponse)
                .toList();
        return new WarehouseDtos.WarehouseResponse(warehouse.getId(), warehouse.getCode(), warehouse.getName(),
                warehouse.isActive(), binList);
    }

    private static WarehouseDtos.BinResponse toResponse(BinLocation bin) {
        return new WarehouseDtos.BinResponse(bin.getId(), bin.getWarehouseId(), bin.getCode(), bin.isReceiving(), bin.isActive());
    }

    private static WarehouseInfo info(Warehouse w) {
        return new WarehouseInfo(w.getId(), w.getCode(), w.getName(), w.isActive());
    }

    private static BinInfo info(BinLocation b) {
        return new BinInfo(b.getId(), b.getWarehouseId(), b.getCode(), b.isReceiving());
    }
}
