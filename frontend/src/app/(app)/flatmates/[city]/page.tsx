import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { TrackView } from "@/components/analytics/TrackView";
import { APP_NAME } from "@/lib/brand";
import { fetchLocalitiesCached, slugify } from "@/lib/seo";

export async function generateMetadata({
  params,
}: {
  params: Promise<{ city: string }>;
}): Promise<Metadata> {
  const { city } = await params;
  if (city !== "mumbai") return {};
  return {
    title: `Find flatmates in Mumbai`,
    description:
      "Compatible flatmates in Mumbai, matched on lifestyle — cleanliness, schedules, food, guests — not just budget. Verified profiles, no brokers.",
    alternates: { canonical: "/flatmates/mumbai" },
  };
}

export default async function FlatmatesCityPage({ params }: { params: Promise<{ city: string }> }) {
  const { city } = await params;
  if (city !== "mumbai") notFound();
  const localities = await fetchLocalitiesCached();

  const steps = [
    ["Tell us how you live", "Cleanliness, schedules, guests, food, smoking — the stuff that actually causes flat fights."],
    ["See scored matches", "Every profile gets a compatibility score with plain-language reasons, not just photos."],
    ["Chat safely, then meet", "Request-based messaging, verified badges, and no payments ever in chat."],
  ] as const;

  return (
    <article className="mx-auto max-w-5xl">
      <TrackView event="seo_page_viewed" properties={{ page: "flatmates-city", city }} />
      <nav aria-label="Breadcrumb" className="text-sm text-text-muted">
        <Link href="/" className="hover:text-text">Home</Link> ›{" "}
        <span className="text-text">Flatmates in Mumbai</span>
      </nav>

      <h1 className="mt-3 text-3xl font-semibold tracking-tight sm:text-4xl">
        Find flatmates in Mumbai
      </h1>
      <p className="mt-3 max-w-2xl leading-relaxed text-text-muted">
        The wrong flatmate costs more than rent. {APP_NAME} matches people on how they actually
        live — sleep schedules, kitchens, cleaning standards, guests — and explains every match in
        plain words.
      </p>
      <div className="mt-5 flex flex-wrap gap-3">
        <Link
          href="/flatmates"
          className="rounded-control bg-brand px-5 py-2.5 text-sm font-medium text-white hover:bg-brand-hover"
        >
          Browse flatmates
        </Link>
        <Link
          href="/search"
          className="rounded-control border border-border px-5 py-2.5 text-sm font-medium hover:bg-surface-2"
        >
          ✦ Describe your ideal flatmate
        </Link>
      </div>

      <section className="mt-10 grid gap-4 sm:grid-cols-3">
        {steps.map(([title, body], i) => (
          <div key={title} className="rounded-card border border-border bg-surface p-5 shadow-card">
            <p className="text-xs font-semibold text-brand">Step {i + 1}</p>
            <h2 className="mt-1 font-semibold">{title}</h2>
            <p className="mt-1.5 text-sm leading-relaxed text-text-muted">{body}</p>
          </div>
        ))}
      </section>

      <section className="mt-10">
        <h2 className="text-xl font-semibold">Flat-shares by neighbourhood</h2>
        <div className="mt-4 flex flex-wrap gap-2">
          {localities.map((l) => (
            <Link
              key={l.id}
              href={`/rent/mumbai/${slugify(l.name)}`}
              className="rounded-chip border border-border bg-surface px-3.5 py-1.5 text-sm transition-colors hover:border-brand hover:text-brand"
            >
              {l.name}
            </Link>
          ))}
        </div>
      </section>
    </article>
  );
}
