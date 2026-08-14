"use client";

import { useState } from "react";
import Link from "next/link";
import { formatINR } from "@/lib/domain";

/** Description clamped to a few lines with a Read more / Read less toggle. */
export function ExpandableText({ text }: { text: string }) {
  const [open, setOpen] = useState(false);
  const isLong = text.length > 280;
  return (
    <div>
      <p className={`whitespace-pre-line leading-relaxed text-text ${!open && isLong ? "line-clamp-4" : ""}`}>
        {text}
      </p>
      {isLong && (
        <button
          type="button"
          onClick={() => setOpen((o) => !o)}
          className="mt-1.5 cursor-pointer text-sm font-medium text-brand hover:underline"
        >
          {open ? "Read less" : "Read more"}
        </button>
      )}
    </div>
  );
}

const AMENITY_ICONS: [RegExp, string][] = [
  [/wifi/i, "📶"],
  [/air condition|ac\b/i, "❄️"],
  [/washing/i, "🧺"],
  [/refrigerator|fridge/i, "🧊"],
  [/microwave/i, "🍲"],
  [/geyser/i, "🚿"],
  [/parking/i, "🅿️"],
  [/gym/i, "🏋️"],
  [/pool/i, "🏊"],
  [/security/i, "🛡️"],
  [/lift/i, "🛗"],
  [/power/i, "🔋"],
  [/balcony/i, "🌇"],
  [/wardrobe/i, "🚪"],
  [/television|tv\b/i, "📺"],
  [/cook/i, "🍳"],
  [/maid/i, "🧹"],
  [/water/i, "💧"],
  [/gas/i, "🔥"],
];

function iconFor(label: string): string {
  for (const [re, icon] of AMENITY_ICONS) {
    if (re.test(label)) return icon;
  }
  return "✓";
}

/** First N amenity chips with a "+n more" expander, icons keyword-matched from labels. */
export function AmenityChips({ labels, initial = 8 }: { labels: string[]; initial?: number }) {
  const [open, setOpen] = useState(false);
  const shown = open ? labels : labels.slice(0, initial);
  const hidden = labels.length - initial;
  return (
    <div className="flex flex-wrap gap-1.5">
      {shown.map((a) => (
        <span key={a} className="flex items-center gap-1.5 rounded-chip bg-surface-2 px-2.5 py-1 text-sm">
          <span aria-hidden>{iconFor(a)}</span> {a}
        </span>
      ))}
      {!open && hidden > 0 && (
        <button
          type="button"
          onClick={() => setOpen(true)}
          className="cursor-pointer rounded-chip border border-border px-2.5 py-1 text-sm font-medium text-brand hover:bg-brand-soft"
        >
          +{hidden} more
        </button>
      )}
    </div>
  );
}

/** Mobile-only sticky bar: price at a glance + the primary CTA, per the design. */
export function MobileActionBar({
  rent,
  deposit,
  listerId,
  listingId,
}: {
  rent: number;
  deposit: number;
  listerId: string;
  listingId: string;
}) {
  return (
    <div
      className="fixed inset-x-0 bottom-14 z-30 border-t border-border bg-surface/95 px-4 py-2.5 backdrop-blur lg:hidden"
      style={{ paddingBottom: "max(0.625rem, env(safe-area-inset-bottom))" }}
    >
      <div className="mx-auto flex max-w-5xl items-center justify-between gap-3">
        <div className="min-w-0">
          <p className="tnum truncate text-lg font-bold leading-tight">
            {formatINR(rent)}
            <span className="text-sm font-normal text-text-muted">/mo</span>
          </p>
          {deposit > 0 && (
            <p className="tnum truncate text-xs text-text-muted">Dep: {formatINR(deposit)}</p>
          )}
        </div>
        <Link
          href={`/messages?to=${listerId}&listing=${listingId}`}
          className="shrink-0 rounded-control bg-brand px-6 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-brand-hover"
        >
          💬 Message
        </Link>
      </div>
    </div>
  );
}
