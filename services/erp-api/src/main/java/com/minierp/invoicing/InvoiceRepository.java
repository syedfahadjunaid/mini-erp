package com.minierp.invoicing;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    Optional<Invoice> findByShipmentId(long shipmentId);

    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);

    List<Invoice> findTop100ByOrderByIdDesc();

    List<Invoice> findBySalesOrderIdOrderByIdAsc(long salesOrderId);
}
