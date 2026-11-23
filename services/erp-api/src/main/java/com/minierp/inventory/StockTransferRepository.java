package com.minierp.inventory;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface StockTransferRepository extends JpaRepository<StockTransfer, Long> {

    /**
     * {@code SELECT ... FOR UPDATE}: whoever receives a transfer first holds the row lock until
     * commit; a second "receive" waits, then sees RECEIVED and gets a 409. No double receipt.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from StockTransfer t where t.id = :id")
    Optional<StockTransfer> findForUpdate(long id);

    List<StockTransfer> findTop100ByOrderByIdDesc();
}
