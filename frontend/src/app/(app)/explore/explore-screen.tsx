"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useQueryStates } from "nuqs";
import { FilterPanel } from "@/components/filters/FilterPanel";
import { ListingCardView } from "@/components/listing/ListingCard";
import { Button } from "@/components/ui/button";
import { Sheet } from "@/components/ui/sheet";
import { Skeleton } from "@/components/ui/skeleton";
import type { AmenityRef, Locality } from "@/lib/domain";
import {
  countActiveFilters,
  exploreParsers,
  filtersToSearchParams,
  type ExploreFilters,
} from "@/lib/explore-params";
import { fetchListings, type ListingCard } from "@/lib/listings-client";
import { getAmenities, getLocalities } from "@/lib/profile-client";

const PAGE_SIZE = 18;

export function ExploreScreen() {
  const [params, setParams] = useQueryStates(exploreParsers);
  const filters = params as ExploreFilters;

  const [items, setItems] = useState<ListingCard[]>([]);
  const [total, setTotal] = useState<number | null>(null);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [sheetOpen, setSheetOpen] = useState(false);
  const [localities, setLocalities] = useState<Locality[]>([]);
  const [amenities, setAmenities] = useState<AmenityRef[]>([]);
  const requestSeq = useRef(0);

  useEffect(() => {
    getLocalities().then(setLocalities).catch(() => {});
    getAmenities().then(setAmenities).catch(() => {});
  }, []);

  const filterKey = useMemo(() => JSON.stringify(filters), [filters]);

  const load = useCallback(
    async (nextPage: number, append: boolean) => {
      const seq = ++requestSeq.current;
      setLoading(true);
      try {
        const result = await fetchListings(filtersToSearchParams(filters, nextPage, PAGE_SIZE));
        if (seq !== requestSeq.current) return;
        setItems((prev) => (append ? [...prev, ...result.items] : result.items));
        setTotal(result.total);
        setPage(nextPage);
      } finally {
        if (seq === requestSeq.current) setLoading(false);
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [filterKey],
  );

  // Debounced reload on any filter change
  useEffect(() => {
    const t = setTimeout(() => load(0, false), 250);
    return () => clearTimeout(t);
  }, [load]);

  const setFilter = useCallback(
    <K extends keyof ExploreFilters>(key: K, value: ExploreFilters[K]) => {
      setParams({ [key]: value } as never);
    },
    [setParams],
  );

  const clearAll = () =>
    setParams(
      Object.fromEntries(Object.keys(exploreParsers).map((k) => [k, null])) as never,
    );

  const activeCount = countActiveFilters(filters);
  const hasMore = total !== null && items.length < total;

  return (
    <div className="flex gap-8">
      {/* Desktop sidebar */}
      <aside className="hidden w-72 shrink-0 lg:block">
        <div className="sticky top-20 max-h-[calc(100dvh-6rem)] overflow-y-auto pr-2">
          <div className="mb-4 flex items-center justify-between">
            <h2 className="text-sm font-semibold">Filters</h2>
            {activeCount > 0 && (
              <button type="button" onClick={clearAll} className="cursor-pointer text-xs text-brand hover:underline">
                Clear all ({activeCount})
              </button>
            )}
          </div>
          <FilterPanel filters={filters} onChange={setFilter} localities={localities} amenities={amenities} />
        </div>
      </aside>

      <div className="min-w-0 flex-1">
        <div className="mb-4 flex items-center justify-between gap-3">
          <div>
            <h1 className="text-xl font-semibold tracking-tight">Explore homes</h1>
            {total !== null && (
              <p className="text-sm text-text-muted">
                {activeCount > 0
                  ? `Showing ${total} curated match${total === 1 ? "" : "es"} based on your filters`
                  : `${total} place${total === 1 ? "" : "s"} in Mumbai`}
              </p>
            )}
          </div>
          <div className="flex items-center gap-2">
            <Button variant="outline" size="sm" className="lg:hidden" onClick={() => setSheetOpen(true)}>
              Filters{activeCount > 0 ? ` (${activeCount})` : ""}
            </Button>
            <label className="flex items-center gap-1 text-sm">
              <span className="hidden text-text-muted sm:inline">Sort by:</span>
              <select
                aria-label="Sort"
                value={filters.sort}
                onChange={(e) => setFilter("sort", e.target.value as ExploreFilters["sort"])}
                className="h-9 cursor-pointer rounded-control border-none bg-transparent pr-1 text-sm font-medium text-brand focus:outline-none"
              >
                <option value="newest">Newest</option>
                <option value="price_asc">Price: low to high</option>
                <option value="price_desc">Price: high to low</option>
              </select>
            </label>
          </div>
        </div>

        {loading && items.length === 0 ? (
          <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 xl:grid-cols-3">
            {Array.from({ length: 6 }, (_, i) => (
              <div key={i} className="overflow-hidden rounded-card border border-border">
                <Skeleton className="aspect-[8/5] rounded-none" />
                <div className="space-y-2 p-4">
                  <Skeleton className="h-5 w-3/4" />
                  <Skeleton className="h-4 w-1/2" />
                  <Skeleton className="h-6 w-1/3" />
                </div>
              </div>
            ))}
          </div>
        ) : items.length === 0 ? (
          <div className="rounded-card border border-border bg-surface p-10 text-center">
            <p className="text-lg font-medium">No places match these filters</p>
            <p className="mt-1 text-sm text-text-muted">Try widening the budget or removing a filter.</p>
            {activeCount > 0 && (
              <Button variant="outline" className="mt-4" onClick={clearAll}>
                Clear all filters
              </Button>
            )}
          </div>
        ) : (
          <>
            <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 xl:grid-cols-3">
              {items.map((listing) => (
                <ListingCardView key={listing.id} listing={listing} />
              ))}
            </div>
            {hasMore && (
              <div className="mt-8 text-center">
                <Button variant="outline" onClick={() => load(page + 1, true)} disabled={loading}>
                  {loading ? "Loading…" : "Load more"}
                </Button>
              </div>
            )}
          </>
        )}
      </div>

      {/* Mobile filter sheet */}
      <Sheet
        open={sheetOpen}
        onClose={() => setSheetOpen(false)}
        title="Filters"
        footer={
          <div className="flex gap-3">
            <Button variant="ghost" className="flex-1" onClick={clearAll}>
              Clear all
            </Button>
            <Button className="flex-1" onClick={() => setSheetOpen(false)}>
              Show {total ?? "…"} homes
            </Button>
          </div>
        }
      >
        <FilterPanel filters={filters} onChange={setFilter} localities={localities} amenities={amenities} />
      </Sheet>
    </div>
  );
}
