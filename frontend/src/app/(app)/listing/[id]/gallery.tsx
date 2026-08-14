"use client";

import { useState } from "react";
import { cn } from "@/lib/utils";
import type { ListingImage } from "@/lib/listings-client";

export function ListingGallery({ images, title }: { images: ListingImage[]; title: string }) {
  const [active, setActive] = useState(0);
  if (images.length === 0) {
    return (
      <div className="flex aspect-[16/7] items-center justify-center rounded-card border border-border bg-surface-2 text-text-muted">
        No photos yet
      </div>
    );
  }
  return (
    <div>
      <div className="relative overflow-hidden rounded-card border border-border bg-surface-2">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={images[active].url}
          alt={`${title} — photo ${active + 1}`}
          className="aspect-[16/8] w-full object-cover"
        />
        <span className="tnum absolute bottom-2.5 right-2.5 flex items-center gap-1.5 rounded-chip bg-black/55 px-2.5 py-1 text-xs font-medium text-white backdrop-blur-sm">
          <span aria-hidden>🖼</span> {active + 1}/{images.length}
        </span>
      </div>
      {images.length > 1 && (
        <div className="mt-2 flex gap-2 overflow-x-auto pb-1">
          {images.map((img, i) => (
            <button
              key={img.id}
              type="button"
              onClick={() => setActive(i)}
              className={cn(
                "h-16 w-24 shrink-0 cursor-pointer overflow-hidden rounded-control border-2",
                i === active ? "border-brand" : "border-transparent opacity-70 hover:opacity-100",
              )}
              aria-label={`Photo ${i + 1}`}
            >
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={img.url} alt="" className="h-full w-full object-cover" />
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
