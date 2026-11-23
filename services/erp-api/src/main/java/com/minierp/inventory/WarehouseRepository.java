package com.minierp.inventory;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    boolean existsByCode(String code);

    List<Warehouse> findAllByOrderByCodeAsc();
}
