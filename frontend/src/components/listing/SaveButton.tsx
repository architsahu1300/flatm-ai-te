"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { getSession } from "@/lib/auth-client";
import { fetchSavedIds, saveListing, unsaveListing } from "@/lib/saved-client";
import { cn } from "@/lib/utils";

/** Optimistic heart. Unauthenticated clicks route to sign-in. */
export function SaveButton({ listingId, className }: { listingId: string; className?: string }) {
  const router = useRouter();
  const [saved, setSaved] = useState(false);
  const [authed, setAuthed] = useState<boolean | null>(null);

  useEffect(() => {
    getSession()
      .then((s) => {
        setAuthed(s.authenticated);
        if (s.authenticated) {
          fetchSavedIds().then((ids) => setSaved(ids.includes(listingId))).catch(() => {});
        }
      })
      .catch(() => setAuthed(false));
  }, [listingId]);

  async function toggle(e: React.MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    if (!authed) {
      router.push(`/signin?next=/listing/${listingId}`);
      return;
    }
    const next = !saved;
    setSaved(next);
    try {
      if (next) {
        await saveListing(listingId);
      } else {
        await unsaveListing(listingId);
      }
    } catch {
      setSaved(!next);
    }
  }

  return (
    <button
      type="button"
      onClick={toggle}
      aria-label={saved ? "Remove from saved" : "Save listing"}
      aria-pressed={saved}
      className={cn(
        "flex h-9 w-9 cursor-pointer items-center justify-center rounded-chip bg-surface/90 text-lg shadow-sm backdrop-blur transition-transform hover:scale-110",
        className,
      )}
    >
      {saved ? "❤️" : "🤍"}
    </button>
  );
}
