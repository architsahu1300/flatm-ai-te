"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { formatINR, formatRelativeTime } from "@/lib/domain";
import { fetchAgreements, type Agreement, type AgreementStatus } from "@/lib/agreements-client";

export const STATUS_STYLES: Record<AgreementStatus, "outline" | "brand" | "success" | "warning"> = {
  DRAFT: "outline",
  UNDER_REVIEW: "warning",
  FINALIZED: "brand",
  SIGNED: "success",
  CANCELLED: "outline",
};

export function AgreementsScreen() {
  const [items, setItems] = useState<Agreement[] | null>(null);

  useEffect(() => {
    fetchAgreements().then(setItems).catch(() => setItems([]));
  }, []);

  return (
    <div className="mx-auto max-w-3xl">
      <div className="mb-6 flex items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Rental agreements</h1>
          <p className="text-sm text-text-muted">
            Draft, review and e-sign — with government charges always separate.
          </p>
        </div>
        <Link href="/agreements/new">
          <Button>+ New agreement</Button>
        </Link>
      </div>

      {items === null ? (
        <div className="space-y-3">
          {Array.from({ length: 3 }, (_, i) => (
            <Skeleton key={i} className="h-28 rounded-card" />
          ))}
        </div>
      ) : items.length === 0 ? (
        <div className="rounded-card border border-border bg-surface p-10 text-center">
          <p className="text-lg font-medium">No agreements yet</p>
          <p className="mx-auto mt-1 max-w-sm text-sm text-text-muted">
            Found your place or flatmate? Draft a Maharashtra leave &amp; license agreement in
            minutes — AI helps with clauses, you stay in control.
          </p>
          <Link href="/agreements/new">
            <Button className="mt-4">Create your first agreement</Button>
          </Link>
        </div>
      ) : (
        <div className="space-y-3">
          {items.map((a) => {
            const signed = a.signatures.filter((s) => s.status === "SIGNED").length;
            return (
              <Link
                key={a.id}
                href={`/agreements/${a.id}`}
                className="block rounded-card border border-border bg-surface p-4 shadow-card transition-colors hover:bg-surface-2/50"
              >
                <div className="flex items-center justify-between gap-3">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <Badge variant={STATUS_STYLES[a.status]}>{a.status}</Badge>
                      <span className="text-xs text-text-muted">v{a.currentVersion}</span>
                    </div>
                    <p className="mt-1.5 truncate font-medium">
                      {a.propertyAddress ?? a.listingTitle ?? "Rental agreement"}
                    </p>
                    <p className="tnum text-sm text-text-muted">
                      {formatINR(a.rentMonthly)}/mo · {a.durationMonths} months · from{" "}
                      {new Date(a.startDate).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" })}
                    </p>
                  </div>
                  <div className="shrink-0 text-right text-xs text-text-muted">
                    <p>
                      ✍ {signed}/{a.signatures.length} signed
                    </p>
                    <p className="mt-1">{formatRelativeTime(a.updatedAt)}</p>
                  </div>
                </div>
              </Link>
            );
          })}
        </div>
      )}
    </div>
  );
}
