"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import type { CitySearch } from "@/lib/ai-client";

const DISMISS_KEY = "fm-city-scope-dismissed";

function readDismissed(): boolean {
  if (typeof window === "undefined") return false;
  try {
    return sessionStorage.getItem(DISMISS_KEY) === "1";
  } catch {
    // private mode / storage disabled — just don't remember the dismissal
    return false;
  }
}

function persistDismissed(): void {
  try {
    sessionStorage.setItem(DISMISS_KEY, "1");
  } catch {
    // ditto — dismissing still hides it for the rest of this render
  }
}

/**
 * Informational, never gating (spec §4.11). Renders nothing for a profile-scoped search.
 * `UNSET` covers every anonymous searcher too — anonymous search is first-class here, so this
 * banner sits above the results without ever blocking or hiding them below it.
 */
export function CityScopePrompt({ citySearch }: { citySearch?: CitySearch | null }) {
  const [dismissed, setDismissed] = useState(false);

  useEffect(() => {
    setDismissed(readDismissed());
  }, []);

  if (!citySearch || citySearch.source !== "UNSET" || !citySearch.prompt || dismissed) {
    return null;
  }

  return (
    <div className="mb-4 flex items-start gap-2 rounded-card border border-border bg-surface-2 p-3 text-[13px] leading-relaxed text-text-muted">
      <span aria-hidden>📍</span>
      <p className="flex-1">
        {citySearch.prompt}{" "}
        <Link href="/profile" className="font-medium text-brand hover:underline">
          Set your city
        </Link>
      </p>
      <button
        type="button"
        onClick={() => {
          persistDismissed();
          setDismissed(true);
        }}
        aria-label="Dismiss"
        className="cursor-pointer text-text-muted hover:text-text"
      >
        ✕
      </button>
    </div>
  );
}
