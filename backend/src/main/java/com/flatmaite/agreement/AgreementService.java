package com.flatmaite.agreement;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatmaite.agreement.AgreementDtos.AgreementResponse;
import com.flatmaite.agreement.AgreementDtos.Clause;
import com.flatmaite.agreement.AgreementDtos.CreateAgreementRequest;
import com.flatmaite.agreement.AgreementDtos.Signature;
import com.flatmaite.agreement.AgreementDtos.UpdateAgreementRequest;
import com.flatmaite.common.domain.AgreementStatus;
import com.flatmaite.common.domain.NotificationType;
import com.flatmaite.common.web.ApiException;
import com.flatmaite.listing.Listing;
import com.flatmaite.listing.ListingRepository;
import com.flatmaite.notification.Notification;
import com.flatmaite.notification.NotificationRepository;
import com.flatmaite.user.User;
import com.flatmaite.user.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rental agreement lifecycle: DRAFT (editable) → FINALIZED (terms locked, signing opens) → SIGNED
 * (all parties signed via the mock e-sign provider) or CANCELLED. Every mutation snapshots a
 * version row; the PDF is generated from the live state and stored per version at finalize time.
 */
@Service
@RequiredArgsConstructor
public class AgreementService {

  private final AgreementRepository agreements;
  private final AgreementVersionRepository versions;
  private final UserRepository users;
  private final ListingRepository listings;
  private final NotificationRepository notifications;
  private final ObjectMapper objectMapper;

  @SneakyThrows
  @Transactional
  public Agreement create(UUID creatorId, CreateAgreementRequest req) {
    List<UUID> tenantIds = new ArrayList<>();
    for (String email : req.tenantEmails()) {
      User tenant =
          users
              .findByEmailIgnoreCase(email.trim().toLowerCase(Locale.ROOT))
              .filter(u -> u.getDeletedAt() == null)
              .orElseThrow(
                  () ->
                      ApiException.badRequest(
                          "tenant_not_found",
                          "No member with email %s — they need an account first".formatted(email)));
      if (tenant.getId().equals(creatorId)) {
        throw ApiException.badRequest("self_tenant", "You can't be both landlord and tenant");
      }
      if (!tenantIds.contains(tenant.getId())) {
        tenantIds.add(tenant.getId());
      }
    }

    Listing listing =
        req.listingId() == null
            ? null
            : listings
                .findByIdAndDeletedAtIsNull(req.listingId())
                .orElseThrow(() -> ApiException.notFound("Listing not found"));

    List<Clause> clauses =
        req.clauses() == null || req.clauses().isEmpty()
            ? StandardClauses.forState("MH")
            : req.clauses();

    Agreement agreement =
        Agreement.builder()
            .listingId(listing == null ? null : listing.getId())
            .propertyId(listing == null ? null : listing.getPropertyId())
            .landlordId(creatorId)
            .createdBy(creatorId)
            .tenantIds(tenantIds.toArray(UUID[]::new))
            .rentMonthly(req.rentMonthly())
            .deposit(req.deposit())
            .durationMonths(req.durationMonths() == null ? 11 : req.durationMonths().shortValue())
            .noticePeriodDays(req.noticePeriodDays() == null ? 30 : req.noticePeriodDays().shortValue())
            .lockInMonths(req.lockInMonths() == null ? 6 : req.lockInMonths().shortValue())
            .annualEscalationPct(req.annualEscalationPct() == null ? BigDecimal.ZERO : req.annualEscalationPct())
            .startDate(req.startDate())
            .propertyAddress(
                req.propertyAddress() != null
                    ? req.propertyAddress()
                    : listing != null ? listing.getTitle() : null)
            .clauses(objectMapper.writeValueAsString(clauses))
            .stampDuty(
                objectMapper.writeValueAsString(
                    Map.of(
                        "state", "MH",
                        "method", "e-stamp (provider integration pending)",
                        "note", "Government stamp duty and registration charges are separate from platform fees",
                        "disclaimer", true)))
            .signatures(objectMapper.writeValueAsString(initialSignatures(creatorId, tenantIds)))
            .build();
    agreement = agreements.save(agreement);
    snapshot(agreement, creatorId, null);
    notifyParties(agreement, creatorId, "New rental agreement draft",
        "You've been added to a rental agreement draft. Review the terms.");
    return agreement;
  }

