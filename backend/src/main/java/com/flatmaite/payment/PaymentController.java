package com.flatmaite.payment;

import com.flatmaite.common.domain.SubscriptionStatus;
import com.flatmaite.common.security.AuthPrincipal;
import com.flatmaite.common.security.CurrentUser;
import com.flatmaite.common.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

  private final PaymentService service;
  private final PlanRepository plans;
  private final SubscriptionRepository subscriptions;
  private final OrderRepository orders;

  public record PlanResponse(
      UUID id, String slug, String name, String tier, int priceMonthly, String features) {
    static PlanResponse from(Plan p) {
      return new PlanResponse(
          p.getId(), p.getSlug(), p.getName(), p.getTier().name(), p.getPriceMonthly().intValue(), p.getFeatures());
    }
  }

  @GetMapping("/plans")
  public ResponseEntity<Map<String, Object>> listPlans() {
    List<PlanResponse> all =
        plans.findAll(Sort.by("priceMonthly")).stream().map(PlanResponse::from).toList();
    return ResponseEntity.ok(
        Map.of("data", Map.of("plans", all, "boostPrices", PaymentService.BOOST_PRICES)));
  }

  @GetMapping("/me")
  public ResponseEntity<Map<String, Object>> myBilling() {
    AuthPrincipal user = CurrentUser.require();
    var activeSub =
        subscriptions.findByUserId(user.userId()).stream()
            .filter(s -> s.getStatus() == SubscriptionStatus.ACTIVE)
            .filter(s -> s.getCurrentPeriodEnd().isAfter(Instant.now()))
            .max(Comparator.comparing(Subscription::getCurrentPeriodEnd));
    var history =
        orders.findByUserIdOrderByCreatedAtDesc(user.userId()).stream()
            .limit(20)
            .map(
                o ->
                    Map.of(
                        "id", o.getId(),
                        "kind", o.getKind().name(),
                        "amount", o.getAmount().intValue(),
                        "status", o.getStatus().name(),
                        "createdAt", o.getCreatedAt()))
            .toList();
    return ResponseEntity.ok(
        Map.of(
            "data",
            Map.of(
                "premium", activeSub.isPresent(),
                "premiumUntil",
                    activeSub.map(s -> s.getCurrentPeriodEnd().toString()).orElse(""),
                "orders", history)));
  }

  public record CreateOrderRequest(
      @NotBlank String kind, // BOOST | SUBSCRIPTION
      UUID listingId,
      Integer boostDays,
      UUID planId,
      @NotNull @Size(min = 8, max = 64) String idempotencyKey) {}

  @PostMapping("/orders")
  public ResponseEntity<Map<String, Object>> createOrder(@Valid @RequestBody CreateOrderRequest body) {
    AuthPrincipal user = CurrentUser.require();
    Order order =
        switch (body.kind()) {
          case "BOOST" -> {
            if (body.listingId() == null || body.boostDays() == null) {
              throw ApiException.badRequest("missing_fields", "listingId and boostDays are required");
            }
            yield service.createBoostOrder(
                user.userId(), body.listingId(), body.boostDays(), body.idempotencyKey());
          }
          case "SUBSCRIPTION" -> {
            if (body.planId() == null) {
              throw ApiException.badRequest("missing_fields", "planId is required");
            }
            yield service.createSubscriptionOrder(user.userId(), body.planId(), body.idempotencyKey());
          }
          default -> throw ApiException.badRequest("invalid_kind", "kind must be BOOST or SUBSCRIPTION");
        };
    return ResponseEntity.ok(Map.of("data", orderResponse(order)));
  }

  @PostMapping("/orders/{id}/confirm")
  public ResponseEntity<Map<String, Object>> confirm(@PathVariable UUID id) {
    AuthPrincipal user = CurrentUser.require();
    return ResponseEntity.ok(Map.of("data", orderResponse(service.confirm(user.userId(), id))));
  }

  private Map<String, Object> orderResponse(Order order) {
    return Map.of(
        "id", order.getId(),
        "kind", order.getKind().name(),
        "amount", order.getAmount().intValue(),
        "currency", order.getCurrency(),
        "status", order.getStatus().name());
  }
}
