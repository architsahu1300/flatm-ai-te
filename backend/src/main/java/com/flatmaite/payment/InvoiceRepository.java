package com.flatmaite.payment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<Invoice, java.util.UUID> {
}
