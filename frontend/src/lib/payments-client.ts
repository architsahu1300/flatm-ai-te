import { apiFetch, apiPost } from "@/lib/api";

export interface Plan {
  id: string;
  slug: string;
  name: string;
  tier: "FREE" | "PREMIUM";
  priceMonthly: number;
  features: string; // JSON array of strings
}

export interface OrderSummary {
  id: string;
  kind: "BOOST" | "SUBSCRIPTION" | "AGREEMENT_FEE" | "VERIFICATION_FEE";
  amount: number;
  currency?: string;
  status: "CREATED" | "PENDING" | "SUCCEEDED" | "FAILED" | "REFUNDED";
  createdAt?: string;
}

export function fetchPlans() {
  return apiFetch<{ plans: Plan[]; boostPrices: Record<string, number> }>("/api/v1/payments/plans");
}

export function fetchMyBilling() {
  return apiFetch<{ premium: boolean; premiumUntil: string; orders: OrderSummary[] }>(
    "/api/v1/payments/me",
  );
}

export function createBoostOrder(listingId: string, boostDays: number) {
  return apiPost<OrderSummary>("/api/v1/payments/orders", {
    kind: "BOOST",
    listingId,
    boostDays,
    idempotencyKey: crypto.randomUUID(),
  });
}

export function createSubscriptionOrder(planId: string) {
  return apiPost<OrderSummary>("/api/v1/payments/orders", {
    kind: "SUBSCRIPTION",
    planId,
    idempotencyKey: crypto.randomUUID(),
  });
}

export function confirmOrder(orderId: string) {
  return apiPost<OrderSummary>(`/api/v1/payments/orders/${orderId}/confirm`, {});
}

export function planFeatures(plan: Plan): string[] {
  try {
    const parsed = JSON.parse(plan.features);
    return Array.isArray(parsed) ? parsed.map(String) : [];
  } catch {
    return [];
  }
}
