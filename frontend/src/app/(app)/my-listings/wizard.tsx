"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { parseAsInteger, useQueryState } from "nuqs";
import { ChipSelect, toOptions } from "@/components/ui/chip-select";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { LABELS, type AmenityRef, type Locality } from "@/lib/domain";
import {
  createListing,
  deleteListingImage,
  updateListing,
  uploadListingImage,
  changeListingStatus,
  type ListingImage,
} from "@/lib/listings-client";
import { apiFetch } from "@/lib/api";
import { getAmenities, getLocalities } from "@/lib/profile-client";
import type { ListingDetail } from "@/lib/listings-client";

const STEPS = ["Type", "Location", "Details", "Photos", "Flatmates", "Review"] as const;

interface Draft {
  type: string | null;
  bhk: number | null;
  localityId: string | null;
  societyName: string;
  title: string;
  description: string;
  rentMonthly: string;
  deposit: string;
  maintenanceMonthly: string;
  availableFrom: string;
  furnishing: string | null;
  amenitySlugs: string[];
  preferredGender: string | null;
  couplesAllowed: boolean;
  householdSmoking: boolean | null;
  householdPets: boolean | null;
  householdDiet: string | null;
  householdSocial: string | null;
  occupantsDesc: string;
}

const EMPTY: Draft = {
  type: null, bhk: null, localityId: null, societyName: "", title: "", description: "",
  rentMonthly: "", deposit: "", maintenanceMonthly: "", availableFrom: "", furnishing: null,
  amenitySlugs: [], preferredGender: null, couplesAllowed: false, householdSmoking: null,
  householdPets: null, householdDiet: null, householdSocial: null, occupantsDesc: "",
};

function draftToBody(d: Draft): Record<string, unknown> {
  const body: Record<string, unknown> = {};
  if (d.type) body.type = d.type;
  if (d.bhk != null) body.bhk = d.bhk;
  if (d.localityId) body.localityId = d.localityId;
  if (d.societyName) body.societyName = d.societyName;
  if (d.title) body.title = d.title;
  if (d.description) body.description = d.description;
  if (d.rentMonthly) body.rentMonthly = Number(d.rentMonthly);
  if (d.deposit) body.deposit = Number(d.deposit);
  if (d.maintenanceMonthly) body.maintenanceMonthly = Number(d.maintenanceMonthly);
  if (d.availableFrom) body.availableFrom = d.availableFrom;
  if (d.furnishing) body.furnishing = d.furnishing;
  if (d.amenitySlugs.length) body.amenitySlugs = d.amenitySlugs;
  if (d.preferredGender) body.preferredGender = d.preferredGender;
  body.couplesAllowed = d.couplesAllowed;
  if (d.householdSmoking != null) body.householdSmoking = d.householdSmoking;
  if (d.householdPets != null) body.householdPets = d.householdPets;
  if (d.householdDiet) body.householdDiet = d.householdDiet;
  if (d.householdSocial) body.householdSocial = d.householdSocial;
  if (d.occupantsDesc) body.occupantsDesc = d.occupantsDesc;
  return body;
}

