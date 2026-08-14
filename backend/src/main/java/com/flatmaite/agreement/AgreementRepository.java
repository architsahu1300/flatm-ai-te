package com.flatmaite.agreement;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgreementRepository extends JpaRepository<Agreement, java.util.UUID> {
}
