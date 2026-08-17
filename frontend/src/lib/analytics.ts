/**
 * Fire-and-forget product analytics. Failures are swallowed — tracking must
 * never break or slow the product. Event names must match the backend whitelist.
 */

export type AnalyticsEvent =
  | "ai_search"
  | "search_refined"
  | "result_clicked"
  | "listing_viewed"
  | "flatmate_viewed"
  | "listing_saved"
  | "search_saved"
  | "contact_initiated"
  | "message_sent"
  | "agreement_started"
  | "boost_purchased"
  | "plan_viewed"
  | "seo_page_viewed";

function sessionId(): string | undefined {
  if (typeof window === "undefined") return undefined;
  try {
    let id = sessionStorage.getItem("fm-analytics-session");
    if (!id) {
      id = crypto.randomUUID();
      sessionStorage.setItem("fm-analytics-session", id);
    }
    return id;
  } catch {
    return undefined;
  }
}

export function track(event: AnalyticsEvent, properties?: Record<string, unknown>): void {
  if (typeof window === "undefined") return;
  try {
    void fetch("/api/v1/analytics/events", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ event, properties, sessionId: sessionId() }),
      keepalive: true,
    }).catch(() => {});
  } catch {
    // never throw from analytics
  }
}
