import type { MetadataRoute } from "next";

const BASE = process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3000";

export default function robots(): MetadataRoute.Robots {
  return {
    rules: {
      userAgent: "*",
      allow: "/",
      // private/stateful surfaces add no search value
      disallow: ["/messages", "/saved", "/profile", "/my-listings", "/admin", "/agreements", "/notifications"],
    },
    sitemap: `${BASE}/sitemap.xml`,
  };
}
