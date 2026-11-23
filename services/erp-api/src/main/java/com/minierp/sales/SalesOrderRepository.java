package com.minierp.sales;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {

    /** {@code SELECT ... FOR UPDATE}: confirm, cancel, close and ship of one order run one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SalesOrder o where o.id = :id")
    Optional<SalesOrder> findForUpdate(long id);

    List<SalesOrder> findTop100ByStatusOrderByIdDesc(SalesOrderStatus status);

    List<SalesOrder> findTop100ByOrderByIdDesc();
}
