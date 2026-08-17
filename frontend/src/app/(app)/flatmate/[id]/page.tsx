import { cookies } from "next/headers";
import Link from "next/link";
import { notFound } from "next/navigation";
import { CompatibilityRing } from "@/components/flatmate/CompatibilityRing";
import { Avatar } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { TrackView } from "@/components/analytics/TrackView";
import { ReportDialog } from "@/components/report/ReportDialog";
import { serverFetch } from "@/lib/api";
import { formatINR } from "@/lib/domain";
import type { FlatmateDetail } from "@/lib/flatmates-client";

const LIFESTYLE_ROWS: [keyof Omit<FlatmateDetail, "card" | "about" | "bio" | "companyOrCollege" | "languages">, string, string][] = [
  ["socialStyle", "Social vibe", "☕"],
  ["cleanliness", "Cleanliness", "🧼"],
  ["sleepSchedule", "Schedule", "⏰"],
  ["smoking", "Smoking", "🚭"],
  ["drinking", "Drinking", "🍷"],
  ["diet", "Food", "🥗"],
  ["pets", "Pets", "🐾"],
  ["wfhFrequency", "Work from home", "💻"],
  ["partyFrequency", "Parties", "🎉"],
  ["guestFrequency", "Guests", "🚪"],
  ["cookingFrequency", "Cooking", "🍳"],
];

function pretty(value: string | null): string {
  if (!value) return "—";
  return value.charAt(0) + value.slice(1).toLowerCase().replace(/_/g, " ");
}

export default async function FlatmateDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const cookieHeader = (await cookies()).toString();

  let detail: FlatmateDetail;
  try {
    detail = await serverFetch<FlatmateDetail>(`/api/v1/flatmates/${id}`, cookieHeader);
  } catch {
    notFound();
  }
  const { card } = detail;

  return (
    <article className="mx-auto max-w-3xl">
      <TrackView event="flatmate_viewed" properties={{ flatmateId: id }} />
      <Link
        href="/flatmates"
        className="mb-4 inline-flex items-center gap-1.5 text-sm font-medium text-text-muted transition-colors hover:text-text"
      >
        <span aria-hidden>←</span> Back to flatmates
      </Link>
      <div className="rounded-card border border-border bg-surface p-6 shadow-card sm:p-8">
        <div className="flex items-start gap-4">
          <Avatar name={card.name} src={card.image} size={72} />
          <div className="min-w-0 flex-1">
            <h1 className="flex items-center gap-2 text-2xl font-semibold tracking-tight">
              {card.name}
              {card.age ? `, ${card.age}` : ""}
              {card.idVerified && <Badge variant="success">✓ ID verified</Badge>}
            </h1>
            <p className="mt-0.5 text-text-muted">
              {card.occupationDetail ?? pretty(card.occupation)}
              {detail.companyOrCollege ? ` · ${detail.companyOrCollege}` : ""}
            </p>
          </div>
          {card.compatibility != null && <CompatibilityRing value={card.compatibility} size={56} />}
        </div>

        <p className="mt-5 text-lg font-medium leading-snug">{card.headline}</p>

        {card.sharedTraits.length > 0 && (
          <p className="mt-3 rounded-control bg-brand-soft px-3 py-2 text-sm italic text-brand">
            ✦ {card.sharedTraits.join(" · ")}
          </p>
        )}

        <div className="mt-4 flex flex-wrap gap-1.5">
          {card.hasFlat && <Badge variant="brand">Has a flat</Badge>}
          {card.budgetMax != null && (
            <span className="tnum rounded-chip bg-surface-2 px-2.5 py-1 text-sm text-text-muted">
              Budget {card.budgetMin ? `${formatINR(card.budgetMin)}–` : "up to "}
              {formatINR(card.budgetMax)}
            </span>
          )}
          {card.localityNames.length > 0 && (
            <span className="rounded-chip bg-surface-2 px-2.5 py-1 text-sm text-text-muted">
              📍 {card.localityNames.join(", ")}
            </span>
          )}
          {card.moveInFrom && (
            <span className="rounded-chip bg-surface-2 px-2.5 py-1 text-sm text-text-muted">
              Moving from{" "}
              {new Date(card.moveInFrom).toLocaleDateString("en-IN", { day: "numeric", month: "long" })}
            </span>
          )}
        </div>

        {(detail.about || detail.bio) && (
          <section className="mt-6">
            <h2 className="text-lg font-semibold">About</h2>
            <p className="mt-2 whitespace-pre-line leading-relaxed">{detail.about || detail.bio}</p>
          </section>
        )}

        <section className="mt-6">
          <h2 className="text-lg font-semibold">Lifestyle</h2>
          <dl className="mt-3 grid grid-cols-2 gap-2.5 sm:grid-cols-3">
            {LIFESTYLE_ROWS.filter(([key]) => detail[key]).map(([key, label, icon]) => (
              <div key={key} className="flex items-start gap-2.5 rounded-control bg-surface-2 px-3 py-2.5">
                <span aria-hidden className="mt-0.5">{icon}</span>
                <div className="min-w-0">
                  <dt className="text-xs text-text-muted">{label}</dt>
                  <dd className="truncate text-sm font-medium">{pretty(detail[key])}</dd>
                </div>
              </div>
            ))}
          </dl>
        </section>

        <div className="mt-8 flex items-center gap-3">
          <Link
            href={`/messages?to=${card.userId}`}
            className="flex-1 rounded-control bg-brand px-4 py-2.5 text-center text-sm font-medium text-white transition-colors hover:bg-brand-hover"
          >
            Message {card.name.split(" ")[0]}
          </Link>
          <ReportDialog userId={card.userId} subject={card.name.split(" ")[0]} />
        </div>
      </div>
    </article>
  );
}
