import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { TrackView } from "@/components/analytics/TrackView";
import { APP_NAME } from "@/lib/brand";
import { formatINR } from "@/lib/domain";
import { fetchListingsCached, fetchLocalitiesCached, slugify } from "@/lib/seo";
import { SeoListingCard } from "./seo-listing-card";

export async function generateMetadata({
  params,
}: {
  params: Promise<{ city: string }>;
}): Promise<Metadata> {
  const { city } = await params;
  if (city !== "mumbai") return {};
  return {
    title: `Flats & rooms for rent in Mumbai`,
    description:
      "Rooms, shared flats and entire apartments for rent across Mumbai — with AI match scores, verified listers and honest lifestyle fit. No brokers.",
    alternates: { canonical: "/rent/mumbai" },
  };
}

export default async function RentCityPage({ params }: { params: Promise<{ city: string }> }) {
  const { city } = await params;
  if (city !== "mumbai") notFound();

  const [localities, latest] = await Promise.all([
    fetchLocalitiesCached(),
    fetchListingsCached({ size: 9 }),
  ]);

  return (
    <article className="mx-auto max-w-5xl">
      <TrackView event="seo_page_viewed" properties={{ page: "rent-city", city }} />
      <nav aria-label="Breadcrumb" className="text-sm text-text-muted">
        <Link href="/" className="hover:text-text">Home</Link> ›{" "}
        <span className="text-text">Rent in Mumbai</span>
      </nav>

      <h1 className="mt-3 text-3xl font-semibold tracking-tight sm:text-4xl">
        Flats & rooms for rent in Mumbai
      </h1>
      <p className="mt-3 max-w-2xl leading-relaxed text-text-muted">
        {latest.total}+ live listings across {localities.length} neighbourhoods — private rooms,
        shared flats and entire apartments. Describe what you need in plain words and {APP_NAME}&apos;s
        AI finds the places (and people) that actually fit.
      </p>
      <div className="mt-5 flex flex-wrap gap-3">
        <Link
          href="/search"
          className="rounded-control bg-brand px-5 py-2.5 text-sm font-medium text-white hover:bg-brand-hover"
        >
          ✦ Search with AI
        </Link>
        <Link
          href="/explore"
          className="rounded-control border border-border px-5 py-2.5 text-sm font-medium hover:bg-surface-2"
        >
          Browse all listings
        </Link>
      </div>

      <section className="mt-10">
        <h2 className="text-xl font-semibold">Popular neighbourhoods</h2>
        <div className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
          {localities.map((l) => (
            <Link
              key={l.id}
              href={`/rent/mumbai/${slugify(l.name)}`}
              className="rounded-card border border-border bg-surface p-4 shadow-card transition-colors hover:border-brand"
            >
              <p className="font-medium">{l.name}</p>
              <p className="mt-0.5 text-xs text-text-muted">Flats & rooms →</p>
            </Link>
          ))}
        </div>
      </section>

      <section className="mt-10">
        <h2 className="text-xl font-semibold">Fresh on {APP_NAME}</h2>
        <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {latest.items.map((card) => (
            <SeoListingCard key={card.id} card={card} />
          ))}
        </div>
        <p className="mt-4 text-sm text-text-muted">
          Rents shown are asking prices set by listers —{" "}
          <Link href="/explore" className="text-brand hover:underline">
            see all {latest.total} listings
          </Link>
          . Median 1-room budgets range roughly {formatINR(8000)}–{formatINR(45000)} depending on the
          neighbourhood.
        </p>
      </section>
    </article>
  );
}
