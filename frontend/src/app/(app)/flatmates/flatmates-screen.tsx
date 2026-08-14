"use client";

import { useEffect, useState } from "react";
import { useQueryStates, parseAsBoolean, parseAsInteger, parseAsString } from "nuqs";
import { FlatmateCardView } from "@/components/flatmate/FlatmateCard";
import { ChipSelect } from "@/components/ui/chip-select";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import type { Locality } from "@/lib/domain";
import { fetchFlatmates, type FlatmateCard } from "@/lib/flatmates-client";
import { getLocalities } from "@/lib/profile-client";

export function FlatmatesScreen() {
  const [params, setParams] = useQueryStates({
    localityId: parseAsString,
    budgetMax: parseAsInteger,
    hasFlat: parseAsBoolean,
  });
  const [items, setItems] = useState<FlatmateCard[] | null>(null);
  const [localities, setLocalities] = useState<Locality[]>([]);

  useEffect(() => {
    getLocalities().then(setLocalities).catch(() => {});
  }, []);

  useEffect(() => {
    const t = setTimeout(() => {
      const search = new URLSearchParams();
      if (params.localityId) search.set("localityId", params.localityId);
      if (params.budgetMax != null) search.set("budgetMax", String(params.budgetMax));
      if (params.hasFlat != null) search.set("hasFlat", String(params.hasFlat));
      search.set("size", "30");
      fetchFlatmates(search)
        .then(setItems)
        .catch(() => setItems([]));
    }, 250);
    return () => clearTimeout(t);
  }, [params]);

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-xl font-semibold tracking-tight">Find a flatmate</h1>
        <p className="text-sm text-text-muted">
          People looking to share a home — compatibility is scored from both your lifestyle profiles.
        </p>
      </div>

      <div className="mb-6 flex flex-wrap items-end gap-4 rounded-card border border-border bg-surface p-4">
        <div className="space-y-1.5">
          <Label>Area</Label>
          <ChipSelect
            options={localities.map((l) => ({ value: l.id, label: l.name }))}
            value={params.localityId}
            onChange={(v) => setParams({ localityId: params.localityId === v ? null : v })}
          />
        </div>
        <div className="w-36 space-y-1.5">
          <Label htmlFor="fbudget">Their budget fits</Label>
          <Input
            id="fbudget"
            type="number"
            inputMode="numeric"
            placeholder="₹ max"
            value={params.budgetMax ?? ""}
            onChange={(e) => setParams({ budgetMax: e.target.value ? Number(e.target.value) : null })}
          />
        </div>
        <div className="space-y-1.5">
          <Label>Situation</Label>
          <ChipSelect
            options={[
              { value: "true", label: "Has a flat" },
              { value: "false", label: "Looking too" },
            ]}
            value={params.hasFlat == null ? null : String(params.hasFlat)}
            onChange={(v) =>
              setParams({ hasFlat: params.hasFlat === (v === "true") ? null : v === "true" })
            }
          />
        </div>
      </div>

      {items === null ? (
        <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 xl:grid-cols-3">
          {Array.from({ length: 6 }, (_, i) => (
            <Skeleton key={i} className="h-56 rounded-card" />
          ))}
        </div>
      ) : items.length === 0 ? (
        <div className="rounded-card border border-border bg-surface p-10 text-center">
          <p className="text-lg font-medium">No flatmates match these filters</p>
          <p className="mt-1 text-sm text-text-muted">Try removing a filter.</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 xl:grid-cols-3">
          {items.map((f) => (
            <FlatmateCardView key={f.id} flatmate={f} />
          ))}
        </div>
      )}
    </div>
  );
}
