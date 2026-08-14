"use client";

import { useState } from "react";
import Link from "next/link";
import { CompatibilityRing } from "@/components/flatmate/CompatibilityRing";
import { SaveButton } from "@/components/listing/SaveButton";
import { Avatar } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { formatINR, LABELS } from "@/lib/domain";
import type { AiResult } from "@/lib/ai-client";
import { cn } from "@/lib/utils";

export function AiMatchCard({
  result,
  compareSelected,
  onToggleCompare,
}: {
  result: AiResult;
  compareSelected: boolean;
  onToggleCompare: () => void;
}) {
  const [whyOpen, setWhyOpen] = useState(false);
  const id = result.home?.id ?? result.flatmate?.id ?? "";
  const href = result.home ? `/listing/${id}` : `/flatmate/${id}`;

  return (
    <div
      className={cn(
        "overflow-hidden rounded-card border bg-surface shadow-card transition-all",
        compareSelected ? "border-brand ring-2 ring-(--color-brand-soft)" : "border-border",
      )}
    >
      {/* Home cards stack on mobile (photo full-width, match ring overlaid) so the title
          gets the whole column; they go side-by-side from sm up. Flatmate cards stay
          side-by-side throughout — a 64px avatar leaves the title enough room. */}
      <div
        className={cn(
          result.home
            ? "flex flex-col sm:flex-row sm:gap-4 sm:p-4"
            : "flex gap-4 p-4",
        )}
      >
        {/* visual */}
        {result.home ? (
          <Link
            href={href}
            className="relative block aspect-[16/10] w-full shrink-0 overflow-hidden bg-surface-2 sm:aspect-auto sm:h-32 sm:w-44 sm:rounded-control"
          >
            {result.home.coverImageUrl ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={result.home.coverImageUrl} alt="" className="h-full w-full object-cover" loading="lazy" />
            ) : (
              <span className="flex h-full items-center justify-center text-xs text-text-muted">No photo</span>
            )}
            {result.home.isBoosted && (
              <span className="absolute left-2.5 bottom-2.5 rounded-chip bg-warning-soft px-2 py-0.5 text-[10px] font-medium text-warning sm:left-1 sm:top-1 sm:bottom-auto sm:px-1.5 sm:py-0">
                Featured
              </span>
            )}
            {/* ring rides the photo on mobile; lives in the body from sm up */}
            <span className="absolute left-2.5 top-2.5 rounded-chip bg-surface/90 p-0.5 shadow-sm backdrop-blur sm:hidden">
              <CompatibilityRing value={result.matchScore} size={40} />
            </span>
            <SaveButton
              listingId={result.home.id}
              className="absolute right-2 top-2 h-9 w-9 sm:bottom-1 sm:right-1 sm:top-auto sm:h-8 sm:w-8 sm:text-base"
            />
          </Link>
        ) : result.flatmate ? (
          <Link href={href} className="shrink-0">
            <Avatar name={result.flatmate.name} src={result.flatmate.image} size={64} />
          </Link>
        ) : null}

        {/* body */}
        <div className={cn("min-w-0 flex-1", result.home && "px-4 pb-4 pt-3 sm:p-0")}>
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              {result.home ? (
                <>
                  <Link href={href} className="line-clamp-2 font-semibold hover:underline sm:line-clamp-1">
                    {result.home.title}
                  </Link>
                  <p className="text-[13px] text-text-muted">
                    📍 {result.home.localityName} · {LABELS.roomType[result.home.roomType]}
                    {result.home.bhk ? ` · ${result.home.bhk} BHK` : ""}
                  </p>
                  <p className="mt-1 flex flex-wrap items-baseline gap-x-2">
                    <span>
                      <span className="tnum text-lg font-bold">{formatINR(result.home.rentMonthly)}</span>
                      <span className="text-sm text-text-muted">/mo</span>
                    </span>
                    {result.home.deposit > 0 && (
                      <span className="tnum whitespace-nowrap text-xs text-text-muted">
                        {formatINR(result.home.deposit)} deposit
                      </span>
                    )}
                  </p>
                </>
              ) : result.flatmate ? (
                <>
                  <Link href={href} className="font-semibold hover:underline">
                    {result.flatmate.name}
                    {result.flatmate.age ? `, ${result.flatmate.age}` : ""}
                  </Link>
                  <p className="line-clamp-2 text-[13px] text-text-muted sm:line-clamp-1">
                    {result.flatmate.headline}
                  </p>
                  <div className="mt-1 flex flex-wrap gap-1">
                    {result.flatmate.hasFlat && <Badge variant="brand">Has a flat</Badge>}
                    {result.flatmate.budgetMax != null && (
                      <span className="tnum rounded-chip bg-surface-2 px-2 py-0.5 text-xs text-text-muted">
                        ≤ {formatINR(result.flatmate.budgetMax)}
                      </span>
                    )}
                    {result.flatmate.lifestyleTags.slice(0, 3).map((t) => (
                      <span key={t} className="rounded-chip bg-surface-2 px-2 py-0.5 text-xs text-text-muted">
                        {t}
                      </span>
                    ))}
                  </div>
                </>
              ) : null}
              {result.commuteLabel && (
                <span className="mt-1.5 inline-block rounded-chip bg-brand-soft px-2 py-0.5 text-xs font-medium text-brand">
                  🚇 {result.commuteLabel}
                </span>
              )}
            </div>
            {/* home cards show the ring over the photo on mobile — avoid a second one */}
            <div className={cn("shrink-0", result.home && "hidden sm:block")}>
              <CompatibilityRing value={result.matchScore} size={48} />
            </div>
          </div>

          {/* reasons + concerns */}
          <ul className="mt-2 space-y-0.5 text-[13px]">
            {result.matchReasons.slice(0, 3).map((r) => (
              <li key={r} className="text-success">
                ✓ <span className="text-text">{r}</span>
              </li>
            ))}
            {result.concerns.slice(0, 2).map((c) => (
              <li key={c} className="text-warning">
                ⚠ <span className="text-text">{c}</span>
              </li>
            ))}
          </ul>
        </div>
      </div>

      {/* footer */}
      <div className="flex items-center justify-between border-t border-border bg-surface-2/50 px-4 py-2">
        <button
          type="button"
          onClick={() => setWhyOpen((o) => !o)}
          className="cursor-pointer text-[13px] font-medium text-brand hover:underline"
          aria-expanded={whyOpen}
        >
          {whyOpen ? "Hide score details" : "Why this match?"}
        </button>
        <div className="flex items-center gap-3">
          <label className="flex cursor-pointer items-center gap-1.5 text-[13px] text-text-muted">
            <input
              type="checkbox"
              checked={compareSelected}
              onChange={onToggleCompare}
              className="h-3.5 w-3.5 accent-(--color-brand)"
            />
            Compare
          </label>
          <Link href={href} className="text-[13px] font-medium text-text hover:underline">
            View →
          </Link>
        </div>
      </div>

      {whyOpen && (
        <div className="border-t border-border px-4 py-3">
          <p className="mb-2 text-xs font-medium uppercase tracking-wide text-text-muted">
            Score breakdown — {result.matchScore}%
          </p>
          <ul className="space-y-1.5">
            {result.scoreBreakdown.map((comp) => (
              <li key={comp.component} className="flex items-center gap-2 text-[13px]">
                <span className="w-24 shrink-0 capitalize text-text-muted">{comp.component}</span>
                <span className="h-1.5 w-28 shrink-0 overflow-hidden rounded-chip bg-surface-2">
                  <span
                    className={cn(
                      "block h-full rounded-chip",
                      comp.score >= 0.75 ? "bg-success" : comp.score >= 0.4 ? "bg-warning" : "bg-danger",
                    )}
                    style={{ width: `${Math.round(comp.score * 100)}%` }}
                  />
                </span>
                <span className="min-w-0 flex-1 truncate text-text-muted">{comp.detail ?? ""}</span>
              </li>
            ))}
          </ul>
          <p className="mt-2 text-[11px] text-text-muted">
            Estimated from listing data and profiles. Always verify in person before deciding.
          </p>
        </div>
      )}
    </div>
  );
}
