"use client";

import { useEffect } from "react";
import { track, type AnalyticsEvent } from "@/lib/analytics";

/** Drop into any server-rendered page to log a view event once on mount. */
export function TrackView({
  event,
  properties,
}: {
  event: AnalyticsEvent;
  properties?: Record<string, unknown>;
}) {
  useEffect(() => {
    track(event, properties);
    // fire exactly once per mount — properties identity churn must not re-fire
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [event]);
  return null;
}
