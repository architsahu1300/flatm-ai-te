package com.flatmaite.payment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRecordRepository extends JpaRepository<TransactionRecord, java.util.UUID> {
}
