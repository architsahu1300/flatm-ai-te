import type { MetadataRoute } from "next";
import { fetchLocalitiesCached, slugify } from "@/lib/seo";

const BASE = process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3000";

// The sitemap is prerendered at build time, and the build kills a page after 60s. A free-tier
// backend that is asleep or mid-deploy holds the connection open rather than failing, so without
// a cap the fallback below never runs. The hourly revalidation adds the localities back later.
const BUILD_FETCH_TIMEOUT_MS = 10_000;

export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  let localitySlugs: string[] = [];
  try {
    localitySlugs = (await fetchLocalitiesCached(BUILD_FETCH_TIMEOUT_MS)).map((l) =>
      slugify(l.name),
    );
  } catch {
    // backend down or too slow at build time — ship the static routes at least
  }

  const staticRoutes: MetadataRoute.Sitemap = [
    { url: `${BASE}/`, changeFrequency: "daily", priority: 1 },
    { url: `${BASE}/explore`, changeFrequency: "hourly", priority: 0.9 },
    { url: `${BASE}/flatmates`, changeFrequency: "daily", priority: 0.8 },
    { url: `${BASE}/rent/mumbai`, changeFrequency: "daily", priority: 0.9 },
    { url: `${BASE}/flatmates/mumbai`, changeFrequency: "weekly", priority: 0.8 },
  ];

  return [
    ...staticRoutes,
    ...localitySlugs.map((slug) => ({
      url: `${BASE}/rent/mumbai/${slug}`,
      changeFrequency: "daily" as const,
      priority: 0.7,
    })),
  ];
}
