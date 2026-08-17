"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Sheet } from "@/components/ui/sheet";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { ApiError } from "@/lib/api";
import { REPORT_REASONS, submitReport, type ReportReason } from "@/lib/safety-client";
import { cn } from "@/lib/utils";

/**
 * Report a listing or member. Renders its own trigger; unauthenticated users get sent to sign-in
 * by the API's 401. Reports are rate-limited server-side (5/day).
 */
export function ReportDialog({
  listingId,
  userId,
  subject,
  triggerClassName,
}: {
  listingId?: string;
  userId?: string;
  subject: string;
  triggerClassName?: string;
}) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState<ReportReason | null>(null);
  const [details, setDetails] = useState("");
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (!reason) return;
    setBusy(true);
    setError(null);
    try {
      await submitReport({
        reportedListingId: listingId,
        reportedUserId: userId,
        reason,
        details: details.trim() || undefined,
      });
      setDone(true);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        window.location.href = "/signin";
        return;
      }
      setError(e instanceof ApiError ? e.message : "Could not submit the report");
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className={cn(
          "cursor-pointer text-xs font-medium text-text-muted transition-colors hover:text-danger",
          triggerClassName,
        )}
      >
        🚩 Report
      </button>
      <Sheet open={open} onClose={() => setOpen(false)} title={`Report ${subject}`}>
        {done ? (
          <div className="py-6 text-center">
            <p className="text-lg font-medium">Thanks — report received</p>
            <p className="mx-auto mt-1 max-w-xs text-sm text-text-muted">
              Our team reviews every report. If this involves money already sent, contact your bank
              immediately as well.
            </p>
            <Button className="mt-4" onClick={() => setOpen(false)}>Close</Button>
          </div>
        ) : (
          <div className="space-y-4">
            <div className="flex flex-wrap gap-2">
              {REPORT_REASONS.map((r) => (
                <button
                  key={r.value}
                  type="button"
                  onClick={() => setReason(r.value)}
                  className={cn(
                    "cursor-pointer rounded-chip border px-3 py-1.5 text-sm transition-colors",
                    reason === r.value
                      ? "border-danger bg-danger-soft text-danger"
                      : "border-border text-text-muted hover:border-text-muted",
                  )}
                >
                  {r.label}
                </button>
              ))}
            </div>
            <Textarea
              rows={3}
              placeholder="What happened? Anything specific helps our review."
              value={details}
              onChange={(e) => setDetails(e.target.value)}
            />
            <p className="rounded-control bg-warning-soft p-2.5 text-xs leading-relaxed text-warning">
              ⚠ Never transfer a deposit or share OTPs before verifying in person. If someone asked
              you to pay before a visit, report them here.
            </p>
            {error && <p className="text-sm text-danger">{error}</p>}
            <Button className="w-full" variant="danger" onClick={submit} disabled={!reason || busy}>
              {busy ? <Spinner /> : "Submit report"}
            </Button>
          </div>
        )}
      </Sheet>
    </>
  );
}
