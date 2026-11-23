package com.minierp.partners;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Package-private: other modules go through PartnerService. */
interface CustomerRepository extends JpaRepository<Customer, Long> {

    boolean existsByCode(String code);

    List<Customer> findAllByOrderByCodeAsc();
}
