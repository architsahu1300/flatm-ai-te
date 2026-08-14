"use client";

import dynamic from "next/dynamic";
import { Skeleton } from "@/components/ui/skeleton";

/**
 * MapProvider seam: everything map-related renders through this module, so swapping
 * Leaflet/OSM for Mapbox or Google later touches only src/components/map/.
 */
const InnerMap = dynamic(() => import("./LeafletApproxMap"), {
  ssr: false,
  loading: () => <Skeleton className="h-full w-full rounded-card" />,
});

export function ApproxMap({ lat, lng, label }: { lat: number; lng: number; label?: string }) {
  return (
    <div className="h-64 overflow-hidden rounded-card border border-border">
      <InnerMap lat={lat} lng={lng} label={label} />
    </div>
  );
}
