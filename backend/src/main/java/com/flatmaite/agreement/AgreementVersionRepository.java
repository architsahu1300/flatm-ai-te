package com.flatmaite.agreement;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgreementVersionRepository extends JpaRepository<AgreementVersion, java.util.UUID> {
  java.util.List<AgreementVersion> findByAgreementIdOrderByVersionDesc(java.util.UUID agreementId);
}
