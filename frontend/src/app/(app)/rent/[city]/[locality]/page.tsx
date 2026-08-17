import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { TrackView } from "@/components/analytics/TrackView";
import { APP_NAME } from "@/lib/brand";
import { formatINR } from "@/lib/domain";
import {
  ROOM_TYPE_SEO_LABEL,
  fetchListingsCached,
  fetchLocalityStats,
  findLocalityBySlug,
} from "@/lib/seo";
import { SeoListingCard } from "../seo-listing-card";

const LOCALITY_BLURBS: Record<string, string> = {
  bandra: "Cafés, sea breeze and the city's most social flat-shares — Bandra is where young Mumbai wants to live.",
  andheri: "Studios to 3BHKs around the metro and film city — Andheri balances price, connectivity and nightlife.",
  powai: "Lakeside towers, tech offices and IIT energy — Powai suits professionals who want calm with amenities.",
  bkc: "Walk to work in the business district — BKC commands a premium but kills the commute.",
  "lower-parel": "Mill-district towers next to corporate parks and Phoenix — Lower Parel is dense, fast and central.",
  worli: "Sea-facing high-rises between Bandra and town — Worli is premium Mumbai at its most convenient.",
  dadar: "The city's crossroads — every train line, every bus, and old-Mumbai food culture on your doorstep.",
  ghatkopar: "Metro-connected and budget-friendly — Ghatkopar is the value pick on the central line.",
  malad: "Affordable western-suburb living with malls and offices nearby — Malad stretches every rupee.",
  goregaon: "Between the national park and film city — Goregaon offers greener living on the western line.",
};

interface Params {
  city: string;
  locality: string;
}

export async function generateMetadata({
  params,
}: {
  params: Promise<Params>;
}): Promise<Metadata> {
  const { city, locality } = await params;
  if (city !== "mumbai") return {};
  const loc = await findLocalityBySlug(locality);
  if (!loc) return {};
  return {
    title: `Flats & rooms for rent in ${loc.name}, Mumbai`,
    description: `Live rental listings in ${loc.name} — private rooms, shared flats and apartments with verified listers and AI match scores. No brokerage.`,
    alternates: { canonical: `/rent/mumbai/${locality}` },
  };
}

export default async function RentLocalityPage({ params }: { params: Promise<Params> }) {
  const { city, locality } = await params;
  if (city !== "mumbai") notFound();
  const loc = await findLocalityBySlug(locality);
  if (!loc) notFound();

  const [stats, listings] = await Promise.all([
    fetchLocalityStats(loc.id),
    fetchListingsCached({ localityId: loc.id, size: 9 }),
  ]);

  const faq = [
    {
      q: `What does a room in ${loc.name} cost?`,
      a:
        stats.byRoomType.length > 0
          ? `Right now on ${APP_NAME}: ${stats.byRoomType
              .map(
                (r) =>
                  `${ROOM_TYPE_SEO_LABEL[r.room_type] ?? r.room_type} around ${formatINR(r.median_rent)}/month (median of ${r.listings} listings)`,
              )
              .join(", ")}. Asking rents vary with furnishing, floor and building age.`
          : `Listings in ${loc.name} are refreshed daily — check the live results for current asking rents.`,
    },
    {
      q: `How do I find flatmates in ${loc.name}?`,
      a: `${stats.activeFlatmates} people currently have active flatmate cards mentioning ${loc.name}. Use Find a Flatmate to filter by lifestyle — cleanliness, schedules, food habits — not just budget.`,
    },
    {
      q: "Are listings verified?",
      a: `Listers can verify their identity (government ID + selfie) and property documents. Verified badges appear on cards, and ${APP_NAME} never asks for payments in chat.`,
    },
  ];

  return (
    <article className="mx-auto max-w-5xl">
      <TrackView event="seo_page_viewed" properties={{ page: "rent-locality", locality: loc.name }} />
      <nav aria-label="Breadcrumb" className="text-sm text-text-muted">
        <Link href="/" className="hover:text-text">Home</Link> ›{" "}
        <Link href="/rent/mumbai" className="hover:text-text">Rent in Mumbai</Link> ›{" "}
        <span className="text-text">{loc.name}</span>
      </nav>

      <h1 className="mt-3 text-3xl font-semibold tracking-tight sm:text-4xl">
        Flats & rooms for rent in {loc.name}
      </h1>
      <p className="mt-3 max-w-2xl leading-relaxed text-text-muted">
        {LOCALITY_BLURBS[locality] ?? `Everything currently listed in ${loc.name}, Mumbai.`}
      </p>

      {stats.byRoomType.length > 0 && (
        <div className="mt-6 grid grid-cols-2 gap-3 sm:grid-cols-3">
          {stats.byRoomType.map((r) => (
            <div key={r.room_type} className="rounded-card border border-border bg-surface p-4 shadow-card">
              <p className="text-xs text-text-muted">
                Median · {ROOM_TYPE_SEO_LABEL[r.room_type] ?? r.room_type}
              </p>
              <p className="tnum mt-1 text-xl font-bold">
                {formatINR(r.median_rent)}
                <span className="text-xs font-normal text-text-muted">/mo</span>
              </p>
              <p className="text-xs text-text-muted">{r.listings} live</p>
            </div>
          ))}
        </div>
      )}

      <div className="mt-6 flex flex-wrap gap-3">
        <Link
          href={`/explore?loc=${loc.id}`}
          className="rounded-control bg-brand px-5 py-2.5 text-sm font-medium text-white hover:bg-brand-hover"
        >
          See all in {loc.name}
        </Link>
        <Link
          href="/search"
          className="rounded-control border border-border px-5 py-2.5 text-sm font-medium hover:bg-surface-2"
        >
          ✦ Describe what you need
        </Link>
      </div>

      <section className="mt-10">
        <h2 className="text-xl font-semibold">Live listings in {loc.name}</h2>
        {listings.items.length === 0 ? (
          <p className="mt-4 rounded-card bg-surface-2 p-6 text-sm text-text-muted">
            Nothing live right now — save a search and we&apos;ll alert you when something opens up.
          </p>
        ) : (
          <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {listings.items.map((card) => (
              <SeoListingCard key={card.id} card={card} />
            ))}
          </div>
        )}
      </section>

      <section className="mt-10">
        <h2 className="text-xl font-semibold">Living in {loc.name}</h2>
        <dl className="mt-4 space-y-3">
          {faq.map(({ q, a }) => (
            <div key={q} className="rounded-card border border-border bg-surface p-5 shadow-card">
              <dt className="font-medium">{q}</dt>
              <dd className="mt-1.5 text-sm leading-relaxed text-text-muted">{a}</dd>
            </div>
          ))}
        </dl>
      </section>
    </article>
  );
}
