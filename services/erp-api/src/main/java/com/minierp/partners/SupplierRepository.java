package com.minierp.partners;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Package-private: other modules go through PartnerService. */
interface SupplierRepository extends JpaRepository<Supplier, Long> {

    boolean existsByCode(String code);

    List<Supplier> findAllByOrderByCodeAsc();
}
