package com.minierp.sales;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface SalesOrderHistoryRepository extends JpaRepository<SalesOrderHistory, Long> {

    List<SalesOrderHistory> findBySalesOrderIdOrderByIdAsc(long salesOrderId);
}
