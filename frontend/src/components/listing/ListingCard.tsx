import Link from "next/link";
import { SaveButton } from "@/components/listing/SaveButton";
import { Badge } from "@/components/ui/badge";
import { LABELS, formatINR } from "@/lib/domain";
import type { ListingCard as ListingCardData } from "@/lib/listings-client";

function metaChips(l: ListingCardData): string[] {
  const chips: string[] = [];
  chips.push(LABELS.roomType[l.roomType]);
  if (l.bhk) chips.push(`${l.bhk} BHK`);
  chips.push(LABELS.furnishing[l.furnishing]);
  const avail = new Date(l.availableFrom);
  const now = new Date();
  chips.push(
    avail <= now
      ? "Available now"
      : `From ${avail.toLocaleDateString("en-IN", { day: "numeric", month: "short" })}`,
  );
  return chips;
}

export function ListingCardView({
  listing,
  actions,
  showSave = true,
}: {
  listing: ListingCardData;
  actions?: React.ReactNode;
  showSave?: boolean;
}) {
  return (
    <Link
      href={`/listing/${listing.id}`}
      className="group block overflow-hidden rounded-card border border-border bg-surface shadow-card transition-all duration-150 hover:-translate-y-0.5 hover:shadow-pop"
    >
      <div className="relative aspect-[8/5] overflow-hidden bg-surface-2">
        {listing.coverImageUrl ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={listing.coverImageUrl}
            alt={listing.title}
            className="h-full w-full object-cover transition-transform duration-300 group-hover:scale-[1.02]"
            loading="lazy"
          />
        ) : (
          <div className="flex h-full items-center justify-center text-text-muted">No photos yet</div>
        )}
        {listing.isBoosted && (
          <span className="absolute left-2 top-2 rounded-chip bg-warning-soft px-2 py-0.5 text-xs font-medium text-warning">
            Featured
          </span>
        )}
        {showSave && <SaveButton listingId={listing.id} className="absolute right-2 top-2" />}
        <div className="absolute bottom-2 left-2 flex gap-1.5">
          {listing.listerVerified && <Badge variant="success">✓ ID verified</Badge>}
          {listing.propertyVerified && <Badge variant="success">✓ Property verified</Badge>}
        </div>
      </div>

      <div className="p-4">
        <h3 className="line-clamp-2 text-[17px] font-semibold leading-snug">{listing.title}</h3>
        {listing.localityName && (
          <p className="mt-0.5 text-[13px] text-text-muted">📍 {listing.localityName}, Mumbai</p>
        )}
        <p className="mt-2">
          <span className="tnum text-xl font-bold">{formatINR(listing.rentMonthly)}</span>
          <span className="text-sm text-text-muted">/mo</span>
          {listing.deposit > 0 && (
            <span className="tnum ml-2 text-[13px] text-text-muted">
              · {formatINR(listing.deposit)} deposit
            </span>
          )}
        </p>
        <div className="mt-2.5 flex flex-wrap gap-1.5">
          {metaChips(listing).map((chip) => (
            <span key={chip} className="rounded-chip bg-surface-2 px-2 py-0.5 text-xs text-text-muted">
              {chip}
            </span>
          ))}
        </div>
        {listing.occupantsDesc && (
          <p className="mt-2 line-clamp-1 text-[13px] text-text-muted">👥 {listing.occupantsDesc}</p>
        )}
        {actions && <div className="mt-3 border-t border-border pt-3">{actions}</div>}
      </div>
    </Link>
  );
}
