package com.minierp.purchasing;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    /**
     * {@code SELECT ... FOR UPDATE}: status changes and receipts on the same order run one at a
     * time, so two clerks can't both receive the last 10 units of a line.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PurchaseOrder o where o.id = :id")
    Optional<PurchaseOrder> findForUpdate(long id);

    List<PurchaseOrder> findTop100ByStatusOrderByIdDesc(PurchaseOrderStatus status);

    List<PurchaseOrder> findTop100ByOrderByIdDesc();
}
