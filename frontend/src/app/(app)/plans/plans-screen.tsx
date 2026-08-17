"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { TrackView } from "@/components/analytics/TrackView";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { track } from "@/lib/analytics";
import { ApiError } from "@/lib/api";
import { formatINR } from "@/lib/domain";
import { fetchMyListings, type ListingCard } from "@/lib/listings-client";
import {
  confirmOrder,
  createBoostOrder,
  createSubscriptionOrder,
  fetchMyBilling,
  fetchPlans,
  planFeatures,
  type Plan,
} from "@/lib/payments-client";
import { cn } from "@/lib/utils";

export function PlansScreen() {
  const router = useRouter();
  const params = useSearchParams();
  const preselectedListing = params.get("boost");

  const [plans, setPlans] = useState<Plan[] | null>(null);
  const [boostPrices, setBoostPrices] = useState<Record<string, number>>({});
  const [premium, setPremium] = useState(false);
  const [premiumUntil, setPremiumUntil] = useState("");
  const [myListings, setMyListings] = useState<ListingCard[]>([]);
  const [listingId, setListingId] = useState(preselectedListing ?? "");
  const [days, setDays] = useState(7);
  const [busy, setBusy] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchPlans()
      .then((d) => {
        setPlans(d.plans);
        setBoostPrices(d.boostPrices);
      })
      .catch(() => setPlans([]));
    fetchMyBilling()
      .then((d) => {
        setPremium(d.premium);
        setPremiumUntil(d.premiumUntil);
      })
      .catch(() => {});
    fetchMyListings()
      .then((ls) => {
        const active = ls.filter((l) => l.status === "ACTIVE");
        setMyListings(active);
        setListingId((cur) => cur || active[0]?.id || "");
      })
      .catch(() => {});
  }, []);

  const boostPrice = boostPrices[String(days)] ?? 0;
  const selectedListing = useMemo(
    () => myListings.find((l) => l.id === listingId),
    [myListings, listingId],
  );

  async function purchase(kind: "boost" | "premium", planId?: string) {
    setBusy(kind);
    setError(null);
    setDone(null);
    try {
      const order =
        kind === "boost"
          ? await createBoostOrder(listingId, days)
          : await createSubscriptionOrder(planId!);
      const confirmed = await confirmOrder(order.id);
      if (confirmed.status !== "SUCCEEDED") {
        throw new Error("Payment did not complete");
      }
      if (kind === "boost") {
        track("boost_purchased", { days, amount: boostPrice });
        setDone(`"${selectedListing?.title ?? "Your listing"}" is now featured for ${days} days.`);
      } else {
        setPremium(true);
        setDone("Premium is active — welcome aboard.");
      }
      router.refresh();
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        router.push("/signin?next=/plans");
        return;
      }
      setError(e instanceof Error ? e.message : "Purchase failed");
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="mx-auto max-w-4xl">
      <TrackView event="plan_viewed" />
      <div className="text-center">
        <h1 className="text-3xl font-semibold tracking-tight">Plans & boost</h1>
        <p className="mt-2 text-text-muted">
          Unlock more of the search — or get your place in front of more seekers.
        </p>
        <p className="mt-2 text-xs text-text-muted">
          Payments are mocked in this MVP — no real money moves.
        </p>
      </div>

      {done && (
        <p className="mt-6 rounded-card bg-success-soft p-3 text-center text-sm text-success">✓ {done}</p>
      )}
      {error && (
        <p className="mt-6 rounded-card bg-danger-soft p-3 text-center text-sm text-danger">{error}</p>
      )}

      {/* plans */}
      <div className="mt-8 grid gap-4 sm:grid-cols-2">
        {plans === null ? (
          <>
            <Skeleton className="h-72 rounded-card" />
            <Skeleton className="h-72 rounded-card" />
          </>
        ) : (
          plans.map((plan) => {
            const isPremiumPlan = plan.tier === "PREMIUM";
            const current = isPremiumPlan ? premium : !premium;
            return (
              <section
                key={plan.id}
                className={cn(
                  "relative rounded-card border bg-surface p-6 shadow-card",
                  isPremiumPlan ? "border-brand" : "border-border",
                )}
              >
                {isPremiumPlan && (
                  <Badge variant="brand" className="absolute -top-2.5 left-6">
                    Most popular
                  </Badge>
                )}
                <h2 className="font-semibold">{plan.name}</h2>
                <p className="mt-2">
                  <span className="tnum text-3xl font-bold">{formatINR(plan.priceMonthly)}</span>
                  <span className="text-sm text-text-muted">
                    {plan.priceMonthly === 0 ? " / forever" : " / month"}
                  </span>
                </p>
                <ul className="mt-4 space-y-2 text-sm">
                  {planFeatures(plan).map((f) => (
                    <li key={f} className="flex items-start gap-2">
                      <span aria-hidden className="text-success">✓</span>
                      {f}
                    </li>
                  ))}
                </ul>
                <div className="mt-6">
                  {current ? (
                    <Button variant="outline" className="w-full" disabled>
                      Current plan
                      {isPremiumPlan && premiumUntil
                        ? ` · until ${new Date(premiumUntil).toLocaleDateString("en-IN", { day: "numeric", month: "short" })}`
                        : ""}
                    </Button>
                  ) : isPremiumPlan ? (
                    <Button
                      className="w-full"
                      disabled={busy !== null}
                      onClick={() => purchase("premium", plan.id)}
                    >
                      {busy === "premium" ? <Spinner /> : `Upgrade — ${formatINR(plan.priceMonthly)}/mo`}
                    </Button>
                  ) : (
                    <Button variant="outline" className="w-full" disabled>
                      Included for everyone
                    </Button>
                  )}
                </div>
              </section>
            );
          })
        )}
      </div>

      {/* boost */}
      <section className="mt-8 rounded-card border border-border bg-surface p-6 shadow-card">
        <div className="flex items-start gap-3">
          <span aria-hidden className="text-2xl">🚀</span>
          <div>
            <h2 className="font-semibold">Boost your listing</h2>
            <p className="text-sm text-text-muted">Get seen by more seekers instantly.</p>
          </div>
        </div>

        {myListings.length === 0 ? (
          <p className="mt-4 rounded-control bg-surface-2 p-3 text-sm text-text-muted">
            You need an active listing to boost — publish one from My listings first.
          </p>
        ) : (
          <>
            <div className="mt-4 space-y-1.5">
              <label htmlFor="boost-listing" className="text-sm font-medium">
                Listing
              </label>
              <select
                id="boost-listing"
                value={listingId}
                onChange={(e) => setListingId(e.target.value)}
                className="h-10 w-full rounded-control border border-border bg-surface px-3 text-sm focus:border-brand focus:outline-none"
              >
                {myListings.map((l) => (
                  <option key={l.id} value={l.id}>
                    {l.title}
                  </option>
                ))}
              </select>
            </div>

            <div className="mt-4 grid grid-cols-3 gap-2">
              {[3, 7, 14].map((d) => (
                <button
                  key={d}
                  type="button"
                  onClick={() => setDays(d)}
                  className={cn(
                    "cursor-pointer rounded-control border p-3 text-center transition-colors",
                    days === d
                      ? "border-brand bg-brand-soft"
                      : "border-border hover:border-brand",
                  )}
                >
                  <p className="tnum font-semibold">{formatINR(boostPrices[String(d)] ?? 0)}</p>
                  <p className="text-xs text-text-muted">{d} days</p>
                </button>
              ))}
            </div>

            <p className="mt-4 flex items-start gap-2 rounded-control bg-surface-2 p-3 text-xs leading-relaxed text-text-muted">
              <span aria-hidden>ⓘ</span>
              Honest note: boosting raises visibility by showing you higher in results — it never
              changes anyone&apos;s Match Score.
            </p>

            <Button
              className="mt-4 w-full"
              disabled={busy !== null || !listingId}
              onClick={() => purchase("boost")}
            >
              {busy === "boost" ? <Spinner /> : `Apply ${days}-day boost (${formatINR(boostPrice)})`}
            </Button>
          </>
        )}
      </section>
    </div>
  );
}
