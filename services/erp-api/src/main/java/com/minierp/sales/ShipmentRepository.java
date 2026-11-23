package com.minierp.sales;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    List<Shipment> findBySalesOrderIdOrderByIdAsc(long salesOrderId);
}
