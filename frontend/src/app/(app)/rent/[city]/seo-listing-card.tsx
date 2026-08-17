import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { LABELS, formatINR } from "@/lib/domain";
import type { ListingCard } from "@/lib/listings-client";

/** Lightweight server-rendered card for the SEO pages — no client JS, no match ring. */
export function SeoListingCard({ card }: { card: ListingCard }) {
  return (
    <Link
      href={`/listing/${card.id}`}
      className="group overflow-hidden rounded-card border border-border bg-surface shadow-card transition-colors hover:border-brand"
    >
      <div className="h-36 w-full overflow-hidden bg-surface-2">
        {card.coverImageUrl && (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={card.coverImageUrl}
            alt={card.title}
            className="h-full w-full object-cover transition-transform group-hover:scale-105"
          />
        )}
      </div>
      <div className="p-4">
        <div className="flex items-center gap-1.5">
          <Badge>{LABELS.roomType[card.roomType]}</Badge>
          {card.listerVerified && <Badge variant="success">✓ Verified</Badge>}
          {card.isBoosted && <Badge variant="warning">Featured</Badge>}
        </div>
        <p className="mt-2 line-clamp-2 text-sm font-medium leading-snug">{card.title}</p>
        <p className="mt-1 text-xs text-text-muted">📍 {card.localityName ?? "Mumbai"}</p>
        <p className="tnum mt-2 font-semibold">
          {formatINR(card.rentMonthly)}
          <span className="text-xs font-normal text-text-muted">/month</span>
        </p>
      </div>
    </Link>
  );
}
