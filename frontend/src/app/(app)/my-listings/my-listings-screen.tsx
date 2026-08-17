"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { LABELS, formatINR, type ListingStatus } from "@/lib/domain";
import { changeListingStatus, fetchMyListings, type ListingCard } from "@/lib/listings-client";

const STATUS_STYLES: Record<ListingStatus, "default" | "brand" | "success" | "warning" | "outline"> = {
  DRAFT: "outline",
  ACTIVE: "success",
  PAUSED: "warning",
  RENTED: "brand",
  EXPIRED: "warning",
  REMOVED: "outline",
};

export function MyListingsScreen() {
  const [items, setItems] = useState<ListingCard[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchMyListings().then(setItems).catch(() => setItems([]));
  }, []);

  async function transition(id: string, status: ListingStatus) {
    setError(null);
    try {
      await changeListingStatus(id, status);
      setItems((prev) => prev?.map((l) => (l.id === id ? { ...l, status } : l)) ?? null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Action failed");
    }
  }

  const draft = items?.find((l) => l.status === "DRAFT");

  return (
    <div className="mx-auto max-w-4xl">
      <div className="mb-6 flex items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">My listings</h1>
          <p className="text-sm text-text-muted">Manage your properties and active listings.</p>
        </div>
        <Link href="/my-listings/new">
          <Button>+ New listing</Button>
        </Link>
      </div>

      {/* Resume the most recent draft, per the design */}
      {draft && (
        <div className="mb-4 flex items-center justify-between gap-3 rounded-card border border-brand bg-brand-soft/40 p-4">
          <div className="min-w-0">
            <p className="text-sm font-medium">📝 Listing in progress</p>
            <p className="truncate text-sm text-text-muted">{draft.title}</p>
          </div>
          <Link href={`/my-listings/${draft.id}/edit`} className="shrink-0">
            <Button size="sm">Resume</Button>
          </Link>
        </div>
      )}

      {error && <p className="mb-4 text-sm text-danger">{error}</p>}

      {items === null ? (
        <div className="space-y-3">
          {Array.from({ length: 3 }, (_, i) => (
            <Skeleton key={i} className="h-28 w-full rounded-card" />
          ))}
        </div>
      ) : items.length === 0 ? (
        <div className="rounded-card border border-border bg-surface p-10 text-center">
          <p className="text-lg font-medium">You haven&apos;t listed anything yet</p>
          <p className="mt-1 text-sm text-text-muted">
            List a room or your whole place — it takes about five minutes.
          </p>
          <Link href="/my-listings/new">
            <Button className="mt-4">Create your first listing</Button>
          </Link>
        </div>
      ) : (
        <div className="space-y-3">
          {items.map((l) => (
            <div
              key={l.id}
              className="flex items-center gap-4 rounded-card border border-border bg-surface p-4 shadow-card"
            >
              <div className="h-20 w-28 shrink-0 overflow-hidden rounded-control bg-surface-2">
                {l.coverImageUrl && (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img src={l.coverImageUrl} alt="" className="h-full w-full object-cover" />
                )}
              </div>
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <Badge variant={STATUS_STYLES[l.status]}>{l.status}</Badge>
                  <span className="text-xs text-text-muted">{LABELS.listingType[l.type]}</span>
                </div>
                <p className="mt-1 truncate font-medium">{l.title}</p>
                <p className="tnum text-sm text-text-muted">
                  {formatINR(l.rentMonthly)}/mo · {l.localityName}
                </p>
              </div>
              <div className="flex shrink-0 flex-col gap-1.5 sm:flex-row">
                <Link href={`/my-listings/${l.id}/edit`}>
                  <Button variant="outline" size="sm">Edit</Button>
                </Link>
                {l.status === "DRAFT" && (
                  <Button size="sm" onClick={() => transition(l.id, "ACTIVE")}>Publish</Button>
                )}
                {l.status === "ACTIVE" && (
                  <>
                    <Link href={`/plans?boost=${l.id}`}>
                      <Button variant="outline" size="sm">
                        {l.isBoosted ? "★ Featured" : "🚀 Boost"}
                      </Button>
                    </Link>
                    <Button variant="outline" size="sm" onClick={() => transition(l.id, "PAUSED")}>Pause</Button>
                  </>
                )}
                {l.status === "PAUSED" && (
                  <Button size="sm" onClick={() => transition(l.id, "ACTIVE")}>Resume</Button>
                )}
                {(l.status === "ACTIVE" || l.status === "PAUSED") && (
                  <Button variant="ghost" size="sm" onClick={() => transition(l.id, "RENTED")}>
                    Mark rented
                  </Button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
