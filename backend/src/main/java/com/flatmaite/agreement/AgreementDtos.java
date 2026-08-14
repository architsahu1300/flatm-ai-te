package com.flatmaite.agreement;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class AgreementDtos {

  private AgreementDtos() {}

  public record Clause(String id, String title, String body, String source) {}

  public record Signature(UUID userId, String name, String role, String status, Instant signedAt) {}

  public record CreateAgreementRequest(
      UUID listingId,
      @NotEmpty List<String> tenantEmails,
      @NotNull @Min(1000) @Max(5000000) Integer rentMonthly,
      @NotNull @Min(0) Integer deposit,
      @Min(1) @Max(60) Integer durationMonths,
      @Min(7) @Max(120) Integer noticePeriodDays,
      @Min(0) @Max(24) Integer lockInMonths,
      BigDecimal annualEscalationPct,
      @NotNull LocalDate startDate,
      @Size(max = 500) String propertyAddress,
      List<Clause> clauses) {}

  public record UpdateAgreementRequest(
      @Min(1000) @Max(5000000) Integer rentMonthly,
      @Min(0) Integer deposit,
      @Min(1) @Max(60) Integer durationMonths,
      @Min(7) @Max(120) Integer noticePeriodDays,
      @Min(0) @Max(24) Integer lockInMonths,
      BigDecimal annualEscalationPct,
      LocalDate startDate,
      @Size(max = 500) String propertyAddress,
      List<Clause> clauses) {}

  public record SuggestClausesRequest(@Size(max = 500) String context) {}

  public record AgreementResponse(
      UUID id,
      String status,
      UUID landlordId,
      String landlordName,
      List<Signature> signatures,
      UUID listingId,
      String listingTitle,
      String propertyAddress,
      int rentMonthly,
      int deposit,
      int durationMonths,
      int noticePeriodDays,
      int lockInMonths,
      BigDecimal annualEscalationPct,
      LocalDate startDate,
      String agreementState,
      List<Clause> clauses,
      int currentVersion,
      boolean viewerCanFinalize,
      boolean viewerCanSign,
      Instant createdAt,
      Instant updatedAt) {}
}
