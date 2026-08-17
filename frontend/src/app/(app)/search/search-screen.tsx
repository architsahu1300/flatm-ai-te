"use client";

import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import { AiMatchCard } from "@/components/search/AiMatchCard";
import { IntentChips } from "@/components/search/IntentChips";
import { EXAMPLE_QUERIES, SearchBox } from "@/components/search/SearchBox";
import { Button } from "@/components/ui/button";
import { Sheet } from "@/components/ui/sheet";
import { Skeleton } from "@/components/ui/skeleton";
import { resultId, useAiSearchStore } from "@/stores/ai-search-store";
import { createSavedSearch } from "@/lib/saved-client";
import { ApiError } from "@/lib/api";
import { cn } from "@/lib/utils";

function SaveSearchButton() {
  const store = useAiSearchStore();
  const [state, setState] = useState<"idle" | "saved" | "error">("idle");

  async function save() {
    if (!store.intent) return;
    const suggested = store.intent.originalQuery?.slice(0, 60) ?? "My search";
    const name = window.prompt("Name this search", suggested);
    if (!name) return;
    try {
      await createSavedSearch(name, store.intent);
      setState("saved");
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        window.location.href = "/signin?next=/search";
        return;
      }
      setState("error");
    }
  }

  if (state === "saved") {
    return <span className="text-sm text-success">✓ Saved</span>;
  }
  return (
    <button
      type="button"
      onClick={save}
      className="cursor-pointer text-sm font-medium text-brand hover:underline"
    >
      {state === "error" ? "Retry save" : "Save this search"}
    </button>
  );
}

