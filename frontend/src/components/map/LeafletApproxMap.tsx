"use client";

import { useEffect, useState } from "react";
import { Circle, MapContainer, TileLayer } from "react-leaflet";
import "leaflet/dist/leaflet.css";

/** Approximate-location map: a 350m privacy circle, never an exact pin. */
export default function LeafletApproxMap({
  lat,
  lng,
  label,
}: {
  lat: number;
  lng: number;
  label?: string;
}) {
  // Follow the active theme's accent instead of hardcoding a hex
  const [accent, setAccent] = useState("#005a71");
  useEffect(() => {
    const read = () =>
      setAccent(
        getComputedStyle(document.documentElement).getPropertyValue("--fm-brand").trim() ||
          "#005a71",
      );
    read();
    const observer = new MutationObserver(read);
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["class"] });
    return () => observer.disconnect();
  }, []);

  return (
    <MapContainer
      center={[lat, lng]}
      zoom={14}
      scrollWheelZoom={false}
      style={{ height: "100%", width: "100%" }}
      attributionControl
    >
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        url="https://tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      <Circle
        center={[lat, lng]}
        radius={350}
        pathOptions={{ color: accent, fillColor: accent, fillOpacity: 0.15, weight: 2 }}
      />
      {label ? null : null}
    </MapContainer>
  );
}
