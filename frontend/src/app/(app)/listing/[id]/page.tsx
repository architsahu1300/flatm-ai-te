import { cookies } from "next/headers";
import Link from "next/link";
import { notFound } from "next/navigation";
import { ApproxMap } from "@/components/map/ApproxMap";
import { SaveButton } from "@/components/listing/SaveButton";
import { Avatar } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { serverFetch } from "@/lib/api";
import { LABELS, formatINR } from "@/lib/domain";
import type { ListingDetail } from "@/lib/listings-client";
import { AmenityChips, ExpandableText, MobileActionBar } from "./detail-bits";
import { ListingGallery } from "./gallery";

export default async function ListingDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const cookieHeader = (await cookies()).toString();

  let detail: ListingDetail;
  try {
    detail = await serverFetch<ListingDetail>(`/api/v1/listings/${id}`, cookieHeader);
  } catch {
    notFound();
  }
  const { card } = detail;

  const facts: [string, string, string][] = [
    ["🛏", "Room", LABELS.roomType[card.roomType]],
    ...(card.bhk ? ([["🏢", "Configuration", `${card.bhk} BHK`]] as [string, string, string][]) : []),
    ["🛋", "Furnishing", LABELS.furnishing[card.furnishing]],
    ["📅", "Available from", new Date(card.availableFrom).toLocaleDateString("en-IN", { day: "numeric", month: "long" })],
    ...(detail.minLeaseMonths
      ? ([["⏳", "Min lease", `${detail.minLeaseMonths} months`]] as [string, string, string][])
      : []),
    ["👤", "Preferred", LABELS.genderPreference[card.preferredGender]],
    ...(detail.couplesAllowed ? ([["💑", "Couples", "Allowed"]] as [string, string, string][]) : []),
    ...(detail.bathroomAttached != null
      ? ([["🚿", "Bathroom", detail.bathroomAttached ? "Attached" : "Shared"]] as [string, string, string][])
      : []),
    ...(detail.balcony ? ([["🌇", "Balcony", "Yes"]] as [string, string, string][]) : []),
  ];

  const household: string[] = [];
  if (card.householdSocial) household.push(LABELS.social[card.householdSocial]);
  if (card.householdSmoking === false) household.push("Non-smoking");
  if (card.householdSmoking === true) household.push("Smoking okay");
  if (card.householdPets) household.push("Has pets");
  if (card.householdDiet) household.push(`${LABELS.diet[card.householdDiet]} kitchen`);

  return (
    // extra bottom padding on mobile clears the sticky action bar
    <article className="mx-auto max-w-5xl pb-24 lg:pb-0">
      <ListingGallery images={detail.images} title={card.title} />

      <div className="mt-6 grid gap-8 lg:grid-cols-[1fr_320px]">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <Badge>{LABELS.listingType[card.type]}</Badge>
            {card.listerVerified && <Badge variant="success">✓ ID verified</Badge>}
            {card.propertyVerified && <Badge variant="success">✓ Property verified</Badge>}
            {card.isBoosted && <Badge variant="warning">Featured</Badge>}
          </div>
          <div className="mt-3 flex items-start justify-between gap-3">
            <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">{card.title}</h1>
            <SaveButton listingId={card.id} />
          </div>
          {card.localityName && (
            <p className="mt-1 text-text-muted">📍 {card.localityName}, Mumbai</p>
          )}

          <section className="mt-6">
            <h2 className="text-lg font-semibold">About this place</h2>
            <div className="mt-2">
              <ExpandableText text={detail.description} />
            </div>
          </section>

          {household.length > 0 && (
            <section className="mt-6">
              <h2 className="text-lg font-semibold">The household</h2>
              {card.occupantsDesc && <p className="mt-2 text-text">{card.occupantsDesc}</p>}
              <div className="mt-2 flex flex-wrap gap-1.5">
                {household.map((h) => (
                  <span key={h} className="rounded-chip bg-surface-2 px-2.5 py-1 text-sm">
                    {h}
                  </span>
                ))}
              </div>
            </section>
          )}

          <section className="mt-6">
            <h2 className="text-lg font-semibold">Key facts</h2>
            <dl className="mt-3 grid grid-cols-2 gap-x-6 gap-y-4 sm:grid-cols-3">
              {facts.map(([icon, k, v]) => (
                <div key={k} className="flex items-start gap-2.5">
                  <span aria-hidden className="mt-0.5 text-base">{icon}</span>
                  <div>
                    <dt className="text-xs text-text-muted">{k}</dt>
                    <dd className="text-sm font-medium">{v}</dd>
                  </div>
                </div>
              ))}
            </dl>
          </section>

          {detail.amenityLabels.length > 0 && (
            <section className="mt-6">
              <h2 className="text-lg font-semibold">Amenities</h2>
              <div className="mt-3">
                <AmenityChips labels={detail.amenityLabels} />
              </div>
            </section>
          )}

          {detail.approxLat && detail.approxLng && (
            <section className="mt-6">
              <h2 className="text-lg font-semibold">Location</h2>
              <p className="mb-3 mt-1 text-sm text-text-muted">
                Approximate area shown — the exact address is shared by the lister after you connect.
              </p>
              <ApproxMap lat={detail.approxLat} lng={detail.approxLng} />
            </section>
          )}
        </div>

        <aside className="lg:sticky lg:top-20 lg:self-start">
          <div className="rounded-card border border-border bg-surface p-5 shadow-card">
            <p>
              <span className="tnum text-2xl font-bold">{formatINR(card.rentMonthly)}</span>
              <span className="text-text-muted">/month</span>
            </p>
            <dl className="mt-3 space-y-1.5 text-sm">
              <div className="flex justify-between">
                <dt className="text-text-muted">Deposit</dt>
                <dd className="tnum font-medium">{formatINR(card.deposit)}</dd>
              </div>
              {card.maintenanceMonthly > 0 && (
                <div className="flex justify-between">
                  <dt className="text-text-muted">Maintenance</dt>
                  <dd className="tnum font-medium">{formatINR(card.maintenanceMonthly)}/mo</dd>
                </div>
              )}
            </dl>
            <div className="mt-4 border-t border-border pt-4">
              <div className="flex items-center gap-3">
                <Avatar name={detail.listerName ?? "Lister"} src={detail.listerImage} size={40} />
                <div className="min-w-0">
                  <p className="truncate text-sm font-medium">{detail.listerName}</p>
                  <p className="text-xs text-text-muted">Lister</p>
                </div>
              </div>
              <Link
                href={`/messages?to=${detail.listerId}&listing=${card.id}`}
                className="mt-4 block rounded-control bg-brand px-4 py-2.5 text-center text-sm font-medium text-white transition-colors hover:bg-brand-hover"
              >
                Message
              </Link>
              <p className="mt-3 rounded-control bg-warning-soft p-2.5 text-xs leading-relaxed text-warning">
                ⚠ Never transfer a deposit before verifying the property and owner in person.
              </p>
            </div>
          </div>
        </aside>
      </div>

      {/* Price + CTA always in the thumb zone on mobile (the rail is desktop-only) */}
      <MobileActionBar
        rent={card.rentMonthly}
        deposit={card.deposit}
        listerId={detail.listerId}
        listingId={card.id}
      />
    </article>
  );
}