export function SearchScreen() {
  const searchParams = useSearchParams();
  const store = useAiSearchStore();
  const handedOffQuery = searchParams.get("q");
  const lastHandoff = useRef<string | null>(null);
  const [refineText, setRefineText] = useState("");

  // ?q= handoffs — from the landing hero and the top-nav search field. Reacting to the value
  // (not just mounting) means a nav search from another page runs even when /search is cached.
  useEffect(() => {
    if (!handedOffQuery || handedOffQuery === lastHandoff.current) {
      return;
    }
    lastHandoff.current = handedOffQuery;
    store.submit(handedOffQuery);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [handedOffQuery]);

  const busy = store.status === "understanding" || store.status === "searching";
  const results = store.activeTab === "homes" ? store.homes : store.flatmates;
  const showTabs = store.homes.length > 0 && store.flatmates.length > 0;

  if (store.status === "idle") {
    return (
      <div className="mx-auto flex min-h-[60dvh] max-w-2xl flex-col justify-center">
        <h1 className="text-center text-3xl font-bold tracking-tight sm:text-4xl">
          What are you looking for?
        </h1>
        <p className="mt-2 text-center text-text-muted">
          Describe it like you&apos;d tell a friend — budget, area, vibe, people.
        </p>
        <div className="mt-8">
          <SearchBox onSubmit={store.submit} autoFocus />
        </div>
        <div className="mt-5 flex flex-wrap justify-center gap-2">
          {EXAMPLE_QUERIES.slice(0, 3).map((q) => (
            <button
              key={q}
              type="button"
              onClick={() => store.submit(q)}
              className="cursor-pointer rounded-chip border border-border bg-surface px-3 py-1.5 text-[13px] text-text-muted transition-colors hover:border-brand hover:text-brand"
            >
              {q.length > 48 ? q.slice(0, 46) + "…" : q}
            </button>
          ))}
        </div>
      </div>
    );
  }

  return (
    // bottom padding must clear the docked bar, which grows when the compare row shows
    <div className={cn("mx-auto max-w-3xl", store.compareIds.length >= 2 ? "pb-52" : "pb-36")}>
      <SearchBox onSubmit={store.submit} busy={busy} size="md" />

      {/* Understanding / chips */}
      <div className="mt-5" aria-live="polite">
        {store.status === "understanding" ? (
          <div>
            <p className="mb-3 text-sm font-medium text-brand">
              <span className="mr-1 inline-block animate-pulse">✦</span> Understanding your request…
            </p>
            <div className="flex flex-wrap gap-2">
              {Array.from({ length: 5 }, (_, i) => (
                <Skeleton key={i} className="h-7 w-28 rounded-chip" />
              ))}
            </div>
          </div>
        ) : store.intent ? (
          <div>
            <p className="mb-3 text-sm text-text-muted">
              Got it — here&apos;s what I understood
              {store.providerMode === "mock" && (
                <span className="ml-2 rounded-chip bg-surface-2 px-2 py-0.5 text-[11px]">
                  demo parser — add an OpenAI key for full understanding
                </span>
              )}
            </p>
            {store.note && (
              <p className="mb-3 flex items-start gap-2 rounded-card bg-brand-soft p-2.5 text-[13px] leading-relaxed text-brand">
                <span aria-hidden>✦</span>
                {store.note}
              </p>
            )}
            <IntentChips
              intent={store.intent}
              animateKey={store.sessionId + String(store.turns.length)}
              onRemove={(next, note) => store.applyIntent(next, note)}
              disabled={busy}
            />
          </div>
        ) : null}
      </div>

      {store.status === "error" && (
        <div className="mt-6 rounded-card border border-border bg-danger-soft p-4 text-sm">
          <p className="font-medium text-danger">{store.error}</p>
          <p className="mt-1 text-text-muted">Try rephrasing, or use the filters in Explore.</p>
        </div>
      )}

      {/* Results */}
      {store.status === "done" && (
        <div className="mt-6">
          {showTabs && (
            <div className="mb-4 flex w-fit rounded-control bg-surface-2 p-1 text-sm font-medium">
              {(
                [
                  ["homes", `Homes (${store.homes.length})`],
                  ["flatmates", `Flatmates (${store.flatmates.length})`],
                ] as const
              ).map(([tab, label]) => (
                <button
                  key={tab}
                  type="button"
                  onClick={() => store.setActiveTab(tab)}
                  className={cn(
                    "cursor-pointer rounded-[calc(var(--radius-control)-4px)] px-4 py-1.5 transition-colors",
                    store.activeTab === tab ? "bg-surface shadow-sm" : "text-text-muted",
                  )}
                >
                  {label}
                </button>
              ))}
            </div>
          )}

          {results.length > 0 ? (
            <>
              {store.widenedBy && (
                <div className="mb-3 flex items-start gap-2 rounded-card bg-warning-soft p-3 text-[13px] leading-relaxed text-warning">
                  <span aria-hidden>⚠</span>
                  <p className="flex-1">
                    Widened search — <strong>{store.widenedBy}</strong>. Some results fall outside
                    what you originally asked for; the chips above show what is actually being
                    matched.
                  </p>
                  <button
                    type="button"
                    onClick={() => store.submit(store.intent?.originalQuery ?? "")}
                    className="cursor-pointer whitespace-nowrap font-medium underline"
                  >
                    Undo
                  </button>
                </div>
              )}
              <div className="mb-3 flex items-center justify-between">
                <p className="text-sm text-text-muted">
                  {results.length} match{results.length === 1 ? "" : "es"}, best first
                </p>
                <SaveSearchButton />
              </div>
              <div className="space-y-4">
                {results.map((r) => (
                  <AiMatchCard
                    key={resultId(r)}
                    result={r}
                    compareSelected={store.compareIds.includes(resultId(r))}
                    onToggleCompare={() => store.toggleCompare(resultId(r))}
                  />
                ))}
              </div>
            </>
          ) : (
            <div className="rounded-card border border-border bg-surface p-8 text-center">
              <p className="text-lg font-medium">No close matches for this</p>
              {store.relaxers.length > 0 ? (
                <>
                  <p className="mt-1 text-sm text-text-muted">One tap widens the search:</p>
                  <div className="mt-4 flex flex-wrap justify-center gap-2">
                    {store.relaxers.map((rx) => (
                      <Button
                        key={rx.label}
                        variant="outline"
                        onClick={() => store.applyRelaxer(rx.relaxedIntent, rx.label)}
                      >
                        {rx.label} <span className="text-text-muted">· {rx.description}</span>
                      </Button>
                    ))}
                  </div>
                </>
              ) : (
                <p className="mt-1 text-sm text-text-muted">Try changing the area or budget.</p>
              )}
            </div>
          )}
        </div>
      )}

      {/* Docked bar: compare row (when active) + quick refines + refine input.
          Compare lives INSIDE this container so the two can never collide —
          hardcoded offsets broke as soon as the bar's height changed. */}
      <div className="fixed inset-x-0 bottom-14 z-30 border-t border-border bg-surface/95 px-4 py-3 backdrop-blur md:bottom-0">
        <div className="mx-auto max-w-3xl">
          {store.compareIds.length >= 2 && (
            <div className="mb-2 flex items-center justify-between gap-3 rounded-control bg-brand-soft px-3 py-2">
              <span className="text-[13px] font-medium text-brand">
                {store.compareIds.length} selected to compare
              </span>
              <div className="flex shrink-0 gap-2">
                <Button size="sm" variant="ghost" onClick={() => store.compareIds.forEach(store.toggleCompare)}>
                  Clear
                </Button>
                <Button size="sm" onClick={store.runComparison}>
                  Compare →
                </Button>
              </div>
            </div>
          )}
          <div className="mb-2 flex gap-2 overflow-x-auto">
              {["Show me cheaper", "Only verified listings", "Show flatmates instead"].map((q) => (
                <button
                  key={q}
                  type="button"
                  disabled={busy}
                  onClick={() => store.refine(q)}
                  className="shrink-0 cursor-pointer rounded-chip border border-border bg-surface px-3 py-1 text-xs text-text-muted transition-colors hover:border-brand hover:text-brand"
                >
                  {q}
                </button>
              ))}
          </div>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              if (refineText.trim().length >= 3 && !busy) {
                store.refine(refineText.trim());
                setRefineText("");
              }
            }}
            className="flex gap-2"
          >
            <input
              value={refineText}
              onChange={(e) => setRefineText(e.target.value)}
              placeholder='Refine — try "closer to work" or "under 20k"'
              aria-label="Refine your search"
              className="h-10 flex-1 rounded-chip border border-border bg-surface px-4 text-sm placeholder:text-text-muted focus:border-brand focus:outline-none"
            />
            <Button type="submit" disabled={busy || refineText.trim().length < 3}>
              Refine
            </Button>
          </form>
        </div>
      </div>

      {/* Comparison sheet */}
      <Sheet
        open={store.compareOpen && store.comparison != null}
        onClose={store.closeComparison}
        title="Compare matches"
      >
        {store.comparison && (
          <div>
            <p className="mb-4 rounded-control bg-brand-soft p-3 text-sm text-brand">
              ✦ {store.comparison.summary}
            </p>
            <div className="overflow-x-auto">
              <table className="w-full min-w-[480px] border-collapse text-sm">
                <thead>
                  <tr>
                    <th className="w-28 pb-2 text-left text-xs font-medium uppercase text-text-muted"> </th>
                    {store.comparison.items.map((item) => (
                      <th key={resultId(item)} className="pb-2 pr-4 text-left align-top font-semibold">
                        {item.home?.title ?? item.flatmate?.name}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {store.comparison.rows.map((row) => (
                    <tr key={row.label} className="border-t border-border">
                      <td className="py-2 pr-3 text-text-muted">{row.label}</td>
                      {row.values.map((v, i) => (
                        <td
                          key={i}
                          className={cn(
                            "py-2 pr-4",
                            row.bestIndex === i && "rounded bg-success-soft font-medium text-success",
                          )}
                        >
                          {v}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </Sheet>
    </div>
  );
}
