import type { MetadataRoute } from "next";
import { APP_NAME } from "@/lib/brand";

export default function manifest(): MetadataRoute.Manifest {
  return {
    name: `${APP_NAME} — AI flatmate & rental matching`,
    short_name: APP_NAME,
    description:
      "Describe what you're looking for in plain words — AI finds, ranks and explains your best home and flatmate matches.",
    start_url: "/search",
    display: "standalone",
    background_color: "#fafafa",
    theme_color: "#005a71",
    icons: [
      {
        src: "/icon.svg",
        sizes: "any",
        type: "image/svg+xml",
        purpose: "any",
      },
      {
        src: "/icon.svg",
        sizes: "any",
        type: "image/svg+xml",
        purpose: "maskable",
      },
    ],
    shortcuts: [
      { name: "New AI search", url: "/search" },
      { name: "Messages", url: "/messages" },
    ],
  };
}
