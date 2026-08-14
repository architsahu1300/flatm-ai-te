"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { ListingCardView } from "@/components/listing/ListingCard";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { chipsFromIntent, type SearchIntent } from "@/lib/ai-client";
import { formatRelativeTime } from "@/lib/domain";
import type { ListingCard } from "@/lib/listings-client";
import {
  deleteSavedSearch,
  fetchSavedListings,
  fetchSavedSearches,
  unsaveListing,
  updateSavedSearch,
  type SavedSearch,
} from "@/lib/saved-client";
import { cn } from "@/lib/utils";

export function SavedScreen() {
  const router = useRouter();
  const [tab, setTab] = useState<"homes" | "searches">("homes");
  const [homes, setHomes] = useState<ListingCard[] | null>(null);
  const [searches, setSearches] = useState<SavedSearch[] | null>(null);

  useEffect(() => {
    fetchSavedListings().then(setHomes).catch(() => setHomes([]));
    fetchSavedSearches().then(setSearches).catch(() => setSearches([]));
  }, []);

  async function removeListing(id: string) {
    setHomes((prev) => prev?.filter((l) => l.id !== id) ?? null);
    unsaveListing(id).catch(() => {});
  }

  async function removeSearch(id: string) {
    setSearches((prev) => prev?.filter((s) => s.id !== id) ?? null);
    deleteSavedSearch(id).catch(() => {});
  }

  async function rename(search: SavedSearch) {
    const name = window.prompt("Rename saved search", search.name);
    if (!name || name === search.name) return;
    const updated = await updateSavedSearch(search.id, { name });
    setSearches((prev) => prev?.map((s) => (s.id === search.id ? updated : s)) ?? null);
  }

  function runSearch(search: SavedSearch) {
    const intent = JSON.parse(search.intent) as SearchIntent;
    const query = intent.originalQuery ?? intent.freeText ?? search.name;
    router.push(`/search?q=${encodeURIComponent(query)}`);
  }

  async function toggleAlerts(search: SavedSearch) {
    const updated = await updateSavedSearch(search.id, { alertsEnabled: !search.alertsEnabled });
    setSearches((prev) => prev?.map((s) => (s.id === search.id ? updated : s)) ?? null);
  }

  return (
    <div className="mx-auto max-w-4xl">
      <div className="mb-4">
        <h1 className="text-xl font-semibold tracking-tight">Saved</h1>
        <p className="text-sm text-text-muted">
          Manage your shortlisted homes and saved search criteria.
        </p>
      </div>

      <div className="mb-6 flex w-fit rounded-control bg-surface-2 p-1 text-sm font-medium">
        {(
          [
            ["homes", `Homes (${homes?.length ?? "…"})`],
            ["searches", `Searches (${searches?.length ?? "…"})`],
          ] as const
        ).map(([t, label]) => (
          <button
            key={t}
            type="button"
            onClick={() => setTab(t)}
            className={cn(
              "cursor-pointer rounded-[calc(var(--radius-control)-4px)] px-4 py-1.5 transition-colors",
              tab === t ? "bg-surface shadow-sm" : "text-text-muted",
            )}
          >
            {label}
          </button>
        ))}
      </div>

      {tab === "homes" ? (
        homes === null ? (
          <div className="grid gap-5 sm:grid-cols-2">
            {Array.from({ length: 4 }, (_, i) => (
              <Skeleton key={i} className="h-72 rounded-card" />
            ))}
          </div>
        ) : homes.length === 0 ? (
          <EmptyState
            title="Nothing saved yet"
            hint="Tap the heart on any listing to keep it here."
            action={<Button onClick={() => router.push("/explore")}>Browse homes</Button>}
          />
        ) : (
          <div className="grid gap-5 sm:grid-cols-2">
            {homes.map((l) => (
              <div key={l.id} className="relative">
                {/* card's own SaveButton is off — this screen's heart removes from the list */}
                <ListingCardView listing={l} showSave={false} />
                <button
                  type="button"
                  onClick={() => removeListing(l.id)}
                  aria-label="Remove from saved"
                  className="absolute right-3 top-3 flex h-9 w-9 cursor-pointer items-center justify-center rounded-chip bg-surface/90 text-lg shadow-sm backdrop-blur"
                >
                  ❤️
                </button>
              </div>
            ))}
          </div>
        )
      ) : searches === null ? (
        <Skeleton className="h-40 rounded-card" />
      ) : searches.length === 0 ? (
        <EmptyState
          title="No saved searches"
          hint='Run an AI search and hit "Save this search" to replay it anytime.'
          action={<Button onClick={() => router.push("/search")}>Start a search</Button>}
        />
      ) : (
        <div className="space-y-3">
          {searches.map((s) => {
            let chips: string[] = [];
            try {
              chips = chipsFromIntent(JSON.parse(s.intent) as SearchIntent).map(
                (c) => `${c.icon} ${c.value}`,
              );
            } catch {
              // ignore malformed intents
            }
            return (
              <div key={s.id} className="rounded-card border border-border bg-surface p-4 shadow-card">
                <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
                  <div className="flex min-w-0 flex-1 basis-56 items-center gap-2">
                    <p className="truncate font-medium">{s.name}</p>
                    <button
                      type="button"
                      onClick={() => toggleAlerts(s)}
                      aria-pressed={s.alertsEnabled}
                      title={s.alertsEnabled ? "Alerts on — click to pause" : "Alerts off — click to enable"}
                      className={cn(
                        "shrink-0 cursor-pointer rounded-chip px-2 py-0.5 text-[11px] font-medium transition-colors",
                        s.alertsEnabled
                          ? "bg-brand-soft text-brand"
                          : "bg-surface-2 text-text-muted hover:text-text",
                      )}
                    >
                      {s.alertsEnabled ? "🔔 Alerts on" : "🔕 Alerts off"}
                    </button>
                  </div>
                  <div className="flex shrink-0 gap-2">
                    <Button size="sm" onClick={() => runSearch(s)}>Run</Button>
                    <Button size="sm" variant="ghost" onClick={() => rename(s)}>Rename</Button>
                    <Button size="sm" variant="ghost" className="text-danger" onClick={() => removeSearch(s.id)}>
                      Delete
                    </Button>
                  </div>
                </div>
                {chips.length > 0 && (
                  <div className="mt-2 flex flex-wrap gap-1.5">
                    {chips.slice(0, 6).map((c) => (
                      <span key={c} className="rounded-chip bg-surface-2 px-2 py-0.5 text-xs text-text-muted">
                        {c}
                      </span>
                    ))}
                  </div>
                )}
                {s.lastRunAt && (
                  <p className="mt-2 text-xs text-text-muted">
                    Last run: {formatRelativeTime(s.lastRunAt)} · {s.lastResultCount ?? 0} matches
                  </p>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

function EmptyState({ title, hint, action }: { title: string; hint: string; action: React.ReactNode }) {
  return (
    <div className="rounded-card border border-border bg-surface p-10 text-center">
      <p className="text-lg font-medium">{title}</p>
      <p className="mt-1 text-sm text-text-muted">{hint}</p>
      <div className="mt-4">{action}</div>
    </div>
  );
}