export function ListingWizard({ listingId }: { listingId?: string }) {
  const router = useRouter();
  const [step, setStep] = useQueryState("step", parseAsInteger.withDefault(1));
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [serverId, setServerId] = useState<string | null>(listingId ?? null);
  const [images, setImages] = useState<ListingImage[]>([]);
  const [localities, setLocalities] = useState<Locality[]>([]);
  const [amenities, setAmenities] = useState<AmenityRef[]>([]);
  const [saving, setSaving] = useState(false);
  const [savedAt, setSavedAt] = useState<Date | null>(null);
  const [publishError, setPublishError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    getLocalities().then(setLocalities).catch(() => {});
    getAmenities().then(setAmenities).catch(() => {});
  }, []);

  // Edit mode: hydrate from the server
  useEffect(() => {
    if (!listingId) return;
    apiFetch<ListingDetail>(`/api/v1/listings/${listingId}`)
      .then((detail) => {
        const { card } = detail;
        setDraft({
          type: card.type,
          bhk: card.bhk,
          localityId: card.localityId,
          societyName: "",
          title: card.title,
          description: detail.description,
          rentMonthly: String(card.rentMonthly || ""),
          deposit: String(card.deposit || ""),
          maintenanceMonthly: String(card.maintenanceMonthly || ""),
          availableFrom: card.availableFrom,
          furnishing: card.furnishing,
          amenitySlugs: card.amenitySlugs,
          preferredGender: card.preferredGender,
          couplesAllowed: false,
          householdSmoking: card.householdSmoking,
          householdPets: card.householdPets,
          householdDiet: card.householdDiet,
          householdSocial: card.householdSocial,
          occupantsDesc: card.occupantsDesc ?? "",
        });
        setImages(detail.images);
      })
      .catch(() => {});
  }, [listingId]);

  const persist = useCallback(
    async (d: Draft) => {
      if (!d.type || !d.localityId) return; // draft needs at least these
      setSaving(true);
      try {
        if (!serverId) {
          const created = await createListing(draftToBody(d));
          setServerId(created.id);
        } else {
          await updateListing(serverId, draftToBody(d));
        }
        setSavedAt(new Date());
      } catch {
        // keep local state; retry on next change
      } finally {
        setSaving(false);
      }
    },
    [serverId],
  );

  const patch = useCallback(
    (partial: Partial<Draft>) => {
      setDraft((prev) => {
        const next = { ...prev, ...partial };
        if (saveTimer.current) clearTimeout(saveTimer.current);
        saveTimer.current = setTimeout(() => persist(next), 1200);
        return next;
      });
    },
    [persist],
  );

  async function handleUpload(files: FileList | null) {
    if (!files || !serverId) return;
    setBusy(true);
    try {
      for (const file of Array.from(files)) {
        const updated = await uploadListingImage(serverId, file);
        setImages(updated);
      }
    } catch (e) {
      setPublishError(e instanceof Error ? e.message : "Upload failed");
    } finally {
      setBusy(false);
    }
  }

  async function publish() {
    if (!serverId) return;
    setBusy(true);
    setPublishError(null);
    try {
      await persist(draft);
      await changeListingStatus(serverId, "ACTIVE");
      router.push("/my-listings");
    } catch (e) {
      setPublishError(e instanceof Error ? e.message : "Could not publish");
      setBusy(false);
    }
  }

  const canContinueFromStep1 = draft.type != null && draft.bhk != null;
  const canContinueFromStep2 = draft.localityId != null;

  return (
    <div className="mx-auto max-w-2xl">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-xl font-semibold tracking-tight">
          {listingId ? "Edit listing" : "List your place"}
        </h1>
        <span className="text-xs text-text-muted">
          {saving ? "Saving…" : savedAt ? "Saved ✓" : ""}
        </span>
      </div>

      {/* step rail */}
      <ol className="mb-8 flex items-center gap-1">
        {STEPS.map((label, i) => (
          <li key={label} className="flex flex-1 flex-col items-center gap-1">
            <button
              type="button"
              onClick={() => (i + 1 < step || serverId) && setStep(i + 1)}
              className={`h-1.5 w-full cursor-pointer rounded-chip ${i + 1 <= step ? "bg-brand" : "bg-surface-2"}`}
              aria-label={`Step ${i + 1}: ${label}`}
            />
            <span className={`hidden text-[11px] sm:block ${i + 1 === step ? "font-medium text-brand" : "text-text-muted"}`}>
              {label}
            </span>
          </li>
        ))}
      </ol>

      <div className="rounded-card border border-border bg-surface p-6 shadow-card">
        {step === 1 && (
          <div className="space-y-5">
            <div className="space-y-2">
              <Label>What are you listing?</Label>
              <ChipSelect
                options={toOptions(LABELS.listingType)}
                value={draft.type}
                onChange={(v) => patch({ type: v })}
              />
            </div>
            <div className="space-y-2">
              <Label>Flat configuration</Label>
              <ChipSelect
                options={[1, 2, 3, 4].map((n) => ({ value: String(n), label: `${n} BHK` }))}
                value={draft.bhk != null ? String(draft.bhk) : null}
                onChange={(v) => patch({ bhk: Number(v) })}
              />
            </div>
            <div className="flex justify-end">
              <Button disabled={!canContinueFromStep1} onClick={() => setStep(2)}>Continue</Button>
            </div>
          </div>
        )}

        {step === 2 && (
          <div className="space-y-5">
            <div className="space-y-2">
              <Label>Locality</Label>
              <ChipSelect
                options={localities.map((l) => ({ value: l.id, label: l.name }))}
                value={draft.localityId}
                onChange={(v) => patch({ localityId: v })}
              />
              <p className="text-xs text-text-muted">
                We only ever show an approximate area circle publicly — never your exact address.
              </p>
            </div>
            <div className="space-y-2">
              <Label htmlFor="society">Society / building (optional)</Label>
              <Input id="society" value={draft.societyName} onChange={(e) => patch({ societyName: e.target.value })} />
            </div>
            <div className="flex justify-between">
              <Button variant="ghost" onClick={() => setStep(1)}>Back</Button>
              <Button disabled={!canContinueFromStep2} onClick={() => { persist(draft); setStep(3); }}>
                Continue
              </Button>
            </div>
          </div>
        )}

        {step === 3 && (
          <div className="space-y-5">
            <div className="space-y-2">
              <Label htmlFor="title">Title</Label>
              <Input
                id="title"
                placeholder="Furnished private room in 2BHK, Bandra West"
                value={draft.title}
                onChange={(e) => patch({ title: e.target.value })}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="desc">Description</Label>
              <Textarea
                id="desc"
                rows={5}
                placeholder="What makes this place good to live in? Who lives here now?"
                value={draft.description}
                onChange={(e) => patch({ description: e.target.value })}
              />
            </div>
            <div className="grid grid-cols-3 gap-3">
              <div className="space-y-2">
                <Label htmlFor="rent">Rent (₹/mo)</Label>
                <Input id="rent" type="number" value={draft.rentMonthly} onChange={(e) => patch({ rentMonthly: e.target.value })} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="dep">Deposit</Label>
                <Input id="dep" type="number" value={draft.deposit} onChange={(e) => patch({ deposit: e.target.value })} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="maint">Maintenance</Label>
                <Input id="maint" type="number" value={draft.maintenanceMonthly} onChange={(e) => patch({ maintenanceMonthly: e.target.value })} />
              </div>
            </div>
            <div className="space-y-2">
              <Label htmlFor="avail">Available from</Label>
              <Input id="avail" type="date" value={draft.availableFrom} onChange={(e) => patch({ availableFrom: e.target.value })} />
            </div>
            <div className="space-y-2">
              <Label>Furnishing</Label>
              <ChipSelect options={toOptions(LABELS.furnishing)} value={draft.furnishing} onChange={(v) => patch({ furnishing: v })} />
            </div>
            <div className="space-y-2">
              <Label>Amenities</Label>
              <ChipSelect
                multi
                options={amenities.map((a) => ({ value: a.slug, label: a.label }))}
                value={draft.amenitySlugs}
                onChange={(slug) =>
                  patch({
                    amenitySlugs: draft.amenitySlugs.includes(slug)
                      ? draft.amenitySlugs.filter((s) => s !== slug)
                      : [...draft.amenitySlugs, slug],
                  })
                }
              />
            </div>
            <div className="flex justify-between">
              <Button variant="ghost" onClick={() => setStep(2)}>Back</Button>
              <Button onClick={() => { persist(draft); setStep(4); }}>Continue</Button>
            </div>
          </div>
        )}

        {step === 4 && (
          <div className="space-y-5">
            <div className="space-y-2">
              <Label>Photos</Label>
              <p className="text-xs text-text-muted">At least 1 photo is required to publish. 3+ get far more replies.</p>
              {!serverId && <p className="text-sm text-warning">Complete the earlier steps first — the draft saves automatically.</p>}
              <input
                type="file"
                accept="image/jpeg,image/png,image/webp"
                multiple
                disabled={!serverId || busy}
                onChange={(e) => handleUpload(e.target.files)}
                className="block w-full cursor-pointer rounded-control border border-dashed border-border bg-surface-2 p-6 text-sm text-text-muted file:mr-3 file:cursor-pointer file:rounded-control file:border-0 file:bg-brand file:px-3 file:py-1.5 file:text-white"
              />
              {busy && <Spinner className="text-brand" />}
              <div className="grid grid-cols-3 gap-2">
                {images.map((img) => (
                  <div key={img.id} className="group relative overflow-hidden rounded-control border border-border">
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={img.url} alt="" className="aspect-[4/3] w-full object-cover" />
                    {img.isCover && (
                      <span className="absolute left-1 top-1 rounded-chip bg-brand px-1.5 text-[10px] text-white">Cover</span>
                    )}
                    <button
                      type="button"
                      onClick={() => serverId && deleteListingImage(serverId, img.id).then(setImages)}
                      className="absolute right-1 top-1 hidden cursor-pointer rounded-chip bg-black/60 px-1.5 text-xs text-white group-hover:block"
                      aria-label="Remove photo"
                    >
                      ✕
                    </button>
                  </div>
                ))}
              </div>
            </div>
            <div className="flex justify-between">
              <Button variant="ghost" onClick={() => setStep(3)}>Back</Button>
              <Button onClick={() => setStep(5)}>Continue</Button>
            </div>
          </div>
        )}

        {step === 5 && (
          <div className="space-y-5">
            <div className="space-y-2">
              <Label>Who lives here now?</Label>
              <Input
                placeholder="2 working professionals in their 20s"
                value={draft.occupantsDesc}
                onChange={(e) => patch({ occupantsDesc: e.target.value })}
              />
            </div>
            <div className="space-y-2">
              <Label>Preferred flatmate</Label>
              <ChipSelect options={toOptions(LABELS.genderPreference)} value={draft.preferredGender} onChange={(v) => patch({ preferredGender: v })} />
            </div>
            <div className="space-y-2">
              <Label>Household vibe</Label>
              <ChipSelect options={toOptions(LABELS.social)} value={draft.householdSocial} onChange={(v) => patch({ householdSocial: v })} />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label>Smoking at home?</Label>
                <ChipSelect
                  options={[{ value: "yes", label: "Okay" }, { value: "no", label: "Not allowed" }]}
                  value={draft.householdSmoking == null ? null : draft.householdSmoking ? "yes" : "no"}
                  onChange={(v) => patch({ householdSmoking: v === "yes" })}
                />
              </div>
              <div className="space-y-2">
                <Label>Pets in the flat?</Label>
                <ChipSelect
                  options={[{ value: "yes", label: "Yes" }, { value: "no", label: "No" }]}
                  value={draft.householdPets == null ? null : draft.householdPets ? "yes" : "no"}
                  onChange={(v) => patch({ householdPets: v === "yes" })}
                />
              </div>
            </div>
            <div className="space-y-2">
              <Label>Kitchen</Label>
              <ChipSelect options={toOptions(LABELS.diet)} value={draft.householdDiet} onChange={(v) => patch({ householdDiet: v })} />
            </div>
            <div className="flex justify-between">
              <Button variant="ghost" onClick={() => setStep(4)}>Back</Button>
              <Button onClick={() => { persist(draft); setStep(6); }}>Review</Button>
            </div>
          </div>
        )}

        {step === 6 && (
          <div className="space-y-5">
            <h2 className="font-semibold">Review &amp; publish</h2>
            <dl className="space-y-2 text-sm">
              {[
                ["Type", draft.type ? LABELS.listingType[draft.type as keyof typeof LABELS.listingType] : "—"],
                ["Title", draft.title || "—"],
                ["Rent", draft.rentMonthly ? `₹${Number(draft.rentMonthly).toLocaleString("en-IN")}/mo` : "—"],
                ["Deposit", draft.deposit ? `₹${Number(draft.deposit).toLocaleString("en-IN")}` : "—"],
                ["Available", draft.availableFrom || "—"],
                ["Photos", `${images.length}`],
                ["Amenities", `${draft.amenitySlugs.length} selected`],
              ].map(([k, v]) => (
                <div key={k} className="flex justify-between border-b border-border pb-2">
                  <dt className="text-text-muted">{k}</dt>
                  <dd className="font-medium">{v}</dd>
                </div>
              ))}
            </dl>
            {publishError && <p className="text-sm text-danger">{publishError}</p>}
            <div className="flex justify-between">
              <Button variant="ghost" onClick={() => setStep(5)}>Back</Button>
              <div className="flex gap-2">
                <Button variant="outline" onClick={() => { persist(draft); router.push("/my-listings"); }}>
                  Save as draft
                </Button>
                <Button onClick={publish} disabled={busy || !serverId}>
                  {busy ? <Spinner /> : "Publish"}
                </Button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
