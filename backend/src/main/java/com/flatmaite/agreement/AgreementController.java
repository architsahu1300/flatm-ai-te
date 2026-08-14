package com.flatmaite.agreement;

import com.flatmaite.agreement.AgreementDtos.Clause;
import com.flatmaite.agreement.AgreementDtos.CreateAgreementRequest;
import com.flatmaite.agreement.AgreementDtos.SuggestClausesRequest;
import com.flatmaite.agreement.AgreementDtos.UpdateAgreementRequest;
import com.flatmaite.common.ratelimit.RateLimiter;
import com.flatmaite.common.security.AuthPrincipal;
import com.flatmaite.common.security.CurrentUser;
import com.flatmaite.common.web.ApiException;
import com.flatmaite.search.AiUsageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agreements")
@RequiredArgsConstructor
public class AgreementController {

  private final AgreementService service;
  private final AgreementPdfService pdfService;
  private final ClauseAdvisor clauseAdvisor;
  private final AiUsageService usage;
  private final RateLimiter rateLimiter;
  private final ObjectMapper objectMapper;

  @GetMapping
  public ResponseEntity<Map<String, Object>> list() {
    AuthPrincipal user = CurrentUser.require();
    return ResponseEntity.ok(
        Map.of(
            "data",
            service.listFor(user.userId()).stream()
                .map(a -> service.toResponse(a, user.userId()))
                .toList()));
  }

  @GetMapping("/standard-clauses")
  public ResponseEntity<Map<String, Object>> standardClauses() {
    CurrentUser.require();
    return ResponseEntity.ok(Map.of("data", StandardClauses.forState("MH")));
  }

  @PostMapping
  public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateAgreementRequest body) {
    AuthPrincipal user = CurrentUser.require();
    Agreement agreement = service.create(user.userId(), body);
    return ResponseEntity.ok(Map.of("data", service.toResponse(agreement, user.userId())));
  }

  @GetMapping("/{id}")
  public ResponseEntity<Map<String, Object>> get(@PathVariable UUID id) {
    AuthPrincipal user = CurrentUser.require();
    return ResponseEntity.ok(
        Map.of("data", service.toResponse(service.partyAgreement(user.userId(), id), user.userId())));
  }

  @PatchMapping("/{id}")
  public ResponseEntity<Map<String, Object>> update(
      @PathVariable UUID id, @Valid @RequestBody UpdateAgreementRequest body) {
    AuthPrincipal user = CurrentUser.require();
    Agreement agreement = service.update(user.userId(), id, body);
    return ResponseEntity.ok(Map.of("data", service.toResponse(agreement, user.userId())));
  }

  /** AI clause drafting — quota-gated, suggestions only (the client applies accepted ones). */
  @SneakyThrows
  @PostMapping("/{id}/clauses/suggest")
  public ResponseEntity<Map<String, Object>> suggestClauses(
      @PathVariable UUID id, @RequestBody(required = false) SuggestClausesRequest body) {
    AuthPrincipal user = CurrentUser.require();
    if (!rateLimiter.tryAcquire("clauses:" + user.userId(), 5, 60)) {
      throw ApiException.tooManyRequests("Slow down a little");
    }
    usage.checkQuota(user.userId(), null);
    Agreement agreement = service.partyAgreement(user.userId(), id);
    List<Clause> existing =
        objectMapper.readValue(agreement.getClauses(), new TypeReference<>() {});
    long start = System.currentTimeMillis();
    List<Clause> suggestions =
        clauseAdvisor.suggest(body == null ? null : body.context(), existing);
    usage.log(
        user.userId(),
        null,
        com.flatmaite.common.domain.AiFeature.AGREEMENT_DRAFT,
        clauseAdvisor.providerName(),
        clauseAdvisor.providerName().equals("mock") ? "clause-pool" : "gpt-4o-mini",
        400,
        suggestions.size() * 80,
        false,
        true,
        System.currentTimeMillis() - start,
        null);
    return ResponseEntity.ok(Map.of("data", suggestions));
  }

  @PostMapping("/{id}/finalize")
  public ResponseEntity<Map<String, Object>> finalizeAgreement(@PathVariable UUID id) {
    AuthPrincipal user = CurrentUser.require();
    // generate + store the locked PDF alongside the finalize snapshot
    Agreement current = service.partyAgreement(user.userId(), id);
    var preview = service.toResponse(current, user.userId());
    byte[] pdf = pdfService.render(preview, com.flatmaite.common.Brand.APP_NAME);
    String pdfPath = pdfService.store(id, current.getCurrentVersion() + 1, pdf);
    Agreement agreement = service.finalize(user.userId(), id, pdfPath);
    return ResponseEntity.ok(Map.of("data", service.toResponse(agreement, user.userId())));
  }

  @PostMapping("/{id}/sign")
  public ResponseEntity<Map<String, Object>> sign(@PathVariable UUID id) {
    AuthPrincipal user = CurrentUser.require();
    Agreement agreement = service.sign(user.userId(), id);
    return ResponseEntity.ok(Map.of("data", service.toResponse(agreement, user.userId())));
  }

  @PostMapping("/{id}/cancel")
  public ResponseEntity<Map<String, Object>> cancel(@PathVariable UUID id) {
    AuthPrincipal user = CurrentUser.require();
    Agreement agreement = service.cancel(user.userId(), id);
    return ResponseEntity.ok(Map.of("data", service.toResponse(agreement, user.userId())));
  }

  @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
  public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
    AuthPrincipal user = CurrentUser.require();
    Agreement agreement = service.partyAgreement(user.userId(), id);
    byte[] pdf = pdfService.render(service.toResponse(agreement, user.userId()), com.flatmaite.common.Brand.APP_NAME);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=agreement-" + id + ".pdf")
        .body(pdf);
  }
}
