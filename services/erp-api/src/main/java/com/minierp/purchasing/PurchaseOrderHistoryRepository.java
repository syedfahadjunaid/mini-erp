package com.minierp.purchasing;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface PurchaseOrderHistoryRepository extends JpaRepository<PurchaseOrderHistory, Long> {

    List<PurchaseOrderHistory> findByPurchaseOrderIdOrderByIdAsc(long purchaseOrderId);
}
