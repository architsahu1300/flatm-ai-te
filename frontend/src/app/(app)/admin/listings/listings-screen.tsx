"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ChipSelect } from "@/components/ui/chip-select";
import { Skeleton } from "@/components/ui/skeleton";
import {
  fetchListings,
  removeListing,
  rescoreListings,
  type AdminListing,
} from "@/lib/admin-client";
import { formatINR } from "@/lib/domain";
import { EmptyRow, TableCard, Td, Th, riskTone } from "../admin-bits";

const FILTERS = [
  { value: "active", label: "Active" },
  { value: "suspicious", label: "Suspicious" },
  { value: "all", label: "All statuses" },
];

export function ListingsScreen() {
  const [filter, setFilter] = useState("active");
  const [listings, setListings] = useState<AdminListing[] | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback((f: string) => {
    setListings(null);
    fetchListings({
      status: f === "all" ? undefined : "ACTIVE",
      suspicious: f === "suspicious",
    })
      .then(setListings)
      .catch(() => setListings([]));
  }, []);

  useEffect(() => load(filter), [filter, load]);

  async function remove(id: string) {
    setBusyId(id);
    setError(null);
    try {
      await removeListing(id);
      setListings((prev) => (prev ?? []).filter((l) => l.id !== id));
      setNotice("Listing removed.");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Remove failed");
    } finally {
      setBusyId(null);
    }
  }

  async function rescore() {
    setBusyId("rescore");
    setError(null);
    try {
      const { recomputed } = await rescoreListings();
      setNotice(`Scam scores recomputed for ${recomputed} listings.`);
      load(filter);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Rescore failed");
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-semibold tracking-tight">Listings</h1>
        <Button size="sm" variant="outline" onClick={rescore} disabled={busyId !== null}>
          {busyId === "rescore" ? "Rescoring…" : "↻ Recompute scam scores"}
        </Button>
      </div>

      <ChipSelect options={FILTERS} value={filter} onChange={setFilter} />

      {notice && <p className="rounded-card bg-brand-soft p-3 text-sm text-brand">{notice}</p>}
      {error && <p className="rounded-card bg-danger-soft p-3 text-sm text-danger">{error}</p>}

      <TableCard title={listings ? `${listings.length} shown · sorted by risk` : "Loading…"}>
        {listings === null ? (
          <div className="p-4"><Skeleton className="h-48 rounded-card" /></div>
        ) : (
          <table className="w-full min-w-[760px]">
            <thead>
              <tr className="border-b border-border">
                <Th>Listing</Th>
                <Th>Lister</Th>
                <Th className="text-right">Rent</Th>
                <Th className="text-right">Risk</Th>
                <Th className="text-right">Open reports</Th>
                <Th>Status</Th>
                <Th />
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {listings.length === 0 && <EmptyRow colSpan={7} text="Nothing here." />}
              {listings.map((l) => (
                <tr key={l.id}>
                  <Td>
                    <Link href={`/listing/${l.id}`} className="font-medium hover:text-brand hover:underline">
                      {l.title}
                    </Link>
                    <p className="text-xs text-text-muted">{l.locality ?? "—"}</p>
                  </Td>
                  <Td className="text-text-muted">{l.lister_name}</Td>
                  <Td className="tnum whitespace-nowrap text-right">{formatINR(l.rent_monthly)}</Td>
                  <Td className={`tnum text-right font-medium ${riskTone(Number(l.scam_risk_score))}`}>
                    {Number(l.scam_risk_score).toFixed(2)}
                  </Td>
                  <Td className={`tnum text-right ${l.open_reports > 0 ? "font-medium text-warning" : ""}`}>
                    {l.open_reports}
                  </Td>
                  <Td>
                    <Badge variant={l.status === "ACTIVE" ? "success" : "outline"}>{l.status}</Badge>
                  </Td>
                  <Td className="text-right">
                    {l.status !== "REMOVED" && (
                      <Button
                        size="sm"
                        variant="outline"
                        className="text-danger hover:bg-danger-soft"
                        disabled={busyId !== null}
                        onClick={() => remove(l.id)}
                      >
                        {busyId === l.id ? "…" : "Remove"}
                      </Button>
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
