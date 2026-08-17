package com.flatmaite.payment;

import com.flatmaite.common.domain.NotificationType;
import com.flatmaite.common.domain.OrderKind;
import com.flatmaite.common.domain.PaymentStatus;
import com.flatmaite.common.domain.SubscriptionStatus;
import com.flatmaite.common.web.ApiException;
import com.flatmaite.listing.Listing;
import com.flatmaite.listing.ListingRepository;
import com.flatmaite.notification.Notification;
import com.flatmaite.notification.NotificationRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mock payment rails: orders and payments move through the real state machine
 * (CREATED → PENDING → SUCCEEDED) with idempotency keys, invoices and ledger rows — only the
 * provider charge is faked. Swapping in Razorpay/Stripe means replacing confirm()'s middle.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

  /** Boost pricing: days → rupees. Mirrored in the frontend plans page. */
  public static final Map<Integer, Integer> BOOST_PRICES = Map.of(3, 99, 7, 199, 14, 299);

  private final PlanRepository plans;
  private final SubscriptionRepository subscriptions;
  private final OrderRepository orders;
  private final PaymentRepository payments;
  private final InvoiceRepository invoices;
  private final TransactionRecordRepository transactions;
  private final ListingRepository listings;
  private final NotificationRepository notifications;

  @Transactional
  public Order createBoostOrder(UUID userId, UUID listingId, int days, String idempotencyKey) {
    Integer price = BOOST_PRICES.get(days);
    if (price == null) {
      throw ApiException.badRequest("invalid_boost", "Boost duration must be 3, 7 or 14 days");
    }
    Listing listing =
        listings.findById(listingId).orElseThrow(() -> ApiException.notFound("Listing not found"));
    if (!listing.getListerId().equals(userId)) {
      throw ApiException.forbidden("You can only boost your own listings");
    }

    Optional<Payment> existing = payments.findByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) {
      return orders.findById(existing.get().getOrderId()).orElseThrow();
    }

    Order order =
        orders.save(
            Order.builder()
                .userId(userId)
                .kind(OrderKind.BOOST)
                .amount(BigDecimal.valueOf(price))
                .status(PaymentStatus.PENDING)
                .metadata("{\"listingId\":\"%s\",\"days\":%d}".formatted(listingId, days))
                .build());
    payments.save(
        Payment.builder()
            .orderId(order.getId())
            .amount(order.getAmount())
            .status(PaymentStatus.PENDING)
            .idempotencyKey(idempotencyKey)
            .build());
    return order;
  }

  @Transactional
  public Order createSubscriptionOrder(UUID userId, UUID planId, String idempotencyKey) {
    Plan plan = plans.findById(planId).orElseThrow(() -> ApiException.notFound("Plan not found"));
    if (plan.getPriceMonthly().signum() == 0) {
      throw ApiException.badRequest("free_plan", "The free plan doesn't need a purchase");
    }
    Optional<Payment> existing = payments.findByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) {
      return orders.findById(existing.get().getOrderId()).orElseThrow();
    }
    Order order =
        orders.save(
            Order.builder()
                .userId(userId)
                .kind(OrderKind.SUBSCRIPTION)
                .amount(plan.getPriceMonthly())
                .status(PaymentStatus.PENDING)
                .metadata("{\"planId\":\"%s\"}".formatted(planId))
                .build());
    payments.save(
        Payment.builder()
            .orderId(order.getId())
            .amount(order.getAmount())
            .status(PaymentStatus.PENDING)
            .idempotencyKey(idempotencyKey)
            .build());
    return order;
  }

  @Transactional
  public Order confirm(UUID userId, UUID orderId) {
    Order order = orders.findById(orderId).orElseThrow(() -> ApiException.notFound("Order not found"));
    if (!order.getUserId().equals(userId)) {
      throw ApiException.notFound("Order not found");
    }
    if (order.getStatus() == PaymentStatus.SUCCEEDED) {
      return order; // confirm is idempotent too
    }
    if (order.getStatus() != PaymentStatus.PENDING) {
      throw ApiException.badRequest("not_confirmable", "Order is " + order.getStatus());
    }

    Payment payment =
        payments.findByOrderId(order.getId()).orElseThrow(() -> ApiException.notFound("Payment missing"));
    // mock provider: the charge always succeeds
    payment.setStatus(PaymentStatus.SUCCEEDED);
    payment.setProviderPaymentId("mockpay_" + UUID.randomUUID().toString().substring(0, 8));
    payments.save(payment);

    order.setStatus(PaymentStatus.SUCCEEDED);
    orders.save(order);

    invoices.save(
        Invoice.builder()
            .orderId(order.getId())
            .invoiceNumber("INV-" + Instant.now().getEpochSecond() + "-" + order.getId().toString().substring(0, 4))
            .build());
    transactions.save(
        TransactionRecord.builder()
            .paymentId(payment.getId())
            .type("DEBIT")
            .amount(order.getAmount())
            .build());

    applyEntitlement(order);
    return order;
  }

  private void applyEntitlement(Order order) {
    if (order.getKind() == OrderKind.BOOST) {
      Map<String, Object> meta = parseMeta(order.getMetadata());
      UUID listingId = UUID.fromString(String.valueOf(meta.get("listingId")));
      int days = ((Number) meta.get("days")).intValue();
      Listing listing = listings.findById(listingId).orElseThrow();
      Instant base =
          listing.getBoostedUntil() != null && listing.getBoostedUntil().isAfter(Instant.now())
              ? listing.getBoostedUntil()
              : Instant.now();
      listing.setBoosted(true);
      listing.setBoostedUntil(base.plus(Duration.ofDays(days)));
      listings.save(listing);
      notifications.save(
          Notification.builder()
              .userId(order.getUserId())
              .type(NotificationType.LISTING_STATUS)
              .title("Boost active")
              .body("\"%s\" is now featured for %d days.".formatted(listing.getTitle(), days))
              .data("{\"listingId\":\"" + listingId + "\"}")
              .build());
    } else if (order.getKind() == OrderKind.SUBSCRIPTION) {
      Map<String, Object> meta = parseMeta(order.getMetadata());
      UUID planId = UUID.fromString(String.valueOf(meta.get("planId")));
      Instant now = Instant.now();
      Subscription sub =
          subscriptions.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(
                  order.getUserId(), SubscriptionStatus.ACTIVE)
              .orElse(null);
      Instant start = sub != null && sub.getCurrentPeriodEnd().isAfter(now) ? sub.getCurrentPeriodEnd() : now;
      subscriptions.save(
          Subscription.builder()
              .userId(order.getUserId())
              .planId(planId)
              .status(SubscriptionStatus.ACTIVE)
              .currentPeriodStart(start)
              .currentPeriodEnd(start.plus(Duration.ofDays(30)))
              .build());
      notifications.save(
          Notification.builder()
              .userId(order.getUserId())
              .type(NotificationType.SYSTEM)
              .title("Welcome to Premium")
              .body("Your premium plan is active for the next 30 days.")
              .build());
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseMeta(String json) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
    } catch (Exception e) {
      throw ApiException.badRequest("bad_metadata", "Order metadata unreadable");
    }
  }
}
