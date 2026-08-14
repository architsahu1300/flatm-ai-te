import Link from "next/link";
import { CompatibilityRing } from "@/components/flatmate/CompatibilityRing";
import { Avatar } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { formatINR } from "@/lib/domain";
import type { FlatmateCard as FlatmateCardData } from "@/lib/flatmates-client";

const TAG_ICONS: [RegExp, string][] = [
  [/quiet/i, "🤫"],
  [/easy-going/i, "🙂"],
  [/social/i, "🎉"],
  [/non-smoker/i, "🚭"],
  [/vegetarian|vegan|jain/i, "🥗"],
  [/eggetarian/i, "🍳"],
  [/non-veg|foodie/i, "🍗"],
  [/early riser/i, "🌅"],
  [/night owl/i, "🌙"],
  [/flexible/i, "🕐"],
  [/pet/i, "🐾"],
];

export function tagIcon(tag: string): string {
  for (const [re, icon] of TAG_ICONS) {
    if (re.test(tag)) return icon;
  }
  return "•";
}

export function FlatmateCardView({ flatmate }: { flatmate: FlatmateCardData }) {
  const profileHref = `/flatmate/${flatmate.id}`;
  return (
    <div className="flex flex-col rounded-card border border-border bg-surface shadow-card transition-all duration-150 hover:-translate-y-0.5 hover:shadow-pop">
      <Link href={profileHref} className="flex-1 p-5 pb-3">
        <div className="flex items-start gap-3">
          <Avatar name={flatmate.name} src={flatmate.image} size={52} />
          <div className="min-w-0 flex-1">
            <p className="flex items-center gap-1.5 font-semibold">
              <span className="truncate">
                {flatmate.name}
                {flatmate.age ? `, ${flatmate.age}` : ""}
              </span>
              {flatmate.idVerified && (
                <span className="text-success" title="ID verified">✓</span>
              )}
            </p>
            <p className="truncate text-[13px] text-text-muted">
              {flatmate.occupationDetail ?? flatmate.occupation?.replace(/_/g, " ").toLowerCase()}
            </p>
          </div>
          {flatmate.compatibility != null && <CompatibilityRing value={flatmate.compatibility} />}
        </div>

        <p className="mt-3 line-clamp-2 text-[15px] font-medium leading-snug">{flatmate.headline}</p>

        <div className="mt-3 flex flex-wrap gap-1.5 text-xs">
          {flatmate.hasFlat && <Badge variant="brand">Has a flat</Badge>}
          {flatmate.budgetMax != null && (
            <span className="tnum rounded-chip bg-surface-2 px-2 py-0.5 text-text-muted">
              {flatmate.budgetMin ? `${formatINR(flatmate.budgetMin)}–` : "up to "}
              {formatINR(flatmate.budgetMax)}
            </span>
          )}
          {flatmate.localityNames.length > 0 && (
            <span className="rounded-chip bg-surface-2 px-2 py-0.5 text-text-muted">
              📍 {flatmate.localityNames.slice(0, 2).join(", ")}
            </span>
          )}
          {flatmate.moveInFrom && (
            <span className="rounded-chip bg-surface-2 px-2 py-0.5 text-text-muted">
              From {new Date(flatmate.moveInFrom).toLocaleDateString("en-IN", { day: "numeric", month: "short" })}
            </span>
          )}
        </div>

        {flatmate.lifestyleTags.length > 0 && (
          <div className="mt-2 flex flex-wrap gap-1.5">
            {flatmate.lifestyleTags.map((tag) => (
              <span
                key={tag}
                className="flex items-center gap-1 rounded-chip border border-border px-2 py-0.5 text-xs text-text-muted"
              >
                <span aria-hidden>{tagIcon(tag)}</span> {tag}
              </span>
            ))}
          </div>
        )}

        {flatmate.sharedTraits.length > 0 && (
          <p className="mt-3 rounded-control bg-brand-soft px-3 py-2 text-[13px] italic text-brand">
            ✦ {flatmate.sharedTraits.join(" · ")}
          </p>
        )}
      </Link>

      {/* actions live outside the profile link — per the design, Message is one tap from the card */}
      <div className="flex items-center justify-between border-t border-border px-5 py-2.5">
        <Link href={profileHref} className="text-[13px] font-medium text-text-muted hover:text-text">
          View profile
        </Link>
        <Link
          href={`/messages?to=${flatmate.userId}`}
          className="rounded-control bg-brand px-4 py-1.5 text-[13px] font-medium text-white transition-colors hover:bg-brand-hover"
        >
          Message
        </Link>
      </div>
    </div>
  );
}
