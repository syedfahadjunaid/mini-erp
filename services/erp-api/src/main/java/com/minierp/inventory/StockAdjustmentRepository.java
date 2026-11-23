package com.minierp.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

interface StockAdjustmentRepository extends JpaRepository<StockAdjustment, Long> {
}