  @SneakyThrows
  @Transactional
  public Agreement update(UUID actorId, UUID agreementId, UpdateAgreementRequest req) {
    Agreement agreement = partyAgreement(actorId, agreementId);
    if (agreement.getStatus() != AgreementStatus.DRAFT) {
      throw ApiException.badRequest("not_editable", "Only drafts can be edited");
    }
    if (!actorId.equals(agreement.getLandlordId())) {
      throw ApiException.forbidden("Only the landlord can edit the draft");
    }
    if (req.rentMonthly() != null) agreement.setRentMonthly(req.rentMonthly());
    if (req.deposit() != null) agreement.setDeposit(req.deposit());
    if (req.durationMonths() != null) agreement.setDurationMonths(req.durationMonths().shortValue());
    if (req.noticePeriodDays() != null) agreement.setNoticePeriodDays(req.noticePeriodDays().shortValue());
    if (req.lockInMonths() != null) agreement.setLockInMonths(req.lockInMonths().shortValue());
    if (req.annualEscalationPct() != null) agreement.setAnnualEscalationPct(req.annualEscalationPct());
    if (req.startDate() != null) agreement.setStartDate(req.startDate());
    if (req.propertyAddress() != null) agreement.setPropertyAddress(req.propertyAddress());
    if (req.clauses() != null) agreement.setClauses(objectMapper.writeValueAsString(req.clauses()));
    agreement.setCurrentVersion(agreement.getCurrentVersion() + 1);
    Agreement saved = agreements.save(agreement);
    snapshot(saved, actorId, null);
    return saved;
  }

  @Transactional
  public Agreement finalize(UUID actorId, UUID agreementId, String pdfPath) {
    Agreement agreement = partyAgreement(actorId, agreementId);
    if (!actorId.equals(agreement.getLandlordId())) {
      throw ApiException.forbidden("Only the landlord can finalize");
    }
    if (agreement.getStatus() != AgreementStatus.DRAFT) {
      throw ApiException.badRequest("not_draft", "Only drafts can be finalized");
    }
    if (agreement.getTenantIds().length == 0) {
      throw ApiException.badRequest("no_tenants", "Add at least one tenant first");
    }
    agreement.setStatus(AgreementStatus.FINALIZED);
    agreement.setCurrentVersion(agreement.getCurrentVersion() + 1);
    Agreement saved = agreements.save(agreement);
    snapshot(saved, actorId, pdfPath);
    notifyParties(saved, actorId, "Agreement finalized",
        "Terms are locked. Please review and sign.");
    return saved;
  }

  /** Mock e-sign: each party signs once; when everyone has signed the agreement becomes SIGNED. */
  @SneakyThrows
  @Transactional
  public Agreement sign(UUID actorId, UUID agreementId) {
    Agreement agreement = partyAgreement(actorId, agreementId);
    if (agreement.getStatus() != AgreementStatus.FINALIZED) {
      throw ApiException.badRequest("not_finalized", "The agreement must be finalized before signing");
    }
    List<Map<String, Object>> sigs =
        objectMapper.readValue(agreement.getSignatures(), new TypeReference<>() {});
    boolean found = false;
    boolean allSigned = true;
    for (Map<String, Object> sig : sigs) {
      if (actorId.toString().equals(sig.get("userId"))) {
        if ("SIGNED".equals(sig.get("status"))) {
          throw ApiException.badRequest("already_signed", "You've already signed");
        }
        sig.put("status", "SIGNED");
        sig.put("signedAt", Instant.now().toString());
        sig.put("providerRef", "mock-esign-" + UUID.randomUUID().toString().substring(0, 8));
        found = true;
      }
      if (!"SIGNED".equals(sig.get("status"))) {
        allSigned = false;
      }
    }
    if (!found) {
      throw ApiException.forbidden("You are not a signing party on this agreement");
    }
    agreement.setSignatures(objectMapper.writeValueAsString(sigs));
    if (allSigned) {
      agreement.setStatus(AgreementStatus.SIGNED);
      notifyParties(agreement, actorId, "Agreement fully signed",
          "All parties have signed. Download your copy from the agreement page.");
    }
    return agreements.save(agreement);
  }

  @Transactional
  public Agreement cancel(UUID actorId, UUID agreementId) {
    Agreement agreement = partyAgreement(actorId, agreementId);
    if (agreement.getStatus() == AgreementStatus.SIGNED) {
      throw ApiException.badRequest("already_signed", "A fully signed agreement can't be cancelled here");
    }
    agreement.setStatus(AgreementStatus.CANCELLED);
    return agreements.save(agreement);
  }

  @Transactional(readOnly = true)
  public List<Agreement> listFor(UUID userId) {
    return agreements.findAll().stream()
        .filter(a -> isParty(a, userId))
        .sorted((a, b) -> b.getUpdatedAt().compareTo(a.getUpdatedAt()))
        .toList();
  }

