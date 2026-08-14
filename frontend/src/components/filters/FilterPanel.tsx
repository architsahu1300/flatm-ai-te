"use client";

import { ChipSelect, toOptions } from "@/components/ui/chip-select";
import { Input } from "@/components/ui/input";

import { LABELS, type AmenityRef, type Locality } from "@/lib/domain";
import type { ExploreFilters } from "@/lib/explore-params";

function FilterHeading({ children }: { children: React.ReactNode }) {
  return (
    <h3 className="text-[11px] font-semibold uppercase tracking-wider text-text-muted">{children}</h3>
  );
}

/** Shared filter controls — rendered in the desktop sidebar and the mobile sheet. */
export function FilterPanel({
  filters,
  onChange,
  localities,
  amenities,
}: {
  filters: ExploreFilters;
  onChange: <K extends keyof ExploreFilters>(key: K, value: ExploreFilters[K]) => void;
  localities: Locality[];
  amenities: AmenityRef[];
}) {
  return (
    <div className="space-y-6">
      <section className="space-y-2.5">
        <FilterHeading>Locality</FilterHeading>
        <ChipSelect
          multi
          options={localities.map((l) => ({ value: l.id, label: l.name }))}
          value={filters.loc}
          onChange={(id) =>
            onChange(
              "loc",
              filters.loc.includes(id) ? filters.loc.filter((x) => x !== id) : [...filters.loc, id],
            )
          }
        />
      </section>

      <section className="space-y-2.5">
        <FilterHeading>Budget (₹/month)</FilterHeading>
        <div className="flex items-center gap-2">
          <Input
            type="number"
            inputMode="numeric"
            placeholder="Min"
            value={filters.bmin ?? ""}
            onChange={(e) => onChange("bmin", e.target.value ? Number(e.target.value) : null)}
          />
          <span className="text-text-muted">–</span>
          <Input
            type="number"
            inputMode="numeric"
            placeholder="Max"
            value={filters.bmax ?? ""}
            onChange={(e) => onChange("bmax", e.target.value ? Number(e.target.value) : null)}
          />
        </div>
      </section>

      <section className="space-y-2.5">
        <FilterHeading>Room type</FilterHeading>
        <ChipSelect
          options={toOptions(LABELS.roomType)}
          value={filters.room}
          onChange={(v) => onChange("room", filters.room === v ? null : v)}
        />
      </section>

      <section className="space-y-2.5">
        <FilterHeading>Furnishing</FilterHeading>
        <ChipSelect
          multi
          options={toOptions(LABELS.furnishing)}
          value={filters.furn}
          onChange={(v) =>
            onChange(
              "furn",
              filters.furn.includes(v) ? filters.furn.filter((x) => x !== v) : [...filters.furn, v],
            )
          }
        />
      </section>

      <section className="space-y-2.5">
        <FilterHeading>BHK</FilterHeading>
        <div className="flex items-center gap-2">
          <Input
            type="number"
            inputMode="numeric"
            placeholder="Min"
            value={filters.bhkMin ?? ""}
            onChange={(e) => onChange("bhkMin", e.target.value ? Number(e.target.value) : null)}
          />
          <span className="text-text-muted">–</span>
          <Input
            type="number"
            inputMode="numeric"
            placeholder="Max"
            value={filters.bhkMax ?? ""}
            onChange={(e) => onChange("bhkMax", e.target.value ? Number(e.target.value) : null)}
          />
        </div>
      </section>

      <section className="space-y-2.5">
        <FilterHeading>Move-in by</FilterHeading>
        <Input
          type="date"
          value={filters.moveInBy ?? ""}
          onChange={(e) => onChange("moveInBy", e.target.value || null)}
        />
      </section>

      <section className="space-y-2.5">
        <FilterHeading>Lifestyle</FilterHeading>
        <div className="space-y-2">
          {(
            [
              ["smokeFree", "Non-smoking household"],
              ["petFriendly", "Pet friendly"],
              ["veg", "Vegetarian household"],
              ["verified", "Verified only"],
            ] as const
          ).map(([key, label]) => (
            <label key={key} className="flex cursor-pointer items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={filters[key]}
                onChange={(e) => onChange(key, e.target.checked)}
                className="h-4 w-4 accent-(--color-brand)"
              />
              {label}
            </label>
          ))}
        </div>
        <ChipSelect
          options={toOptions(LABELS.social)}
          value={filters.social}
          onChange={(v) => onChange("social", filters.social === v ? null : v)}
        />
      </section>

      <section className="space-y-2.5">
        <FilterHeading>Amenities</FilterHeading>
        <ChipSelect
          multi
          options={amenities.map((a) => ({ value: a.slug, label: a.label }))}
          value={filters.amen}
          onChange={(slug) =>
            onChange(
              "amen",
              filters.amen.includes(slug)
                ? filters.amen.filter((x) => x !== slug)
                : [...filters.amen, slug],
            )
          }
        />
      </section>
    </div>
  );
}
