"use client";

import { useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import {
  fetchPendingVerifications,
  reviewVerification,
  type AdminVerification,
} from "@/lib/admin-client";
import { EmptyRow, TableCard, Td, Th } from "../admin-bits";

const TYPE_LABEL: Record<string, string> = {
  GOV_ID: "Government ID",
  SELFIE: "Selfie check",
  PROPERTY: "Property",
  PHONE: "Phone",
  EMAIL: "Email",
};

export function VerificationsScreen() {
  const [rows, setRows] = useState<AdminVerification[] | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchPendingVerifications().then(setRows).catch(() => setRows([]));
  }, []);

  async function review(id: string, approve: boolean) {
    setBusyId(id);
    setError(null);
    try {
      await reviewVerification(id, approve);
      setRows((prev) => (prev ?? []).filter((v) => v.id !== id));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Review failed");
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold tracking-tight">Verifications</h1>
      <p className="text-sm text-text-muted">
        Pending identity and property checks, oldest first. Approving a property check marks the
        property verified everywhere.
      </p>
      {error && <p className="rounded-card bg-danger-soft p-3 text-sm text-danger">{error}</p>}

      <TableCard title={rows ? `${rows.length} pending` : "Loading…"}>
        {rows === null ? (
          <div className="p-4"><Skeleton className="h-40 rounded-card" /></div>
        ) : (
          <table className="w-full min-w-[640px]">
            <thead>
              <tr className="border-b border-border">
                <Th>User</Th>
                <Th>Type</Th>
                <Th>Requested</Th>
                <Th />
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {rows.length === 0 && <EmptyRow colSpan={4} text="Nothing pending 🎉" />}
              {rows.map((v) => (
                <tr key={v.id}>
                  <Td>
                    <p className="font-medium">{v.user_name ?? "—"}</p>
                    <p className="text-xs text-text-muted">{v.email ?? ""}</p>
                  </Td>
                  <Td>
                    <Badge variant={v.type === "PROPERTY" ? "brand" : "default"}>
                      {TYPE_LABEL[v.type] ?? v.type}
                    </Badge>
                  </Td>
                  <Td className="whitespace-nowrap text-text-muted">
                    {new Date(v.created_at).toLocaleString("en-IN", {
                      day: "numeric",
                      month: "short",
                      hour: "2-digit",
                      minute: "2-digit",
                    })}
                  </Td>
                  <Td>
                    <div className="flex justify-end gap-1.5">
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busyId !== null}
                        onClick={() => review(v.id, false)}
                      >
                        Reject
                      </Button>
                      <Button size="sm" disabled={busyId !== null} onClick={() => review(v.id, true)}>
                        {busyId === v.id ? "…" : "Approve"}
                      </Button>
                    </div>
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