  public Agreement partyAgreement(UUID userId, UUID agreementId) {
    Agreement agreement =
        agreements.findById(agreementId).orElseThrow(() -> ApiException.notFound("Agreement not found"));
    if (!isParty(agreement, userId)) {
      throw ApiException.forbidden("Not your agreement");
    }
    return agreement;
  }

  static boolean isParty(Agreement a, UUID userId) {
    return userId.equals(a.getLandlordId())
        || userId.equals(a.getCreatedBy())
        || Arrays.asList(a.getTenantIds()).contains(userId);
  }

  @SneakyThrows
  public AgreementResponse toResponse(Agreement a, UUID viewerId) {
    Map<UUID, String> names = new HashMap<>();
    List<UUID> partyIds = new ArrayList<>(Arrays.asList(a.getTenantIds()));
    partyIds.add(a.getLandlordId());
    users.findAllById(partyIds).forEach(u -> names.put(u.getId(), u.getName()));

    List<Map<String, Object>> rawSigs =
        objectMapper.readValue(a.getSignatures(), new TypeReference<>() {});
    List<Signature> sigs =
        rawSigs.stream()
            .map(
                s ->
                    new Signature(
                        UUID.fromString((String) s.get("userId")),
                        names.getOrDefault(UUID.fromString((String) s.get("userId")), "Member"),
                        (String) s.get("role"),
                        (String) s.get("status"),
                        s.get("signedAt") == null ? null : Instant.parse((String) s.get("signedAt"))))
            .toList();
    List<Clause> clauses = objectMapper.readValue(a.getClauses(), new TypeReference<>() {});

    String listingTitle =
        a.getListingId() == null
            ? null
            : listings.findById(a.getListingId()).map(Listing::getTitle).orElse(null);

    boolean viewerSigned =
        sigs.stream()
            .anyMatch(s -> s.userId().equals(viewerId) && "SIGNED".equals(s.status()));
    return new AgreementResponse(
        a.getId(),
        a.getStatus().name(),
        a.getLandlordId(),
        names.getOrDefault(a.getLandlordId(), "Landlord"),
        sigs,
        a.getListingId(),
        listingTitle,
        a.getPropertyAddress(),
        a.getRentMonthly(),
        a.getDeposit(),
        a.getDurationMonths(),
        a.getNoticePeriodDays(),
        a.getLockInMonths(),
        a.getAnnualEscalationPct(),
        a.getStartDate(),
        a.getAgreementState(),
        clauses,
        a.getCurrentVersion(),
        a.getStatus() == AgreementStatus.DRAFT && viewerId.equals(a.getLandlordId()),
        a.getStatus() == AgreementStatus.FINALIZED && !viewerSigned
            && sigs.stream().anyMatch(s -> s.userId().equals(viewerId)),
        a.getCreatedAt(),
        a.getUpdatedAt());
  }

  private static List<Map<String, Object>> initialSignatures(UUID landlordId, List<UUID> tenantIds) {
    List<Map<String, Object>> sigs = new ArrayList<>();
    Map<String, Object> landlord = new HashMap<>();
    landlord.put("userId", landlordId.toString());
    landlord.put("role", "landlord");
    landlord.put("status", "PENDING");
    sigs.add(landlord);
    for (UUID tenantId : tenantIds) {
      Map<String, Object> t = new HashMap<>();
      t.put("userId", tenantId.toString());
      t.put("role", "tenant");
      t.put("status", "PENDING");
      sigs.add(t);
    }
    return sigs;
  }

  @SneakyThrows
  private void snapshot(Agreement agreement, UUID actorId, String pdfPath) {
    versions.save(
        AgreementVersion.builder()
            .agreementId(agreement.getId())
            .version(agreement.getCurrentVersion())
            .snapshot(objectMapper.writeValueAsString(toResponse(agreement, actorId)))
            .pdfPath(pdfPath)
            .createdBy(actorId)
            .build());
  }

  private void notifyParties(Agreement agreement, UUID actorId, String title, String body) {
    List<UUID> parties = new ArrayList<>(Arrays.asList(agreement.getTenantIds()));
    parties.add(agreement.getLandlordId());
    for (UUID party : parties) {
      if (!party.equals(actorId)) {
        notifications.save(
            Notification.builder()
                .userId(party)
                .type(NotificationType.AGREEMENT)
                .title(title)
                .body(body)
                .data("{\"agreementId\":\"" + agreement.getId() + "\"}")
                .build());
      }
    }
  }
}
