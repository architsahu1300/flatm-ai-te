"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ChipSelect } from "@/components/ui/chip-select";
import { Skeleton } from "@/components/ui/skeleton";
import {
  fetchReports,
  resolveReport,
  type AdminReport,
  type AdminReportStatus,
} from "@/lib/admin-client";
import { cn } from "@/lib/utils";
import { EmptyRow, TableCard, Td, Th } from "../admin-bits";

const STATUS_FILTERS = [
  { value: "OPEN", label: "Open" },
  { value: "UNDER_REVIEW", label: "Under review" },
  { value: "RESOLVED", label: "Resolved" },
  { value: "DISMISSED", label: "Dismissed" },
  { value: "ALL", label: "All" },
];

const REASON_LABEL: Record<string, string> = {
  SCAM: "Scam",
  FAKE_LISTING: "Fake listing",
  HARASSMENT: "Harassment",
  INAPPROPRIATE_CONTENT: "Inappropriate",
  SPAM: "Spam",
  OTHER: "Other",
};

export function ReportsScreen() {
  const [filter, setFilter] = useState("OPEN");
  const [reports, setReports] = useState<AdminReport[] | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback((f: string) => {
    setReports(null);
    fetchReports(f === "ALL" ? undefined : (f as AdminReportStatus))
      .then(setReports)
      .catch(() => setReports([]));
  }, []);

  useEffect(() => load(filter), [filter, load]);

  async function act(report: AdminReport, status: AdminReportStatus) {
    setBusyId(report.id);
    setError(null);
    try {
      const note =
        status === "RESOLVED"
          ? "Reviewed and actioned by admin"
          : status === "DISMISSED"
            ? "No violation found"
            : undefined;
      await resolveReport(report.id, status, note);
      // moving out of the current filter bucket → drop; staying → update in place
      setReports((prev) =>
        filter === "ALL"
          ? (prev ?? []).map((r) => (r.id === report.id ? { ...r, status } : r))
          : (prev ?? []).filter((r) => r.id !== report.id),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : "Action failed");
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold tracking-tight">Reports</h1>
      <ChipSelect options={STATUS_FILTERS} value={filter} onChange={setFilter} />
      {error && <p className="rounded-card bg-danger-soft p-3 text-sm text-danger">{error}</p>}

      <TableCard title={reports ? `${reports.length} shown` : "Loading…"}>
        {reports === null ? (
          <div className="p-4"><Skeleton className="h-48 rounded-card" /></div>
        ) : (
          <table className="w-full min-w-[820px]">
            <thead>
              <tr className="border-b border-border">
                <Th>Target</Th>
                <Th>Reason</Th>
                <Th>Details</Th>
                <Th>Reporter</Th>
                <Th>Status</Th>
                <Th />
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {reports.length === 0 && <EmptyRow colSpan={6} text="Queue is clear 🎉" />}
              {reports.map((r) => (
                <tr key={r.id}>
                  <Td>
                    {r.reported_listing_id ? (
                      <>
                        <Link
                          href={`/listing/${r.reported_listing_id}`}
                          className="font-medium hover:text-brand hover:underline"
                        >
                          {r.reported_listing_title ?? "Listing"}
                        </Link>
                        {r.scam_risk_score != null && (
                          <p className="tnum text-xs text-text-muted">
                            risk {Number(r.scam_risk_score).toFixed(2)}
                          </p>
                        )}
                      </>
                    ) : (
                      <p className="font-medium">{r.reported_user_name ?? "User"}</p>
                    )}
                  </Td>
                  <Td>
                    <Badge
                      variant={r.reason === "SCAM" || r.reason === "FAKE_LISTING" ? "warning" : "outline"}
                    >
                      {REASON_LABEL[r.reason] ?? r.reason}
                    </Badge>
                  </Td>
                  <Td className="max-w-72">
                    <p className="line-clamp-2 text-text-muted">{r.details || "—"}</p>
                    {r.resolution_note && (
                      <p className="mt-1 text-xs italic text-text-muted">↳ {r.resolution_note}</p>
                    )}
                  </Td>
                  <Td className="whitespace-nowrap text-text-muted">
                    {r.reporter_name}
                    <p className="text-xs">
                      {new Date(r.created_at).toLocaleDateString("en-IN", { day: "numeric", month: "short" })}
                    </p>
                  </Td>
                  <Td>
                    <Badge
                      variant={
                        r.status === "OPEN"
                          ? "warning"
                          : r.status === "RESOLVED"
                            ? "success"
                            : "outline"
                      }
                    >
                      {r.status.replace("_", " ")}
                    </Badge>
                  </Td>
                  <Td>
                    {(r.status === "OPEN" || r.status === "UNDER_REVIEW") && (
                      <div className={cn("flex justify-end gap-1.5", busyId === r.id && "opacity-50")}>
                        {r.status === "OPEN" && (
                          <Button
                            size="sm"
                            variant="ghost"
                            disabled={busyId !== null}
                            onClick={() => act(r, "UNDER_REVIEW")}
                          >
                            Review
                          </Button>
                        )}
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={busyId !== null}
                          onClick={() => act(r, "DISMISSED")}
                        >
                          Dismiss
                        </Button>
                        <Button size="sm" disabled={busyId !== null} onClick={() => act(r, "RESOLVED")}>
                          Resolve
                        </Button>
                      </div>
                    )}
                  </Td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </TableCard>
    </div>
  );
}
