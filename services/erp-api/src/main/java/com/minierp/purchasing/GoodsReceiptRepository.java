package com.minierp.purchasing;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, Long> {

    List<GoodsReceipt> findByPurchaseOrderIdOrderByIdAsc(long purchaseOrderId);
}
