"use client";

import { AiMatchCard } from "@/components/search/AiMatchCard";
import { Button } from "@/components/ui/button";
import { resultId } from "@/stores/ai-search-store";
import type { AiResult, Choice, ResultSummary } from "@/lib/ai-client";

const TIER_ORDER = ["EXACT", "NEARBY", "OVER_BUDGET"] as const;

function tierTitle(tier: (typeof TIER_ORDER)[number], anchorName: string): string {
  switch (tier) {
    case "EXACT":
      return `In ${anchorName}`;
    case "NEARBY":
      return "Within 5 km, under budget";
    case "OVER_BUDGET":
      return `In ${anchorName}, slightly over budget`;
  }
}

/**
 * Groups results by tier under the headline the backend already worded (spec §4.6, §4.8). Renders
 * a flat list with no subheads when `summary.anchorName` is null — that covers both the plain
 * citywide case (no place named) and the case where a named place could not be placed at all; the
 * headline banner is what tells those two apart, and it is rendered verbatim rather than rebuilt.
 *
 * Also surfaces the counted "raise budget" choice, if one came back, alongside the results — this
 * is the one place that choice renders, so a caller must not also render it from `relaxers`.
 */
export function ResultGroups({
  results,
  summary,
  choices,
  compareIds,
  onToggleCompare,
  onApplyChoice,
}: {
  results: AiResult[];
  summary?: ResultSummary | null;
  choices?: Choice[] | null;
  compareIds: string[];
  onToggleCompare: (id: string) => void;
  onApplyChoice: (choice: Choice) => void;
}) {
  const anchorName = summary?.anchorName ?? null;
  const raiseBudget = (choices ?? []).find((c) => c.action === "RAISE_BUDGET") ?? null;

  const card = (r: AiResult) => (
    <AiMatchCard
      key={resultId(r)}
      result={r}
      compareSelected={compareIds.includes(resultId(r))}
      onToggleCompare={() => onToggleCompare(resultId(r))}
    />
  );

  const untiered = results.filter((r) => r.tier == null);

  return (
    <div>
      {summary?.headline && (
        <div className="mb-4 flex items-start gap-2 rounded-card bg-warning-soft p-3 text-[13px] leading-relaxed text-warning">
          <span aria-hidden>ℹ</span>
          <p className="flex-1">{summary.headline}</p>
        </div>
      )}

      {anchorName ? (
        <div className="space-y-6">
          {TIER_ORDER.map((tier) => {
            const items = results.filter((r) => r.tier === tier);
            if (items.length === 0) return null;
            return (
              <div key={tier}>
                <h2 className="mb-3 text-sm font-semibold text-text-muted">
                  {tierTitle(tier, anchorName)}
                </h2>
                <div className="space-y-4">{items.map(card)}</div>
              </div>
            );
          })}
          {/* Defensive only: home rows always carry a tier per contract, but a stale backend
              must never silently drop rows it didn't tier. */}
          {untiered.length > 0 && <div className="space-y-4">{untiered.map(card)}</div>}
        </div>
      ) : (
        <div className="space-y-4">{results.map(card)}</div>
      )}

      {raiseBudget && (
        <div className="mt-4 flex flex-wrap items-center justify-between gap-3 rounded-card border border-dashed border-border bg-surface p-3 text-sm">
          <span>{raiseBudget.label}</span>
          <Button size="sm" variant="outline" onClick={() => onApplyChoice(raiseBudget)}>
            Raise budget
          </Button>
        </div>
      )}

      {summary?.terminus && (
        <p className="mt-4 text-center text-[13px] text-text-muted">{summary.terminus}</p>
      )}
    </div>
  );
}
