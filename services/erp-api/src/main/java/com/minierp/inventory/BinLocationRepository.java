package com.minierp.inventory;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface BinLocationRepository extends JpaRepository<BinLocation, Long> {

    List<BinLocation> findByWarehouseIdOrderByCodeAsc(long warehouseId);

    Optional<BinLocation> findByWarehouseIdAndReceivingTrue(long warehouseId);

    boolean existsByWarehouseIdAndCode(long warehouseId, String code);
}
